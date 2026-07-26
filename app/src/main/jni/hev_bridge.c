/*
 * hev_bridge.c
 * JNI bridge for hev-socks5-tunnel.
 * JNI names match: com.example.vpn.HevSocks5Tunnel
 */
#include <jni.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>

extern int hev_socks5_tunnel_main_from_str(const unsigned char *config,
                                            unsigned int config_len, int tun_fd);
extern void hev_socks5_tunnel_quit(void);
extern void hev_socks5_tunnel_stats(size_t *txp, size_t *txb,
                                     size_t *rxp, size_t *rxb);

static pthread_t g_thread;

JNIEXPORT jint JNICALL
Java_com_example_vpn_HevSocks5Tunnel_nativeMainFromFile(
    JNIEnv *env, jobject thiz, jstring jConfig, jint tunFd)
{
    (void)thiz;
    const char *cfg = (*env)->GetStringUTFChars(env, jConfig, 0);
    if (!cfg) return -1;
    int len = (int)strlen(cfg);
    /* Hev-socks5-tunnel expects an fd dup'd to a known number */
    (void)hev_socks5_tunnel_main_from_str((const unsigned char *)cfg, len, tunFd);
    (*env)->ReleaseStringUTFChars(env, jConfig, cfg);
    return 0;
}

JNIEXPORT void JNICALL
Java_com_example_vpn_HevSocks5Tunnel_nativeQuit(JNIEnv *env, jobject thiz)
{
    (void)env; (void)thiz;
    hev_socks5_tunnel_quit();
}

JNIEXPORT jlongArray JNICALL
Java_com_example_vpn_HevSocks5Tunnel_nativeStats(JNIEnv *env, jobject thiz)
{
    (void)thiz;
    size_t txp = 0, txb = 0, rxp = 0, rxb = 0;
    hev_socks5_tunnel_stats(&txp, &txb, &rxp, &rxb);
    jlong arr[4] = {(jlong)txp, (jlong)txb, (jlong)rxp, (jlong)rxb};
    jlongArray result = (*env)->NewLongArray(env, 4);
    (*env)->SetLongArrayRegion(env, result, 0, 4, arr);
    return result;
}
