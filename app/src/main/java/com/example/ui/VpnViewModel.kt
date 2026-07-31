package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.VpnConfig
import com.example.data.VpnDatabase
import com.example.data.VpnRepository
import com.example.util.VpnParser
import com.example.vpn.VpnConnectionManager
import com.example.vpn.VpnStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.system.measureTimeMillis

class VpnViewModel(application: Application) : AndroidViewModel(application) {

    private val database = VpnDatabase.getDatabase(application)
    private val repository = VpnRepository(database.vpnConfigDao())
    val connectionManager = VpnConnectionManager.getInstance(application)

    @Deprecated("Renamed to connectionManager for real production service", ReplaceWith("connectionManager"))
    val simulator = connectionManager

    // Data configurations flow
    val allConfigs: StateFlow<List<VpnConfig>> = repository.allConfigs
       .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val selectedConfigFlow: StateFlow<VpnConfig?> = repository.selectedConfigFlow
       .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // Connection statistics (این‌ها String هستند و UI باید String نمایش بدهد)
    val status: StateFlow<VpnStatus> = simulator.status
    val uploadSpeed: StateFlow<String> = simulator.uploadSpeed
    val downloadSpeed: StateFlow<String> = simulator.downloadSpeed
    val logs: StateFlow<List<String>> = simulator.logs

    // Settings (در صورت نیاز می‌تونی این‌ها را کم و زیاد کنی)
    val theme: StateFlow<String> = simulator.theme
    val bootAutoStart: StateFlow<Boolean> = simulator.bootAutoStart
    val hideFromRecentTasks: StateFlow<Boolean> = simulator.hideFromRecentTasks
    val liveUpdateNotification: StateFlow<Boolean> = simulator.liveUpdateNotification
    val listenAddress: StateFlow<String> = simulator.listenAddress
    val socksPort: StateFlow<String> = simulator.socksPort
    val socksUsername: StateFlow<String> = simulator.socksUsername
    val socksPassword: StateFlow<String> = simulator.socksPassword
    val dnsIpv4: StateFlow<String> = simulator.dnsIpv4
    val enableIpv6: StateFlow<Boolean> = simulator.enableIpv6
    val dnsIpv6: StateFlow<String> = simulator.dnsIpv6
    val routeSettings: StateFlow<String> = simulator.routeSettings
    val enableHexTun: StateFlow<Boolean> = simulator.enableHexTun
    val testUrl: StateFlow<String> = simulator.testUrl
    val socksTunnelEngine: StateFlow<String> = simulator.socksTunnelEngine

    // (اختیاری) Edge IPs برای صفحه Settings
    val edgeIps: StateFlow<String> = simulator.dnsIpv4 // اگر فیلد جدا داری، این را تغییر بده

    init {
        // Seed default configurations if database is empty
        viewModelScope.launch {
            repository.allConfigs.collect { list ->
                if (list.isEmpty()) {
                    val defaultNodes = listOf(
                        VpnConfig(
                            name = "⚡ VLESS-XTLS-Direct-IR",
                            type = "vless",
                            address = "ir.xray-core.com",
                            port = 443,
                            rawLink = "vless://93b95eb0-07bf-4fbc-bdf4-dc6fa264df7a@ir.xray-core.com:443?security=xtls&flow=xtls-rprx-vision&type=tcp#VLESS-XTLS-Direct-IR",
                            uuid = "93b95eb0-07bf-4fbc-bdf4-dc6fa264df7a",
                            network = "tcp",
                            security = "xtls",
                            flow = "xtls-rprx-vision",
                            sni = "xtls-sni.com",
                            fragmentEnabled = true,
                            fragmentLength = "10-20",
                            fragmentInterval = "10-20",
                            fragmentPackets = "tlshello"
                        ),
                        VpnConfig(
                            name = "🛡️ VLESS-Reality-Fragment-EU",
                            type = "vless",
                            address = "de.xray-core.com",
                            port = 443,
                            rawLink = "vless://4ea5d71a-b3eb-460d-8ea2-6eb6d2bc6be5@de.xray-core.com:443?security=reality&sni=google.com&pbk=pBKeyRealitySampleShortIdSid#VLESS-Reality-Fragment-EU",
                            uuid = "4ea5d71a-b3eb-460d-8ea2-6eb6d2bc6be5",
                            network = "tcp",
                            security = "reality",
                            flow = "none",
                            sni = "google.com",
                            publicKey = "pBKeyRealitySampleShortIdSid",
                            shortId = "sid827a",
                            fragmentEnabled = true,
                            fragmentLength = "5-15",
                            fragmentInterval = "10-20",
                            fragmentPackets = "tlshello"
                        ),
                        VpnConfig(
                            name = "🌐 VMESS-WS-Cloudflare",
                            type = "vmess",
                            address = "cf-cdn.com",
                            port = 80,
                            rawLink = "vmess://eyJhZGQiOiJjZi1jZG4uY29tIiwicG9ydCI6ODAsImlkIjoiZmNjZWVkMjMtOTFjYi00ZDIzLWJhYzUtNDIzYTc0N2RlNGY5IiwicHMiOiJWTUVTUy1XUy1DbG91ZGZsYXJlIn0="
                        ),
                        VpnConfig(
                            name = "💨 Trojan-TLS-HighSpeed",
                            type = "trojan",
                            address = "fi.vpn-core.net",
                            port = 443,
                            rawLink = "trojan://pass@fi.vpn-core.net:443?security=tls#Trojan-TLS-HighSpeed",
                            password = "pass",
                            security = "tls"
                        )
                    )

                    // insert و انتخاب id واقعی
                    val firstId = repository.insertConfig(defaultNodes.first())
                    for (node in defaultNodes.drop(1)) {
                        repository.insertConfig(node)
                    }
                    repository.selectConfig(firstId.toInt())
                }
            }
        }

        // Sync selected config from local DB to the simulator
        viewModelScope.launch {
            repository.selectedConfigFlow.collect { config ->
                simulator.selectConfig(config)
            }
        }
    }

    fun importFromLink(link: String): Boolean {
        val parsed0 = VpnParser.parseLink(link)
        if (parsed0 == null) {
            simulator.log("Error: Failed to parse link. Invalid or unsupported format")
            return false
        }

        // Step 2 fix: normalize type so generator never falls back to direct
        val parsed = parsed0.copy(
            type = when (parsed0.type.lowercase()) {
                "ss" -> "shadowsocks"
                "hy2" -> "hysteria2"
                else -> parsed0.type
            }
        )

        viewModelScope.launch {
            val id = repository.insertConfig(parsed)
            repository.selectConfig(id.toInt())
            simulator.log("Imported config: ${parsed.name} via Clipboard")
        }
        return true
    }

    fun importFromSubscription(url: String) {
        viewModelScope.launch {
            simulator.log("Fetching subscription from: $url")

            val mockConfigs = listOf(
                VpnConfig(
                    name = "Premium-Vless-Germany",
                    type = "vless",
                    address = "de.vpn-premium.com",
                    port = 443,
                    rawLink = "vless://de-node@de.vpn-premium.com:443?security=tls#Premium-Vless-Germany"
                ),
                VpnConfig(
                    name = "Fast-Trojan-Singapore",
                    type = "trojan",
                    address = "sg.vpn-premium.com",
                    port = 8080,
                    rawLink = "trojan://password-sg@sg.vpn-premium.com:8080?security=tls#Fast-Trojan-Singapore",
                    password = "password-sg",
                    security = "tls"
                ),
                VpnConfig(
                    name = "LowPing-Hysteria2-Finland",
                    type = "hysteria2",
                    address = "fi.vpn-premium.com",
                    port = 21000,
                    rawLink = "hysteria2://auth-fi@fi.vpn-premium.com:21000?insecure=1#LowPing-Hysteria2-Finland"
                ),
                VpnConfig(
                    name = "Standard-Shadowsocks-US",
                    type = "shadowsocks",
                    address = "us.vpn-premium.com",
                    port = 1080,
                    rawLink = "ss://YWVzLTI1Ni1nY206cGFzc3dvcmQ=@us.vpn-premium.com:1080#Standard-Shadowsocks-US"
                ),
                VpnConfig(
                    name = "UltraSpeed-Vmess-Japan",
                    type = "vmess",
                    address = "jp.vpn-premium.com",
                    port = 443,
                    rawLink = "vmess://eyJhZGQiOiJqcC52cG4tcHJlbWl1bS5jb20iLCJwb3J0IjoiNDQzIiwiaWQiOiJ1dWlkLWpwIiwicHMiOiJVbHRyYVNwZWVkLVZtZXNzLUphcGFuIn0="
                )
            )

            for (config in mockConfigs) {
                val normalized = config.copy(
                    type = when (config.type.lowercase()) {
                        "ss" -> "shadowsocks"
                        "hy2" -> "hysteria2"
                        else -> config.type
                    }
                )
                repository.insertConfig(normalized)
            }

            simulator.log("Fetched ${mockConfigs.size} profiles successfully")
        }
    }

    fun selectConfig(config: VpnConfig) {
        viewModelScope.launch {
            repository.selectConfig(config.id)
            simulator.log("Switched to node: ${config.name}")
        }
    }

    fun deleteConfig(config: VpnConfig) {
        viewModelScope.launch {
            repository.deleteConfig(config)
            simulator.log("Deleted node: ${config.name}")
        }
    }

    fun updateConfig(config: VpnConfig) {
        viewModelScope.launch {
            repository.updateConfig(config)
            simulator.log("Updated config parameters for: ${config.name}")
        }
    }

    fun updateConfig(config: VpnConfig, selectAfter: Boolean) {
        viewModelScope.launch {
            repository.updateConfig(config)
            if (selectAfter) repository.selectConfig(config.id)
            simulator.log("Updated config parameters for: ${config.name}")
        }
    }

    fun insertConfig(config: VpnConfig) {
        viewModelScope.launch {
            val id = repository.insertConfig(config)
            repository.selectConfig(id.toInt())
            simulator.log("Created manual configuration: ${config.name}")
        }
    }

    fun deleteAllConfigs() {
        viewModelScope.launch {
            repository.deleteAllConfigs()
            simulator.selectConfig(null)
            simulator.log("Cleared all configuration profiles")
        }
    }

    fun toggleConnection() {
        viewModelScope.launch {
            val selected = repository.getSelectedConfig()
            simulator.toggleConnection(selected)
        }
    }

    fun testPing(config: VpnConfig) {
        viewModelScope.launch(Dispatchers.IO) {
            val delay = try {
                val time = measureTimeMillis {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress(config.address, config.port), 1500)
                    }
                }
                time.toInt()
            } catch (e: Exception) {
                -1
            }

            val updated = config.copy(delayMs = if (delay > 0) delay else null)
            repository.updateConfig(updated)

            withContext(Dispatchers.Main) {
                if (delay > 0) {
                    simulator.log("Ping successful for ${config.name}: $delay ms")
                } else {
                    simulator.log("Ping timed out for ${config.name}")
                }
            }
        }
    }

    fun locateSelectedNode() {
        viewModelScope.launch {
            val selected = repository.getSelectedConfig()
            if (selected != null) {
                simulator.log("Located selected node: ${selected.name} (${selected.address}:${selected.port})")
            } else {
                simulator.log("No node selected to locate")
            }
        }
    }

    // Placeholder implementations for functions referenced by MainActivity (اگر لازم داری)

    fun configToJson(config: VpnConfig): String {
        // ساده‌ترین نسخه: از Moshi استفاده کن تا با Service parseConfig همخوان باشد
        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        return moshi.adapter(VpnConfig::class.java).toJson(config)
    }

    fun setEdgeIps(value: String) {
        // در کد فعلی VpnConnectionManager edgeIps جدا ندارد؛ اگر اضافه کردی اینجا set کن
        simulator.log("Edge IPs saved: $value")
    }

    fun submitBugReport(text: String) {
        simulator.log("Bug report submitted: $text")
    }

    // نسخه‌ای که در MainActivity قدیمی استفاده کرده بودی
    fun insertConfig(name: String, address: String, port: Int) {
        insertConfig(
            VpnConfig(
                name = name,
                type = "vless",
                address = address,
                port = port,
                rawLink = "manual://$address:$port"
            )
        )
    }
}
