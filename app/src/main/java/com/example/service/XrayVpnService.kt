package com.example.service

import com.example.data.ServerEntity
import java.io.File

object XrayConfigGenerator {

    // Xray-core has no "tun" inbound protocol — confirmed against the official
    // Xray-core source and infra/conf parser (unknown config / exit code 23 if
    // attempted). TUN termination is handled entirely outside Xray by
    // hev-socks5-tunnel, which reads/writes the raw TUN fd and forwards traffic
    // into the "socks-in" inbound below over loopback. Xray only ever sees
    // ordinary SOCKS5 connections on 127.0.0.1:10808 — it has no awareness of
    // the VPN/TUN layer at all.
    const val SOCKS_INBOUND_PORT = 10808

    fun generate(server: ServerEntity, filesDir: File? = null): String {
        val outbounds = when (server.type.uppercase()) {
            "VLESS" -> generateVlessOutbound(server)
            "VMESS" -> generateVmessOutbound(server)
            "TROJAN" -> generateTrojanOutbound(server)
            "SHADOWSOCKS" -> generateShadowsocksOutbound(server)
            else -> generateFreedomOutbound()
        }

        // Safety verification: only append geoip strings if the geoip.dat file actually
        // exists, to prevent a core crash from a missing/corrupt file.
        //
        // NOTE: We intentionally do NOT reference "geosite:ir" here. The geosite.dat
        // bundled with official Xray-core releases does not include an "ir" domain
        // category (confirmed: https://github.com/XTLS/Xray-core/issues/1406 —
        // "we couldn't use category-ir in Iran using xray"). Referencing it causes
        // Xray-core to fail parsing the config at startup:
        //   "infra/conf: failed to parse ...domain rule: geosite:ir"
        // The regexp rules below already cover .ir domains without needing that category.
        val hasGeoip = filesDir != null && File(filesDir, "geoip.dat").exists()

        val routingRules = """
          "routing": {
            "domainStrategy": "IPIfNonMatch",
            "rules": [
              {
                "type": "field",
                "outboundTag": "direct",
                "domain": [
                  "regexp:\\.ir$",
                  "regexp:^[^.]*\\.ir$"
                ]
              },
              {
                "type": "field",
                "outboundTag": "direct",
                "ip": [
                  "10.0.0.0/8",
                  "172.16.0.0/12",
                  "192.168.0.0/16",
                  "127.0.0.0/8",
                  "100.64.0.0/10",
                  "fc00::/7",
                  "fe80::/10"
                  ${if (hasGeoip) ",\"geoip:private\",\"geoip:ir\"" else ""}
                ]
              },
              {
                "type": "field",
                "outboundTag": "proxy",
                "network": "tcp,udp"
              }
            ]
          },
        """.trimIndent()

        return """
        {
          "log": {
            "loglevel": "warning"
          },
          $routingRules
          "inbounds": [
            {
              "tag": "socks-in",
              "port": $SOCKS_INBOUND_PORT,
              "listen": "127.0.0.1",
              "protocol": "socks",
              "settings": {
                "auth": "noauth",
                "udp": true
              },
              "sniffing": {
                "enabled": false,
                "destOverride": ["http", "tls"]
              }
            },
            {
              "port": 10809,
              "listen": "127.0.0.1",
              "protocol": "http",
              "settings": {},
              "sniffing": {
                "enabled": false,
                "destOverride": ["http", "tls"]
              }
            }
          ],
          "outbounds": [
            $outbounds,
            {
              "protocol": "freedom",
              "settings": {},
              "tag": "direct"
            }
          ]
        }
        """.trimIndent()
    }

    private fun generateVlessOutbound(server: ServerEntity): String {
        val streamSettingsJson = generateStreamSettings(server)
        val flowValue = server.flow.ifEmpty {
            if (server.security.lowercase() == "reality") "xtls-rprx-vision" else ""
        }
        return """
        {
          "protocol": "vless",
          "settings": {
            "vnext": [
              {
                "address": "${server.address}",
                "port": ${server.port},
                "users": [
                  {
                    "id": "${server.uuid}",
                    "encryption": "none",
                    "flow": "$flowValue",
                    "level": 0
                  }
                ]
              }
            ]
          },
          "streamSettings": $streamSettingsJson,
          "tag": "proxy"
        }
        """
    }

    private fun generateVmessOutbound(server: ServerEntity): String {
        val streamSettingsJson = generateStreamSettings(server)
        return """
        {
          "protocol": "vmess",
          "settings": {
            "vnext": [
              {
                "address": "${server.address}",
                "port": ${server.port},
                "users": [
                  {
                    "id": "${server.uuid}",
                    "alterId": ${server.alterId},
                    "security": "${server.security.ifEmpty { "auto" }}",
                    "level": 0
                  }
                ]
              }
            ]
          },
          "streamSettings": $streamSettingsJson,
          "tag": "proxy"
        }
        """
    }

    private fun generateTrojanOutbound(server: ServerEntity): String {
        val streamSettingsJson = generateStreamSettings(server)
        return """
        {
          "protocol": "trojan",
          "settings": {
            "servers": [
              {
                "address": "${server.address}",
                "port": ${server.port},
                "password": "${server.uuid}",
                "level": 0
              }
            ]
          },
          "streamSettings": $streamSettingsJson,
          "tag": "proxy"
        }
        """
    }

    private fun generateShadowsocksOutbound(server: ServerEntity): String {
        val creds = server.uuid.split(":")
        val method = if (creds.isNotEmpty()) creds[0] else "aes-256-gcm"
        val password = if (creds.size > 1) creds[1] else "mypassword"
        val streamSettingsJson = generateStreamSettings(server)

        return """
        {
          "protocol": "shadowsocks",
          "settings": {
            "servers": [
              {
                "address": "${server.address}",
                "port": ${server.port},
                "method": "$method",
                "password": "$password",
                "level": 0
              }
            ]
          },
          "streamSettings": $streamSettingsJson,
          "tag": "proxy"
        }
        """
    }

    private fun generateFreedomOutbound(): String {
        return """
        {
          "protocol": "freedom",
          "settings": {},
          "tag": "proxy"
        }
        """
    }

    private fun generateStreamSettings(server: ServerEntity): String {
        val isReality = server.security.lowercase() == "reality"
        val securityStr = when {
            isReality -> "reality"
            server.tls -> "tls"
            else -> "none"
        }

        val securityConfig = when {
            isReality -> {
                val sniToUse = server.sni.ifEmpty { "www.google.com" }
                val fingerprintToUse = server.fingerprint.ifEmpty { "chrome" }
                """
                "realitySettings": {
                  "show": false,
                  "fingerprint": "$fingerprintToUse",
                  "serverName": "$sniToUse",
                  "publicKey": "${server.publicKey}",
                  "shortId": "${server.shortId}",
                  "spiderX": "/"
                }
                """
            }
            server.tls -> {
                val sniToUse = server.sni.ifEmpty { server.address }
                """
                "tlsSettings": {
                  "serverName": "$sniToUse",
                  "allowInsecure": true
                }
                """
            }
            else -> ""
        }

        val transportConfig = when (server.network.lowercase()) {
            "ws" -> """
            "wsSettings": {
              "path": "${server.path.ifEmpty { "/" }}",
              "headers": {
                "Host": "${server.host.ifEmpty { server.address }}"
              }
            }
            """
            "grpc" -> """
            "grpcSettings": {
              "serviceName": "${server.path.ifEmpty { "v2ray-grpc" }}"
            }
            """
            else -> ""
        }

        val parts = mutableListOf<String>()
        if (securityConfig.isNotEmpty()) parts.add(securityConfig)
        if (transportConfig.isNotEmpty()) parts.add(transportConfig)

        return """
        {
          "network": "${server.network.lowercase().ifEmpty { "tcp" }}",
          "security": "$securityStr"
          ${if (parts.isNotEmpty()) "," else ""}
          ${parts.joinToString(",")}
        }
        """
    }
}
