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
import com.example.service.XrayConfigGenerator
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * سرویس اصلی VPN — بهینه‌شده برای فیلترینگ ایران.
 *
 * معماری:
 *   TUN fd  ──(hev-socks5-tunnel)──▶  SOCKS5 (127.0.0.1:10808)  ──(Xray-core)──▶  سرور فیلترشکن
 *
 * بهینه‌سازی‌های ایران:
 *   - Fragment: شکستن TLS Hello برای فرار از DPI
 *   - Reality/XTLS: تقلید ترافیک از سایت‌های معروف
 *   - DNS رمزنگاری‌شده (DoH): جلوگیری از DNS Poisoning
 *   - Socket protect(): جلوگیری از loopback loop
 *   - Mux: ترکیب اتصالات برای مقابله با Throttling
 */
class XrayVpnService : VpnService() {

    companion object {
        private const val TAG = "XrayVpnService"

        const val ACTION_START = "com.example.service.action.START"
        const val ACTION_STOP = "com.example.service.action.STOP"
        const val EXTRA_CONFIG_JSON = "extra_config_json"

        private const val NOTIFICATION_CHANNEL_ID = "cf_vpn_channel"
        private const val NOTIFICATION_ID = 1001

        // تنظیمات TUN — باید با tunnel_config.yaml هماهنگ باشد
        private const val TUN_ADDRESS = "172.19.0.1"
        private const val TUN_PREFIX_LENGTH = 30
        private const val TUN_MTU = 1400
        private const val TUN_DNS = "1.1.1.1"
        private const val TUN_SESSION_NAME = "CF-VPN"

        // ── Xray Core JNI Bridge ──
        // libv2ray.aar (2dust/AndroidLibXrayLite) → io.coreny.v2ray.Libv2ray
        // libXray.aar (XTLS/libXray)               → xray.lib.Xray یا Libv2ray
        // اگر هیچکدام وجود نداشته باشد، startXrayCore خطا می‌دهد.
        private var xrayRunMethod: java.lang.reflect.Method? = null
        private var xrayStopMethod: java.lang.reflect.Method? = null

        init {
            // لیست کلاس‌های ممکن برای Xray AAR
            val candidates = listOf(
                "io.coreny.v2ray.Libv2ray",    // AndroidLibXrayLite (2dust)
                "io.coreny.Libv2ray",           // نسخه‌های قدیمی‌تر
                "xray.lib.Xray",                // libXray (XTLS)
                "xray.lib.Libv2ray",            // نسخه‌های جایگزین
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
                    // این کلاس وجود نداره، بعدی رو امتحان کن
                } catch (_: NoSuchMethodException) {
                    // کلاس هست ولی متدها فرق داره
                }
            }

            if (xrayRunMethod == null) {
                Log.w(TAG, "No compatible Xray AAR found. " +
                    "Tried: ${candidates.joinToString()}. " +
                    "Place libv2ray.aar or libXray.aar in app/libs/.")
            }
        }
    }

    // ── وضعیت داخلی ──
    private var tunInterface: ParcelFileDescriptor? = null
    private var tunnelThread: Thread? = null
    private val isRunning = AtomicBoolean(false)
    private lateinit var xrayConfigFile: File
    private lateinit var tunnelConfigFile: File

    // ── فایل کانفیگ Xray (برای protect کردن سوکت‌ها) ──
    private var currentConfig: VpnConfig? = null

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // چرخه حیات سرویس
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log.i(TAG, "STOP action received.")
                stopVpn()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                // دریافت کانفیگ از طریق JSON
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

                // Foreground service (الزام اندروید ۸+)
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

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // راه‌اندازی VPN
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private fun startVpn(config: VpnConfig) {
        if (isRunning.get()) {
            Log.w(TAG, "Service already running; duplicate request ignored.")
            return
        }

        currentConfig = config

        try {
            // ── مرحله ۱: تولید کانفیگ Xray (بهینه‌شده برای ایران) ──
            xrayConfigFile = File(cacheDir, "xray_config.json")
            val xrayConfigJson = XrayConfigGenerator.generate(config, filesDir)
            xrayConfigFile.writeText(xrayConfigJson)
            Log.d(TAG, "Xray config written to ${xrayConfigFile.absolutePath}")

            // ── مرحله ۲: استارت هسته Xray ──
            startXrayCore(xrayConfigFile)

            // ── مرحله ۳: ساخت TUN interface ──
            val establishedFd = establishTunInterface()
                ?: throw IllegalStateException(
                    "TUN establish() returned null — VPN permission not granted " +
                    "or Builder configuration is invalid."
                )
            tunInterface = establishedFd

            // ── مرحله ۴: نوشتن کانفیگ YAML برای hev-socks5-tunnel ──
            tunnelConfigFile = File(cacheDir, "tunnel_config.yaml")
            HevSocks5Tunnel.writeConfig(
                destFile = tunnelConfigFile,
                mtu = TUN_MTU,
                socksPort = XrayConfigGenerator.SOCKS_INBOUND_PORT
            )

            // ── مرحله ۵: اجرای hev-socks5-tunnel (ترد بلاکینگ) ──
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
            // اطلاع‌رسانی وضعیت به UI
            VpnConnectionManager.getInstance(this).setStatus(VpnStatus.CONNECTED)
            Log.i(TAG, "XrayVpnService started successfully for: ${config.name} (${config.type})")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting VPN: ${e.message}", e)
            cleanupAfterFailure()
            stopSelf()
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // TUN Interface — با protect() برای جلوگیری از Loop
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private fun establishTunInterface(): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession(TUN_SESSION_NAME)
            .setMtu(TUN_MTU)
            .addAddress(TUN_ADDRESS, TUN_PREFIX_LENGTH)
            .addDnsServer(TUN_DNS)
            // هدایت تمام ترافیک IPv4 به داخل تونل
            .addRoute("0.0.0.0", 0)
            // IPv6 فعال نمی‌شود (بسیاری از سرورهای VPN از IPv6 پشتیبانی نمی‌کنند
            // و فعال کردن آن ممکن است نشت IPv6 ایجاد کند)
            // .addRoute("::", 0)

            // ── Split Tunneling ──
            // خود اپلیکیشن باید از تونل خارج باشد تا loop ایجاد نشود.
            // اگر از protect() استفاده می‌کنیم، نیازی به addDisallowedApplication نیست
            // ولی به عنوان لایه اضافی ایمنی اضافه می‌کنیم:
            .addDisallowedApplication("com.aistudio.vpnclient.xdvryu")

        return builder.establish()
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // محافظت از سوکت خروجی (جلوگیری از Loopback Loop)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * این متد باید از طریق JNI callback توسط Xray-core فراخوانی شود.
     *
     * اگر سوکت خروجی Xray protect نشود:
     *   → بسته‌ها وارد TUN می‌شوند
     *   → دوباره به Xray برمی‌گردند
     *   → loop بی‌نهایت → قطعی اینترنت + مصرف CPU
     *
     * این متد توسط native layer فراخوانی می‌شود.
     */
    fun protectSocket(socketFd: Int): Boolean {
        return try {
            val result = protect(socketFd)
            if (!result) {
                Log.w(TAG, "protect() failed for fd=$socketFd")
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "Error protecting socket: ${e.message}", e)
            false
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // Xray Core — مدیریت از طریق libv2ray.aar / JNI
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * شروع هسته Xray با فایل کانفیگ.
     *
     * از reflection برای فراخوانی Libv2ray.runV2Ray() استفاده می‌شود
     * تا در صورت عدم وجود AAR، اپ کرش نکند (خطا به وضوح لاگ می‌شود).
     */
    private fun startXrayCore(configFile: File) {
        if (!configFile.exists()) {
            throw IllegalStateException("Xray config file not found: ${configFile.absolutePath}")
        }

        val runMethod = xrayRunMethod
        if (runMethod == null) {
            throw IllegalStateException(
                "libv2ray.aar is not available. " +
                "Please place libv2ray.aar (or libXray.aar) in app/libs/ " +
                "and ensure it exposes Libv2ray.runV2Ray(String):String. " +
                "Download from: https://github.com/XTLS/libXray/releases"
            )
        }

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

    /**
     * توقف هسته Xray.
     */
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

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // توقف و پاک‌سازی — ترتیب بسیار مهم است!
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * ترتیب بستن منابع (معکوسِ ترتیب باز کردن):
     *   ۱) hev-socks5-tunnel را متوقف → دیگر از fd استفاده نکند
     *   ۲) ParcelFileDescriptor تونل را ببندیم
     *   ۳) Xray-core را متوقف کنیم
     *
     * اگر ترتیب رعایت نشود → native crash (SIGSEGV)
     */
    private fun stopVpn() {
        if (!isRunning.getAndSet(false)) {
            Log.d(TAG, "stopVpn called but service was already stopped.")
            return
        }

        Log.i(TAG, "Stopping VPN safely...")
        // اطلاع‌رسانی وضعیت به UI
        VpnConnectionManager.getInstance(this).setStatus(VpnStatus.DISCONNECTED)

        // مرحله ۱: توقف hev-socks5-tunnel
        try {
            HevSocks5Tunnel.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping hev-socks5-tunnel: ${e.message}", e)
        }

        // منتظر برگشت ترد تونل (حداکثر ۲ ثانیه)
        try {
            tunnelThread?.join(2000)
        } catch (e: InterruptedException) {
            Log.w(TAG, "tunnel thread join interrupted: ${e.message}")
            Thread.currentThread().interrupt()
        }
        tunnelThread = null

        // مرحله ۲: بستن TUN interface
        try {
            tunInterface?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing TUN ParcelFileDescriptor: ${e.message}", e)
        } finally {
            tunInterface = null
        }

        // مرحله ۳: توقف Xray-core
        try {
            stopXrayCore()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping Xray-core: ${e.message}", e)
        }

        // پاک‌سازی فایل‌های موقت
        runCatching { if (::xrayConfigFile.isInitialized) xrayConfigFile.delete() }
        runCatching { if (::tunnelConfigFile.isInitialized) tunnelConfigFile.delete() }

        currentConfig = null

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()

        Log.i(TAG, "VPN stopped successfully.")
    }

    /**
     * پاک‌سازی در حالت خطا (قبل از isRunning=true).
     */
    private fun cleanupAfterFailure() {
        runCatching { tunnelThread?.interrupt() }
        tunnelThread = null
        runCatching { HevSocks5Tunnel.stop() }
        runCatching { tunInterface?.close() }
        tunInterface = null
        runCatching { stopXrayCore() }
        isRunning.set(false)
        // اطلاع‌رسانی وضعیت به UI
        runCatching { VpnConnectionManager.getInstance(this).setStatus(VpnStatus.DISCONNECTED) }
        currentConfig = null
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // Notification
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

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
            .setContentTitle("🛡️ CF-VPN فعال است")
            .setContentText("$protocolName | $configName")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "قطع اتصال",
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
                    "اتصال VPN",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "وضعیت اتصال VPN"
                    setShowBadge(false)
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // Helper: Parse VpnConfig from JSON
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

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
