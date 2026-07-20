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

        return when {
            trimmed.startsWith("vmess://", ignoreCase = true) -> parseVmess(trimmed)
            trimmed.startsWith("ss://", ignoreCase = true) -> parseShadowsocks(trimmed)
            trimmed.startsWith("vless://", ignoreCase = true) -> parseStandardUri(trimmed, "vless")
            trimmed.startsWith("trojan://", ignoreCase = true) -> parseStandardUri(trimmed, "trojan")
            trimmed.startsWith("hysteria2://", ignoreCase = true) ||
            trimmed.startsWith("hy2://", ignoreCase = true) -> parseStandardUri(trimmed, "hysteria2")
            else -> null
        }
    }

    // ---------------------------
    // VMESS
    // ---------------------------
    private fun parseVmess(link: String): VpnConfig? {
        val rawBase64 = link.substring(8)
        val jsonStr = try {
            String(Base64.decode(rawBase64, Base64.DEFAULT), StandardCharsets.UTF_8)
        } catch (e: Exception) {
            try {
                String(Base64.decode(rawBase64, Base64.NO_PADDING or Base64.URL_SAFE), StandardCharsets.UTF_8)
            } catch (ex: Exception) {
                return null
            }
        }

        return try {
            val json = JSONObject(jsonStr)

            val address = json.optString("add", "")
            val port = json.optString("port", "443").toIntOrNull() ?: 443
            val name = json.optString("ps", "vmess-node")

            val uuid = json.optString("id", null)
            val network = json.optString("net", "tcp")
            val security = if (json.optString("tls", "none") == "tls") "tls" else "none"
            val sni = json.optString("sni", null)
            val wsPath = json.optString("path", null)
            val wsHost = json.optString("host", null)

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

    // ---------------------------
    // SHADOWSOCKS
    // ---------------------------
    private fun parseShadowsocks(link: String): VpnConfig? {
        val withoutScheme = link.removePrefix("ss://")
        val parts = withoutScheme.split("@")

        if (parts.size != 2) return null

        val methodPassword = String(Base64.decode(parts[0], Base64.NO_PADDING or Base64.URL_SAFE))
        val hostPort = parts[1].substringBefore("#")

        val method = methodPassword.substringBefore(":")
        val password = methodPassword.substringAfter(":")

        val host = hostPort.substringBefore(":")
        val port = hostPort.substringAfter(":").toIntOrNull() ?: 443

        val name = link.substringAfter("#", "ss-node")

        return VpnConfig(
            name = name,
            type = "shadowsocks",
            address = host,
            port = port,
            rawLink = link,
            password = password,
            network = "tcp",
            security = "none"
        )
    }

    // ---------------------------
    // VLESS / TROJAN / HY2
    // ---------------------------
    private fun parseStandardUri(link: String, forcedType: String): VpnConfig? {
        val uri = Uri.parse(link)

        val host = uri.host ?: return null
        val port = if (uri.port != -1) uri.port else 443
        val userInfo = uri.userInfo

        val name = uri.fragment?.let {
            try { URLDecoder.decode(it, "UTF-8") } catch (_: Exception) { it }
        } ?: "${forcedType}_node_${host.take(5)}"

        val network = uri.getQueryParameter("type")
            ?: uri.getQueryParameter("network")
            ?: "tcp"

        val security = uri.getQueryParameter("security") ?: "none"
        val flow = uri.getQueryParameter("flow") ?: "none"
        val sni = uri.getQueryParameter("sni")
        val publicKey = uri.getQueryParameter("pbk") ?: uri.getQueryParameter("publicKey")
        val shortId = uri.getQueryParameter("sid") ?: uri.getQueryParameter("shortId")

        val wsPath = uri.getQueryParameter("path")
        val wsHost = uri.getQueryParameter("host")

        val fragmentEnabled = uri.getQueryParameter("fragment") == "1"
        val fragmentLength = uri.getQueryParameter("fragmentLength") ?: "10-20"
        val fragmentInterval = uri.getQueryParameter("fragmentInterval") ?: "10-20"
        val fragmentPackets = uri.getQueryParameter("fragmentPackets") ?: "tlshello"

        val uuid = if (forcedType == "vless") userInfo else null
        val password = if (forcedType == "trojan" || forcedType == "hysteria2") userInfo else null

        return VpnConfig(
            name = name,
            type = forcedType,
            address = host,
            port = port,
            rawLink = link,
            uuid = uuid,
            password = password,
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
