package com.example.vpn

import com.example.data.VpnConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.system.measureTimeMillis

/**
 * Edge-IP (domain-fronting) pool manager.
 *
 * For TLS-fronted configs (VLESS/VMess/Trojan behind Cloudflare) the SNI
 * certificate stays valid no matter which edge IP we dial, so we can rotate the
 * *connection* IP while keeping the TLS server name fixed.
 *
 * Responsibilities:
 *  - [parseEdges] — normalize `config.edgeIps` ("ip1, ip2, ...") into a clean list.
 *  - [scanAndRank] / [scanAndRankBlocking] — probe candidate edge IPs on
 *    `config.port`, measure raw TCP connect latency, and return the [keepTop]
 *    fastest (fastest first). Unreachable IPs are dropped.
 *
 * The ranked pool is passed to `XrayConfigGenerator.generate(..., edgePool)`.
 * The generator creates one tagged outbound per edge IP and a `random` load
 * balancer, so Xray rotates across every edge IP **per connection** at runtime
 * with no config reload. The pool is excluded from the tunnel via
 * `addDisallowedApplication` in XrayVpnService, so the probe sockets opened here
 * leave on the physical network instead of looping back into tun0.
 */
object IpPoolManager {

    const val DEFAULT_KEEP_TOP = 5
    const val DEFAULT_TIMEOUT_MS = 1200

    /**
     * Parse the comma-separated `edgeIps` field into a trimmed, non-empty list.
     * Returns an empty list if the field is blank either `None` — in which case
     * the caller should use the plain single outbound path (normal behaviour).
     */
    fun parseEdges(config: VpnConfig): List<String> {
        val raw = config.edgeIps?.takeIf { it.isNotBlank() } ?: return emptyList()
        return raw
            .split(',', '،', ';', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    /**
     * Rank [candidates] by connect latency to [port], returning the
     * [keepTop] fastest unreachable-free IPs, fastest first.
     *
     * Suspend version (UI friendly). Each candidate is probed concurrently with
     * a per-connect [timeoutMs].
     */
    suspend fun scanAndRank(
        candidates: List<String>,
        port: Int,
        keepTop: Int = DEFAULT_KEEP_TOP,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
    ): List<String> = withContext(Dispatchers.IO) {
        val results = candidates.distinct().map { ip ->
            async {
                val latency = withTimeoutOrNull(timeoutMs.toLong()) {
                    try {
                        var elapsed: Long = 0
                        Socket().use { socket ->
                            elapsed = measureTimeMillis {
                                socket.connect(InetSocketAddress(ip, port), timeoutMs)
                            }
                        }
                        elapsed
                    } catch (e: Exception) {
                        null
                    }
                }
                ip to latency
            }
        }.awaitAll()

        results
            .filter { it.second != null }
            .sortedBy { it.second }
            .take(keepTop)
            .map { it.first }
    }

    /**
     * Blocking variant for use on a background thread (e.g. the VPN-start
     * thread in XrayVpnService). Delegates to [scanAndRank].
     */
    fun scanAndRankBlocking(
        candidates: List<String>,
        port: Int,
        keepTop: Int = DEFAULT_KEEP_TOP,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
    ): List<String> = runBlocking {
        scanAndRank(candidates, port, keepTop, timeoutMs)
    }
}