package com.example.vpn

import android.content.Context
import android.net.TrafficStats
import com.example.data.VpnConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.*

enum class VpnStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED
}

class VpnConnectionManager private constructor(private val context: Context) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val _status = MutableStateFlow(VpnStatus.DISCONNECTED)
    val status: StateFlow<VpnStatus> = _status.asStateFlow()

    private val _selectedConfig = MutableStateFlow<VpnConfig?>(null)
    val selectedConfig: StateFlow<VpnConfig?> = _selectedConfig.asStateFlow()

    private val _uploadSpeed = MutableStateFlow("0.0 KB/s")
    val uploadSpeed: StateFlow<String> = _uploadSpeed.asStateFlow()

    private val _downloadSpeed = MutableStateFlow("0.0 KB/s")
    val downloadSpeed: StateFlow<String> = _downloadSpeed.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private var trafficJob: Job? = null

    // Preferences and Core configuration states (with default values matching production profiles)
    val theme = MutableStateFlow("Dark")
    val appProxySettings = MutableStateFlow<List<String>>(emptyList())
    val bootAutoStart = MutableStateFlow(false)
    val hideFromRecentTasks = MutableStateFlow(false)
    val liveUpdateNotification = MutableStateFlow(true)
    val listenAddress = MutableStateFlow("127.0.0.1")
    val socksPort = MutableStateFlow("10808")
    val socksUsername = MutableStateFlow("")
    val socksPassword = MutableStateFlow("")
    val dnsIpv4 = MutableStateFlow("8.8.8.8,1.1.1.1")
    val enableIpv6 = MutableStateFlow(false)
    val dnsIpv6 = MutableStateFlow("2001:4860:4860::8888")
    val routeSettings = MutableStateFlow("bypass LAN and mainland")
    val enableHexTun = MutableStateFlow(true)
    val testUrl = MutableStateFlow("https://www.google.com")
    val socksTunnelEngine = MutableStateFlow("System VpnService Engine")

    companion object {
        @Volatile
        private var INSTANCE: VpnConnectionManager? = null

        fun getInstance(context: Context): VpnConnectionManager {
            return INSTANCE ?: synchronized(this) {
                val instance = VpnConnectionManager(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }
    }

    init {
        log("VPN Connection Engine Initialized successfully")
        log("Core: Android VpnService framework wrapper ready")
    }

    fun selectConfig(config: VpnConfig?) {
        _selectedConfig.value = config
        log("Selected profile: ${config?.name ?: "None"} (${config?.type?.uppercase() ?: ""})")
    }

    fun setStatus(newStatus: VpnStatus) {
        _status.value = newStatus
        if (newStatus == VpnStatus.CONNECTED) {
            log("Tunnel connected successfully!")
            startTrafficMonitoring()
        } else if (newStatus == VpnStatus.DISCONNECTED) {
            log("Tunnel disconnected")
            stopTrafficMonitoring()
        }
    }

    fun toggleConnection(config: VpnConfig?) {
        if (config == null) {
            log("Error: No configuration selected to start the VPN")
            return
        }

        // The actual service activation/deactivation is triggered via MainActivity's intents to properly bind with the Android OS lifecycle.
        if (_status.value == VpnStatus.DISCONNECTED) {
            _status.value = VpnStatus.CONNECTING
            log("Requesting VPN connection for ${config.name} (${config.address}:${config.port})...")
        } else {
            log("Requesting VPN termination...")
        }
    }

    private fun startTrafficMonitoring() {
        trafficJob?.cancel()
        trafficJob = scope.launch(Dispatchers.IO) {
            var lastTxBytes = TrafficStats.getUidTxBytes(android.os.Process.myUid())
            var lastRxBytes = TrafficStats.getUidRxBytes(android.os.Process.myUid())
            if (lastTxBytes == TrafficStats.UNSUPPORTED.toLong()) lastTxBytes = 0
            if (lastRxBytes == TrafficStats.UNSUPPORTED.toLong()) lastRxBytes = 0

            while (isActive) {
                delay(1000)
                var currentTxBytes = TrafficStats.getUidTxBytes(android.os.Process.myUid())
                var currentRxBytes = TrafficStats.getUidRxBytes(android.os.Process.myUid())
                if (currentTxBytes == TrafficStats.UNSUPPORTED.toLong()) currentTxBytes = 0
                if (currentRxBytes == TrafficStats.UNSUPPORTED.toLong()) currentRxBytes = 0

                val txDiff = if (currentTxBytes >= lastTxBytes) currentTxBytes - lastTxBytes else 0
                val rxDiff = if (currentRxBytes >= lastRxBytes) currentRxBytes - lastRxBytes else 0

                lastTxBytes = currentTxBytes
                lastRxBytes = currentRxBytes

                withContext(Dispatchers.Main) {
                    _uploadSpeed.value = formatSpeed(txDiff)
                    _downloadSpeed.value = formatSpeed(rxDiff)
                }
            }
        }
    }

    private fun stopTrafficMonitoring() {
        trafficJob?.cancel()
        _uploadSpeed.value = "0.0 KB/s"
        _downloadSpeed.value = "0.0 KB/s"
    }

    private fun formatSpeed(bytes: Long): String {
        return if (bytes < 1024) {
            "$bytes B/s"
        } else if (bytes < 1024 * 1024) {
            String.format(Locale.US, "%.1f KB/s", bytes / 1024.0)
        } else {
            String.format(Locale.US, "%.1f MB/s", bytes / (1024.0 * 1024.0))
        }
    }

    fun log(message: String) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val formattedLog = "[$timestamp] $message"
        _logs.value = listOf(formattedLog) + _logs.value
    }

    fun clearLogs() {
        _logs.value = emptyList()
        log("Logs cleared successfully")
    }
}
