cmake_minimum_required(VERSION 3.22.1)
project(cfvpn_tun2socks C)

# ---------------------------------------------------------------------------
# این فایل سورسِ hev-socks5-tunnel (https://github.com/heiher/hev-socks5-tunnel)
# را به‌صورت خودکار در زمانِ build از GitHub می‌گیرد و با همان کامپایلر NDK که
# Android Gradle Plugin برای هر ABI انتخاب می‌کند می‌سازد. هیچ دانلود یا کپیِ
# دستیِ .a لازم نیست؛ فقط باید git و make روی ماشینی که Gradle را اجرا می‌کند
# نصب باشند (روی لینوکس/مک به‌طور پیش‌فرض هستند؛ روی ویندوز از طریق Git Bash یا
# WSL در دسترس‌اند).
# ---------------------------------------------------------------------------

include(ExternalProject)

set(HEV_SRC_DIR ${CMAKE_CURRENT_BINARY_DIR}/hev-socks5-tunnel-src)
set(HEV_LIB ${HEV_SRC_DIR}/bin/libhev-socks5-tunnel.a)
set(HEV_INCLUDE_DIR ${HEV_SRC_DIR}/include)

ExternalProject_Add(
    hev_socks5_tunnel_ext
    GIT_REPOSITORY    https://github.com/heiher/hev-socks5-tunnel.git
    GIT_TAG           main
    GIT_SUBMODULES_RECURSE ON
    GIT_SHALLOW       ON
    SOURCE_DIR        ${HEV_SRC_DIR}
    CONFIGURE_COMMAND ""
    BUILD_IN_SOURCE   1
    # همان کامپایلر/آرشیوکننده‌ای که AGP برای این ABI مشخص خاص (arm64-v8a,
    # armeabi-v7a, ...) تنظیم کرده مستقیماً به Makefile خودِ پروژه پاس داده می‌شود.
    BUILD_COMMAND     make static
                       CC=${CMAKE_C_COMPILER}
                       AR=${CMAKE_AR}
                       STRIP=${CMAKE_STRIP}
                       "CFLAGS=-fPIC -O2 -DFD_SET_DEFINED -DSOCKLEN_T_DEFINED"
    INSTALL_COMMAND   ""
    BUILD_BYPRODUCTS  ${HEV_LIB}
    LOG_DOWNLOAD      1
    LOG_BUILD         1
)

add_library(hev_socks5_tunnel STATIC IMPORTED)
set_target_properties(hev_socks5_tunnel PROPERTIES
    IMPORTED_LOCATION ${HEV_LIB}
)
add_dependencies(hev_socks5_tunnel hev_socks5_tunnel_ext)

add_library(cfvpn_tun2socks SHARED
    tun2socks_jni.c
)

target_include_directories(cfvpn_tun2socks PRIVATE
    ${HEV_INCLUDE_DIR}
)
add_dependencies(cfvpn_tun2socks hev_socks5_tunnel_ext)

find_library(log-lib log)

target_link_libraries(cfvpn_tun2socks
    hev_socks5_tunnel
    ${log-lib}
)
