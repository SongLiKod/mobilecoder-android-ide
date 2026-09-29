/*
 * LD_PRELOAD 钩子（阶段 2）：exec 前把「官方 Linux 发行包」的 glibc ELF 修好。
 *
 * 阶段 1 已经在 Kotlin 侧把**存量**二进制的 `PT_INTERP` 改写到本机 loader，
 * 剩下的窗口是 `npm i -g` 解包的**瞬间**：postinstall 脚本会立刻 exec 刚落盘、
 * 还没被打补丁的 esbuild / ripgrep 这类原生二进制。钩子就堵这个窗口：
 *
 *   execve(target)
 *     ├─ target 是 glibc ELF → 原地改写 PT_INTERP → 正常 exec
 *     │                        （改不动时退回 `loader --library-path … target`）
 *     └─ 其它（bionic / 脚本 / 静态）→ 清掉 LD_LIBRARY_PATH，
 *        把 LD_PRELOAD 换成与 target ABI 匹配的那份，避免加载器读到异架构的 .so
 *
 * glibc 版还额外覆盖 `getaddrinfo`：Android 没有 /etc/resolv.conf，
 * glibc 会退到 127.0.0.1:53（没人监听）→ 所有域名解析超时，见 mc_dns.c。
 *
 * 两套目标共用本文件：NDK 编出 `libmcexechook.so`（bionic），
 * `tools/glibc-runtime/build.sh` 编出 glibc 版放进运行时压缩包。
 */
#define _GNU_SOURCE
#include "mc_compat.h"

#include <errno.h>
#include <limits.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#ifdef __GLIBC__
#include <dlfcn.h>
#endif

#ifndef PATH_MAX
#define PATH_MAX 4096
#endif

#define MC_MAX_ENV 512
#define MC_MAX_ARG 256

extern char **environ;

/* ------------------------------------------------------------------
 * 组装子进程 envp
 * ------------------------------------------------------------------ */

/*
 * glibc 目标：`LD_LIBRARY_PATH` 指到本机 glibc 库目录（Android 没有 /lib，
 * 不给这个 loader 连 libc.so.6 都找不到），LD_PRELOAD 换成 glibc 版钩子；
 * 其它目标：删掉 LD_LIBRARY_PATH（免得 bionic 去搜 glibc 的目录），
 * LD_PRELOAD 换成 bionic 版钩子（脚本的 shebang 会走 /system/bin/sh）。
 * 两个 hook 环境变量任一缺失时 [mc_env_merge] 收到 NULL，会直接把该项删掉。
 */
static int build_env(const char *target, char *const envp[], char *dst[], int cap) {
    char loader[PATH_MAX];
    char libdir[PATH_MAX];
    char libval[PATH_MAX * 2];
    char hook[PATH_MAX];
    const char *lib_in = NULL;
    const char *hook_in = NULL;

    if (mc_needs_glibc(target) &&
        mc_glibc_paths(loader, sizeof loader, libdir, sizeof libdir) == 0) {
        const char *old = mc_env_value(envp, "LD_LIBRARY_PATH");
        if (old != NULL && *old != '\0') {
            snprintf(libval, sizeof libval, "%s:%s", libdir, old);
        } else {
            snprintf(libval, sizeof libval, "%s", libdir);
        }
        lib_in = libval;
    }
    if (mc_preload_for(target, hook, sizeof hook) == 0) hook_in = hook;

    return mc_env_merge(envp, dst, cap,
                        "LD_LIBRARY_PATH", lib_in,
                        "LD_PRELOAD", hook_in);
}

/* ------------------------------------------------------------------
 * exec
 * ------------------------------------------------------------------ */

static int mc_do_exec(const char *path, char *const argv[], char *const envp[]) {
    if (path == NULL) {
        errno = ENOENT;
        return -1;
    }
    char resolved[PATH_MAX];
    const char *target = path;
    if (path[0] != '/' && mc_resolve_exec(path, resolved, sizeof resolved) == 0) {
        target = resolved;
    }
    /* 没有 glibc 运行时就不做任何 env 改动，钩子退化成一次透明转发 */
    if (getenv("MOBILECODER_GLIBC") == NULL) {
        return mc_raw_execve(target, argv, envp);
    }

    char *menv[MC_MAX_ENV];
    int nenv = build_env(target, envp, menv, MC_MAX_ENV);
    (void) nenv;

    char loader[PATH_MAX];
    char libdir[PATH_MAX];
    if (mc_glibc_paths(loader, sizeof loader, libdir, sizeof libdir) == 0) {
        char cur[PATH_MAX];
        if (mc_read_interp(target, cur, sizeof cur) == 0 && mc_interp_is_glibc(cur)) {
            /* 已经是本机 loader 就别再动：重复追加会让文件越长越大 */
            if (strcmp(cur, loader) != 0 && mc_patch_interp(target, loader) != 0) {
                return mc_exec_via_loader(target, argv, menv);
            }
        }
    }
    return mc_raw_execve(target, argv, menv);
}

/* 可变参数列表 → argv（collect 负责补结尾 NULL）。 */
static void collect(const char *first, va_list ap, char *out[], int cap) {
    if (first == NULL) {
        out[0] = NULL;
        return;
    }
    int n = 0;
    if (n < cap - 1) out[n++] = (char *) first;
    for (;;) {
        if (n >= cap - 1) break;
        const char *s = va_arg(ap, const char *);
        if (s == NULL) break;
        out[n++] = (char *) s;
    }
    out[n] = NULL;
}

int execve(const char *path, char *const argv[], char *const envp[]) {
    return mc_do_exec(path, argv, envp);
}

int execv(const char *path, char *const argv[]) {
    return mc_do_exec(path, argv, environ);
}

int execvp(const char *file, char *const argv[]) {
    return mc_do_exec(file, argv, environ);
}

int execvpe(const char *file, char *const argv[], char *const envp[]) {
    return mc_do_exec(file, argv, envp);
}

int execl(const char *path, const char *arg, ...) {
    char *args[MC_MAX_ARG];
    va_list ap;
    va_start(ap, arg);
    collect(arg, ap, args, MC_MAX_ARG);
    va_end(ap);
    return mc_do_exec(path, args, environ);
}

int execlp(const char *file, const char *arg, ...) {
    char *args[MC_MAX_ARG];
    va_list ap;
    va_start(ap, arg);
    collect(arg, ap, args, MC_MAX_ARG);
    va_end(ap);
    return mc_do_exec(file, args, environ);
}

int execle(const char *path, const char *arg, ...) {
    char *args[MC_MAX_ARG];
    va_list ap;
    va_start(ap, arg);
    collect(arg, ap, args, MC_MAX_ARG);
    char *const *envp = va_arg(ap, char *const *);
    va_end(ap);
    return mc_do_exec(path, args, envp);
}

/* ------------------------------------------------------------------
 * glibc 版：DNS 兜底（bionic 走 netd，本身就没这个问题）
 * ------------------------------------------------------------------ */

#ifdef __GLIBC__
typedef int (*mc_gai_fn)(const char *, const char *,
                         const struct addrinfo *, struct addrinfo **);

int getaddrinfo(const char *node, const char *service,
                const struct addrinfo *hints, struct addrinfo **res) {
    static mc_gai_fn real_gai = NULL;
    static int gai_lookup_done = 0;
    if (!gai_lookup_done) {
        real_gai = (mc_gai_fn) dlsym(RTLD_NEXT, "getaddrinfo");
        gai_lookup_done = 1;
    }
    if (real_gai != NULL) {
        int rc = real_gai(node, service, hints, res);
        if (rc == 0) return rc;
        /* 参数本身不合法就原样报错，只有「查不到 / 超时」才自己动手 */
        if (rc != EAI_AGAIN && rc != EAI_NONAME && rc != EAI_FAIL && rc != EAI_SYSTEM) {
            return rc;
        }
        if (res != NULL && *res != NULL) {
            freeaddrinfo(*res);
            *res = NULL;
        }
    }
    return mc_getaddrinfo_raw(node, service, hints, res);
}
#endif /* __GLIBC__ */
