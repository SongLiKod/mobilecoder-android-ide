# MobileCoder 移动码匠 —— Native 底层（C/C++）
#
# 分层（TECH.md 2.3 核心底层依赖 / 2.4 架构模式第 4 层）：
#   终端引擎   PTY 伪终端（自研 C 实现，libtermux-terminal 风格 JNI 封装）
#   Git 底层   libgit2 静态编译（不依赖系统 Git）
#   SSH 底层   libssh2 静态编译（+ mbedTLS 加密后端；密钥生成由 BouncyCastle 完成）
#   CLI 工具   apt 移动端静态二进制的执行支撑
#
cmake_minimum_required(VERSION 3.18)
project(mobilecoder LANGUAGES C CXX)

set(TP_DIR "${CMAKE_CURRENT_SOURCE_DIR}/third_party")
set(TP_LIB_DIR "${CMAKE_BINARY_DIR}/third_party_libs")
file(MAKE_DIRECTORY "${TP_LIB_DIR}")

# 证书包（HTTPS 仓库克隆），随 APK assets 打包，运行时通过
# git_libgit2_opts(GIT_OPT_SET_SSL_CERT_LOCATIONS) 指向解压后的真实路径。
set(MC_CA_BUNDLE "${CMAKE_CURRENT_SOURCE_DIR}/../assets/certs/cacert.pem")

# ------------------------------------------------------------------
# 第三方构建预设（必须在 add_subdirectory 之前写入 cache）
# ------------------------------------------------------------------
set(BUILD_SHARED_LIBS OFF CACHE BOOL "Static third-party stack" FORCE)

# --- mbedTLS ---
set(ENABLE_PROGRAMS OFF CACHE BOOL "" FORCE)
set(ENABLE_TESTING OFF CACHE BOOL "" FORCE)
set(MBEDTLS_FATAL_WARNINGS OFF CACHE BOOL "" FORCE)
set(USE_SHARED_MBEDTLS_LIBRARY OFF CACHE BOOL "" FORCE)
set(USE_STATIC_MBEDTLS_LIBRARY ON CACHE BOOL "" FORCE)

# --- libssh2 ---
set(BUILD_STATIC_LIBS ON CACHE BOOL "" FORCE)
set(BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(CRYPTO_BACKEND "mbedTLS" CACHE STRING "" FORCE)

# --- libgit2 ---
set(BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(BUILD_CLI OFF CACHE BOOL "" FORCE)
set(BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(BUILD_FUZZERS OFF CACHE BOOL "" FORCE)
set(USE_SSH "libssh2" CACHE STRING "" FORCE)
set(USE_HTTPS "mbedTLS" CACHE STRING "" FORCE)
set(USE_NTLMCLIENT OFF CACHE BOOL "" FORCE)
set(REGEX_BACKEND "builtin" CACHE STRING "" FORCE)
set(USE_HTTP_PARSER "builtin" CACHE STRING "" FORCE)
set(CERT_LOCATION "${MC_CA_BUNDLE}" CACHE PATH "" FORCE)

# libssh2 >= 1.2.6 提供 libssh2_userauth_publickey_frommemory；
# 该探测在交叉编译期无法链接验证（.a 尚未生成），此处直接声明能力，
# 使 libgit2 使用内存密钥接口（私钥不落盘）。
set(HAVE_LIBSSH2_MEMORY_CREDENTIALS 1 CACHE INTERNAL "libssh2 frommemory support" FORCE)

# --- find 模块预设：让 libssh2 / libgit2 命中上面编译出来的静态库 ---
set(MBEDTLS_INCLUDE_DIR "${TP_DIR}/mbedtls/include" CACHE PATH "" FORCE)
set(MBEDTLS_LIBRARY "${TP_LIB_DIR}/libmbedtls.a" CACHE FILEPATH "" FORCE)
set(MBEDX509_LIBRARY "${TP_LIB_DIR}/libmbedx509.a" CACHE FILEPATH "" FORCE)
set(MBEDCRYPTO_LIBRARY "${TP_LIB_DIR}/libmbedcrypto.a" CACHE FILEPATH "" FORCE)
set(LIBSSH2_INCLUDE_DIR "${TP_DIR}/libssh2/include" CACHE PATH "" FORCE)
set(LIBSSH2_LIBRARY "${TP_LIB_DIR}/libssh2.a" CACHE FILEPATH "" FORCE)

if(NOT EXISTS "${MC_CA_BUNDLE}")
    message(FATAL_ERROR "缺少 CA 证书包: ${MC_CA_BUNDLE}（feature 需要 HTTPS 克隆能力）")
endif()

# ------------------------------------------------------------------
# 第三方静态库
# ------------------------------------------------------------------
add_subdirectory("${TP_DIR}/mbedtls")
foreach(_t mbedcrypto mbedx509 mbedtls)
    if(TARGET ${_t})
        set_target_properties(${_t} PROPERTIES ARCHIVE_OUTPUT_DIRECTORY "${TP_LIB_DIR}")
    endif()
endforeach()

add_subdirectory("${TP_DIR}/libssh2")
if(TARGET libssh2_static)
    # PREFIX ""/OUTPUT_NAME libssh2 —— 产物固定为 <TP_LIB_DIR>/libssh2.a
    set_target_properties(libssh2_static PROPERTIES ARCHIVE_OUTPUT_DIRECTORY "${TP_LIB_DIR}")
endif()

add_subdirectory("${TP_DIR}/libgit2")
if(TARGET libgit2package)
    set_target_properties(libgit2package PROPERTIES ARCHIVE_OUTPUT_DIRECTORY "${TP_LIB_DIR}")
endif()

# Android 平台修正：libgit2 给 util/libgit2 目标硬编码了 C_STANDARD 90 且
# C_EXTENSIONS OFF（即 -std=c90），而 bionic/NDK 头文件按 C99 编写
# （如 sysroot 的 linux/swab.h 使用 inline），会导致编译失败：
#   error: unknown type name 'inline'
# libgit2 源码本身兼容 C99，此处统一提升到 C99，不修改第三方源码。
foreach(_t util libgit2 libgit2package)
    if(TARGET ${_t})
        set_target_properties(${_t} PROPERTIES C_STANDARD 99)
    endif()
endforeach()

# ------------------------------------------------------------------
# MobileCoder JNI 共享库
# ------------------------------------------------------------------
add_library(mobilecoder SHARED
    jni_util.c
    terminal_jni.c
    cli_jni.c
    git_jni.c
    ssh_jni.c
)

target_include_directories(mobilecoder PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}"
    "${TP_DIR}/libgit2/include"
    "${TP_DIR}/libssh2/include"
    "${TP_DIR}/mbedtls/include"
)

target_compile_options(mobilecoder PRIVATE -Wall -Wextra -Wno-unused-parameter -Wno-missing-field-initializers)

find_library(LOG_LIB log)

target_link_libraries(mobilecoder PRIVATE
    libgit2package
    libssh2_static
    mbedtls
    mbedx509
    mbedcrypto
    z
    ${LOG_LIB}
)
