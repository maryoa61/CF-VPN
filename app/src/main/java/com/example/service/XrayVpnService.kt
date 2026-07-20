package com.example.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.example.data.VpnConfig
import com.example.vpn.Hev2Socks
import com.example.vpn.IpPoolManager
import com.example.vpn.VpnConnectionManager
import com.example.vpn.VpnStatus
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.runBlocking
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pipeline: TUN (tun0) -> hev2socks (tun2socks) -> Xray-core SOCKS inbound
 *           -> Xray-core Trojan/VLESS outbound -> real server.
 *
 * Xray-core is NOT given the TUN fd directly (tunFd=0 in startLoop): it only
 * exposes a loopback SOCKS proxy on 127.0.0.1:SOCKS_PORT. hev2socks is the
 * component that actually reads/writes tun0 and bridges it to that SOCKS
 * proxy. This matches the architecture already built in this project
 * (Hev2Socks.kt / hev_bridge.c).
 */
class XrayVpnService : VpnService(), CoreCallbackHandler {

    companion object {
        const val EXTRA_CONFIG_JSON = "vpn_config_json"
        private const val NOTIFICATION_CHANNEL_ID = "vpn_status_channel"
        private const val NOTIFICATION_ID = 1
        private const val SOCKS_PORT = 10808
        private const val TUN_ADDRESS_V4 = "10.8.0.2"
        private const val TUN_ADDRESS_V6 = "fd00::2"
        // Bumped from 1500: hev2socks re-segments into normal-sized TCP packets on
        // its own real sockets anyway, so a larger virtual-interface MTU only cuts
        // per-packet syscall/JNI overhead between apps -> tun0 -> hev2socks; it does
        // not require the underlying mobile network to support jumbo frames.
        private const val TUN_MTU = 8500
        private const val IP_POOL_SCAN_PORT_FALLBACK = 443
        private const val IP_POOL_KEEP_TOP = 4
        private const val IP_POOL_SCAN_TIMEOUT_MS = 1500
        private const val IP_POOL_RESCAN_INTERVAL_MS = 60_000L
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var coreController: CoreController? = null
    private var coreEnvInitialized = false
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val moshi by lazy { Moshi.Builder().add(KotlinJsonAdapterFactory()).build() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val manager = VpnConnectionManager.getInstance(this)

        if (action == "STOP") {
            manager.log("Disconnect command received from app interface")
            stopVpn()
            return START_NOT_STICKY
        }

        // The caller (VpnConnectionManager.toggleConnection()) is expected to
        // serialize the selected VpnConfig with Moshi and pass it as this extra:
        //   intent.putExtra(XrayVpnService.EXTRA_CONFIG_JSON,
        //       moshi.adapter(VpnConfig::class.java).toJson(selectedConfig))
        val configJson = intent?.getStringExtra(EXTRA_CONFIG_JSON)
        if (configJson.isNullOrEmpty()) {
            manager.log("Fatal: no VpnConfig payload supplied to service (missing EXTRA_CONFIG_JSON), aborting start")
            manager.setStatus(VpnStatus.DISCONNECTED)
            stopSelf()
            return START_NOT_STICKY
        }

        val config = try {
            moshi.adapter(VpnConfig::class.java).fromJson(configJson)
        } catch (e: Exception) {
            manager.log("Fatal: failed to parse VpnConfig payload: ${e.message}")
            manager.setStatus(VpnStatus.DISCONNECTED)
            stopSelf()
            return START_NOT_STICKY
        }

        val supportedTypes = setOf("trojan", "vless", "vmess", "shadowsocks")
        if (config == null || config.type !in supportedTypes) {
            manager.log("Fatal: XrayVpnService currently only supports type in $supportedTypes (got: ${config?.type})")
            manager.setStatus(VpnStatus.DISCONNECTED)
            stopSelf()
            return START_NOT_STICKY
        }

        if (config.type == "trojan" && config.password.isNullOrEmpty()) {
            manager.log("Fatal: trojan config '${config.name}' is missing a password")
            manager.setStatus(VpnStatus.DISCONNECTED)
            stopSelf()
            return START_NOT_STICKY
        }

        if ((config.type == "vless" || config.type == "vmess") && config.uuid.isNullOrEmpty()) {
            manager.log("Fatal: ${config.type} config '${config.name}' is missing a UUID")
            manager.setStatus(VpnStatus.DISCONNECTED)
            stopSelf()
            return START_NOT_STICKY
        }

        if (config.type == "shadowsocks" && config.password.isNullOrEmpty()) {
            manager.log("Fatal: shadowsocks config '${config.name}' is missing a password")
            manager.setStatus(VpnStatus.DISCONNECTED)
            stopSelf()
            return START_NOT_STICKY
        }

        manager.setStatus(VpnStatus.CONNECTING)
        manager.log("Initializing core-level network tunnel configuration...")
        startVpn(config)
        return START_STICKY
    }

    private fun startVpn(config: VpnConfig) {
        val manager = VpnConnectionManager.getInstance(this)
        try {
            if (vpnInterface != null) {
                manager.log("startVpn() called while already connected, ignoring")
                return
            }

            val builder = Builder()
                .setSession("CFVPN_Secure_Tunnel")
                .setMtu(TUN_MTU)
                .addAddress(TUN_ADDRESS_V4, 32)
                .addAddress(TUN_ADDRESS_V6, 128)

            builder.addRoute("0.0.0.0", 0)
            builder.addRoute("::", 0)
            builder.addDnsServer("8.8.8.8")
            builder.addDnsServer("1.1.1.1")
            builder.addDnsServer("2001:4860:4860::8888")

            // Exclude our own app from the tunnel. Without this, Xray-core's
            // own outbound TCP connection to the Trojan server (opened by this
            // same process) would get captured by the TUN interface and routed
            // back into itself, creating a connection loop and killing the
            // tunnel. This is the standard fix used by every Xray/V2Ray Android
            // client, and it's simpler/more reliable here than VpnService.protect()
            // since the current libv2ray CoreCallbackHandler doesn't expose a
            // protect-socket callback to hook into.
            try {
                builder.addDisallowedApplication(packageName)
            } catch (e: Exception) {
                manager.log("Warning: could not exclude own app from tunnel: ${e.message}")
            }

            val pfd = builder.establish()
            if (pfd == null) {
                manager.log("Fatal: VpnService.Builder.establish() returned null (VPN permission revoked?)")
                manager.setStatus(VpnStatus.DISCONNECTED)
                return
            }
            vpnInterface = pfd
            startForegroundNotification()

            manager.log("Virtual TUN device created successfully: session=CFVPN_Secure_Tunnel, interface=tun0, MTU=$TUN_MTU")
            manager.log("Local interface bound: $TUN_ADDRESS_V4/32 & $TUN_ADDRESS_V6/128")

            if (!coreEnvInitialized) {
                // Second arg is the XUDP base key; empty string = let the core
                // generate/manage it internally. filesDir gives Xray a writable
                // path for its asset/cert lookups.
                Libv2ray.initCoreEnv(filesDir.absolutePath, "")
                coreEnvInitialized = true
            }

            // IP-Pool: only meaningful for TLS-fronted configs (trojan here) where the
            // cert/SNI stays fixed regardless of which edge IP we actually dial. This
            // MUST run after establish()+addDisallowedApplication above, so the probe
            // sockets bypass the tunnel instead of looping back into it.
            val poolCandidates = config.ipPool?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
                ?: IpPoolManager.DEFAULT_POOL
            val resolvedPool: List<String> = if (config.security == "tls" && poolCandidates.isNotEmpty()) {
                val ranked = runBlocking {
                    IpPoolManager.scanAndRank(
                        candidates = poolCandidates,
                        port = config.port,
                        keepTop = IP_POOL_KEEP_TOP,
                        timeoutMs = IP_POOL_SCAN_TIMEOUT_MS
                    )
                }
                if (ranked.isEmpty()) {
                    manager.log("IP-Pool: no candidate edge IP answered within ${IP_POOL_SCAN_TIMEOUT_MS}ms, falling back to ${config.address}")
                    emptyList()
                } else {
                    manager.log("IP-Pool: ${ranked.size} clean edge IP(s) ready for rotation: ${ranked.joinToString()}")
                    IpPoolManager.start(
                        scope = serviceScope,
                        candidates = poolCandidates,
                        port = config.port,
                        intervalMs = IP_POOL_RESCAN_INTERVAL_MS,
                        timeoutMs = IP_POOL_SCAN_TIMEOUT_MS,
                        keepTop = IP_POOL_KEEP_TOP
                    ) { updated -> manager.log("IP-Pool: background rescan refreshed pool: ${updated.joinToString()}") }
                    ranked
                }
            } else {
                emptyList()
            }

            if (config.type == "hysteria2") {
                // Honest limitation: this build's proxy core is Xray-core, which does not
                // speak the Hysteria2/QUIC wire protocol. Routing a hysteria2:// profile
                // through it would silently fail to connect. A real Hysteria2 outbound
                // needs the separate hysteria2 (or sing-box) native core linked in — not
                // done yet in this project. Surface that clearly instead of pretending.
                manager.log("Fatal: type=hysteria2 needs the Hysteria2/QUIC core, which isn't linked into this build yet (Xray-core only). Use a trojan/vless profile for now.")
                manager.setStatus(VpnStatus.DISCONNECTED)
                stopVpn()
                return
            }

            val xrayConfigJson = buildXrayConfig(config, resolvedPool)
            val controller = Libv2ray.newCoreController(this)
            coreController = controller
            // tunFd = 0: tell Xray-core not to manage the TUN device itself.
            // It only opens a SOCKS inbound on 127.0.0.1:SOCKS_PORT; hev2socks
            // (below) is what actually pumps packets between tun0 and that port.
            controller.startLoop(xrayConfigJson, 0)
            manager.log("Xray-core started: ${config.type} outbound -> ${config.address}:${config.port}, socks inbound -> 127.0.0.1:$SOCKS_PORT")

            val hevConfig = buildHevConfig()
            val hevResult = Hev2Socks.start(hevConfig, pfd.fd)
            if (hevResult != 0) {
                manager.log("Fatal: Hev2Socks.start returned error code $hevResult")
                manager.setStatus(VpnStatus.DISCONNECTED)
                stopVpn()
                return
            }
            manager.log("tun2socks bridge active: tun0 -> 127.0.0.1:$SOCKS_PORT")

            manager.setStatus(VpnStatus.CONNECTED)
            manager.log("System-wide VPN tunnel active. Routing 100% of IPv4/IPv6 traffic through tunnel.")
        } catch (e: Exception) {
            manager.log("Fatal: Failed to establish VPN tunnel: ${e.message}")
            manager.setStatus(VpnStatus.DISCONNECTED)
            e.printStackTrace()
            stopVpn()
        }
    }

    /** Builds the Xray-core JSON config: SOCKS inbound (for hev2socks) + protocol outbound.
     *  [resolvedPool], if non-empty, provides fast-ranked edge IPs (from IpPoolManager) to
     *  dial instead of [VpnConfig.address] directly, while the TLS server name (SNI) still
     *  targets the real domain — standard TLS domain-fronting. */
    private fun buildXrayConfig(config: VpnConfig, resolvedPool: List<String>): String {
        // Respect the security mode actually declared by the config/link. Many Trojan/VLESS
        // deployments behind a CDN (e.g. Cloudflare Workers, security=none&type=ws) rely on
        // the CDN edge for TLS termination, so forcing "tls" here breaks the handshake.
        val security = config.security?.takeIf { it.isNotEmpty() } ?: "none"

        // The address Xray-core actually dials. If we have a ranked pool of fronting IPs,
        // use the fastest one for the connection while keeping serverName/SNI as the real
        // domain below, so the TLS handshake and certificate validation are unaffected.
        val dialAddress = resolvedPool.firstOrNull() ?: config.address

        val inbound = JSONObject().apply {
            put("tag", "socks-in")
            put("listen", "127.0.0.1")
            put("port", SOCKS_PORT)
            put("protocol", "socks")
            put("settings", JSONObject().apply {
                put("auth", "noauth")
                put("udp", true)
                put("ip", "127.0.0.1")
            })
            put("sniffing", JSONObject().apply {
                put("enabled", true)
                put("destOverride", JSONArray().put("http").put("tls"))
            })
        }

        val network = config.network ?: "tcp"
        val streamSettings = JSONObject().apply {
            put("network", network)
            put("security", security)
            if (security == "tls" || security == "xtls") {
                put("tlsSettings", JSONObject().apply {
                    put("serverName", config.sni ?: config.address)
                    put("allowInsecure", false)
                })
            }
            if (security == "reality") {
                put("realitySettings", JSONObject().apply {
                    put("serverName", config.sni ?: config.address)
                    put("publicKey", config.publicKey ?: "")
                    put("shortId", config.shortId ?: "")
                    put("fingerprint", "chrome")
                })
            }
            if (network == "ws") {
                put("wsSettings", JSONObject().apply {
                    put("path", config.wsPath ?: "/")
                    if (!config.wsHost.isNullOrEmpty()) {
                        put("headers", JSONObject().apply {
                            put("Host", config.wsHost)
                        })
                    }
                })
            }
        }

        val outbound = JSONObject().apply {
            put("tag", "proxy")
            put("protocol", config.type)
            put("settings", JSONObject().apply {
                when (config.type) {
                    "trojan" -> put("servers", JSONArray().put(JSONObject().apply {
                        put("address", dialAddress)
                        put("port", config.port)
                        put("password", config.password)
                    }))
                    "vless" -> put("vnext", JSONArray().put(JSONObject().apply {
                        put("address", dialAddress)
                        put("port", config.port)
                        put("users", JSONArray().put(JSONObject().apply {
                            put("id", config.uuid)
                            put("encryption", "none")
                            val flow = config.flow?.takeIf { it.isNotEmpty() && it != "none" }
                            if (flow != null && (security == "tls" || security == "xtls" || security == "reality")) {
                                put("flow", flow)
                            }
                        }))
                    }))
                    "vmess" -> put("vnext", JSONArray().put(JSONObject().apply {
                        put("address", dialAddress)
                        put("port", config.port)
                        put("users", JSONArray().put(JSONObject().apply {
                            put("id", config.uuid)
                            put("alterId", 0)
                            put("security", "auto")
                        }))
                    }))
                    "shadowsocks" -> put("servers", JSONArray().put(JSONObject().apply {
                        put("address", dialAddress)
                        put("port", config.port)
                        put("method", config.ssMethod?.takeIf { it.isNotEmpty() } ?: "aes-256-gcm")
                        put("password", config.password)
                    }))
                }
            })
            // Keep the TLS SNI/certificate pointed at the real domain even when dialAddress
            // is a fronting IP from resolvedPool.
            put("streamSettings", streamSettings)
        }

        val directOutbound = JSONObject().apply {
            put("tag", "direct")
            put("protocol", "freedom")
            put("settings", JSONObject())
        }

        val blockOutbound = JSONObject().apply {
            put("tag", "block")
            put("protocol", "blackhole")
            put("settings", JSONObject())
        }

        val root = JSONObject().apply {
            put("log", JSONObject().apply { put("loglevel", "warning") })
            put("inbounds", JSONArray().put(inbound))
            // "proxy" is listed first, so it's the default outbound for unmatched traffic.
            put("outbounds", JSONArray().put(outbound).put(directOutbound).put(blockOutbound))
        }

        return root.toString()
    }

    /** Builds the hev-socks5-tunnel YAML config that bridges tun0 to Xray's local SOCKS inbound. */
    private fun buildHevConfig(): String {
        return """
            tunnel:
              name: tun0
              mtu: $TUN_MTU
              ipv4: $TUN_ADDRESS_V4
              ipv6: '$TUN_ADDRESS_V6'

            socks5:
              port: $SOCKS_PORT
              address: 127.0.0.1
              udp: 'udp'
        """.trimIndent()
    }

    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "VPN Status",
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("VPN Connected")
            .setContentText("Tunnel is active")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopVpn() {
        val manager = VpnConnectionManager.getInstance(this)

        try {
            Hev2Socks.stop()
        } catch (e: Exception) {
            manager.log("Error stopping tun2socks bridge: ${e.message}")
        }

        try {
            coreController?.stopLoop()
        } catch (e: Exception) {
            manager.log("Error stopping Xray-core: ${e.message}")
        }
        coreController = null

        try {
            vpnInterface?.close()
            vpnInterface = null
            manager.log("Virtual TUN interface tun0 has been closed")
        } catch (e: Exception) {
            manager.log("Error closing TUN interface: ${e.message}")
            e.printStackTrace()
        }

        serviceScope.coroutineContext.cancelChildren()

        manager.setStatus(VpnStatus.DISCONNECTED)
        stopSelf()
    }

    override fun onDestroy() {
        stopVpn()
        serviceScope.cancel()
        super.onDestroy()
    }

    // --- libv2ray.CoreCallbackHandler ---

    override fun startup(): Long = 0

    override fun shutdown(): Long = 0

    override fun onEmitStatus(code: Long, message: String): Long {
        VpnConnectionManager.getInstance(this).log("Xray-core [$code]: $message")
        return 0
    }
}
