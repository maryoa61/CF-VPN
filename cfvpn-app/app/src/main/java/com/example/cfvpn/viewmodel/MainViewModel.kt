package com.example.cfvpn.viewmodel

import android.app.Application
import android.content.Intent
import android.net.VpnService
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.cfvpn.data.CloudflareCredentials
import com.example.cfvpn.data.CloudflareRepository
import com.example.cfvpn.data.CloudflareRepositoryImpl
import com.example.cfvpn.data.ConnectionState
import com.example.cfvpn.data.DeployResult
import com.example.cfvpn.data.SettingsRepository
import com.example.cfvpn.data.VpnStats
import com.example.cfvpn.data.WorkerConfig
import com.example.cfvpn.vpn.CfVpnService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel واحد برای هر دو صفحه (Home و Settings). state با StateFlow منتشر می‌شود
 * تا در Compose با collectAsStateWithLifecycle مصرف شود.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsRepository = SettingsRepository(application)
    private val cloudflareRepository: CloudflareRepository = CloudflareRepositoryImpl()

    // ---------- Worker config ----------
    private val _workerConfig = MutableStateFlow(WorkerConfig())
    val workerConfig: StateFlow<WorkerConfig> = _workerConfig.asStateFlow()

    // ---------- Cloudflare credentials (accountId در state، token جدا و رمزنگاری‌شده) ----------
    private val _cloudflareCredentials = MutableStateFlow(CloudflareCredentials())
    val cloudflareCredentials: StateFlow<CloudflareCredentials> = _cloudflareCredentials.asStateFlow()

    // ---------- Connection ----------
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _vpnStats = MutableStateFlow(VpnStats())
    val vpnStats: StateFlow<VpnStats> = _vpnStats.asStateFlow()

    // ---------- Deploy ----------
    private val _deployInProgress = MutableStateFlow(false)
    val deployInProgress: StateFlow<Boolean> = _deployInProgress.asStateFlow()

    private val _deployResult = MutableStateFlow<DeployResult?>(null)
    val deployResult: StateFlow<DeployResult?> = _deployResult.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepository.appConfigFlow.collect { appConfig ->
                _workerConfig.value = appConfig.worker
                _cloudflareCredentials.value = appConfig.cloudflare.copy(
                    apiToken = settingsRepository.readApiTokenOrNull() ?: ""
                )
            }
        }
    }

    // ---------- Worker config actions ----------

    fun updateWorkerHost(value: String) {
        _workerConfig.value = _workerConfig.value.copy(host = value)
    }

    fun updateWorkerPath(value: String) {
        _workerConfig.value = _workerConfig.value.copy(path = value)
    }

    fun updateWorkerUuid(value: String) {
        _workerConfig.value = _workerConfig.value.copy(uuid = value)
    }

    fun persistWorkerConfig() {
        viewModelScope.launch {
            settingsRepository.saveWorkerConfig(_workerConfig.value)
        }
    }

    // ---------- Cloudflare credential actions ----------

    fun updateAccountId(value: String) {
        _cloudflareCredentials.value = _cloudflareCredentials.value.copy(accountId = value)
    }

    fun updateApiToken(value: String) {
        _cloudflareCredentials.value = _cloudflareCredentials.value.copy(apiToken = value)
    }

    fun persistCloudflareCredentials() {
        val creds = _cloudflareCredentials.value
        viewModelScope.launch {
            settingsRepository.saveCloudflareBasics(creds.accountId, creds.email)
            if (creds.apiToken.isNotBlank()) {
                settingsRepository.saveApiToken(creds.apiToken)
            }
        }
    }

    // ---------- VPN connect/disconnect ----------

    /**
     * قبل از صدازدن این متد، UI باید از طریق VpnService.prepare(context) مجوز بگیرد؛
     * اگر prepare() یک Intent برگرداند، باید با startActivityForResult نمایش داده شود.
     */
    fun connectVpn() {
        val app = getApplication<Application>()
        val config = _workerConfig.value

        if (config.host.isBlank() || config.uuid.isBlank()) {
            _connectionState.value = ConnectionState.ERROR
            return
        }

        _connectionState.value = ConnectionState.CONNECTING
        persistWorkerConfig()

        val intent = Intent(app, CfVpnService::class.java).apply {
            action = CfVpnService.ACTION_CONNECT
            putExtra(CfVpnService.EXTRA_HOST, config.host)
            putExtra(CfVpnService.EXTRA_PATH, config.path)
            putExtra(CfVpnService.EXTRA_UUID, config.uuid)
            putExtra(CfVpnService.EXTRA_PORT, config.port)
            putExtra(CfVpnService.EXTRA_USE_TLS, config.useTls)
        }
        app.startService(intent)

        // در پروژه‌ی واقعی این وضعیت باید از طریق Binder/Broadcast از خودِ سرویس بیاید؛
        // اینجا برای سادگی یک تاخیر کوتاه شبیه‌سازی شده است.
        viewModelScope.launch {
            delay(800)
            _connectionState.value = ConnectionState.CONNECTED
            startFakeStatsTicker()
        }
    }

    fun disconnectVpn() {
        val app = getApplication<Application>()
        _connectionState.value = ConnectionState.DISCONNECTING

        val intent = Intent(app, CfVpnService::class.java).apply {
            action = CfVpnService.ACTION_DISCONNECT
        }
        app.startService(intent)

        viewModelScope.launch {
            delay(300)
            _connectionState.value = ConnectionState.DISCONNECTED
            _vpnStats.value = VpnStats()
        }
    }

    /** باید در Activity قبل از connectVpn صدا زده شود تا مجوز VPN گرفته شود. */
    fun vpnPermissionIntent(): Intent? = VpnService.prepare(getApplication())

    private fun startFakeStatsTicker() {
        // TODO: مقادیر واقعی سرعت را از طریق IPC با CfVpnService/xray دریافت کنید
        // (مثلاً با خوانادن /proc/net/dev برای UID اپ یا آماری که خود xray گزارش می‌دهد).
    }

    // ---------- Deploy worker ----------

    fun deployWorker(scriptName: String, workerSourceCode: String) {
        viewModelScope.launch {
            _deployInProgress.value = true
            _deployResult.value = null
            val result = cloudflareRepository.deployWorkerToCloudflare(
                credentials = _cloudflareCredentials.value,
                scriptName = scriptName,
                workerSourceCode = workerSourceCode
            )
            _deployResult.value = result
            _deployInProgress.value = false
        }
    }
}
