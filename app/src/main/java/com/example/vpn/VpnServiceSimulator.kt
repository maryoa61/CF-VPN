package com.example.vpn

import android.content.Context
import com.example.data.VpnConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.random.Random

enum class VpnStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED
}

class VpnServiceSimulator private constructor(private val context: Context) {
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

    // Preferences simulator
    val theme = MutableStateFlow("Light")
    val appProxySettings = MutableStateFlow<List<String>>(emptyList())
    val bootAutoStart = MutableStateFlow(false)
    val hideFromRecentTasks = MutableStateFlow(false)
    val liveUpdateNotification = MutableStateFlow(true)
    val listenAddress = MutableStateFlow("127.0.0.1")
    val socksPort = MutableStateFlow("39519")
    val socksUsername = MutableStateFlow("paVCH9IO")
    val socksPassword = MutableStateFlow("pZoAi59k2eP8aQFm")
    val dnsIpv4 = MutableStateFlow("8.8.8.8,1.1.1.1")
    val enableIpv6 = MutableStateFlow(false)
    val dnsIpv6 = MutableStateFlow("8888::2001:4860:4860")
    val routeSettings = MutableStateFlow("custom route settings")
    val enableHexTun = MutableStateFlow(true)
    val testUrl = MutableStateFlow("https://www.google.com")
    val socksTunnelEngine = MutableStateFlow("HevSocks5Tunnel (C-based)")

    companion object {
        @Volatile
        private var INSTANCE: VpnServiceSimulator? = null

        fun getInstance(context: Context): VpnServiceSimulator {
            return INSTANCE ?: synchronized(this) {
                val instance = VpnServiceSimulator(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }
    }

    init {
        log("Ready to initialize AndroidLibXrayLite and HevSocks5Tunnel")
        log("Core: xray-core version 1.8.4")
        log("Library: AndroidLibXrayLite-v1.2.0")
        log("Tunnel: HevSocks5Tunnel-v2.1.1 initialized successfully")
    }

    fun selectConfig(config: VpnConfig?) {
        _selectedConfig.value = config
        log("Selected profile: ${config?.name ?: "None"} (${config?.type?.uppercase() ?: ""})")
    }

    fun toggleConnection(config: VpnConfig?) {
        if (config == null) {
            log("Error: No configuration file selected to start tunnel")
            return
        }

        if (_status.value == VpnStatus.DISCONNECTED) {
            startConnection(config)
        } else {
            stopConnection()
        }
    }

    private fun startConnection(config: VpnConfig) {
        scope.launch {
            _status.value = VpnStatus.CONNECTING
            log("Connecting to node: ${config.name}")
            log("Parsing configuration structure for ${config.type.uppercase()}...")
            delay(500)

            if (config.type == "vless") {
                log("[Xray] Configured transport: protocol=VLESS, transport=${config.network ?: "tcp"}")
                log("[Xray] Security settings: security=${config.security ?: "none"}, flow=${config.flow ?: "none"}, SNI=${config.sni ?: "none"}")
                if (config.security?.lowercase() == "reality") {
                    log("[Xray] REALITY settings applied: publicKey=${config.publicKey?.take(8) ?: "N/A"}... shortId=${config.shortId ?: "N/A"}")
                }
            }
            
            if (config.fragmentEnabled) {
                log("[Xray-obfuscation] FRAGMENT enabled: packets=${config.fragmentPackets ?: "tlshello"}, length=${config.fragmentLength ?: "10-20"}, interval=${config.fragmentInterval ?: "10-20"} ms (manual override)")
                log("[Xray-obfuscation] Fragmenting TLS client hello to bypass DPI...")
            }

            log("Starting AndroidLibXrayLite daemon service...")
            log("Loading socks5 listener on ${listenAddress.value}:${socksPort.value}")
            delay(500)

            log("Applying ${socksTunnelEngine.value} interface configuration (tun2socks mode)...")
            log("Bypassing system proxy, routing table updated via tun2socks interface.")
            delay(600)

            log("Testing connection handshake to ${config.address}:${config.port}...")
            delay(400)

            _status.value = VpnStatus.CONNECTED
            log("Tunnel connected successfully!")
            log("External routing interface established via xray core")
            
            startTrafficGeneration()
        }
    }

    private fun stopConnection() {
        scope.launch {
            log("Stopping VPN connection...")
            trafficJob?.cancel()
            delay(400)
            _status.value = VpnStatus.DISCONNECTED
            _uploadSpeed.value = "0.0 KB/s"
            _downloadSpeed.value = "0.0 KB/s"
            log("AndroidLibXrayLite daemon stopped")
            log("HevSocks5Tunnel deactivated")
            log("Disconnected")
        }
    }

    private fun startTrafficGeneration() {
        trafficJob?.cancel()
        trafficJob = scope.launch {
            while (isActive) {
                delay(1000)
                val upValue = Random.nextDouble(1.0, 50.0)
                val downValue = Random.nextDouble(5.0, 200.0)
                _uploadSpeed.value = String.format("%.1f KB/s", upValue)
                _downloadSpeed.value = String.format("%.1f KB/s", downValue)
            }
        }
    }

    fun log(message: String) {
        val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        val formattedLog = "[$timestamp] $message"
        _logs.value = listOf(formattedLog) + _logs.value
    }

    fun clearLogs() {
        _logs.value = emptyList()
        log("Logcat cleared")
    }
}
