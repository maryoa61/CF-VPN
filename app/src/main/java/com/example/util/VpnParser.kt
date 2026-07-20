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
            if (trimmed.startsWith("vmess://", ignoreCase = true)) {
                parseVmess(trimmed)
            } else {
                parseStandardUri(trimmed)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            // Return a fallback partial config if the URI scheme is recognized but parse failed
            try {
                val uri = Uri.parse(trimmed)
                val scheme = uri.scheme?.lowercase() ?: "custom"
                if (scheme in listOf("vless", "ss", "trojan", "hysteria2", "hy2")) {
                    val host = uri.host ?: "unknown"
                    val port = if (uri.port != -1) uri.port else 443
                    val name = uri.fragment?.let { URLDecoder.decode(it, "UTF-8") } ?: "imported_${scheme}"
                    VpnConfig(
                        name = name,
                        type = if (scheme == "hy2") "hysteria2" else scheme,
                        address = host,
                        port = port,
                        rawLink = trimmed
                    )
                } else null
            } catch (ex: Exception) {
                null
            }
        }
    }

    private fun parseVmess(link: String): VpnConfig? {
        val rawBase64 = link.substring(8)
        val jsonStr = try {
            val decodedBytes = Base64.decode(rawBase64, Base64.DEFAULT)
            String(decodedBytes, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            // Some vmess links might have padding issues
            try {
                val decodedBytes = Base64.decode(rawBase64, Base64.NO_PADDING or Base64.URL_SAFE)
                String(decodedBytes, StandardCharsets.UTF_8)
            } catch (ex: Exception) {
                return null
            }
        }

        return try {
            val json = JSONObject(jsonStr)
            val address = json.optString("add", "127.0.0.1")
            val portStr = json.optString("port", "443")
            val port = portStr.toIntOrNull() ?: 443
            val name = json.optString("ps", "vmess-node")
            val uuid = json.optString("id", "").takeIf { it.isNotEmpty() }
            val network = json.optString("net", "tcp").takeIf { it.isNotEmpty() } ?: "tcp"
            val tls = json.optString("tls", "")
            val security = if (tls == "tls" || tls == "reality") tls else "none"
            val sni = json.optString("sni", "").takeIf { it.isNotEmpty() }
                ?: json.optString("host", "").takeIf { it.isNotEmpty() }
            val wsPath = json.optString("path", "").takeIf { it.isNotEmpty() }
            val wsHost = json.optString("host", "").takeIf { it.isNotEmpty() }
            VpnConfig(
                name = name,
                type = "vmess",
                address = address,
                port = port,
                rawLink = link,
                uuid = uuid,
                network = network,
                security = security,
                sni = sni,
                wsPath = wsPath,
                wsHost = wsHost
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun parseStandardUri(link: String): VpnConfig? {
        val uri = Uri.parse(link)
        val scheme = uri.scheme?.lowercase() ?: return null

        val type = when (scheme) {
            "vless" -> "vless"
            "ss" -> "shadowsocks"
            "trojan" -> "trojan"
            "hysteria2", "hy2" -> "hysteria2"
            else -> return null // Unsupported protocol
        }

        // URI parsing of host might fail if of format ss://method:password@host:port
        // Let's fallback to manual regex/string splitting if host is null or empty
        var host = uri.host
        var port = uri.port

        // userInfo holds the UUID (vless) or password (trojan). android.net.Uri auto-decodes
        // %XX escapes here (e.g. %5B -> [), so this is already the raw secret.
        var userInfo = uri.userInfo

        if (host == null || port == -1) {
            // Manual parsing
            val withoutScheme = link.substring(scheme.length + 3) // remove "vless://"
            val hashIndex = withoutScheme.indexOf("#")
            val mainPart = if (hashIndex != -1) withoutScheme.substring(0, hashIndex) else withoutScheme

            val atIndex = mainPart.lastIndexOf("@")
            val connPart = if (atIndex != -1) mainPart.substring(atIndex + 1) else mainPart

            if (atIndex != -1) {
                val rawUserInfo = mainPart.substring(0, atIndex)
                userInfo = try {
                    URLDecoder.decode(rawUserInfo, "UTF-8")
                } catch (e: Exception) {
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

        val name = uri.fragment?.let { 
            try {
                URLDecoder.decode(it, "UTF-8")
            } catch (e: Exception) {
                it
            }
        } ?: "${type}_node_${host.take(5)}"

        // Extract query parameters for VLESS, if applicable
        val uuid = if (type == "vless") userInfo else null
        // ss:// userInfo is either plain "method:password" or base64("method:password").
        val ssDecoded = if (type == "shadowsocks") {
            val raw = userInfo ?: ""
            if (raw.contains(":")) raw else try {
                String(Base64.decode(raw, Base64.DEFAULT), StandardCharsets.UTF_8)
            } catch (e: Exception) {
                raw
            }
        } else null
        val ssMethod = ssDecoded?.substringBefore(":", missingDelimiterValue = "")?.takeIf { it.isNotEmpty() }
        val ssPassword = ssDecoded?.substringAfter(":", missingDelimiterValue = "")?.takeIf { it.isNotEmpty() }
        val password = when (type) {
            "trojan" -> userInfo
            "shadowsocks" -> ssPassword
            else -> null
        }
        val security = uri.getQueryParameter("security") ?: "none"
        val flow = uri.getQueryParameter("flow") ?: "none"
        val sni = uri.getQueryParameter("sni")
        val publicKey = uri.getQueryParameter("pbk") ?: uri.getQueryParameter("publicKey")
        val shortId = uri.getQueryParameter("sid") ?: uri.getQueryParameter("shortId")
        val network = uri.getQueryParameter("type") ?: uri.getQueryParameter("network") ?: "tcp"

        // WebSocket transport settings (used when network == "ws"). Uri.getQueryParameter
        // already URL-decodes once, which is correct here: e.g. "path" commonly carries an
        // embedded query string itself (like "...?ed=2560" for WS early-data), and decoding
        // it twice would corrupt it.
        val wsPath = uri.getQueryParameter("path")
        val wsHost = uri.getQueryParameter("host")

        // Extract Fragment parameters if specified in link
        val fragmentEnabled = uri.getQueryParameter("fragment") == "1" || uri.getQueryParameter("fragmentEnabled") == "true"
        val fragmentLength = uri.getQueryParameter("fragmentLength") ?: "10-20"
        val fragmentInterval = uri.getQueryParameter("fragmentInterval") ?: "10-20"
        val fragmentPackets = uri.getQueryParameter("fragmentPackets") ?: "tlshello"

        return VpnConfig(
            name = name,
            type = type,
            address = host ?: "127.0.0.1",
            port = port,
            rawLink = link,
            uuid = uuid,
            password = password,
            ssMethod = ssMethod,
            network = network,
            security = security,
            flow = flow,
            sni = sni,
            publicKey = publicKey,
            shortId = shortId,
            wsPath = wsPath,
            wsHost = wsHost,
            fragmentEnabled = fragmentEnabled,
            fragmentLength = fragmentLength,
            fragmentInterval = fragmentInterval,
            fragmentPackets = fragmentPackets
        )
    }
}
