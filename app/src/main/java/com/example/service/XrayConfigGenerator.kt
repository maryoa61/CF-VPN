package com.example.service

import android.util.Log
import com.example.data.VpnConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * تولید کانفیگ Xray-core با تمرکز بر عبور از فیلترینگ شدید ایران.
 *
 * بهینه‌سازی‌های ایران:
 *   - Fragment: شکستن بسته‌های TLS Hello برای فرار از DPI
 *   - Reality: تقلید ترافیک از سایت‌های معروف (Google, Cloudflare)
 *   - XTLS-Vision: رمزنگاری حمل‌ونقل برای کاهش شناسایی
 *   - WebSocket + CDN: پنهان‌سازی ترافیک در ترافیک CDN
 *   - DoH/DNS رمزنگاری‌شده: جلوگیری از مسمومیت DNS توسط ISP
 *   - Fingerprint Chrome: تقلید اثرانگشت مرورگر واقعی
 *   - Routing: عبور مستقیم ترافیک داخلی ایران (سرعت بهتر)
 *   - Sniffing: شناسایی پروتکل واقعی از ترافیک خام
 *   - Mux: ترکیب اتصالات برای مقابله با Throttling
 */
object XrayConfigGenerator {

    private const val TAG = "XrayConfigGenerator"
    const val SOCKS_INBOUND_PORT = 10808

    /**
     * تولید JSON کانفیگ Xray از VpnConfig.
     * @return رشته JSON معتبر برای Xray-core
     */
    fun generate(config: VpnConfig, filesDir: File): String {
        val json = JSONObject()

        // ── لاگ ──
        json.put("log", JSONObject().apply {
            put("loglevel", "warning")
            put("access", "none")
            put("error", "none")
        })

        // ── DNS رمزنگاری‌شده (DoH) — جلوگیری از مسمومیت DNS ایران ──
        json.put("dns", buildDnsConfig())

        // ── Inbound: SOCKS5 روی localhost ──
        json.put("inbounds", buildInbounds())

        // ── Outbound: پروکسی اصلی ──
        val proxyOutbound = buildProxyOutbound(config)
        val directOutbound = buildDirectOutbound()
        val blockOutbound = buildBlockOutbound()
        json.put("outbounds", JSONArray().apply {
            put(proxyOutbound)
            put(directOutbound)
            put(blockOutbound)
        })

        // ── Routing: هدایت هوشمند ترافیک ──
        json.put("routing", buildRouting(config))

        // ── Policy ──
        json.put("policy", JSONObject().apply {
            put("system", JSONObject().apply {
                put("statsInboundUplink", false)
                put("statsInboundDownlink", false)
                put("statsOutboundUplink", false)
                put("statsOutboundDownlink", false)
            })
        })

        val result = json.toString(2)
        Log.d(TAG, "Xray config generated (${result.length} bytes) for protocol: ${config.type}")
        return result
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // DNS — جلوگیری از DNS Poisoning ایران
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private fun buildDnsConfig(): JSONObject {
        return JSONObject().apply {
            // سرورهای DoH ایران‌دوستانه
            put("servers", JSONArray().apply {
                // Cloudflare DoH
                put("https://1.1.1.1/dns-query")
                // Google DoH
                put("https://dns.google/dns-query")
                // Quad9 DoH
                put("https://dns.quad9.net/dns-query")
                // fallback به سرور خام (اگر DoH بلاک شد)
                put(JSONObject().apply {
                    put("address", "8.8.8.8")
                    put("port", 53)
                })
            })

            // دامنه‌های ایرانی → DNS مستقیم (سریع‌تر + جلوگیری از leak)
            put("queryStrategy", "UseIP")
            put("disableCache", false)
            put("disableFallback", false)
            put("disableExpire", false)

            // فعال‌سازی ClientSubnet برای بهتر DNS Resolution
            put("clientIp", "")
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // Inbound — SOCKS5 + HTTP
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private fun buildInbounds(): JSONArray {
        return JSONArray().apply {
            // SOCKS5 inbound — hev-socks5-tunnel به این وصل میشه
            put(JSONObject().apply {
                put("tag", "socks-in")
                put("port", SOCKS_INBOUND_PORT)
                put("listen", "127.0.0.1")
                put("protocol", "socks")
                put("settings", JSONObject().apply {
                    put("auth", "noauth")
                    put("udp", true)
                    put("allowTransparent", false)
                })
                // Sniffing: شناسایی پروتکل واقعی از ترافیک خام
                put("sniffing", JSONObject().apply {
                    put("enabled", true)
                    put("destOverride", JSONArray().apply {
                        put("http")
                        put("tls")
                        put("quic")
                    })
                    put("routeOnly", false)
                })
            })
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // Outbound — پروکسی اصلی با بهینه‌سازی ایران
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private fun buildProxyOutbound(config: VpnConfig): JSONObject {
        val outbound = JSONObject()
        outbound.put("tag", "proxy")

        // ── پروتکل ──
        when (config.type.lowercase()) {
            "vless" -> {
                outbound.put("protocol", "vless")
                outbound.put("settings", buildVlessSettings(config))
                outbound.put("streamSettings", buildStreamSettings(config))
            }
            "vmess" -> {
                outbound.put("protocol", "vmess")
                outbound.put("settings", buildVmessSettings(config))
                outbound.put("streamSettings", buildStreamSettings(config))
            }
            "trojan" -> {
                outbound.put("protocol", "trojan")
                outbound.put("settings", buildTrojanSettings(config))
                outbound.put("streamSettings", buildStreamSettings(config))
            }
            "shadowsocks" -> {
                outbound.put("protocol", "shadowsocks")
                outbound.put("settings", buildShadowsocksSettings(config))
                // Shadowsocks معمولاً streamSettings نداره
                // مگر اینکه از obfs استفاده بشه
            }
            else -> {
                Log.w(TAG, "Unsupported protocol: ${config.type}, falling back to freedom")
                outbound.put("protocol", "freedom")
            }
        }

        // ── Mux: ترکیب اتصالات ──
        // فقط برای TCP (نه WS، Reality/XTLS، gRPC)
        // WS+Mux با Cloudflare و بعضی سرورها مشکل داره
        if (config.security != "reality" && config.security != "xtls" &&
            config.network != "grpc" && config.network != "ws") {
            outbound.put("mux", JSONObject().apply {
                put("enabled", true)
                put("concurrency", 8)
                put("xudpConcurrency", 8)
                put("xudpPolicy", "zero-zerocopy")
            })
        }

        return outbound
    }

    // ── VLESS ──
    private fun buildVlessSettings(config: VpnConfig): JSONObject {
        return JSONObject().apply {
            put("vnext", JSONArray().apply {
                put(JSONObject().apply {
                    put("address", resolveAddress(config))
                    put("port", config.port)
                    put("users", JSONArray().apply {
                        put(JSONObject().apply {
                            put("id", config.uuid ?: "")
                            put("encryption", "none")
                            // XTLS-Vision: بهترین عملکرد در برابر فیلترینگ
                            if (config.flow == "xtls-rprx-vision") {
                                put("flow", "xtls-rprx-vision")
                            }
                        })
                    })
                })
            })
        }
    }

    // ── VMess ──
    private fun buildVmessSettings(config: VpnConfig): JSONObject {
        return JSONObject().apply {
            put("vnext", JSONArray().apply {
                put(JSONObject().apply {
                    put("address", resolveAddress(config))
                    put("port", config.port)
                    put("users", JSONArray().apply {
                        put(JSONObject().apply {
                            put("id", config.uuid ?: "")
                            put("alterId", config.alterId)
                            put("security", "auto")
                        })
                    })
                })
            })
        }
    }

    // ── Trojan ──
    private fun buildTrojanSettings(config: VpnConfig): JSONObject {
        return JSONObject().apply {
            put("servers", JSONArray().apply {
                put(JSONObject().apply {
                    put("address", resolveAddress(config))
                    put("port", config.port)
                    put("password", config.password ?: "")
                })
            })
        }
    }

    // ── Shadowsocks ──
    private fun buildShadowsocksSettings(config: VpnConfig): JSONObject {
        return JSONObject().apply {
            put("servers", JSONArray().apply {
                put(JSONObject().apply {
                    put("address", resolveAddress(config))
                    put("port", config.port)
                    put("method", config.method ?: "aes-256-gcm")
                    put("password", config.password ?: "")
                })
            })
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // Stream Settings — قلب بهینه‌سازی ضدفیلترینگ ایران
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private fun buildStreamSettings(config: VpnConfig): JSONObject {
        return JSONObject().apply {
            // ── نوع حمل‌ونقل ──
            put("network", config.network ?: "tcp")

            // ── امنیت ──
            when (config.security?.lowercase()) {
                "reality" -> {
                    put("security", "reality")
                    put("realitySettings", JSONObject().apply {
                        put("serverName", config.sni ?: config.address)
                        put("fingerprint", "chrome")  // تقلید اثرانگشت Chrome
                        put("publicKey", config.publicKey ?: "")
                        put("shortId", config.shortId ?: "")
                        put("spiderX", "")
                    })
                }
                "xtls" -> {
                    put("security", "xtls")
                    put("xtlsSettings", JSONObject().apply {
                        put("serverName", config.sni ?: config.address)
                        put("fingerprint", "chrome")
                        // Fragment برای XTLS — شکستن TLS Hello
                        if (config.fragmentEnabled) {
                            put("fragment", buildFragmentObject(config))
                        }
                    })
                }
                "tls" -> {
                    put("security", "tls")
                    put("tlsSettings", JSONObject().apply {
                        put("serverName", config.sni ?: config.address)
                        put("fingerprint", "chrome")  // تقلید Chrome
                        put("allowInsecure", false)
                        // Fragment برای TLS — حیاتی برای DPI ایران
                        if (config.fragmentEnabled) {
                            put("fragment", buildFragmentObject(config))
                        }
                    })
                }
                else -> {
                    put("security", "none")
                }
            }

            // ── Transport: WebSocket ──
            if (config.network == "ws") {
                put("wsSettings", JSONObject().apply {
                    put("path", config.wsPath ?: "/")
                    if (config.wsHost != null) {
                        put("headers", JSONObject().apply {
                            put("Host", config.wsHost)
                        })
                    }
                })
            }

            // ── Transport: gRPC ──
            if (config.network == "grpc") {
                put("grpcSettings", JSONObject().apply {
                    put("serviceName", "")
                })
            }
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // Fragment — کلید اصلی عبور از DPI ایران
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * Fragment بسته‌های TLS ClientHello رو به تکه‌های کوچک میشکنه
     * تا DPI نتونه SNI (نام دامنه) رو تشخیص بده.
     *
     * پارامترها برای ایران بهینه شده:
     *   - packets: "tlshello" → فقط Hello رو میشکنه (کارآمدتر)
     *   - length: "10-20" → اندازه تکه‌ها (خیلی کوچک = شناسایی سخت‌تر)
     *   - interval: "10-20" → فاصله بین تکه‌ها (جلوگیری از reassembly)
     */
    private fun buildFragmentObject(config: VpnConfig): JSONObject {
        return JSONObject().apply {
            put("enabled", true)
            put("packets", config.fragmentPackets ?: "tlshello")
            put("length", config.fragmentLength ?: "10-20")
            put("interval", config.fragmentInterval ?: "10-20")
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // Routing — هدایت هوشمند ترافیک
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * Routing RuleSet:
     *   1. دامنه‌های ایرانی → مستقیم (سرعت + جلوگیری از leak)
     *   2. آدرس‌های خصوصی (LAN) → مستقیم
     *   3. بقیه → پروکسی
     */
    private fun buildRouting(config: VpnConfig): JSONObject {
        return JSONObject().apply {
            put("domainStrategy", "AsIs")
            put("domainStrategy4", "AsIs")
            put("domainStrategy6", "AsIs")

            put("rules", JSONArray().apply {
                // ── قانون ۱: LAN → مستقیم ──
                put(JSONObject().apply {
                    put("type", "field")
                    put("outboundTag", "direct")
                    put("ip", JSONArray().apply {
                        put("geoip:private")
                    })
                })

                // ── قانون ۲: دامنه‌های ایرانی → مستقیم ──
                put(JSONObject().apply {
                    put("type", "field")
                    put("outboundTag", "direct")
                    put("domain", JSONArray().apply {
                        // دامنه‌های مرکزی ایران
                        put("geosite:ir")
                    })
                    put("ip", JSONArray().apply {
                        put("geoip:ir")
                    })
                })

                // ── قانون ۳: DNS ایرانی → مستقیم ──
                put(JSONObject().apply {
                    put("type", "field")
                    put("outboundTag", "direct")
                    put("port", JSONArray().apply {
                        put(53)
                    })
                    put("ip", JSONArray().apply {
                        put("geoip:ir")
                    })
                })

                // ── قانون ۴: بقیه → پروکسی ──
                put(JSONObject().apply {
                    put("type", "field")
                    put("outboundTag", "proxy")
                    put("port", JSONArray().apply {
                        put("0-65535")
                    })
                })
            })
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // Direct / Block Outbounds
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private fun buildDirectOutbound(): JSONObject {
        return JSONObject().apply {
            put("tag", "direct")
            put("protocol", "freedom")
            put("settings", JSONObject().apply {
                put("domainStrategy", "AsIs")
                put("udpConcurrency", 8)
            })
        }
    }

    private fun buildBlockOutbound(): JSONObject {
        return JSONObject().apply {
            put("tag", "block")
            put("protocol", "blackhole")
            put("settings", JSONObject().apply {
                put("response", JSONObject().apply {
                    put("type", "none")
                })
            })
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // Helper: آدرس Edge IP (DN-Fronting)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * اگر edgeIps تنظیم شده باشد، یک IP تصادفی از pool انتخاب می‌شود
     * تا ترافیک از IP‌های مختلف CDN عبور کنه (جلوگیری از throttling).
     */
    private fun resolveAddress(config: VpnConfig): String {
        val edgeIps = config.edgeIps
        if (!edgeIps.isNullOrBlank()) {
            val ips = edgeIps.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            if (ips.isNotEmpty()) {
                val selected = ips.random()
                Log.d(TAG, "Edge IP selected: $selected (from ${ips.size} candidates)")
                return selected
            }
        }
        return config.address
    }
}
