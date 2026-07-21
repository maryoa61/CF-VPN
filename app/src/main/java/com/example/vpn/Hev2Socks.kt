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
        // IMPORTANT: do NOT System.loadLibrary("hev-socks5-tunnel") here.
        // That upstream .so has its own internal JNI_OnLoad (meant for the
        // upstream project's own Java class, which doesn't exist in this
        // package). When ART explicitly loads a library via
        // System.loadLibrary, it calls that library's JNI_OnLoad; upstream's
        // FindClass() fails, returns null, and its RegisterNatives(null, ...)
        // call hard-aborts the whole process ("JNI DETECTED ERROR IN
        // APPLICATION: java_class == null in call to RegisterNatives").
        //
        // We only need hev-socks5-tunnel's plain C API (declared extern in
        // hev_bridge.c), not its Java bindings. hev2socks_bridge already
        // declares LOCAL_SHARED_LIBRARIES := hev-socks5-tunnel in Android.mk,
        // so the dynamic linker pulls it in automatically as a normal ELF
        // dependency when we load hev2socks_bridge below -- that path never
        // triggers JNI_OnLoad, so the crash never happens.
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
