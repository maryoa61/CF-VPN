package com.example.cfvpn.data

/**
 * انتزاع ارتباط با Cloudflare API. هرگونه جزئیات HTTP باید پشت این اینترفیس پنهان بماند
 * تا ViewModel/UI هیچ وابستگی‌ای به کتابخانه‌ی شبکه نداشته باشند.
 */
interface CloudflareRepository {

    /**
     * دیپلوی (آپلود) کد یک Worker به اکانت کلودفلر کاربر.
     *
     * @param credentials accountId + apiToken کاربر
     * @param scriptName نام Worker که باید ساخته/به‌روزرسانی شود
     * @param workerSourceCode کد جاوااسکریپت Worker (فایل .js که باید آپلود شود)
     * @return [DeployResult] موفق یا ناموفق
     */
    suspend fun deployWorkerToCloudflare(
        credentials: CloudflareCredentials,
        scriptName: String,
        workerSourceCode: String
    ): DeployResult

    /** بررسی صحت accountId/apiToken قبل از دیپلوی (اختیاری ولی مفید برای UX). */
    suspend fun verifyCredentials(credentials: CloudflareCredentials): Boolean

    /** فهرست Worker‌های موجود در اکانت، برای نمایش در تنظیمات. */
    suspend fun listWorkers(credentials: CloudflareCredentials): List<String>
}

/**
 * پیاده‌سازی مبتنی بر OkHttp روی Cloudflare API v4.
 * توجه: این کلاس فقط اسکلت درخواست‌هاست؛ منطق کامل خطایابی/ریتری در نسخه‌ی نهایی افزوده شود.
 *
 * مستندات رسمی:
 * PUT https://api.cloudflare.com/client/v4/accounts/{account_id}/workers/scripts/{script_name}
 */
class CloudflareRepositoryImpl(
    private val httpClient: okhttp3.OkHttpClient = okhttp3.OkHttpClient()
) : CloudflareRepository {

    private val baseUrl = "https://api.cloudflare.com/client/v4"

    override suspend fun deployWorkerToCloudflare(
        credentials: CloudflareCredentials,
        scriptName: String,
        workerSourceCode: String
    ): DeployResult {
        return try {
            val url = "$baseUrl/accounts/${credentials.accountId}/workers/scripts/$scriptName"

            val body = okhttp3.MultipartBody.Builder()
                .setType(okhttp3.MultipartBody.FORM)
                .addFormDataPart(
                    "metadata", null,
                    okhttp3.RequestBody.create(
                        "application/json".toMediaType(),
                        """{"main_module":"$scriptName.js"}"""
                    )
                )
                .addFormDataPart(
                    "$scriptName.js", "$scriptName.js",
                    okhttp3.RequestBody.create(
                        "application/javascript+module".toMediaType(),
                        workerSourceCode
                    )
                )
                .build()

            val request = okhttp3.Request.Builder()
                .url(url)
                .put(body)
                .header("Authorization", "Bearer ${credentials.apiToken}")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    DeployResult.Success(
                        scriptName = scriptName,
                        deployedUrl = "https://$scriptName.${credentials.accountId}.workers.dev"
                    )
                } else {
                    DeployResult.Failure(
                        message = response.body?.string() ?: "Unknown error",
                        httpCode = response.code
                    )
                }
            }
        } catch (e: Exception) {
            DeployResult.Failure(message = e.message ?: "Network error")
        }
    }

    override suspend fun verifyCredentials(credentials: CloudflareCredentials): Boolean {
        return try {
            val request = okhttp3.Request.Builder()
                .url("$baseUrl/accounts/${credentials.accountId}")
                .header("Authorization", "Bearer ${credentials.apiToken}")
                .get()
                .build()
            httpClient.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun listWorkers(credentials: CloudflareCredentials): List<String> {
        return try {
            val request = okhttp3.Request.Builder()
                .url("$baseUrl/accounts/${credentials.accountId}/workers/scripts")
                .header("Authorization", "Bearer ${credentials.apiToken}")
                .get()
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                // TODO: پارس JSON پاسخ با kotlinx.serialization یا Moshi و استخراج فیلد result[].id
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun String.toMediaType() = okhttp3.MediaType.parse(this)
        ?: okhttp3.MediaType.parse("text/plain")!!
}
