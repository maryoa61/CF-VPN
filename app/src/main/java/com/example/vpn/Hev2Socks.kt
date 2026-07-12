package com.example.vpn

/**
 * Kotlin bridge to the native hev-socks5-tunnel engine (tun2socks).
 *
 * Loads two native libraries:
 *  - hev-socks5-tunnel  : upstream engine (github.com/heiher/hev-socks5-tunnel)
 *  - hev2socks_bridge   : our thin JNI wrapper (jni/hev_bridge.c)
 *
 * Usage:
 *   val configYaml = buildHevConfig(socksPort = 10808)
 *   Hev2Socks.start(configYaml, vpnInterface.fd)
 *   ...
 *   Hev2Socks.stop()
 */
object Hev2Socks {

    init {
        System.loadLibrary("hev-socks5-tunnel")
        System.loadLibrary("hev2socks_bridge")
    }

    /**
     * Starts the tun2socks engine on a background native thread.
     * @param configYaml the hev-socks5-tunnel YAML config as a string
     *                   (tunnel + socks5 sections — see upstream README)
     * @param tunFd the raw file descriptor of the VpnService TUN interface
     * @return 0 on success, negative on error (already running / bad args)
     */
    fun start(configYaml: String, tunFd: Int): Int = nativeStart(configYaml, tunFd)

    /** Stops the tun2socks engine and blocks until its thread exits. */
    fun stop() = nativeStop()

    /** Returns [txPackets, txBytes, rxPackets, rxBytes]. */
    fun stats(): LongArray = nativeStats()

    private external fun nativeStart(configYaml: String, tunFd: Int): Int
    private external fun nativeStop()
    private external fun nativeStats(): LongArray
}
