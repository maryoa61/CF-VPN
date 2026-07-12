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

    // Connection statistics
    val status: StateFlow<VpnStatus> = simulator.status
    val uploadSpeed: StateFlow<String> = simulator.uploadSpeed
    val downloadSpeed: StateFlow<String> = simulator.downloadSpeed
    val logs: StateFlow<List<String>> = simulator.logs

    // Settings
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
                    for (node in defaultNodes) {
                        repository.insertConfig(node)
                    }
                    // Auto-select first node
                    repository.selectConfig(1)
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
        val parsed = VpnParser.parseLink(link)
        return if (parsed != null) {
            viewModelScope.launch {
                val id = repository.insertConfig(parsed)
                repository.selectConfig(id.toInt())
                simulator.log("Imported config: ${parsed.name} via Clipboard")
            }
            true
        } else {
            simulator.log("Error: Failed to parse link. Invalid or unsupported format")
            false
        }
    }

    fun importFromSubscription(url: String) {
        viewModelScope.launch {
            simulator.log("Fetching subscription from: $url")
            // Mocking profile list returned from subscription link
            val mockConfigs = listOf(
                VpnConfig(name = "Premium-Vless-Germany", type = "vless", address = "de.vpn-premium.com", port = 443, rawLink = "vless://de-node@de.vpn-premium.com:443?security=tls#Premium-Vless-Germany"),
                VpnConfig(name = "Fast-Trojan-Singapore", type = "trojan", address = "sg.vpn-premium.com", port = 8080, rawLink = "trojan://password-sg@sg.vpn-premium.com:8080?security=tls#Fast-Trojan-Singapore", password = "password-sg", security = "tls"),
                VpnConfig(name = "LowPing-Hysteria2-Finland", type = "hysteria2", address = "fi.vpn-premium.com", port = 21000, rawLink = "hysteria2://auth-fi@fi.vpn-premium.com:21000?insecure=1#LowPing-Hysteria2-Finland"),
                VpnConfig(name = "Standard-Shadowsocks-US", type = "shadowsocks", address = "us.vpn-premium.com", port = 1080, rawLink = "ss://YWVzLTI1Ni1nY206cGFzc3dvcmQ=@us.vpn-premium.com:1080#Standard-Shadowsocks-US"),
                VpnConfig(name = "UltraSpeed-Vmess-Japan", type = "vmess", address = "jp.vpn-premium.com", port = 443, rawLink = "vmess://eyJhZGQiOiJqcC52cG4tcHJlbWl1bS5jb20iLCJwb3J0IjoiNDQzIiwiaWQiOiJ1dWlkLWpwIiwicHMiOiJVbHRyYVNwZWVkLVZtZXNzLUphcGFuIn0=")
            )
            for (config in mockConfigs) {
                repository.insertConfig(config)
            }
            simulator.log("Fetched 5 profiles successfully")
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

    // Setters for settings
    fun setTheme(mode: String) {
        simulator.theme.value = mode
        simulator.log("Theme updated to: $mode")
    }

    fun setBootAutoStart(enabled: Boolean) {
        simulator.bootAutoStart.value = enabled
        simulator.log("Boot Auto Start set to: $enabled")
    }

    fun setHideFromRecentTasks(enabled: Boolean) {
        simulator.hideFromRecentTasks.value = enabled
        simulator.log("Hide from Recent Tasks set to: $enabled")
    }

    fun setLiveUpdateNotification(enabled: Boolean) {
        simulator.liveUpdateNotification.value = enabled
        simulator.log("Live Update Notification set to: $enabled")
    }

    fun setListenAddress(address: String) {
        simulator.listenAddress.value = address
        simulator.log("SOCKS5 Listen Address set to: $address")
    }

    fun setSocksPort(port: String) {
        simulator.socksPort.value = port
        simulator.log("SOCKS5 Port set to: $port")
    }

    fun setSocksUsername(username: String) {
        simulator.socksUsername.value = username
        simulator.log("SOCKS5 Username set to: $username")
    }

    fun setSocksPassword(password: String) {
        simulator.socksPassword.value = password
        simulator.log("SOCKS5 Password updated")
    }

    fun setDnsIpv4(dns: String) {
        simulator.dnsIpv4.value = dns
        simulator.log("DNS IPv4 updated to: $dns")
    }

    fun setEnableIpv6(enabled: Boolean) {
        simulator.enableIpv6.value = enabled
        simulator.log("IPv6 Proxy " + (if (enabled) "enabled" else "disabled"))
    }

    fun setDnsIpv6(dns: String) {
        simulator.dnsIpv6.value = dns
        simulator.log("DNS IPv6 updated to: $dns")
    }

    fun setRouteSettings(settings: String) {
        simulator.routeSettings.value = settings
        simulator.log("Route settings updated to: $settings")
    }

    fun setEnableHexTun(enabled: Boolean) {
        simulator.enableHexTun.value = enabled
        simulator.log("Hex TUN " + (if (enabled) "enabled" else "disabled") + " as third-party tool")
    }

    fun setTestUrl(url: String) {
        simulator.testUrl.value = url
        simulator.log("Test URL updated to: $url")
    }

    fun setSocksTunnelEngine(engine: String) {
        simulator.socksTunnelEngine.value = engine
        simulator.log("SOCKS Tunnel Engine changed to: $engine")
    }
}
