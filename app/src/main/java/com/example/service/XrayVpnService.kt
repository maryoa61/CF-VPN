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
import com.example.vpn.VpnConnectionManager
import com.example.vpn.VpnStatus
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pipeline: TUN (tun0) -> hev2socks (tun2socks) -> Xray-core SOCKS inbound
 *           -> Xray-core Trojan outbound -> real server.
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
        private const val TUN_MTU = 1500
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var coreController: CoreController? = null
    private var coreEnvInitialized = false

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
            manager.log("Fatal: unsupported config type '${config?.type}'. Supported: $supportedTypes. " +
                "(hysteria2/TUIC need a QUIC core that isn't bundled in this build.)")
            manager.setStatus(VpnStatus.DISCONNECTED)
            stopSelf()
            return START_NOT_STICKY
        }

        val missingSecret = when (config.type) {
            "trojan" -> config.password.isNullOrEmpty()
            "vless" -> config.uuid.isNullOrEmpty()
            "vmess" -> config.uuid.isNullOrEmpty()
            "shadowsocks" -> config.password.isNullOrEmpty() || config.method.isNullOrEmpty()
            else -> true
        }
        if (missingSecret) {
            manager.log("Fatal: '${config.name}' (${config.type}) is missing required credentials")
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

            val xrayConfigJson = buildXrayConfig(config)
            val controller = Libv2ray.newCoreController(this)
            coreController = controller
            // tunFd = 0: tell Xray-core not to manage the TUN device itself.
            // It only opens a SOCKS inbound on 127.0.0.1:SOCKS_PORT; hev2socks
            // (below) is what actually pumps packets between tun0 and that port.
            controller.startLoop(xrayConfigJson, 0)
            val poolInfo = config.edgeIps?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            if (poolInfo != null && poolInfo.size >= 2) {
                manager.log("Xray-core started: ${config.type} outbound pool (${poolInfo.size} edge IPs, random per-connection) -> port ${config.port}, socks inbound -> 127.0.0.1:$SOCKS_PORT")
            } else {
                manager.log("Xray-core started: ${config.type} outbound -> ${config.address}:${config.port}, socks inbound -> 127.0.0.1:$SOCKS_PORT")
            }

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

    /**
     * Builds the Xray-core JSON config: SOCKS inbound (for hev2socks) + one outbound
     * per supported protocol (vless / vmess / shadowsocks / trojan).
     *
     * IP-Pool mode: if config.edgeIps has 2+ comma-separated IPs, we emit one outbound
     * per IP (same protocol/port/creds/SNI, only "address" differs) plus a routing
     * balancer with strategy "random", so every *new* TCP/UDP connection picks a
     * random edge IP while the TLS SNI / Host stay identical. This spreads traffic
     * volume across IPs instead of hammering a single one (mitigates per-IP DPI
     * throttling), without needing any active background scanning.
     */
    private fun buildXrayConfig(config: VpnConfig): String {
        // Respect the security mode actually declared by the config/link. Many deployments
        // behind a CDN (e.g. Cloudflare Workers, security=none&type=ws) rely on the CDN edge
        // for TLS termination, so forcing "tls" here breaks the handshake.
        val security = config.security?.takeIf { it.isNotEmpty() } ?: "none"

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

        fun buildStreamSettings(): JSONObject = JSONObject().apply {
            put("network", network)
            put("security", security)
            when (security) {
                "tls" -> put("tlsSettings", JSONObject().apply {
                    put("serverName", config.sni ?: config.address)
                    put("allowInsecure", false)
                })
                "reality" -> put("realitySettings", JSONObject().apply {
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
                        put("headers", JSONObject().apply { put("Host", config.wsHost) })
                    }
                })
            }
            // Client-Hello fragmentation: splits the TLS handshake into smaller TCP
            // segments so DPI can't signature-match it in one shot. Real Xray-core
            // sockopt field (present since v1.8+ of the core the libv2ray aar wraps).
            if (config.fragmentEnabled) {
                put("sockopt", JSONObject().apply {
                    put("fragment", JSONObject().apply {
                        put("packets", config.fragmentPackets ?: "tlshello")
                        put("length", config.fragmentLength ?: "10-20")
                        put("interval", config.fragmentInterval ?: "10-20")
                    })
                })
            }
        }

        /** Builds one protocol-correct outbound for a given target IP/host. */
        fun buildOutbound(tag: String, targetAddress: String): JSONObject {
            val settings = JSONObject()
            val protocol = when (config.type) {
                "vless" -> {
                    settings.put("vnext", JSONArray().put(JSONObject().apply {
                        put("address", targetAddress)
                        put("port", config.port)
                        put("users", JSONArray().put(JSONObject().apply {
                            put("id", config.uuid)
                            put("encryption", "none")
                            put("flow", config.flow?.takeIf { it != "none" } ?: "")
                        }))
                    }))
                    "vless"
                }
                "vmess" -> {
                    settings.put("vnext", JSONArray().put(JSONObject().apply {
                        put("address", targetAddress)
                        put("port", config.port)
                        put("users", JSONArray().put(JSONObject().apply {
                            put("id", config.uuid)
                            put("alterId", config.alterId)
                            put("security", "auto")
                        }))
                    }))
                    "vmess"
                }
                "shadowsocks" -> {
                    settings.put("servers", JSONArray().put(JSONObject().apply {
                        put("address", targetAddress)
                        put("port", config.port)
                        put("method", config.method)
                        put("password", config.password)
                    }))
                    "shadowsocks"
                }
                else -> { // trojan
                    settings.put("servers", JSONArray().put(JSONObject().apply {
                        put("address", targetAddress)
                        put("port", config.port)
                        put("password", config.password)
                    }))
                    "trojan"
                }
            }
            return JSONObject().apply {
                put("tag", tag)
                put("protocol", protocol)
                put("settings", settings)
                put("streamSettings", buildStreamSettings())
            }
        }

        val edgeIpPool = config.edgeIps?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        val outbounds = JSONArray()
        val routing = JSONObject()

        if (edgeIpPool.size >= 2) {
            edgeIpPool.forEachIndexed { i, ip -> outbounds.put(buildOutbound("proxy-$i", ip)) }
            routing.put("balancers", JSONArray().put(JSONObject().apply {
                put("tag", "pool")
                put("selector", JSONArray().put("proxy-"))
                put("strategy", JSONObject().apply { put("type", "random") })
            }))
            routing.put("rules", JSONArray().put(JSONObject().apply {
                put("type", "field")
                put("network", "tcp,udp")
                put("balancerTag", "pool")
            }))
        } else {
            // Single target: "address" resolved by the OS/DNS as usual. Listed first,
            // so it's the default outbound for unmatched traffic (no routing block needed).
            outbounds.put(buildOutbound("proxy", config.address))
        }

        outbounds.put(JSONObject().apply {
            put("tag", "direct"); put("protocol", "freedom"); put("settings", JSONObject())
        })
        outbounds.put(JSONObject().apply {
            put("tag", "block"); put("protocol", "blackhole"); put("settings", JSONObject())
        })

        val root = JSONObject().apply {
            put("log", JSONObject().apply { put("loglevel", "warning") })
            put("inbounds", JSONArray().put(inbound))
            put("outbounds", outbounds)
            if (routing.has("rules")) put("routing", routing)
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

            misc:
              task-stack-size: 20480
              connect-timeout: 5000
              read-write-timeout: 60000
              log-level: warn
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
            // Must match android:foregroundServiceType="connectedDevice" in the manifest.
            // On API 34+ (UPSIDE_DOWN_CAKE) Android enforces this match strictly and throws
            // MissingForegroundServiceTypeException / SecurityException on mismatch.
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
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

        manager.setStatus(VpnStatus.DISCONNECTED)
        stopSelf()
    }

    override fun onDestroy() {
        stopVpn()
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
