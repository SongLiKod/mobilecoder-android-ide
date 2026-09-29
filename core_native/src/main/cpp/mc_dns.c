/*
 * glibc 在 Android 上的 DNS 兜底（阶段 2）。
 *
 * Android 根本没有 `/etc/resolv.conf`，glibc 的 stub resolver 找不到配置时会退到
 * `127.0.0.1:53` —— Android 上没有任何进程监听这个端口，于是所有域名解析都超时，
 * 症状是 npm / git 一连网就卡几秒再报 `EAI_AGAIN`。bionic 没这个问题（它走 netd），
 * 所以只有 glibc 程序需要这段。
 *
 * 策略：先让系统自己解析（正常情况下永远走不到下面），失败了才用
 * `MOBILECODER_DNS` 里的 nameserver 直接发一次 UDP 查询。
 */
#define _GNU_SOURCE
#include "mc_compat.h"

#include <arpa/inet.h>
#include <errno.h>
#include <netinet/in.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <time.h>
#include <unistd.h>

#define MC_DNS_MAX 3
#define MC_DNS_BUF 1500

static unsigned rd16(const unsigned char *p) {
    return ((unsigned) p[0] << 8) | (unsigned) p[1];
}

static unsigned rd32(const unsigned char *p) {
    return ((unsigned) p[0] << 24) | ((unsigned) p[1] << 16) |
           ((unsigned) p[2] << 8) | (unsigned) p[3];
}

/** nameserver 列表（`MOBILECODER_DNS`，空格 / 逗号分隔的 IPv4）。 */
static int servers(struct in_addr out[MC_DNS_MAX]) {
    const char *spec = getenv("MOBILECODER_DNS");
    if (spec == NULL || *spec == '\0') spec = "223.5.5.5 119.29.29.29 8.8.8.8";
    int n = 0;
    const char *p = spec;
    while (*p != '\0' && n < MC_DNS_MAX) {
        while (*p == ' ' || *p == ',' || *p == ';' || *p == '\t') p++;
        if (*p == '\0') break;
        char tok[64];
        size_t len = 0;
        while (p[len] != '\0' && p[len] != ' ' && p[len] != ',' &&
               p[len] != ';' && p[len] != '\t' && len < sizeof tok - 1) {
            len++;
        }
        memcpy(tok, p, len);
        tok[len] = '\0';
        p += len;
        if (inet_pton(AF_INET, tok, &out[n]) == 1) n++;
    }
    return n;
}

/** `a.b.c` → DNS 标签编码；返回写入字节数，非法返回 -1。 */
static int encode_name(unsigned char *p, const char *name) {
    int n = 0;
    const char *s = name;
    while (*s != '\0') {
        while (*s == '.') s++;
        if (*s == '\0') break;
        const char *dot = strchr(s, '.');
        size_t lab = (dot != NULL) ? (size_t) (dot - s) : strlen(s);
        if (lab == 0 || lab > 63) return -1;
        p[n++] = (unsigned char) lab;
        memcpy(p + n, s, lab);
        n += (int) lab;
        s += lab;
    }
    p[n++] = 0;
    return n;
}

/** 跳过域名（支持 0xC0 压缩）；越界返回 NULL。 */
static const unsigned char *skip_name(const unsigned char *p, const unsigned char *end) {
    while (p < end) {
        if ((*p & 0xC0) == 0xC0) return (p + 2 <= end) ? p + 2 : NULL;
        if (*p == 0) return p + 1;
        p += (size_t) *p + 1;
    }
    return NULL;
}

/** 向一个 nameserver 发一次查询并收包；返回响应长度，失败返回 -1。 */
static int query(const struct in_addr *server, const char *qname, int qtype,
                 unsigned char *resp, size_t cap) {
    unsigned char msg[512];
    unsigned id = (unsigned) getpid() ^ (unsigned) time(NULL) ^ (unsigned) qtype;
    memset(msg, 0, 12);
    msg[0] = (unsigned char) (id >> 8);
    msg[1] = (unsigned char) id;
    msg[2] = 0x01; /* RD */
    msg[5] = 0x01; /* QDCOUNT = 1 */
    int n = encode_name(msg + 12, qname);
    if (n < 0) return -1;
    int off = 12 + n;
    if (off + 4 > (int) sizeof msg) return -1;
    msg[off++] = (unsigned char) (qtype >> 8);
    msg[off++] = (unsigned char) qtype;
    msg[off++] = 0;
    msg[off++] = 1; /* CLASS IN */

    int fd = socket(AF_INET, SOCK_DGRAM, 0);
    if (fd < 0) return -1;
    struct timeval tv;
    tv.tv_sec = 2;
    tv.tv_usec = 0;
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof tv);
    struct sockaddr_in dst;
    memset(&dst, 0, sizeof dst);
    dst.sin_family = AF_INET;
    dst.sin_addr = *server;
    dst.sin_port = htons(53);
    ssize_t sent = sendto(fd, msg, (size_t) off, 0,
                          (struct sockaddr *) &dst, sizeof dst);
    if (sent < 0) {
        close(fd);
        return -1;
    }
    ssize_t got = recvfrom(fd, resp, cap, 0, NULL, NULL);
    close(fd);
    if (got < 12) return -1;
    if ((resp[2] & 0x80) == 0) return -1; /* 不是应答 */
    return (int) got;
}

/** 从响应里取第一条 type 匹配的 A / AAAA 记录。 */
static int parse(const unsigned char *resp, int rlen, int qtype, unsigned char *addr) {
    if (rlen < 12) return -1;
    unsigned qd = rd16(resp + 4);
    unsigned an = rd16(resp + 6);
    const unsigned char *p = resp + 12;
    const unsigned char *end = resp + rlen;
    for (unsigned i = 0; i < qd; i++) {
        p = skip_name(p, end);
        if (p == NULL || p + 4 > end) return -1;
        p += 4;
    }
    for (unsigned i = 0; i < an; i++) {
        p = skip_name(p, end);
        if (p == NULL || p + 10 > end) return -1;
        unsigned type = rd16(p);
        unsigned cls = rd16(p + 2);
        unsigned ttl = rd32(p + 4);
        unsigned rdlen = rd16(p + 8);
        p += 10;
        (void) ttl;
        if (rdlen > (unsigned) (end - p)) return -1;
        if (cls == 1 && type == (unsigned) qtype &&
            rdlen == (qtype == 1 ? 4u : 16u)) {
            memcpy(addr, p, rdlen);
            return 0;
        }
        p += rdlen;
    }
    return -1;
}

/** 造一个 addrinfo 节点并挂到链尾（ai_addr / ai_canonname 与节点同一块内存）。 */
static int push(struct addrinfo **head, struct addrinfo **tail,
                int af, const void *addr, int addrlen, int port,
                const struct addrinfo *hints, const char *canon) {
    /*
     * 单块分配、ai_addr 与 ai_canonname 指向块内 —— 这样即使调用方用的是 libc
     * 自己的 `freeaddrinfo()`（它只对每个节点做一次 free），我们的节点也不会
     * 泄漏或崩溃；反过来如果它们单独 malloc，libc 的 freeaddrinfo 不会去 free。
     */
    size_t off = (sizeof(struct addrinfo) + 7u) & ~(size_t) 7u;
    size_t extra = (size_t) addrlen;
    if (canon != NULL) extra += strlen(canon) + 1;
    struct addrinfo *ai = (struct addrinfo *) calloc(1, off + extra);
    if (ai == NULL) return -1;
    ai->ai_family = af;
    ai->ai_socktype = (hints != NULL && hints->ai_socktype != 0)
                          ? hints->ai_socktype : SOCK_STREAM;
    ai->ai_protocol = (hints != NULL && hints->ai_protocol != 0)
                          ? hints->ai_protocol : IPPROTO_TCP;
    ai->ai_addrlen = (socklen_t) addrlen;
    ai->ai_addr = (struct sockaddr *) ((char *) ai + off);
    char *tail_ptr = (char *) ai + off + (size_t) addrlen;
    if (canon != NULL) {
        memcpy(tail_ptr, canon, strlen(canon) + 1);
        ai->ai_canonname = tail_ptr;
    }
    if (af == AF_INET) {
        struct sockaddr_in *in = (struct sockaddr_in *) ai->ai_addr;
        in->sin_family = AF_INET;
        in->sin_port = htons((unsigned short) port);
        memcpy(&in->sin_addr, addr, 4);
    } else {
        struct sockaddr_in6 *in6 = (struct sockaddr_in6 *) ai->ai_addr;
        in6->sin6_family = AF_INET6;
        in6->sin6_port = htons((unsigned short) port);
        memcpy(&in6->sin6_addr, addr, 16);
    }
    if (*tail != NULL) {
        (*tail)->ai_next = ai;
    } else {
        *head = ai;
    }
    *tail = ai;
    return 0;
}

int mc_getaddrinfo_raw(const char *node, const char *service,
                       const struct addrinfo *hints, struct addrinfo **res) {
    if (res == NULL) return EAI_FAIL;
    *res = NULL;
    if (node == NULL || *node == '\0') return EAI_NONAME;

    int family = (hints != NULL) ? hints->ai_family : AF_UNSPEC;
    int port = 0;
    if (service != NULL && *service != '\0') {
        char *endp = NULL;
        long v = strtol(service, &endp, 10);
        if (endp == service || *endp != '\0' || v < 0 || v > 65535) return EAI_SERVICE;
        port = (int) v;
    }

    struct addrinfo *head = NULL;
    struct addrinfo *tail = NULL;
    const char *canon = (hints != NULL && (hints->ai_flags & AI_CANONNAME) != 0)
                            ? node : NULL;

    /* 纯 IP 字面量：不用查 DNS */
    struct in_addr v4;
    struct in6_addr v6;
    if (inet_pton(AF_INET, node, &v4) == 1) {
        if (push(&head, &tail, AF_INET, &v4, (int) sizeof v4, port, hints, canon) != 0) {
            return EAI_FAIL;
        }
        *res = head;
        return 0;
    }
    if (inet_pton(AF_INET6, node, &v6) == 1) {
        if (push(&head, &tail, AF_INET6, &v6, (int) sizeof v6, port, hints, canon) != 0) {
            return EAI_FAIL;
        }
        *res = head;
        return 0;
    }
    if (family != AF_UNSPEC && family != AF_INET && family != AF_INET6) return EAI_FAMILY;

    int types[2];
    int ntype = 0;
    if (family == AF_INET) types[ntype++] = 1;
    else if (family == AF_INET6) types[ntype++] = 28;
    else {
        types[ntype++] = 1;
        types[ntype++] = 28;
    }

    struct in_addr ns[MC_DNS_MAX];
    int nscount = servers(ns);
    if (nscount == 0) return EAI_AGAIN;

    for (int t = 0; t < ntype && head == NULL; t++) {
        int qtype = types[t];
        unsigned char addr[16];
        memset(addr, 0, sizeof addr);
        int found = 0;
        for (int i = 0; i < nscount && !found; i++) {
            unsigned char resp[MC_DNS_BUF];
            int rlen = query(&ns[i], node, qtype, resp, sizeof resp);
            if (rlen > 0 && parse(resp, rlen, qtype, addr) == 0) found = 1;
        }
        if (!found) continue;
        if (qtype == 1) {
            push(&head, &tail, AF_INET, addr, 4, port, hints, canon);
        } else {
            push(&head, &tail, AF_INET6, addr, 16, port, hints, canon);
        }
    }
    if (head == NULL) return EAI_NONAME;
    *res = head;
    return 0;
}
