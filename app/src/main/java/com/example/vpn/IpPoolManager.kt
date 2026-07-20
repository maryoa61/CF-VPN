package com.example.vpn

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.system.measureTimeMillis

/**
 * Domain-fronting edge-IP pool: for TLS-fronted configs (e.g. Trojan/VLESS behind
 * Cloudflare), the TLS SNI/certificate stays valid no matter which edge IP we
 * actually dial. This scans a list of candidate IPs, ranks them by raw TCP
 * connect latency to the target port, and keeps the fastest few so the caller
 * can rotate the *connection* address while keeping the *TLS server name*
 * (config.sni / config.address) fixed.
 *
 * This does not replace VpnService.protect() handling: XrayVpnService already
 * excludes its own package from the tunnel via addDisallowedApplication, so the
 * probe sockets opened here go out directly instead of looping back into tun0.
 */
object IpPoolManager {

    /** A small set of well-known Cloudflare edge ranges, used when no config.ipPool is set. */
    val DEFAULT_POOL: List<String> = listOf(
        "104.16.0.1", "104.17.0.1", "104.18.0.1", "104.19.0.1",
        "104.20.0.1", "104.21.0.1", "172.64.0.1", "172.65.0.1"
    )

    /**
     * Connects to each candidate:port with a short timeout, measures latency,
     * and returns the [keepTop] fastest that answered, fastest first.
     */
    suspend fun scanAndRank(
        candidates: List<String>,
        port: Int,
        keepTop: Int,
        timeoutMs: Int
    ): List<String> = withContext(Dispatchers.IO) {
        val results = candidates.distinct().map { ip ->
            async {
                val latency = withTimeoutOrNull(timeoutMs.toLong()) {
                    try {
                        var elapsed: Long
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
     * Launches a repeating background rescan on [scope] every [intervalMs], invoking
     * [onUpdate] with the freshly ranked pool each time it changes. Cancelled
     * automatically when [scope] is cancelled (e.g. XrayVpnService's serviceScope
     * on disconnect) — callers don't need to hold onto a Job.
     */
    fun start(
        scope: CoroutineScope,
        candidates: List<String>,
        port: Int,
        intervalMs: Long,
        timeoutMs: Int,
        keepTop: Int,
        onUpdate: (List<String>) -> Unit
    ) {
        scope.launch(Dispatchers.IO) {
            var lastResult: List<String> = emptyList()
            while (isActive) {
                kotlinx.coroutines.delay(intervalMs)
                if (!isActive) break
                val ranked = scanAndRank(candidates, port, keepTop, timeoutMs)
                if (ranked.isNotEmpty() && ranked != lastResult) {
                    lastResult = ranked
                    withContext(Dispatchers.Main) { onUpdate(ranked) }
                }
            }
        }
    }
}
