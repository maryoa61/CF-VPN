package com.example.vpn

import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Kotlin wrapper around the native hev-socks5-tunnel library.
 * Loads libhev2socks_bridge.so (built from hev_bridge.c + libhev-socks5-tunnel.a).
 */
object HevSocks5Tunnel {
    private const val TAG = "HevSocks5Tunnel"
    private const val LIB_NAME = "hev2socks_bridge"
    private val loaded = AtomicBoolean(false)

    init {
        try {
            System.loadLibrary(LIB_NAME)
            loaded.set(true)
            Log.i(TAG, "Native library loaded: $LIB_NAME")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load native library: $LIB_NAME", e)
        }
    }

    // ── JNI native methods (implemented in hev_bridge.c) ──
    private external fun nativeMainFromFile(configStr: String, tunFd: Int): Int
    private external fun nativeQuit()
    private external fun nativeStats(): LongArray

    /**
     * Write hev-socks5-tunnel YAML config file.
     * Called by XrayVpnService before starting the tunnel.
     */
    fun writeConfig(destFile: File, mtu: Int, socksPort: Int) {
        val config = """
            |main:
            |  tun-fd: -1
            |  mtu: $mtu
            |  log-level: info
            |  socks5-server: 127.0.0.1:$socksPort
        """.trimMargin()
        destFile.writeText(config)
        Log.i(TAG, "Config written to ${destFile.absolutePath} (mtu=$mtu, socks=$socksPort)")
    }

    /**
     * Start the tunnel event loop on a background thread.
     * Reads the config from [configPath], then passes it to the native layer.
     */
    fun start(configPath: String, tunFd: Int) {
        if (!loaded.get()) {
            Log.e(TAG, "Cannot start: native library not loaded")
            return
        }
        Thread({
            try {
                val configFile = File(configPath)
                val configStr = configFile.readText()
                // Pass tunFd directly — native layer forwards it to hev-socks5-tunnel
                Log.i(TAG, "Starting tunnel with config=$configPath, tunFd=$tunFd")
                val result = nativeMainFromFile(configStr, tunFd)
                Log.i(TAG, "Tunnel exited with code: $result")
            } catch (e: Exception) {
                Log.e(TAG, "Tunnel start failed", e)
            }
        }, "hev-socks5-tunnel").start()
    }

    /**
     * Signal the tunnel to shut down.
     */
    fun stop() {
        if (!loaded.get()) return
        try {
            nativeQuit()
            Log.i(TAG, "Tunnel quit signaled")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to quit tunnel", e)
        }
    }

    /**
     * Get tunnel traffic statistics.
     * Returns [txPackets, txBytes, rxPackets, rxBytes].
     */
    fun stats(): LongArray {
        if (!loaded.get()) return longArrayOf(0, 0, 0, 0)
        return try {
            nativeStats()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get stats", e)
            longArrayOf(0, 0, 0, 0)
        }
    }
}
