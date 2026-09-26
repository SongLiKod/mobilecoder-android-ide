/*
 * MobileCoder —— SSH 连接引擎（libssh2 静态 JNI 封装）
 *
 * 需求对应（PRD 2.6 / TECH 4.5）：
 *  - 可视化生成的 RSA / Ed25519 私钥（BouncyCastle 生成，PEM）以
 *    “内存凭据”方式直接传入本层，全程不落盘
 *  - 连接测试：TCP → 握手 → 主机密钥指纹 → 公钥认证
 *  - 支持多密钥多账号（Kotlin 层每次调用传入不同的密钥/账号）
 *
 * 返回帧（'\x01' 分隔）：
 *   code \x01 message \x01 fingerprint \x01 hostKeyType
 *   code: 0 连接+认证成功 / 1 连接成功但认证失败 / -1 连接失败
 */
#include <jni.h>

#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <netdb.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/select.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <unistd.h>

#include <libssh2.h>

#include "jni_util.h"

#define MC_FIELD_SEP '\x01'

static int g_ssh_refcount = 0;

/* ------------------------------------------------------------------ */
/* 工具                                                                */
/* ------------------------------------------------------------------ */

static const char *b64_table =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

static void base64_encode(const unsigned char *src, size_t len, char *out, size_t outsize) {
    size_t i = 0, o = 0;

    while (i < len && o + 5 < outsize) {
        unsigned int v = (unsigned int) src[i] << 16;
        if (i + 1 < len) {
            v |= (unsigned int) src[i + 1] << 8;
        }
        if (i + 2 < len) {
            v |= src[i + 2];
        }
        out[o++] = b64_table[(v >> 18) & 0x3f];
        out[o++] = b64_table[(v >> 12) & 0x3f];
        out[o++] = (i + 1 < len) ? b64_table[(v >> 6) & 0x3f] : '=';
        out[o++] = (i + 2 < len) ? b64_table[v & 0x3f] : '=';
        i += 3;
    }
    out[o] = '\0';
}

static const char *hostkey_type_name(int type) {
    switch (type) {
        case LIBSSH2_HOSTKEY_TYPE_RSA:      return "ssh-rsa";
        case LIBSSH2_HOSTKEY_TYPE_DSS:      return "ssh-dss";
        case LIBSSH2_HOSTKEY_TYPE_ECDSA_256: return "ecdsa-sha2-nistp256";
        case LIBSSH2_HOSTKEY_TYPE_ECDSA_384: return "ecdsa-sha2-nistp384";
        case LIBSSH2_HOSTKEY_TYPE_ECDSA_521: return "ecdsa-sha2-nistp521";
        case LIBSSH2_HOSTKEY_TYPE_ED25519:  return "ssh-ed25519";
        default:                           return "unknown";
    }
}

static const char *ssh_strerror(int rc) {
    switch (rc) {
        case LIBSSH2_ERROR_SOCKET_NONE:        return "套接字无效";
        case LIBSSH2_ERROR_BANNER_RECV:        return "未收到 SSH 服务标识（端口可能不是 SSH）";
        case LIBSSH2_ERROR_BANNER_SEND:        return "发送 SSH 标识失败";
        case LIBSSH2_ERROR_KEX_FAILURE:        return "密钥交换失败";
        case LIBSSH2_ERROR_ALLOC:              return "内存分配失败";
        case LIBSSH2_ERROR_SOCKET_SEND:        return "发送数据失败";
        case LIBSSH2_ERROR_KEY_EXCHANGE_FAILURE: return "密钥交换失败";
        case LIBSSH2_ERROR_TIMEOUT:            return "连接超时";
        case LIBSSH2_ERROR_HOSTKEY_INIT:       return "主机密钥初始化失败";
        case LIBSSH2_ERROR_HOSTKEY_SIGN:       return "主机密钥签名校验失败";
        case LIBSSH2_ERROR_DECRYPT:            return "解密失败（私钥口令错误？）";
        case LIBSSH2_ERROR_SOCKET_DISCONNECT:  return "连接被断开";
        case LIBSSH2_ERROR_PROTO:              return "SSH 协议错误";
        case LIBSSH2_ERROR_AUTHENTICATION_FAILED: return "认证失败（密钥未被服务器接受）";
        case LIBSSH2_ERROR_PUBLICKEY_UNVERIFIED: return "公钥未通过验证";
        case LIBSSH2_ERROR_METHOD_NONE:        return "服务器不支持所需认证方式";
        case LIBSSH2_ERROR_FILE:               return "密钥数据无效（PEM 格式错误？）";
        case LIBSSH2_ERROR_SOCKET_TIMEOUT:     return "连接超时";
        case LIBSSH2_ERROR_BAD_SOCKET:         return "套接字已关闭";
        case LIBSSH2_ERROR_EAGAIN:             return "操作未完成，请重试";
        default:                               return "SSH 错误";
    }
}

/* 非阻塞 connect + 超时 */
static int mc_connect(const char *host, int port, int timeout_ms, char *errbuf, size_t errsize) {
    struct addrinfo hints;
    struct addrinfo *res = NULL, *ai;
    char portstr[16];
    int fd = -1;
    int rc;

    memset(&hints, 0, sizeof(hints));
    hints.ai_family = AF_UNSPEC;
    hints.ai_socktype = SOCK_STREAM;
    snprintf(portstr, sizeof(portstr), "%d", port);

    rc = getaddrinfo(host, portstr, &hints, &res);
    if (rc != 0 || res == NULL) {
        snprintf(errbuf, errsize, "无法解析主机：%s", host);
        return -1;
    }

    for (ai = res; ai != NULL; ai = ai->ai_next) {
        int flags;

        fd = socket(ai->ai_family, ai->ai_socktype, ai->ai_protocol);
        if (fd < 0) {
            continue;
        }

        flags = fcntl(fd, F_GETFL, 0);
        fcntl(fd, F_SETFL, flags | O_NONBLOCK);

        rc = connect(fd, ai->ai_addr, ai->ai_addrlen);
        if (rc < 0 && errno == EINPROGRESS) {
            fd_set wset;
            struct timeval tv;
            int soerr = 0;
            socklen_t slen = sizeof(soerr);

            FD_ZERO(&wset);
            FD_SET(fd, &wset);
            tv.tv_sec = timeout_ms / 1000;
            tv.tv_usec = (timeout_ms % 1000) * 1000;

            rc = select(fd + 1, NULL, &wset, NULL, &tv);
            if (rc > 0 && getsockopt(fd, SOL_SOCKET, SO_ERROR, &soerr, &slen) == 0 && soerr == 0) {
                rc = 0;
            } else {
                rc = -1;
            }
        }

        if (rc == 0) {
            int one = 1;
            fcntl(fd, F_SETFL, flags);
            setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, sizeof(one));
            break;
        }
        close(fd);
        fd = -1;
    }

    freeaddrinfo(res);

    if (fd < 0) {
        snprintf(errbuf, errsize, "无法连接 %s:%d", host, port);
    }
    return fd;
}

/* ------------------------------------------------------------------ */
/*  JNI                                                                */
/* ------------------------------------------------------------------ */

JNIEXPORT jboolean JNICALL
Java_com_mobilecoder_ide_core_nativebridge_SshNative_runtimeInit(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    if (g_ssh_refcount <= 0) {
        if (libssh2_init(0) != 0) {
            return JNI_FALSE;
        }
        g_ssh_refcount = 1;
    } else {
        g_ssh_refcount++;
    }
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_mobilecoder_ide_core_nativebridge_SshNative_shutdown(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    if (g_ssh_refcount > 0) {
        g_ssh_refcount--;
        if (g_ssh_refcount == 0) {
            libssh2_exit();
        }
    }
}

/*
 * 连接测试。
 * 返回：code \x01 message \x01 fingerprint \x01 hostKeyType
 *   code  0 = 连接 + 认证成功
 *         1 = 连接成功，但公钥认证失败
 *        -1 = 连接失败
 * privateKey / passphrase 允许为空（仅做连通性与主机密钥探测）。
 */
JNIEXPORT jstring JNICALL
Java_com_mobilecoder_ide_core_nativebridge_SshNative_testConnection(
        JNIEnv *env, jclass clazz,
        jstring host, jint port, jstring username,
        jstring privateKey, jstring passphrase, jint timeoutMs) {
    (void) clazz;

    char *c_host = mc_jstring_to_cstr(env, host);
    char *c_user = mc_jstring_to_cstr(env, username);
    char *c_key = mc_jstring_to_cstr(env, privateKey);
    char *c_pass = mc_jstring_to_cstr(env, passphrase);

    char errbuf[256];
    char fingerprint[128];
    char keytype[64];
    char message[512];
    int code = -1;

    int fd = -1;
    LIBSSH2_SESSION *session = NULL;
    int rc;

    errbuf[0] = '\0';
    fingerprint[0] = '\0';
    snprintf(keytype, sizeof(keytype), "%s", "unknown");
    snprintf(message, sizeof(message), "%s", "");

    if (c_host == NULL || c_host[0] == '\0') {
        snprintf(message, sizeof(message), "主机地址为空");
        goto done;
    }
    if (c_user == NULL || c_user[0] == '\0') {
        snprintf(message, sizeof(message), "用户名为空");
        goto done;
    }
    if (g_ssh_refcount <= 0) {
        if (libssh2_init(0) != 0) {
            snprintf(message, sizeof(message), "libssh2 初始化失败");
            goto done;
        }
        g_ssh_refcount = 1;
    }

    fd = mc_connect(c_host, port > 0 ? (int) port : 22,
                    timeoutMs > 0 ? (int) timeoutMs : 10000,
                    errbuf, sizeof(errbuf));
    if (fd < 0) {
        snprintf(message, sizeof(message), "%s", errbuf);
        goto done;
    }

    session = libssh2_session_init();
    if (session == NULL) {
        snprintf(message, sizeof(message), "创建 SSH 会话失败");
        goto done;
    }
    libssh2_session_set_blocking(session, 1);
    libssh2_session_set_timeout(session, timeoutMs > 0 ? (int) timeoutMs : 10000);

    rc = libssh2_session_handshake(session, fd);
    if (rc != 0) {
        snprintf(message, sizeof(message), "%s (%d)", ssh_strerror(rc), rc);
        goto done;
    }

    /* 主机密钥指纹（OpenSSH 风格 SHA256:base64） */
    {
        const char *raw = libssh2_hostkey_hash(session, LIBSSH2_HOSTKEY_HASH_SHA256);
        if (raw != NULL) {
            char b64[64];
            base64_encode((const unsigned char *) raw, 32, b64, sizeof(b64));
            snprintf(fingerprint, sizeof(fingerprint), "SHA256:%s", b64);
        }
        {
            size_t len = 0;
            int type = LIBSSH2_HOSTKEY_TYPE_UNKNOWN;
            const char *key = libssh2_session_hostkey(session, &len, &type);
            (void) key;
            snprintf(keytype, sizeof(keytype), "%s", hostkey_type_name(type));
        }
    }

    if (c_key == NULL || c_key[0] == '\0') {
        code = 1;
        snprintf(message, sizeof(message), "SSH 服务器可达（未提供私钥，跳过认证）");
        goto done;
    }

    rc = libssh2_userauth_publickey_frommemory(
            session,
            c_user, strlen(c_user),
            NULL, 0,
            c_key, strlen(c_key),
            (c_pass != NULL && c_pass[0] != '\0') ? c_pass : NULL);

    if (rc != 0) {
        code = 1;
        snprintf(message, sizeof(message), "%s (%d)", ssh_strerror(rc), rc);
        goto done;
    }

    if (libssh2_userauth_authenticated(session)) {
        code = 0;
        snprintf(message, sizeof(message), "连接并认证成功");
    } else {
        code = 1;
        snprintf(message, sizeof(message), "认证未通过");
    }

done:
    if (session != NULL) {
        libssh2_session_disconnect(session, "bye");
        libssh2_session_free(session);
    }
    if (fd >= 0) {
        close(fd);
    }

    free(c_host);
    free(c_user);
    free(c_key);
    free(c_pass);

    {
        char frame[1024];
        snprintf(frame, sizeof(frame), "%d%c%s%c%s%c%s",
                 code, MC_FIELD_SEP, message, MC_FIELD_SEP,
                 fingerprint, MC_FIELD_SEP, keytype);
        return mc_cstr_to_jstring(env, frame);
    }
}
