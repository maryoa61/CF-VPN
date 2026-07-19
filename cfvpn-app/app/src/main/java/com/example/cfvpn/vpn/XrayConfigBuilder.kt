package com.example.cfvpn.vpn

import com.example.cfvpn.data.WorkerConfig
import org.json.JSONArray
import org.json.JSONObject

/**
 * می‌سازد کانفیگ JSON مورد نیاز Xray-core برای اتصال VLESS+WS(+TLS)
 * به یک Cloudflare Worker که به عنوان outbound عمل می‌کند.
 *
 * Xray به صورت لوکال روی [localSocksPort] یک SOCKS5 inbound باز می‌کند؛
 * VpnService ترافیک TUN را به همین پورت هدایت می‌کند.
 */
object XrayConfigBuilder {

    fun build(config: WorkerConfig, localSocksPort: Int = 10808): String {
        val root = JSONObject()

        root.put("log", JSONObject().apply {
            put("loglevel", "warning")
        })

        // ---------- Inbounds: لوکال SOCKS که VpnService به آن وصل می‌شود ----------
        val inbounds = JSONArray()
        inbounds.put(
            JSONObject().apply {
                put("tag", "socks-in")
                put("port", localSocksPort)
                put("listen", "127.0.0.1")
                put("protocol", "socks")
                put("settings", JSONObject().apply {
                    put("udp", true)
                    put("auth", "noauth")
                })
                put("sniffing", JSONObject().apply {
                    put("enabled", true)
                    put("destOverride", JSONArray().put("http").put("tls"))
                })
            }
        )
        root.put("inbounds", inbounds)

        // ---------- Outbounds: VLESS به سمت Worker از طریق WebSocket+TLS ----------
        val vnextUser = JSONObject().apply {
            put("id", config.uuid)
            put("encryption", "none")
        }

        val vnextEntry = JSONObject().apply {
            put("address", config.host)
            put("port", config.port)
            put("users", JSONArray().put(vnextUser))
        }

        val streamSettings = JSONObject().apply {
            put("network", "ws")
            put("security", if (config.useTls) "tls" else "none")
            if (config.useTls) {
                put("tlsSettings", JSONObject().apply {
                    put("serverName", config.host)
                    put("allowInsecure", false)
                })
            }
            put("wsSettings", JSONObject().apply {
                put("path", config.path)
                put("headers", JSONObject().apply {
                    put("Host", config.host)
                })
            })
        }

        val proxyOutbound = JSONObject().apply {
            put("tag", "proxy")
            put("protocol", "vless")
            put("settings", JSONObject().apply {
                put("vnext", JSONArray().put(vnextEntry))
            })
            put("streamSettings", streamSettings)
        }

        val directOutbound = JSONObject().apply {
            put("tag", "direct")
            put("protocol", "freedom")
            put("settings", JSONObject())
        }

        root.put("outbounds", JSONArray().put(proxyOutbound).put(directOutbound))

        // ---------- Routing: ترافیک داخلی/lan مستقیم، بقیه از پروکسی ----------
        val routing = JSONObject().apply {
            put("domainStrategy", "AsIs")
            put("rules", JSONArray().put(
                JSONObject().apply {
                    put("type", "field")
                    put("ip", JSONArray().put("geoip:private"))
                    put("outboundTag", "direct")
                }
            ))
        }
        root.put("routing", routing)

        return root.toString(2)
    }
}
