# Save our own jni/ directory ONCE, before any includes.
CFVPN_JNI_DIR := $(call my-dir)
LOCAL_PATH := $(CFVPN_JNI_DIR)

# IMPORTANT: we deliberately do NOT include hev-socks5-tunnel/Android.mk here.
#
# That upstream makefile has flip-flopped between two very different builds:
#   - a pure-C build (LOCAL_CFLAGS += -DENABLE_LIBRARY, no Java code at all)
#   - a build with JNI bindings baked in for THEIR OWN sample app
#     (JNI entry point Java_TProxyService_StartService), which calls
#     FindClass() for a Java class that doesn't exist in this app's package
#     and aborts the whole process the moment the library is loaded
#     ("JNI DETECTED ERROR IN APPLICATION: java_class == null in call to
#     RegisterNatives"). We hit this exact crash in production.
#
# Instead, a dedicated CI step ("Build clean tun2socks static library (no
# JNI) for all ABIs") builds upstream's plain `make static` target directly
# with the NDK toolchain -- the same recipe upstream documents for
# Linux/Unix -- and drops the resulting libhev-socks5-tunnel.a here:
#   app/src/main/jni/prebuilt/<ABI>/libhev-socks5-tunnel.a
# That build path never touches Android.mk, so it can never pick up a JNI
# wrapper no matter what upstream's Android.mk does next.
include $(CLEAR_VARS)
LOCAL_MODULE := hev-socks5-tunnel-static
LOCAL_SRC_FILES := prebuilt/$(TARGET_ARCH_ABI)/libhev-socks5-tunnel.a
include $(PREBUILT_STATIC_LIBRARY)

LOCAL_PATH := $(CFVPN_JNI_DIR)
include $(CLEAR_VARS)
LOCAL_MODULE := hev2socks_bridge
LOCAL_SRC_FILES := hev_bridge.c
LOCAL_C_INCLUDES := $(CFVPN_JNI_DIR)/hev-socks5-tunnel/src
LOCAL_STATIC_LIBRARIES := hev-socks5-tunnel-static
LOCAL_LDLIBS := -llog
include $(BUILD_SHARED_LIBRARY)
