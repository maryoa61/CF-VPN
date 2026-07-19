package com.example.cfvpn.data

/**
 * تنظیمات مربوط به Worker که ترافیک VPN از طریق آن تانل می‌شود.
 * این مقادیر مستقیماً از فیلدهای ورودی صفحه‌ی اصلی خوانده می‌شوند.
 */
data class WorkerConfig(
    val host: String = "",       // مثال: my-worker.username.workers.dev
    val path: String = "/",      // مسیر WebSocket، مثال: /ws
    val uuid: String = "",       // UUID کاربر (باید با مقدار داخل کد Worker یکی باشد)
    val port: Int = 443,
    val useTls: Boolean = true,
    val remark: String = "My Worker"
)

/**
 * اعتبارنامه‌های کلودفلر - این مقادیر حساس هستند و باید رمزنگاری‌شده ذخیره شوند.
 */
data class CloudflareCredentials(
    val accountId: String = "",
    val apiToken: String = "",   // Global API Token یا API Token با scope محدود (پیشنهاد می‌شود Scoped Token استفاده شود)
    val email: String = ""       // فقط در صورت استفاده از Global API Key به همراه ایمیل لازم است
)

/**
 * ظرف کلی تمام تنظیمات اپ که در DataStore ذخیره/بازیابی می‌شود.
 */
data class AppConfig(
    val worker: WorkerConfig = WorkerConfig(),
    val cloudflare: CloudflareCredentials = CloudflareCredentials()
)

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DISCONNECTING,
    ERROR
}

data class VpnStats(
    val uploadSpeedBps: Long = 0L,
    val downloadSpeedBps: Long = 0L,
    val connectedDurationSec: Long = 0L
)

/** نتیجه‌ی عملیات دیپلوی که از Repository برمی‌گردد. */
sealed class DeployResult {
    data class Success(val scriptName: String, val deployedUrl: String) : DeployResult()
    data class Failure(val message: String, val httpCode: Int? = null) : DeployResult()
}
