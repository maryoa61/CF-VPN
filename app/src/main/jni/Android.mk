# Android.mk — Links prebuilt static libs + JNI bridge into one .so
# Each .a is kept separate to avoid .o name collisions (especially in liblwip.a)
CFVPN_JNI_DIR := $(call my-dir)
LOCAL_PATH := $(CFVPN_JNI_DIR)

include $(CLEAR_VARS)
LOCAL_MODULE := hev-socks5-tunnel-static
LOCAL_SRC_FILES := prebuilt/$(TARGET_ARCH_ABI)/libhev-socks5-tunnel.a
include $(PREBUILT_STATIC_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := hev-task-system-static
LOCAL_SRC_FILES := prebuilt/$(TARGET_ARCH_ABI)/libhev-task-system.a
include $(PREBUILT_STATIC_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := lwip-static
LOCAL_SRC_FILES := prebuilt/$(TARGET_ARCH_ABI)/liblwip.a
include $(PREBUILT_STATIC_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := yaml-static
LOCAL_SRC_FILES := prebuilt/$(TARGET_ARCH_ABI)/libyaml.a
include $(PREBUILT_STATIC_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := hev2socks_bridge
LOCAL_SRC_FILES := hev_bridge.c
LOCAL_STATIC_LIBRARIES := hev-socks5-tunnel-static hev-task-system-static lwip-static yaml-static
LOCAL_LDLIBS := -llog
include $(BUILD_SHARED_LIBRARY)
