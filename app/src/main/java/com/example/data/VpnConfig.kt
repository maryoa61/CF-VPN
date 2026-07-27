package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "vpn_configs")
data class VpnConfig(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val type: String, // "vless", "vmess", "shadowsocks", "trojan", "hysteria2"
    val address: String,
    val port: Int,
    val rawLink: String,
    val isSelected: Boolean = false,
    val delayMs: Int? = null,

    // VLESS-specific settings
    val uuid: String? = null,
    val network: String? = "tcp", // "tcp", "ws", "grpc", etc.
    val security: String? = "none", // "none", "tls", "xtls", "reality"
    val flow: String? = "none", // "none", "xtls-rprx-vision"
    val sni: String? = null,
    val publicKey: String? = null,
    val shortId: String? = null,

    // WebSocket transport settings (used when network == "ws")
    val wsPath: String? = null,
    val wsHost: String? = null,

    // Trojan-specific settings
    val password: String? = null,

    // Shadowsocks-specific settings
    val method: String? = null, // e.g. "aes-256-gcm", "chacha20-ietf-poly1305"

    // VMess-specific settings
    val alterId: Int = 0,

    // Fragment settings
    val fragmentEnabled: Boolean = false,
    val fragmentLength: String? = "10-20",
    val fragmentInterval: String? = "10-20",
    val fragmentPackets: String? = "tlshello",

    // Edge-IP pool (comma-separated IPs). When set, every new connection is routed
    // through a randomly-picked IP from this pool instead of always `address`,
    // while keeping the same SNI/Host — mitigates per-IP traffic-volume throttling.
    val edgeIps: String? = null
)
