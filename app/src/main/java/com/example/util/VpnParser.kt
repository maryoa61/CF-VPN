package com.example.util

import android.net.Uri
import android.util.Base64
import com.example.data.VpnConfig
import org.json.JSONObject
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

object VpnParser {

    fun parseLink(link: String): VpnConfig? {
        val trimmed = link.trim()
        if (trimmed.isEmpty()) return null

        return try {
            val cfg = if (trimmed.startsWith("vmess://", ignoreCase = true)) {
                parseVmess(trimmed)
            } else {
                parseStandardUri(trimmed)
            }
            cfg?.let { normalize(it) }
        } catch (e: Exception) {
            e.printStackTrace()

            // Fallback: اگر scheme را تشخیص دادیم، حداقل یک config قابل ذخیره بسازیم
            try {
                val uri = Uri.parse(trimmed)
                val schemeRaw = uri.scheme?.lowercase() ?: return null
                val scheme = normalizeScheme(schemeRaw)

                if (scheme in listOf("vless", "shadowsocks", "trojan", "hysteria2")) {
                    val host = uri.host ?: "unknown"
                    val port = if (uri.port != -1) uri.port else 443

                    // fragment ممکن است null باشد یا encoding عجیب داشته باشد
                    val name = uri.fragment?.let {
                        try {
                            URLDecoder.decode(it, "UTF-8")
                        } catch (_: Exception) {
                            it
                        }
                    } ?: "imported_${scheme}"

                    VpnConfig(
                        name = name,
                        type = scheme,
                        address = host,
                        port = port,
                        rawLink = trimmed
                    )
                } else null
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun normalizeScheme(s: String): String {
        return when (s.lowercase()) {
            "ss" -> "shadowsocks"
            "hy2" -> "hysteria2"
            else -> s.lowercase()
        }
    }

    private fun normalize(cfg: VpnConfig): VpnConfig {
        val normalizedType = normalizeScheme(cfg.type)
        return if (normalizedType == cfg.type) cfg else cfg.copy(type = normalizedType)
    }

    private fun parseVmess(link: String): VpnConfig? {
        val rawBase64 = link.removePrefix("vmess://")
        val jsonStr = try {
            val decodedBytes = Base64.decode(rawBase64, Base64.DEFAULT)
            String(decodedBytes, StandardCharsets.UTF_8)
        } catch (_: Exception) {
            // بعضی vmess ها padding ندارند یا URL-safe هستند
            try {
                val decodedBytes = Base64.decode(rawBase64, Base64.NO_PADDING or Base64.URL_SAFE)
                String(decodedBytes, StandardCharsets.UTF_8)
            } catch (_: Exception) {
                return null
            }
        }

        return try {
            val json = JSONObject(jsonStr)
            val address = json.optString("add", "127.0.0.1")
            val portStr = json.optString("port", "443")
            val port = portStr.toIntOrNull() ?: 443
            val name = json.optString("ps", "vmess-node")
            val uuid = json.optString("id", "")
            val alterId = json.optString("aid", "0").toIntOrNull() ?: 0
            val network = json.optString("net", "tcp").ifEmpty { "tcp" }

            val tlsField = json.optString("tls", "")
            val security = if (tlsField == "tls" || tlsField == "reality") tlsField else "none"

            val sni = json.optString("sni", "").ifEmpty { null }
            val wsPath = json.optString("path", "").ifEmpty { null }
            val wsHost = json.optString("host", "").ifEmpty { null }

            VpnConfig(
                name = name,
                type = "vmess",
                address = address,
                port = port,
                rawLink = link,
                uuid = uuid,
                alterId = alterId,
                network = network,
                security = security,
                sni = sni,
                wsPath = wsPath,
                wsHost = wsHost
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun parseStandardUri(link: String): VpnConfig? {
        val uri = Uri.parse(link)
        val schemeRaw = uri.scheme?.lowercase() ?: return null

        val type = when (schemeRaw) {
            "vless" -> "vless"
            "ss" -> "shadowsocks"
            "trojan" -> "trojan"
            "hysteria2", "hy2" -> "hysteria2"
            else -> return null
        }

        var host = uri.host
        var port = uri.port
        var userInfo = uri.userInfo

        // manual parsing اگر Uri نتوانست host/port را درست بخواند
        var manualFragment: String? = null
        if (host == null || port == -1) {
            val withoutScheme = link.substring(schemeRaw.length + 3) // remove "xxx://"

            val hashIndex = withoutScheme.indexOf("#")
            val mainPart = if (hashIndex != -1) withoutScheme.substring(0, hashIndex) else withoutScheme
            manualFragment = if (hashIndex != -1) withoutScheme.substring(hashIndex + 1) else null

            val atIndex = mainPart.lastIndexOf("@")
            val connPart = if (atIndex != -1) mainPart.substring(atIndex + 1) else mainPart

            if (atIndex != -1) {
                val rawUserInfo = mainPart.substring(0, atIndex)
                userInfo = try {
                    URLDecoder.decode(rawUserInfo, "UTF-8")
                } catch (_: Exception) {
                    rawUserInfo
                }
            }

            // connPart is "host:port?query"
            val queryIndex = connPart.indexOf("?")
            val hostPortPart = if (queryIndex != -1) connPart.substring(0, queryIndex) else connPart

            val colonIndex = hostPortPart.lastIndexOf(":")
            if (colonIndex != -1) {
                host = hostPortPart.substring(0, colonIndex)
                port = hostPortPart.substring(colonIndex + 1).toIntOrNull() ?: 443
            } else {
                host = hostPortPart
                port = 443
            }
        }

        val name = run {
            // اول fragment Uri را ترجیح بده، اگر نبود از manualFragment
            
