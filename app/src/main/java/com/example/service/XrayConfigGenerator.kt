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

    /** تگ balancer روتینگ و پیشوند تگ outboundهای edge — باید همراستا باشند. */
    const val BALANCER_TAG = "edge-balance"
    const val EDGE_TAG_PREFIX = "edge-"
    const val PROXY_TAG = "proxy"

    /**
     * تولید JSON کانفیگ Xray از VpnConfig.
     *
     * @param edgePool لیست IPهای لبه (Edge/Cloudflare) که قبلاً توسط
     *        [com.example.vpn.IpPoolManager.scanAndRankBlocking] رتبه‌بندی شده‌اند.
     *        اگر خالی باشد یک outbound ثابت (proxy) با آدرس اصلی ساخته می‌شود.
     *        اگر غیرخالی باشد به ازای هر IP یک outbound تگ‌شده (`edge-0..n`)
     *        و یک load balancer با استراتژی random می‌سازیم تا Xray به‌ازای هر
     *        اتصال یک IP تصادفی از pool را انتخاب کند (SNI ثابت می‌ماند) —
     *        بدون نیاز به بارگذاری مجدد کانفیگ.
     *
     * @return رشته JSON معتبر برای Xray-core
     */
    fun generate(config: VpnConfig, filesDir: File, edgePool: List<String> = emptyList()): String {
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

        // ── Outbound: پروکسی اصلی (edge pool یا تک آدرس) ──
        val edgeActive = edgePool.isNotEmpty()
        val directOutbound = buildDirectOutbound()
        val blockOutbound = buildBlockOutbound()
        val proxyOutbounds = JSONArray().apply {
            if (edgeActive) {
                edgePool.distinct().forEachIndexed { index, ip ->
                    put(buildProxyOutbound(config, tag = "edge-$index", address = ip))
                }
                // پایه‌ی پروکسی (آدرس اصلی) به‌عنوان fallback — در بالانس‌ر نیست،
                // فقط وقتی همه‌ی لبه‌ها از کار بیفتند استفاده می‌شود (هنوز پروکسی‌شده؛
                // هرگز به `direct` برنمی‌گردد تا نشت ترافیک رخ ندهد).
                put(buildProxyOutbound(config, tag = PROXY_TAG, address = config.address))
            } else {
                put(buildProxyOutbound(config, tag = PROXY_TAG, address = config.address))
            }
        }
        json.put("outbounds", JSONArray().apply {
            for (i in 0 until proxyOutbounds.length()) {
                put(proxyOutbounds.get(i))
            }
            put(directOutbound)
            put(blockOutbound)
        })

        // ── Routing: هدایت هوشمند ترافیک ──
        json.put("routing", buildRouting(edgeActive))

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
            // فقط DoH رمزنگاری‌شده — هیچ DNS خام/md5 اینجا نیست.
            // (قبلاً یک fallback خام `8.8.8.8:53` بود که در ایران مسموّم/بلاک می‌شود و
            //  همیشه راه را برای شنود DNS باز می‌گذاشت؛ حذف شد تا DNS صددرصد رمزنگاری‌شده بماند.)
            put("servers", JSONArray().apply {
                // Cloudflare DoH (IP: بدون وابستگی به رزولوشن DNS)
                put("https://1.1.1.1/dns-query")
                // Google DoH
                put("https://dns.google/dns-query")
                // Quad9 DoH
                put("https://dns.quad9.net/dns-query")
                // Cloudflare DoH (آلت)
                put("https://one.one.one.one/dns-query")
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

    private fun buildProxyOutbound(config: VpnConfig, tag: String, address: String): JSONObject {
        val outbound = JSONObject()
        outbound.put("tag", tag)

        // ── پروتکل ──
        when (config.type.lowercase()) {
            "vless" -> {
                outbound.put("protocol", "vless")
                outbound.put("settings", buildVlessSettings(config, address))
                outbound.put("streamSettings", buildStreamSettings(config))
            }
            "vmess" -> {
                outbound.put("protocol", "vmess")
                outbound.put("settings", buildVmessSettings(config, address))
                outbound.put("streamSettings", buildStreamSettings(config))
            }
            "trojan" -> {
                outbound.put("protocol", "trojan")
                outbound.put("settings", buildTrojanSettings(config, address))
                outbound.put("streamSettings", buildStreamSettings(config))
            }
            "shadowsocks" -> {
                outbound.put("protocol", "shadowsocks")
                outbound.put("settings", buildShadowsocksSettings(config, address))
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
        // XTLS-Vision با Mux ناسازگار است — همیشه باید غیرفعال باشد.
        if (config.security != "reality" && config.security != "xtls" &&
            config.flow != "xtls-rprx-vision" &&
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
    private fun buildVlessSettings(config: VpnConfig, address: String): JSONObject {
        return JSONObject().apply {
            put("vnext", JSONArray().apply {
                put(JSONObject().apply {
                    put("address", address)
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
    private fun buildVmessSettings(config: VpnConfig, address: String): JSONObject {
        return JSONObject().apply {
            put("vnext", JSONArray().apply {
                put(JSONObject().apply {
                    put("address", address)
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
    private fun buildTrojanSettings(config: VpnConfig, address: String): JSONObject {
        return JSONObject().apply {
            put("servers", JSONArray().apply {
                put(JSONObject().apply {
                    put("address", address)
                    put("port", config.port)
                    put("password", config.password ?: "")
                })
            })
        }
    }

    // ── Shadowsocks ──
    private fun buildShadowsocksSettings(config: VpnConfig, address: String): JSONObject {
        return JSONObject().apply {
            put("servers", JSONArray().apply {
                put(JSONObject().apply {
                    put("address", address)
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
     *   1. آدرس‌های خصوصی (LAN) → مستقیم
     *   2. دامنه‌های ایرانی → مستقیم
     *   3. DNS ایرانی → مستقیم
     *   4. بقیه → پروکسی (اگر edgeActive باشد یک load balancer با استراتژی
     *      random بر روی `edge-*` که به‌ازای هر اتصال یک IP انتخاب می‌کند)
     */
    private fun buildRouting(edgeActive: Boolean): JSONObject {
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

                // ── قانون ۴: بقیه → پروکسی (یا balancer لبه) ──
                put(JSONObject().apply {
                    put("type", "field")
                    if (edgeActive) {
                        put("balancerTag", BALANCER_TAG)
                    } else {
                        put("outboundTag", PROXY_TAG)
                    }
                    put("port", JSONArray().apply {
                        put("0-65535")
                    })
                })
            })

            // ── Load balancer برای edge pool ──
            if (edgeActive) {
                put("balancers", JSONArray().apply {
                    put(JSONObject().apply {
                        put("tag", BALANCER_TAG)
                        put("selector", JSONArray().apply {
                            put(EDGE_TAG_PREFIX)   // پیشوندی: فقط `edge-*` (نه پایه‌ی proxy)
                        })
                        // fallback به پایه‌ی پروکسی (جلوگیری از نشت) — وقتی همه‌ی لبه‌ها
                        // بر اساس نتیجه‌ی observe پایین باشند، ترافیک به‌جای عبور مستقیم،
                        // همچنان سفارش‌شده از آدرس اصلی می‌رود.
                        put("fallbackTag", PROXY_TAG)
                        // استراتژی `random`: با no observability دقیقاً همان هدف است —
                        // به‌ازای هر اتصال یک IP تصادفی از بین IPهای رتبه‌بندی‌شده انتخاب می‌شود
                        // (چرخش = دور زدن throttling). برخلاف `leastPing` نیازی به probeURL/
                        // observatory ندارد و هیچ اتصال addدقیقه وقتی را به یک IP ثابت قفل نمی‌کند.
                        put("strategy", JSONObject().apply {
                            put("type", "random")
                        })
                    })
                })
            }
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
}
