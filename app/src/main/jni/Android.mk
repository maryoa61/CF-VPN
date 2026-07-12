LOCAL_PATH := $(call my-dir)

# Build the upstream hev-socks5-tunnel shared library (libhev-socks5-tunnel.so).
# Its source is cloned by CI into ./hev-socks5-tunnel before this file runs
# (see: android.yml, step "Fetch native VPN engine sources").
include $(LOCAL_PATH)/hev-socks5-tunnel/Android.mk

# The included makefile resets LOCAL_PATH internally, restore it.
LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := hev2socks_bridge
LOCAL_SRC_FILES := hev_bridge.c
LOCAL_C_INCLUDES := $(LOCAL_PATH)/hev-socks5-tunnel/src
LOCAL_SHARED_LIBRARIES := hev-socks5-tunnel
LOCAL_LDLIBS := -llog
include $(BUILD_SHARED_LIBRARY)
