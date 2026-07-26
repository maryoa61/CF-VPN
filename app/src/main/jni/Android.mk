# Android.mk — Links multiple prebuilt static libs + JNI bridge into one .so
CFVPN_JNI_DIR := $(call my-dir)
LOCAL_PATH := $(CFVPN_JNI_DIR)

include $(CLEAR_VARS)
LOCAL_MODULE := hev-socks5-tunnel-static
LOCAL_SRC_FILES := prebuilt/$(TARGET_ARCH_ABI)/libhev-socks5-tunnel.a
include $(PREBUILT_STATIC_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := hev-socks-bridge
LOCAL_SRC_FILES := hev_bridge.c
LOCAL_STATIC_LIBRARIES := hev-socks5-tunnel-static
LOCAL_LDLIBS := -llog
include $(BUILD_SHARED_LIBRARY)
