package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.data.VpnConfig
import com.example.vpn.HevSocks5Tunnel
import com.example.vpn.VpnConnectionManager
import com.example.vpn.VpnStatus
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class XrayVpnService : VpnService() {

    companion object {
        private const val TAG = "XrayVpnService"

        const val ACTION_START = "com.example.service.action.START"
        const val ACTION_STOP = "com.example.service.action.STOP"
        const val EXTRA_CONFIG_JSON = "extra_config_json"

        private const val NOTIFICATION_CHANNEL_ID = "cf_vpn_channel"
        private const val NOTIFICATION_ID = 1001

        private const val TUN_ADDRESS = "172.19.0.1"
        private const val TUN_PREFIX_LENGTH = 30
        private const val TUN_MTU = 1400
        private const val TUN_DNS = "1.1.1.1"
        private const val TUN_SESSION_NAME = "CF-VPN"

        private var xrayRunMethod: java.lang.reflect.Method? = null
        private var xrayStopMethod: java.lang.reflect.Method? = null

        init {
            val candidates = listOf(
                "libv2ray.Libv2ray",
                "io.coreny.v2ray.Libv2ray",
                "io.coreny.Libv2ray",
                "xray.lib.Xray",
                "xray.lib.Libv2ray"
            )

            for (className in candidates) {
                try {
                    val clazz = Class.forName(className)
                    val run = clazz.getMethod("runV2Ray", String::class.java)
                    val stop = clazz.getMethod("stopV2Ray")
                    xrayRunMethod = run
                    xrayStopMethod = stop
                    Log.i(TAG, "Xray AAR loaded: $className")
                    break
                } catch (_: ClassNotFoundException) {
                } catch (_: NoSuchMethodException) {
                }
            }

            if (xrayRunMethod == null) {
                Log.w(
                    TAG,
                    "No compatible Xray AAR found. " +
                        "Tried: ${candidates.joinToString()}. " +
                        "Place libv2ray.aar or libXray.aar in app/libs/."
                )
            }
        }
    }

    private var tunInterface: ParcelFileDescriptor? = null
    private var tunnelThread: Thread? = null
    private val isRunning = AtomicBoolean(false)
    private lateinit var xrayConfigFile: File
    private lateinit var tunnelConfigFile: File

    private var currentConfig: VpnConfig? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log.i(TAG, "STOP action received.")
                stopVpn()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val configJson = intent.getStringExtra(EXTRA_CONFIG_JSON)
                if (configJson == null) {
                    Log.e(TAG, "EXTRA_CONFIG_JSON is null; stopping service.")
                    stopSelf()
                    return START_NOT_STICKY
                }

                val config = parseConfig(configJson)
                if (config == null) {
                    Log.e(TAG, "Failed to parse VpnConfig from JSON; stopping service.")
                    stopSelf()
                    return START_NOT_STICKY
                }

                startForeground(NOTIFICATION_ID, buildNotification())
                startVpn(config)
                return START_STICKY
            }
            else -> {
                Log.w(TAG, "Unknown action '${intent?.action}'; ignoring.")
                return START_NOT_STICKY
            }
        }
    }

    override fun onRevoke() {
        Log.i(TAG, "onRevoke called; stopping VPN safely.")
        stopVpn()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    private fun startVpn(config: VpnConfig) {
        if (isRunning.get()) {
            Log.w(TAG, "Service already running; duplicate request ignored.")
            return
        }

        currentConfig = config

        try {
            xrayConfigFile = File(cacheDir, "xray_config.json")
            val xrayConfigJson = XrayConfigGenerator.generate(config, filesDir)
            xrayConfigFile.writeText(xrayConfigJson)
            Log.d(TAG, "Xray config written to ${xrayConfigFile.absolutePath}")

            startXrayCore(xrayConfigFile)

            val establishedFd = establishTunInterface()
                ?: throw IllegalStateException("TUN establish() returned null")
            tunInterface = establishedFd

            tunnelConfigFile = File(cacheDir, "tunnel_config.yaml")
            HevSocks5Tunnel.writeConfig(
                destFile = tunnelConfigFile,
                mtu = TUN_MTU,
                socksPort = XrayConfigGenerator.SOCKS_INBOUND_PORT
            )

            tunnelThread = Thread({
                try {
                    Log.i(TAG, "hev-socks5-tunnel thread started.")
                    HevSocks5Tunnel.start(
                        tunnelConfigFile.absolutePath,
                        establishedFd.fd
                    )
                    Log.i(TAG, "hev-socks5-tunnel thread ended (start() returned).")
                } catch (t: Throwable) {
                    Log.e(TAG, "Unexpected error in tunnel thread: ${t.message}", t)
                    stopVpn()
                }
            }, "hev-socks5-tunnel-thread").apply {
                isDaemon = true
                start()
            }

            isRunning.set(true)
            VpnConnectionManager.getInstance(this).setStatus(VpnStatus.CONNECTED)
            Log.i(TAG, "XrayVpnService started successfully for: ${config.name} (${config.type})")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting VPN: ${e.message}", e)
            cleanupAfterFailure()
            stopSelf()
        }
    }

    private fun establishTunInterface(): ParcelFileDescriptor? {
        val builder = Builder()
           .setSession(TUN_SESSION_NAME)
           .setMtu(TUN_MTU)
           .addAddress(TUN_ADDRESS, TUN_PREFIX_LENGTH)
           .addDnsServer(TUN_DNS)
           .addRoute("0.0.0.0", 0)
           .addDisallowedApplication(packageName)

        return builder.establish()
    }

    fun protectSocket(socketFd: Int): Boolean {
        return try {
            val result = protect(socketFd)
            if (!result) Log.w(TAG, "protect() failed for fd=$socketFd")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Error protecting socket: ${e.message}", e)
            false
        }
    }

    private fun startXrayCore(configFile: File) {
        if (!configFile.exists()) {
            throw IllegalStateException("Xray config file not found: ${configFile.absolutePath}")
        }

        val runMethod = xrayRunMethod
            ?: throw IllegalStateException(
                "libv2ray.aar is not available. Place libv2ray.aar (or libXray.aar) in app/libs/."
            )

        try {
            Log.i(TAG, "Starting Xray-core with config: ${configFile.absolutePath}")
            val errorMsg = runMethod.invoke(null, configFile.absolutePath) as? String
            if (!errorMsg.isNullOrEmpty()) {
                throw IllegalStateException("Xray-core returned error: $errorMsg")
            }
            Log.i(TAG, "Xray-core started successfully.")
        } catch (e: java.lang.reflect.InvocationTargetException) {
            val cause = e.cause ?: e
            throw IllegalStateException("Xray-core JNI call failed: ${cause.message}", cause)
        }
    }

    private fun stopXrayCore() {
        val stopMethod = xrayStopMethod
        if (stopMethod == null) {
            Log.w(TAG, "libv2ray not available; skip stopXrayCore.")
            return
        }

        try {
            Log.i(TAG, "Stopping Xray-core...")
            stopMethod.invoke(null)
            Log.i(TAG, "Xray-core stopped.")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping Xray-core: ${e.message}", e)
        }
    }

    private fun stopVpn() {
        if (!isRunning.getAndSet(false)) {
            Log.d(TAG, "stopVpn called but service was already stopped.")
            return
        }

        Log.i(TAG, "Stopping VPN safely...")
        VpnConnectionManager.getInstance(this).setStatus(VpnStatus.DISCONNECTED)

        runCatching { HevSocks5Tunnel.stop() }
        runCatching { tunnelThread?.join(2000) }
        tunnelThread = null

        runCatching { tunInterface?.close() }
        tunInterface = null

        runCatching { stopXrayCore() }

        runCatching { if (::xrayConfigFile.isInitialized) xrayConfigFile.delete() }
        runCatching { if (::tunnelConfigFile.isInitialized) tunnelConfigFile.delete() }

        currentConfig = null

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()

        Log.i(TAG, "VPN stopped successfully.")
    }

    private fun cleanupAfterFailure() {
        runCatching { tunnelThread?.interrupt() }
        tunnelThread = null
        runCatching { HevSocks5Tunnel.stop() }
        runCatching { tunInterface?.close() }
        tunInterface = null
        runCatching { stopXrayCore() }
        isRunning.set(false)
        runCatching { VpnConnectionManager.getInstance(this).setStatus(VpnStatus.DISCONNECTED) }
        currentConfig = null
    }

    private fun buildNotification(): Notification {
        createNotificationChannelIfNeeded()

        val stopIntent = Intent(this, XrayVpnService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val configName = currentConfig?.name ?: "VPN"
        val protocolName = currentConfig?.type?.uppercase() ?: ""

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
           .setContentTitle("CF-VPN Active")
           .setContentText("$protocolName | $configName")
           .setSmallIcon(android.R.drawable.ic_lock_lock)
           .setOngoing(true)
           .setPriority(NotificationCompat.PRIORITY_LOW)
           .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Disconnect",
                stopPendingIntent
            )
           .build()
    }

    private fun createNotificationChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "VPN Connection",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "VPN connection status"
                    setShowBadge(false)
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    private fun parseConfig(json: String): VpnConfig? {
        return try {
            val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
            moshi.adapter(VpnConfig::class.java).fromJson(json)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse VpnConfig JSON: ${e.message}", e)
            null
        }
    }
}
