package com.example.vpn

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Thin Kotlin wrapper around the native hev-socks5-tunnel library
 * (https://github.com/heiher/hev-socks5-tunnel).
 *
 * WHY THIS EXISTS:
 * Xray-core has no "tun" inbound — it only understands socks/http/vmess/
 * vless/trojan/shadowsocks/dokodemo-door. This library is the layer that
 * actually terminates the raw TUN file descriptor handed out by
 * VpnService.Builder.establish(), and forwards the IP packets into a plain
 * SOCKS5 endpoint — in our case Xray's own "socks-in" inbound on
 * 127.0.0.1:$SOCKS_INBOUND_PORT (see XrayConfigGenerator).
 *
 * REQUIRES: libhev2socks_bridge.so present under
 * src/main/jniLibs/<abi>/ for every ABI you ship (arm64-v8a at minimum).
 * This .so is built by the Android.mk in jni/ which links the prebuilt
 * libhev-socks5-tunnel.a with hev_bridge.c (JNI wrapper).
 */
object HevSocks5Tunnel {

    private val isRunning = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)

    @Volatile
    private var libraryLoaded = false

    private var libraryLoadError: Throwable? = null

    init {
        try {
            // IMPORTANT: This must match the LOCAL_MODULE name in Android.mk.
            // Android.mk builds "hev2socks_bridge" → libhev2socks_bridge.so
            System.loadLibrary("hev2socks_bridge")
            libraryLoaded = true
        } catch (e: UnsatisfiedLinkError) {
            // Most likely cause: libhev2socks_bridge.so isn't present under
            // jniLibs/<abi>/ for this ABI — e.g. the Gradle NDK build hasn't
            // run, or ran for the wrong ABI.
            libraryLoadError = e
        }
    }

    /**
     * Blocking call — runs the tunnel's event loop on the calling thread
     * until [stop] is invoked or the tunnel exits on its own (e.g. TUN fd
     * closed). MUST be launched on a dedicated Thread, never on a shared
     * coroutine dispatcher, or it will starve every other coroutine on
     * that dispatcher for the lifetime of the VPN session.
     *
     * @return the native exit code (0 on clean shutdown via [stop]).
     */
    private external fun nativeMainFromFile(configPath: String, tunFd: Int): Int

    /** Signals the running tunnel loop to shut down. Safe from any thread. */
    private external fun nativeQuit()

    /**
     * Starts the tunnel and blocks until it stops. Call this from a
     * dedicated Thread (see XrayVpnService), not from a coroutine.
     */
    fun start(configPath: String, tunFd: Int): Int {
        if (!libraryLoaded) {
            throw IllegalStateException(
                "libhev2socks_bridge.so failed to load (${libraryLoadError?.message}). " +
                "Ensure the NDK build ran successfully and the .so is bundled " +
                "under jniLibs/<abi>/ for this device's ABI.",
                libraryLoadError
            )
        }
        stopRequested.set(false)
        isRunning.set(true)
        return try {
            nativeMainFromFile(configPath, tunFd)
        } finally {
            isRunning.set(false)
        }
    }

    /**
     * Requests a clean shutdown of the tunnel loop, if one is running.
     * Safe to call multiple times and/or concurrently from multiple
     * threads/coroutines — only the first call in a given session actually
     * reaches the native layer; every other call becomes a no-op.
     */
    fun stop() {
        if (isRunning.get() && stopRequested.compareAndSet(false, true)) {
            nativeQuit()
        }
    }

    fun isActive(): Boolean = isRunning.get()

    /**
     * Writes the YAML config hev-socks5-tunnel expects. This is a separate
     * file from xray_config.json — the two processes/threads don't share
     * a config format.
     */
    fun writeConfig(
        destFile: File,
        socksPort: Int,
        mtu: Int = 1400
    ): File {
        destFile.writeText(
            """
            tunnel:
              name: tun0
              mtu: $mtu
              multi-queue: false
            socks5:
              address: 127.0.0.1
              port: $socksPort
              udp: 'udp'
            misc:
              task-stack-size: 20480
            """.trimIndent()
        )
        return destFile
    }
}
