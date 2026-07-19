package com.example.cfvpn.vpn

import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File

/**
 * پوششی روی کتابخانه‌ی بومی hev-socks5-tunnel (https://github.com/heiher/hev-socks5-tunnel).
 *
 * این کتابخانه مسئول خواندنِ بسته‌های خام IP از فایل‌دیسکریپتور TUN و هدایتِ آن‌ها به
 * SOCKS5 محلی است که xray-core باز کرده (127.0.0.1:DEFAULT_SOCKS_PORT). بدون این لایه،
 * VpnService فقط یک TUN بی‌استفاده می‌سازد و به همین دلیل بود که اینترنت قطع می‌شد.
 *
 * پیش‌نیاز: فایل libcfvpn_tun2socks.so باید برای هر ABI ساخته و در
 * app/src/main/jniLibs/<abi>/ قرار گرفته باشد (به README.md مراجعه کنید).
 *
 * توابع native این کلاس مستقیماً روی API مستندشده‌ی hev-socks5-tunnel پیاده شده‌اند:
 *   hev_socks5_tunnel_main_from_str(config, len, tun_fd)  -> blocking
 *   hev_socks5_tunnel_quit()
 *   hev_socks5_tunnel_stats(...)
 */
class Tun2SocksManager {

    companion object {
        private const val TAG = "Tun2SocksManager"

        init {
            System.loadLibrary("cfvpn_tun2socks")
        }
    }

    private var workerThread: Thread? = null

    val isRunning: Boolean
        get() = workerThread?.isAlive == true

    /**
     * شروع تونل. این متد بلافاصله برمی‌گردد؛ حلقه‌ی اصلی hev-socks5-tunnel
     * (که بلاک‌کننده است) در یک ترد جدا اجرا می‌شود.
     *
     * @param tunFd فایل‌دیسکریپتور TUN که از VpnService.Builder.establish() گرفته شده
     * @param socksHost آدرس SOCKS5 محلی (معمولاً 127.0.0.1)
     * @param socksPort پورتی که xray-core روی آن گوش می‌دهد
     */
    fun start(tunFd: ParcelFileDescriptor, socksHost: String, socksPort: Int) {
        stop()

        val config = buildYamlConfig(socksHost = socksHost, socksPort = socksPort)
        val fd = tunFd.fd

        workerThread = Thread({
            try {
                val result = nativeStart(config, fd)
                if (result != 0) {
                    Log.e(TAG, "hev_socks5_tunnel_main_from_str exited with code $result")
                }
            } catch (e: Throwable) {
                Log.e(TAG, "tun2socks native thread crashed", e)
            }
        }, "tun2socks-worker").apply {
            isDaemon = true
            start()
        }
    }

    /** توقف تونل؛ باعث برگشتن nativeStart از حلقه‌ی بلاک‌شده می‌شود. */
    fun stop() {
        if (isRunning) {
            nativeStop()
            workerThread?.join(2000)
        }
        workerThread = null
    }

    /** آمار لحظه‌ای ترافیک تونل (بایت‌های ارسال/دریافت). */
    fun currentStats(): Tun2SocksStats {
        val raw = nativeStats() // [txPackets, txBytes, rxPackets, rxBytes]
        return Tun2SocksStats(
            txBytes = raw.getOrElse(1) { 0 },
            rxBytes = raw.getOrElse(3) { 0 }
        )
    }

    private fun buildYamlConfig(socksHost: String, socksPort: Int): String {
        // نام و آدرس اینترفیس اینجا صرفاً برای فایل کانفیگ hev-socks5-tunnel لازم است؛
        // چون خودِ TUN را VpnService.Builder می‌سازد، این کتابخانه TUN جدید نمی‌سازد
        // و فقط از روی tun_fd پاس‌شده کار می‌کند (فیلد tunnel.name نادیده گرفته می‌شود).
        return """
            tunnel:
              mtu: 1500
            socks5:
              address: $socksHost
              port: $socksPort
              udp: 'udp'
            misc:
              log-level: warn
              connect-timeout: 10000
              tcp-read-write-timeout: 300000
              udp-read-write-timeout: 60000
        """.trimIndent()
    }

    private external fun nativeStart(yamlConfig: String, tunFd: Int): Int
    private external fun nativeStop()
    private external fun nativeStats(): LongArray
}

data class Tun2SocksStats(
    val txBytes: Long,
    val rxBytes: Long
)
