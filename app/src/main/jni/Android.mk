# Save our own jni/ directory ONCE, before any includes. We can't rely on a
# second $(call my-dir) after including hev-socks5-tunnel/Android.mk: that
# upstream makefile pulls in several nested makefiles of its own (yaml, lwip,
# hev-task-system, ...), and $(call my-dir) resolves from the LAST entry in
# the global $(MAKEFILE_LIST) — which after that whole include chain is one
# of those nested files, not this Android.mk. That mismatch is what caused
# hev_bridge.c to be looked up relative to the NDK's own build/core dir
# instead of this jni/ directory.
CFVPN_JNI_DIR := $(call my-dir)

LOCAL_PATH := $(CFVPN_JNI_DIR)

# Build the upstream hev-socks5-tunnel shared library (libhev-socks5-tunnel.so).
# Its source is cloned by CI into ./hev-socks5-tunnel before this file runs
# (see: android.yml, step "Fetch native VPN engine sources").
include $(CFVPN_JNI_DIR)/hev-socks5-tunnel/Android.mk

LOCAL_PATH := $(CFVPN_JNI_DIR)

include $(CLEAR_VARS)
LOCAL_MODULE := hev2socks_bridge
LOCAL_SRC_FILES := hev_bridge.c
LOCAL_C_INCLUDES := $(CFVPN_JNI_DIR)/hev-socks5-tunnel/src
LOCAL_SHARED_LIBRARIES := hev-socks5-tunnel
LOCAL_LDLIBS := -llog
include $(BUILD_SHARED_LIBRARY)
