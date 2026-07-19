package com.example.cfvpn.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File

/**
 * DataStore پایه برای تنظیمات غیر حساس (Host/Path/UUID) و نیز به عنوان لایه‌ی fallback.
 * توکن‌های حساس (apiToken) علاوه بر DataStore، با EncryptedFile (Jetpack Security / Tink)
 * روی دیسک هم رمزنگاری می‌شوند تا در صورت روت‌شدن دستگاه یا بکاپ ابری خام، خوانده نشوند.
 *
 * نکته: androidx.datastore.preferences به‌خودی‌خود مقادیر را در قالب متن ساده (XML/Proto)
 * ذخیره می‌کند و رمزنگاری نمی‌کند؛ به همین دلیل توکن را جدا و رمزنگاری‌شده نگه می‌داریم.
 */
private val Context.dataStore by preferencesDataStore(name = "cfvpn_settings")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val HOST = stringPreferencesKey("worker_host")
        val PATH = stringPreferencesKey("worker_path")
        val UUID = stringPreferencesKey("worker_uuid")
        val PORT = intPreferencesKey("worker_port")
        val USE_TLS = booleanPreferencesKey("worker_use_tls")
        val REMARK = stringPreferencesKey("worker_remark")
        val ACCOUNT_ID = stringPreferencesKey("cf_account_id")
        val EMAIL = stringPreferencesKey("cf_email")
        // توکن در DataStore ذخیره نمی‌شود؛ فقط پرچم می‌گوییم که آیا فایل رمزنگاری‌شده موجود است.
        val HAS_TOKEN = booleanPreferencesKey("cf_has_token")
    }

    val appConfigFlow: Flow<AppConfig> = context.dataStore.data.map { prefs ->
        AppConfig(
            worker = WorkerConfig(
                host = prefs[Keys.HOST] ?: "",
                path = prefs[Keys.PATH] ?: "/",
                uuid = prefs[Keys.UUID] ?: "",
                port = prefs[Keys.PORT] ?: 443,
                useTls = prefs[Keys.USE_TLS] ?: true,
                remark = prefs[Keys.REMARK] ?: "My Worker"
            ),
            cloudflare = CloudflareCredentials(
                accountId = prefs[Keys.ACCOUNT_ID] ?: "",
                email = prefs[Keys.EMAIL] ?: "",
                apiToken = "" // توکن جداگانه و به‌صورت رمزنگاری‌شده خوانده می‌شود، نه از اینجا
            )
        )
    }

    suspend fun saveWorkerConfig(config: WorkerConfig) {
        context.dataStore.edit { prefs ->
            prefs[Keys.HOST] = config.host
            prefs[Keys.PATH] = config.path
            prefs[Keys.UUID] = config.uuid
            prefs[Keys.PORT] = config.port
            prefs[Keys.USE_TLS] = config.useTls
            prefs[Keys.REMARK] = config.remark
        }
    }

    suspend fun saveCloudflareBasics(accountId: String, email: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.ACCOUNT_ID] = accountId
            prefs[Keys.EMAIL] = email
        }
    }

    // ---------- ذخیره‌ی رمزنگاری‌شده‌ی API Token ----------

    private fun tokenFile(): File = File(context.filesDir, "cf_token.enc")

    private fun masterKey() = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    suspend fun saveApiToken(token: String) {
        val file = tokenFile()
        if (file.exists()) file.delete()
        val encryptedFile = EncryptedFile.Builder(
            context,
            file,
            masterKey(),
            EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
        ).build()
        encryptedFile.openFileOutput().use { it.write(token.toByteArray(Charsets.UTF_8)) }

        context.dataStore.edit { prefs -> prefs[Keys.HAS_TOKEN] = true }
    }

    fun readApiTokenOrNull(): String? {
        val file = tokenFile()
        if (!file.exists()) return null
        val encryptedFile = EncryptedFile.Builder(
            context,
            file,
            masterKey(),
            EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
        ).build()
        return encryptedFile.openFileInput().use { it.readBytes().toString(Charsets.UTF_8) }
    }

    suspend fun clearApiToken() {
        tokenFile().delete()
        context.dataStore.edit { prefs -> prefs[Keys.HAS_TOKEN] = false }
    }
}
