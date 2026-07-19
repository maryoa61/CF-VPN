/*
 * tun2socks_jni.c
 *
 * لایه‌ی نازک JNI که کلاس Kotlin کام.example.cfvpn.vpn.Tun2SocksManager را به
 * توابع عمومی کتابخانه‌ی hev-socks5-tunnel وصل می‌کند.
 *
 * منبع hev-socks5-tunnel (شامل include/hev-main.h) باید طبق دستورالعمل README.md
 * در کنار این فایل clone و کامپایل شده باشد؛ این فایل هیچ کد hev-socks5-tunnel را
 * کپی نمی‌کند، فقط توابع مستند/عمومی آن را صدا می‌زند:
 *
 *   int  hev_socks5_tunnel_main_from_str(const unsigned char *config,
 *                                        unsigned int config_len, int tun_fd);
 *   void hev_socks5_tunnel_quit(void);
 *   void hev_socks5_tunnel_stats(size_t *tx_packets, size_t *tx_bytes,
 *                                size_t *rx_packets, size_t *rx_bytes);
 */

#include <jni.h>
#include <string.h>
#include "hev-main.h" /* از third_party/hev-socks5-tunnel/include */

JNIEXPORT jint JNICALL
Java_com_example_cfvpn_vpn_Tun2SocksManager_nativeStart(JNIEnv *env, jobject thiz,
                                                          jstring j_config, jint tun_fd)
{
    const char *config_utf8 = (*env)->GetStringUTFChars(env, j_config, NULL);
    if (config_utf8 == NULL) {
        return -1;
    }

    jsize config_len = (*env)->GetStringUTFLength(env, j_config);

    /* این فراخوانی بلاک‌کننده است و تا صدازدنِ nativeStop از این ترد خارج نمی‌شود. */
    int result = hev_socks5_tunnel_main_from_str(
        (const unsigned char *)config_utf8, (unsigned int)config_len, (int)tun_fd);

    (*env)->ReleaseStringUTFChars(env, j_config, config_utf8);
    return result;
}

JNIEXPORT void JNICALL
Java_com_example_cfvpn_vpn_Tun2SocksManager_nativeStop(JNIEnv *env, jobject thiz)
{
    hev_socks5_tunnel_quit();
}

JNIEXPORT jlongArray JNICALL
Java_com_example_cfvpn_vpn_Tun2SocksManager_nativeStats(JNIEnv *env, jobject thiz)
{
    size_t tx_packets = 0, tx_bytes = 0, rx_packets = 0, rx_bytes = 0;
    hev_socks5_tunnel_stats(&tx_packets, &tx_bytes, &rx_packets, &rx_bytes);

    jlong values[4] = {
        (jlong)tx_packets, (jlong)tx_bytes, (jlong)rx_packets, (jlong)rx_bytes
    };

    jlongArray result = (*env)->NewLongArray(env, 4);
    if (result != NULL) {
        (*env)->SetLongArrayRegion(env, result, 0, 4, values);
    }
    return result;
}
