package com.example.cfvpn.vpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.cfvpn.data.WorkerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * VpnService که رابط TUN را برپا می‌کند، xray-core را (روی 127.0.0.1:DEFAULT_SOCKS_PORT)
 * اجرا می‌کند، و با [Tun2SocksManager] بسته‌های خامِ TUN را به همان SOCKS محلی هدایت می‌کند.
 *
 * ترتیب صحیح راه‌اندازی (رعایتش برای اتصال واقعی اینترنت ضروری است):
 *   ۱. xray-core را اجرا کن تا SOCKS محلی باز شود
 *   ۲. TUN را برپا کن (VpnService.Builder.establish)
 *   ۳. tun2socks را با fd همان TUN و آدرس SOCKS محلی اجرا کن
 * اگر مرحله‌ی ۳ حذف شود، گوشی فکر می‌کند از VPN رد می‌شود ولی بسته‌ها به هیچ‌جا نمی‌رسند
 * (دقیقاً همان علامتِ «اینترنت تونل نمی‌شود»).
 */
class CfVpnService : VpnService() {

    companion object {
        const val ACTION_CONNECT = "com.example.cfvpn.CONNECT"
        const val ACTION_DISCONNECT = "com.example.cfvpn.DISCONNECT"
        const val EXTRA_HOST = "extra_host"
        const val EXTRA_PATH = "extra_path"
        const val EXTRA_UUID = "extra_uuid"
        const val EXTRA_PORT = "extra_port"
        const val EXTRA_USE_TLS = "extra_use_tls"

        private const val NOTIF_CHANNEL_ID = "cfvpn_channel"
        private const val NOTIF_ID = 1
    }

    private var tunInterface: ParcelFileDescriptor? = null
    private lateinit var xrayCoreManager: XrayCoreManager
    private lateinit var tun2SocksManager: Tun2SocksManager
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        xrayCoreManager = XrayCoreManager(applicationContext)
        tun2SocksManager = Tun2SocksManager()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val config = WorkerConfig(
                    host = intent.getStringExtra(EXTRA_HOST).orEmpty(),
                    path = intent.getStringExtra(EXTRA_PATH) ?: "/",
                    uuid = intent.getStringExtra(EXTRA_UUID).orEmpty(),
                    port = intent.getIntExtra(EXTRA_PORT, 443),
                    useTls = intent.getBooleanExtra(EXTRA_USE_TLS, true)
                )
                connect(config)
            }
            ACTION_DISCONNECT -> disconnect()
        }
        return START_STICKY
    }

    private fun connect(config: WorkerConfig) {
        serviceScope.launch {
            try {
                // ۱. اجرای هسته‌ی xray که روی SOCKS محلی گوش می‌دهد
                val startResult = xrayCoreManager.start(config, XrayCoreManager.DEFAULT_SOCKS_PORT)
                startResult.getOrThrow()

                // به xray کمی زمان بده تا پورت SOCKS را باز کند، قبل از این‌که
                // tun2socks بخواهد به آن وصل شود.
                kotlinx.coroutines.delay(300)

                // ۲. برپاسازی رابط TUN
                establishTunInterface()
                val tun = tunInterface ?: throw IllegalStateException("VPN permission not granted / establish() failed")

                // ۳. راه‌اندازی لایه‌ی tun2socks: از این لحظه بسته‌های خامِ TUN
                //    به 127.0.0.1:DEFAULT_SOCKS_PORT (یعنی به xray) هدایت می‌شوند.
                //    توجه: ParcelFileDescriptor باید تا پایان اتصال زنده (open) بماند
                //    چون tun2socks مستقیماً از روی همان fd کار می‌کند.
                tun2SocksManager.start(
                    tunFd = tun,
                    socksHost = "127.0.0.1",
                    socksPort = XrayCoreManager.DEFAULT_SOCKS_PORT
                )

                showForegroundNotification(connected = true)
            } catch (e: Exception) {
                Log.e("CfVpnService", "connect() failed", e)
                stopSelfCleanly()
            }
        }
    }

    private fun disconnect() {
        serviceScope.launch {
            tun2SocksManager.stop()
            xrayCoreManager.stop()
            tunInterface?.close()
            tunInterface = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun establishTunInterface() {
        val builder = Builder()
            .setSession("CfVpn")
            .setMtu(1500)
            .addAddress("10.0.0.2", 32)
            .addDnsServer("1.1.1.1")
            .addDnsServer("1.0.0.1")
            .addRoute("0.0.0.0", 0)

        tunInterface = builder.establish()
    }

    private fun stopSelfCleanly() {
        serviceScope.launch {
            tun2SocksManager.stop()
            xrayCoreManager.stop()
            tunInterface?.close()
            tunInterface = null
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIF_CHANNEL_ID,
                "CF VPN Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun showForegroundNotification(connected: Boolean) {
        val notification = NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle("CF VPN")
            .setContentText(if (connected) "متصل" else "قطع شده")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .build()
        startForeground(NOTIF_ID, notification)
    }

    override fun onDestroy() {
        serviceScope.launch {
            tun2SocksManager.stop()
            xrayCoreManager.stop()
        }
        tunInterface?.close()
        super.onDestroy()
    }

    override fun onRevoke() {
        disconnect()
        super.onRevoke()
    }
}
