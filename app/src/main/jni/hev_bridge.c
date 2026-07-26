/*
 * hev_bridge.c
 *
 * Thin JNI wrapper around heiher/hev-socks5-tunnel's public C API.
 * Compiled into libhev2socks_bridge.so via Android.mk.
 *
 * JNI function names match: com.example.vpn.HevSocks5Tunnel
 */

#include <jni.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

/* Public API of hev-socks5-tunnel */
extern int hev_socks5_tunnel_main_from_str(const unsigned char *config_str,
                                            unsigned int config_len,
                                            int tun_fd);
extern void hev_socks5_tunnel_quit(void);
extern void hev_socks5_tunnel_stats(size_t *tx_packets, size_t *tx_bytes,
                                     size_t *rx_packets, size_t *rx_bytes);

static pthread_t g_thread;
static volatile int g_running = 0;

typedef struct {
    char *config;
    int config_len;
    int tun_fd;
} start_args_t;

static void *
run_tunnel(void *arg)
{
    start_args_t *args = (start_args_t *) arg;
    hev_socks5_tunnel_main_from_str((unsigned char *) args->config,
                                     args->config_len, args->tun_fd);
    free(args->config);
    free(args);
    g_running = 0;
    return NULL;
}

/*
 * JNI: nativeMainFromFile(String configPath, int tunFd)
 * Runs the tunnel event loop on the calling thread (blocking).
 */
JNIEXPORT jint JNICALL
Java_com_example_vpn_HevSocks5Tunnel_nativeMainFromFile(JNIEnv *env, jobject thiz,
                                                        jstring jConfig, jint tunFd)
{
    (void) thiz;

    if (g_running) {
        return -1;
    }

    const char *cConfig = (*env)->GetStringUTFChars(env, jConfig, NULL);
    if (cConfig == NULL) {
        return -2;
    }
    int len = (int) strlen(cConfig);

    start_args_t *args = (start_args_t *) malloc(sizeof(start_args_t));
    if (args == NULL) {
        (*env)->ReleaseStringUTFChars(env, jConfig, cConfig);
        return -3;
    }
    args->config = (char *) malloc((size_t) len + 1);
    if (args->config == NULL) {
        free(args);
        (*env)->ReleaseStringUTFChars(env, jConfig, cConfig);
        return -3;
    }
    memcpy(args->config, cConfig, (size_t) len + 1);
    args->config_len = len;
    args->tun_fd = tunFd;

    (*env)->ReleaseStringUTFChars(env, jConfig, cConfig);

    g_running = 1;
    if (pthread_create(&g_thread, NULL, run_tunnel, args) != 0) {
        g_running = 0;
        free(args->config);
        free(args);
        return -4;
    }

    /* Block until tunnel thread exits (no timeout — hev-socks5-tunnel
       handles its own shutdown via nativeQuit). */
    pthread_join(g_thread, NULL);

    return 0;
}

/*
 * JNI: nativeQuit()
 * Signals the running tunnel loop to shut down.
 * Note: pthread_cancel is NOT available on Android Bionic.
 * Instead, hev_socks5_tunnel_quit() sets an internal flag that makes
 * the event loop exit naturally, after which pthread_join returns.
 */
JNIEXPORT void JNICALL
Java_com_example_vpn_HevSocks5Tunnel_nativeQuit(JNIEnv *env, jobject thiz)
{
    (void) env;
    (void) thiz;

    hev_socks5_tunnel_quit();
    /* The tunnel loop will detect the quit flag and exit on its own.
       The caller (Kotlin) does pthread_join via Thread.join(2000). */
}

/*
 * JNI: nativeStats()
 * Returns [tx_packets, tx_bytes, rx_packets, rx_bytes] as a long array.
 */
JNIEXPORT jlongArray JNICALL
Java_com_example_vpn_HevSocks5Tunnel_nativeStats(JNIEnv *env, jobject thiz)
{
    (void) thiz;

    size_t tx_packets = 0, tx_bytes = 0, rx_packets = 0, rx_bytes = 0;
    hev_socks5_tunnel_stats(&tx_packets, &tx_bytes, &rx_packets, &rx_bytes);

    jlong result[4];
    result[0] = (jlong) tx_packets;
    result[1] = (jlong) tx_bytes;
    result[2] = (jlong) rx_packets;
    result[3] = (jlong) rx_bytes;

    jlongArray arr = (*env)->NewLongArray(env, 4);
    (*env)->SetLongArrayRegion(env, arr, 0, 4, result);
    return arr;
}
