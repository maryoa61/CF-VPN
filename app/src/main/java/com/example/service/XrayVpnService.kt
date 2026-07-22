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
import com.example.data.ServerEntity
import java.io.File
import java.io.FileWriter
import java.util.concurrent.atomic.AtomicBoolean

/**
 * سرویس اصلی VPN.
 *
 * معماری:
 *   TUN fd  --(hev-socks5-tunnel، ترد نیتیو بلاکینگ)-->  SOCKS5 روی 127.0.0.1:10808  --(Xray-core)-->  سرور فیلترشکن
 *
 * توجه مهم: Xray-core هیچ آگاهی‌ای از لایه TUN/VPN ندارد. او فقط یک کلاینت/سرور SOCKS
 * می‌بیند. تمام کار «تبدیل بسته‌های IP خام به کانکشن‌های TCP/UDP» را hev-socks5-tunnel
 * به صورت نیتیو (native thread) انجام می‌دهد.
 */
class V2RayVpnService : VpnService() {

    companion object {
        private const val TAG = "V2RayVpnService"

        const val ACTION_START = "com.example.service.action.START"
        const val ACTION_STOP = "com.example.service.action.STOP"
        const val EXTRA_SERVER = "extra_server_entity"

        private const val NOTIFICATION_CHANNEL_ID = "v2ray_vpn_channel"
        private const val NOTIFICATION_ID = 1001

        // مشخصات اینترفیس TUN - این مقادیر باید دقیقاً با فایل تنظیمات
        // hev-socks5-tunnel (tunnel.yaml) هم‌خوانی داشته باشد.
        private const val TUN_ADDRESS = "172.19.0.1"
        private const val TUN_PREFIX_LENGTH = 30
        private const val TUN_MTU = 1400
        private const val TUN_DNS = "1.1.1.1"
        private const val TUN_SESSION_NAME = "V2Ray VPN"
    }

    // ------------------------------------------------------------------
    // وضعیت داخلی سرویس
    // ------------------------------------------------------------------

    private var tunInterface: ParcelFileDescriptor? = null
    private var tunnelThread: Thread? = null
    private val isRunning = AtomicBoolean(false)

    // مسیر فایل کانفیگ Xray و فایل کانفیگ تونل که در cache ساخته می‌شوند
    private lateinit var xrayConfigFile: File
    private lateinit var tunnelConfigFile: File

    // ------------------------------------------------------------------
    // چرخه حیات سرویس
    // ------------------------------------------------------------------

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopVpn()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val server = intent.getParcelableExtraCompat<ServerEntity>(EXTRA_SERVER)
                if (server == null) {
                    Log.e(TAG, "ServerEntity ارسال نشده؛ سرویس متوقف می‌شود.")
                    stopSelf()
                    return START_NOT_STICKY
                }
                // برای جلوگیری از بلاک‌شدن ترد اصلی، ابتدا Foreground را بالا می‌آوریم
                // (الزام اندروید ۸+ که باید ظرف چند ثانیه startForeground صدا زده شود)
                startForeground(NOTIFICATION_ID, buildNotification())
                startVpn(server)
                return START_STICKY
            }
            else -> {
                Log.w(TAG, "Action نامشخص؛ سرویس نادیده گرفته شد.")
                return START_NOT_STICKY
            }
        }
    }

    override fun onRevoke() {
        // این متد وقتی صدا زده می‌شود که کاربر از تنظیمات سیستم دسترسی VPN را
        // برای اپ دیگری فعال کند یا مستقیماً وی‌پی‌ان را از سیستم قطع کند.
        Log.i(TAG, "onRevoke فراخوانی شد؛ در حال توقف ایمن سرویس.")
        stopVpn()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // راه‌اندازی
    // ------------------------------------------------------------------

    private fun startVpn(server: ServerEntity) {
        if (isRunning.get()) {
            Log.w(TAG, "سرویس از قبل در حال اجراست؛ درخواست تکراری نادیده گرفته شد.")
            return
        }

        try {
            // مرحله ۱: تولید و ذخیره کانفیگ Xray
            xrayConfigFile = File(cacheDir, "xray_config.json")
            val xrayConfigJson = XrayConfigGenerator.generate(server, filesDir)
            xrayConfigFile.writeText(xrayConfigJson)
            Log.d(TAG, "کانفیگ Xray در ${xrayConfigFile.absolutePath} نوشته شد.")

            // مرحله ۲: استارت هسته Xray (به صورت داخل-پروسه/JNI، غیر بلاکینگ)
            startXrayCore(xrayConfigFile)

            // مرحله ۳: ساخت اینترفیس TUN با VpnService.Builder
            val establishedFd = establishTunInterface()
                ?: throw IllegalStateException("establish() مقدار null برگرداند - مجوز VPN تایید نشده یا Builder نامعتبر است.")
            tunInterface = establishedFd

            // مرحله ۴: نوشتن فایل تنظیمات YAML برای hev-socks5-tunnel
            tunnelConfigFile = File(cacheDir, "tunnel_config.yaml")
            HevSocks5Tunnel.writeConfig(
                outFile = tunnelConfigFile,
                tunFd = establishedFd.fd,
                mtu = TUN_MTU,
                socksHost = "127.0.0.1",
                socksPort = XrayConfigGenerator.SOCKS_INBOUND_PORT
            )

            // مرحله ۵: اجرای ترد نیتیو بلاکینگ hev-socks5-tunnel
            // این متد تا زمانی که stop() صدا زده نشود برنمی‌گردد، پس حتماً
            // باید در یک ترد جدا (نه ترد اصلی/Binder) اجرا شود.
            tunnelThread = Thread({
                try {
                    Log.i(TAG, "ترد hev-socks5-tunnel شروع شد.")
                    HevSocks5Tunnel.start(tunnelConfigFile.absolutePath, establishedFd.fd)
                    Log.i(TAG, "ترد hev-socks5-tunnel به پایان رسید (متد start برگشت).")
                } catch (t: Throwable) {
                    Log.e(TAG, "خطای غیرمنتظره در ترد تونل: ${t.message}", t)
                    // اگر تونل به‌طور غیرمنتظره کرش کند، کل سرویس را با احتیاط متوقف می‌کنیم
                    // تا در وضعیت نیمه‌فعال (نشت‌آفرین) باقی نماند.
                    stopVpn()
                }
            }, "hev-socks5-tunnel-thread").apply {
                isDaemon = true
                start()
            }

            isRunning.set(true)
            Log.i(TAG, "V2RayVpnService با موفقیت راه‌اندازی شد.")
        } catch (e: Exception) {
            Log.e(TAG, "خطا در راه‌اندازی VPN: ${e.message}", e)
            // در صورت بروز خطا در هر مرحله، همه چیزی که تا اینجا باز شده را پاک‌سازی می‌کنیم
            cleanupAfterFailure()
            stopSelf()
        }
    }

    /**
     * ساخت و برقراری (establish) اینترفیس TUN.
     * تمام پارامترهای Builder دقیقاً باید با فایل YAML تونل هم‌خوانی داشته باشند.
     */
    private fun establishTunInterface(): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession(TUN_SESSION_NAME)
            .setMtu(TUN_MTU)
            .addAddress(TUN_ADDRESS, TUN_PREFIX_LENGTH)
            .addDnsServer(TUN_DNS)
            // هدایت تمام ترافیک IPv4 و IPv6 به داخل تونل.
            // نکته: اگر بخواهید فعلاً IPv6 را کامل مسدود کنید (رایج‌ترین علت نشت IPv6)
            // به‌جای addRoute("::/0",0) اصلاً آدرس IPv6 اضافه نکنید تا سیستم مسیر IPv6
            // را به‌کل غیرفعال کند؛ در اینجا چون addRoute صدا زده می‌شود، ترافیک IPv6
            // هم داخل تونل هدایت می‌شود (لازم است tunnel هم از IPv6 پشتیبانی کند).
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
            .allowFamily(android.system.OsConstants.AF_INET)
            .allowFamily(android.system.OsConstants.AF_INET6)

        // ------------------------------------------------------------------
        // Split Tunneling (Allow/Disallow Apps)
        // فقط یکی از دو حالت زیر باید فعال باشد؛ اندروید اگر هر دو صدا زده شوند
        // IllegalArgumentException می‌دهد. اینجا به صورت پیش‌فرض هیچ‌کدام صدا
        // زده نمی‌شود (یعنی همه اپ‌ها داخل تونل هستند) و کد نمونه برای توسعه بعدی
        // شما به صورت کامنت آماده است.
        // ------------------------------------------------------------------
        //
        // حالت الف) فقط اپ‌های مشخص از تونل عبور کنند (Whitelist):
        // try {
        //     builder.addAllowedApplication("com.example.someapp")
        // } catch (e: PackageManager.NameNotFoundException) {
        //     Log.w(TAG, "پکیج پیدا نشد: ${e.message}")
        // }
        //
        // حالت ب) همه اپ‌ها داخل تونل باشند به‌جز موارد استثنا (Blacklist) -
        // مثلاً اپ‌های بانکی که معمولاً به دلیل SSL Pinning یا سیاست‌های امنیتی
        // بهتر است از تونل خارج بمانند:
        // val excludedApps = listOf(
        //     "com.bank.mellat",
        //     "com.bank.melli",
        //     "ir.shaparak.samanpardakht"
        // )
        // excludedApps.forEach { pkg ->
        //     try {
        //         builder.addDisallowedApplication(pkg)
        //     } catch (e: PackageManager.NameNotFoundException) {
        //         Log.w(TAG, "پکیج $pkg پیدا نشد؛ نادیده گرفته شد.")
        //     }
        // }
        //
        // نکته حیاتی: خود اپلیکیشن جاری (پکیج خودتان) را همیشه باید یا با
        // addDisallowedApplication از تونل خارج کنید یا مطمئن شوید سوکت خروجی
        // Xray با protect() از تونل خارج شده (که در این کد از طریق متد protect
        // انجام می‌شود). در غیر این صورت لوپ اتصال (Loopback Loop) رخ می‌دهد.

        return builder.establish()
    }

    // ------------------------------------------------------------------
    // محافظت از سوکت خروجی (جلوگیری از لوپ)
    // ------------------------------------------------------------------

    /**
     * این متد باید از سمت لایه نیتیو Xray (از طریق JNI callback) صدا زده شود،
     * درست در لحظه‌ای که Xray سوکت TCP/UDP خروجی به سمت سرور واقعی فیلترشکن را
     * می‌سازد (قبل از connect). اگر این سوکت protect نشود، بسته‌های خروجی آن
     * دوباره وارد TUN می‌شوند و یک حلقه بی‌نهایت (loopback loop) ایجاد می‌کنند
     * که هم باعث قطعی کامل اینترنت می‌شود و هم مصرف batteries/CPU را می‌ترکاند.
     *
     * @param socketFd فایل دسکریپتور خام سوکت (از JNI/native layer دریافت می‌شود)
     * @return true اگر protect موفق بود
     */
    fun protectSocket(socketFd: Int): Boolean {
        return try {
            val result = protect(socketFd)
            if (!result) {
                Log.w(TAG, "protect() برای fd=$socketFd شکست خورد.")
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "خطا هنگام protect کردن سوکت: ${e.message}", e)
            false
        }
    }

    // ------------------------------------------------------------------
    // هسته Xray (شبیه‌سازی شده - در پروژه واقعی از طریق JNI/AAR به لایبرری
    // نیتیو Xray یا از طریق libv2ray/Xray wrapper وصل می‌شود)
    // ------------------------------------------------------------------

    /**
     * شروع هسته Xray با فایل کانفیگ تولید شده.
     *
     * در پیاده‌سازی واقعی این متد معمولاً یکی از این دو شکل را دارد:
     *   ۱) فراخوانی یک متد JNI مثل: XrayCoreJNI.run(configFile.absolutePath)
     *      که خودِ Xray-core (نوشته‌شده به Go و کامپایل‌شده با gomobile) را در
     *      یک ترد داخلی خودش (نه ترد شما) اجرا می‌کند و بلافاصله برمی‌گردد.
     *   ۲) استفاده از AAR رسمی مثل libXray که متدهایی مثل
     *      `Libv2ray.runV2Ray(...)` را expose می‌کند.
     *
     * نکته مهم: بر خلاف hev-socks5-tunnel، اجرای Xray-core از طریق gomobile
     * بلاکینگ نیست (خودش داخلاً گوروتین‌های Go را مدیریت می‌کند)، پس نیازی به
     * ساخت Thread جداگانه در این‌جا نیست؛ اما لاگ خطا و try/catch الزامی است
     * چون هرگونه خطای parse در کانفیگ JSON باعث پرتاب Exception از JNI می‌شود.
     */
    private fun startXrayCore(configFile: File) {
        if (!configFile.exists()) {
            throw IllegalStateException("فایل کانفیگ Xray پیدا نشد: ${configFile.absolutePath}")
        }
        // TODO: جایگزین کنید با فراخوانی واقعی JNI، مثلاً:
        // val errorMsg = Libv2ray.runV2Ray(configFile.absolutePath)
        // if (!errorMsg.isNullOrEmpty()) {
        //     throw IllegalStateException("Xray-core شروع نشد: $errorMsg")
        // }
        Log.i(TAG, "startXrayCore فراخوانی شد با مسیر: ${configFile.absolutePath} (شبیه‌سازی شده)")
    }

    /**
     * توقف هسته Xray. در پیاده‌سازی واقعی معادل چیزی شبیه
     * Libv2ray.stopV2Ray() یا XrayCoreJNI.stop() است.
     */
    private fun stopXrayCore() {
        // TODO: جایگزین کنید با فراخوانی واقعی، مثلاً: Libv2ray.stopV2Ray()
        Log.i(TAG, "stopXrayCore فراخوانی شد (شبیه‌سازی شده)")
    }

    // ------------------------------------------------------------------
    // توقف و پاک‌سازی
    // ------------------------------------------------------------------

    /**
     * توقف کامل و ایمن سرویس.
     * ترتیب بستن منابع بسیار مهم است و باید دقیقاً «معکوسِ» ترتیب ساخت آن‌ها باشد:
     *   ۱) اول tunnel نیتیو (hev-socks5-tunnel) را متوقف می‌کنیم تا دیگر از fd استفاده نکند
     *   ۲) سپس خود ParcelFileDescriptor تونل را می‌بندیم
     *   ۳) در آخر هسته Xray را متوقف می‌کنیم
     * اگر این ترتیب رعایت نشود ممکن است hev-socks5-tunnel روی fd بسته‌شده
     * عملیات read/write انجام دهد و باعث native crash (SIGSEGV) شود.
     */
    private fun stopVpn() {
        if (!isRunning.getAndSet(false)) {
            // از فراخوانی تکراری/همزمان جلوگیری می‌کند
            Log.d(TAG, "stopVpn فراخوانی شد اما سرویس از قبل متوقف بوده.")
            return
        }

        Log.i(TAG, "در حال توقف ایمن VPN...")

        // مرحله ۱: توقف ترد بلاکینگ تونل
        try {
            HevSocks5Tunnel.stop()
        } catch (e: Exception) {
            Log.e(TAG, "خطا هنگام توقف hev-socks5-tunnel: ${e.message}", e)
        }

        // منتظر می‌مانیم ترد نیتیو واقعاً از متد start() برگردد (حداکثر ۲ ثانیه،
        // تا اپ در onDestroy برای همیشه بلاک نشود)
        try {
            tunnelThread?.join(2000)
        } catch (e: InterruptedException) {
            Log.w(TAG, "join ترد تونل قطع شد: ${e.message}")
            Thread.currentThread().interrupt()
        }
        tunnelThread = null

        // مرحله ۲: بستن ایمن ParcelFileDescriptor - همیشه در try/finally
        try {
            tunInterface?.close()
        } catch (e: Exception) {
            Log.e(TAG, "خطا هنگام بستن ParcelFileDescriptor: ${e.message}", e)
        } finally {
            tunInterface = null
        }

        // مرحله ۳: توقف هسته Xray
        try {
            stopXrayCore()
        } catch (e: Exception) {
            Log.e(TAG, "خطا هنگام توقف Xray-core: ${e.message}", e)
        }

        // پاک‌سازی فایل‌های موقت کانفیگ (اختیاری اما توصیه‌شده برای امنیت/فضا)
        runCatching { if (::xrayConfigFile.isInitialized) xrayConfigFile.delete() }
        runCatching { if (::tunnelConfigFile.isInitialized) tunnelConfigFile.delete() }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()

        Log.i(TAG, "VPN با موفقیت متوقف شد.")
    }

    /**
     * پاک‌سازی ویژه‌ی حالت خطا هنگام startVpn (قبل از اینکه isRunning=true شود).
     * چون ترتیب دقیق مراحل شکست‌خورده نامشخص است، هر منبع را جداگانه و
     * محافظت‌شده (try/catch مجزا) می‌بندیم تا یک خطا مانع بسته‌شدن بقیه نشود.
     */
    private fun cleanupAfterFailure() {
        runCatching { tunnelThread?.interrupt() }
        tunnelThread = null

        runCatching { HevSocks5Tunnel.stop() }

        runCatching { tunInterface?.close() }
        tunInterface = null

        runCatching { stopXrayCore() }

        isRunning.set(false)
    }

    // ------------------------------------------------------------------
    // Notification برای Foreground Service
    // ------------------------------------------------------------------

    private fun buildNotification(): Notification {
        createNotificationChannelIfNeeded()

        val stopIntent = Intent(this, V2RayVpnService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("اتصال VPN فعال است")
            .setContentText("در حال محافظت از ترافیک شما")
            .setSmallIcon(android.R.drawable.ic_lock_lock) // آیکون واقعی پروژه خود را جایگزین کنید
            .setOngoing(true) // کاربر نمی‌تواند با سوایپ آن را ببندد
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
                    description = "نمایش وضعیت فعال اتصال VPN"
                    setShowBadge(false)
                }
                manager.createNotificationChannel(channel)
            }
        }
    }
}

// ------------------------------------------------------------------
// Helper: خواندن ایمن Parcelable با سازگاری روی نسخه‌های مختلف اندروید
// (getParcelableExtra ساده در اندروید ۱۳+ Deprecated شده است)
// ------------------------------------------------------------------
private inline fun <reified T : android.os.Parcelable> Intent.getParcelableExtraCompat(key: String): T? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(key, T::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(key) as? T
    }
}
