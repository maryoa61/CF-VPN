package com.example.cfvpn.vpn

import android.content.Context
import android.util.Log
import com.example.cfvpn.data.WorkerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * مسئول کپی‌کردن باینری xray از assets به filesDir، اجرا و توقف پروسه از طریق ProcessBuilder.
 * این کلاس عمداً ساده نگه‌داشته شده (بدون وابستگی به AIDL/Binder) چون فقط برای اجرای
 * یک هسته‌ی SOCKS محلی نیاز است، نه یک کلاینت V2Ray کامل.
 */
class XrayCoreManager(private val context: Context) {

    companion object {
        private const val TAG = "XrayCoreManager"
        private const val ASSET_BINARY_NAME = "xray"      // نام فایل باینری داخل assets/
        private const val LOCAL_BINARY_NAME = "xray"       // نام فایل بعد از کپی به filesDir
        private const val CONFIG_FILE_NAME = "xray_config.json"
        const val DEFAULT_SOCKS_PORT = 10808
    }

    private var process: Process? = null

    val isRunning: Boolean
        get() = process?.isAlive == true

    /** کپی باینری xray از assets به filesDir و اعطای مجوز اجرا. Idempotent است. */
    suspend fun ensureBinaryInstalled(): File = withContext(Dispatchers.IO) {
        val outFile = File(context.filesDir, LOCAL_BINARY_NAME)

        val needsCopy = !outFile.exists() || outFile.length() == 0L
        if (needsCopy) {
            context.assets.open(ASSET_BINARY_NAME).use { input ->
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output)
                }
            }
        }

        if (!outFile.setExecutable(true, true)) {
            Log.w(TAG, "setExecutable(true) failed for ${outFile.absolutePath}")
        }
        // اطمینان از دسترسی خواندن/نوشتن برای پروسه‌ی خودمان
        outFile.setReadable(true, true)

        outFile
    }

    /** ساخت فایل کانفیگ JSON بر اساس تنظیمات Worker وارد شده توسط کاربر. */
    suspend fun writeConfig(workerConfig: WorkerConfig, socksPort: Int = DEFAULT_SOCKS_PORT): File =
        withContext(Dispatchers.IO) {
            val configJson = XrayConfigBuilder.build(workerConfig, socksPort)
            val configFile = File(context.filesDir, CONFIG_FILE_NAME)
            configFile.writeText(configJson)
            configFile
        }

    /**
     * اجرای هسته‌ی xray با فایل کانفیگ مشخص‌شده.
     * خروجی استاندارد/خطا به لاگ هدایت می‌شود تا برای دیباگ در دسترس باشد.
     */
    suspend fun start(workerConfig: WorkerConfig, socksPort: Int = DEFAULT_SOCKS_PORT): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                stop() // اگر پروسه‌ی قبلی در حال اجرا بود، اول متوقفش کن

                val binary = ensureBinaryInstalled()
                val configFile = writeConfig(workerConfig, socksPort)

                val builder = ProcessBuilder(
                    binary.absolutePath,
                    "run",
                    "-config", configFile.absolutePath
                )
                    .directory(context.filesDir)
                    .redirectErrorStream(true)

                process = builder.start()

                // لاگ‌گیری غیرمسدودکننده از خروجی پروسه (اختیاری، مفید برای دیباگ)
                Thread {
                    try {
                        process?.inputStream?.bufferedReader()?.forEachLine { line ->
                            Log.d(TAG, "xray: $line")
                        }
                    } catch (_: Exception) {
                        // پروسه بسته شده؛ نادیده گرفتن
                    }
                }.apply { isDaemon = true }.start()

                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start xray-core", e)
                Result.failure(e)
            }
        }

    /** متوقف کردن هسته‌ی در حال اجرا. */
    suspend fun stop() = withContext(Dispatchers.IO) {
        process?.let {
            try {
                it.destroy()
                if (it.isAlive) it.destroyForcibly()
            } catch (e: Exception) {
                Log.w(TAG, "Error while stopping xray-core", e)
            }
        }
        process = null
    }
}
