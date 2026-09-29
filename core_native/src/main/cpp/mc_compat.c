/* glibc 兼容层公共实现（见 mc_compat.h 的说明）。 */
#define _GNU_SOURCE
#include "mc_compat.h"

#include <dirent.h>
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

#ifndef PATH_MAX
#define PATH_MAX 4096
#endif

#define MC_MAX_ENV 512

/* ------------------------------------------------------------------
 * ELF 解析（只支持小端：arm64 / x86_64 / armeabi 全是小端）
 * ------------------------------------------------------------------ */

/* 从 [p] 读 [n] 字节整数（n = 1/2/4/8），小端。 */
static unsigned long long rd(const unsigned char *p, int n) {
    unsigned long long v = 0;
    for (int i = n - 1; i >= 0; i--) v = (v << 8) | p[i];
    return v;
}

static int pread_full(int fd, void *buf, size_t n, off_t off) {
    unsigned char *p = (unsigned char *) buf;
    size_t got = 0;
    while (got < n) {
        ssize_t r = pread(fd, p + got, n - got, off + (off_t) got);
        if (r <= 0) return -1;
        got += (size_t) r;
    }
    return 0;
}

int mc_read_interp(const char *path, char *out, size_t out_len) {
    if (path == NULL || out == NULL || out_len == 0) return -1;
    out[0] = '\0';
    int fd = open(path, O_RDONLY);
    if (fd < 0) return -1;
    int rc = -1;
    unsigned char ident[16];
    unsigned char eh[64];
    if (pread_full(fd, ident, sizeof ident, 0) != 0) goto done;
    if (ident[0] != 0x7f || ident[1] != 'E' || ident[2] != 'L' || ident[3] != 'F') goto done;
    if (ident[4] != ELFCLASS64 && ident[4] != ELFCLASS32) goto done;
    if (ident[5] != ELFDATA2LSB) goto done; /* Android 目标只有小端 */
    int is64 = (ident[4] == ELFCLASS64);
    size_t eh_size = is64 ? sizeof(Elf64_Ehdr) : sizeof(Elf32_Ehdr);
    if (pread_full(fd, eh, eh_size, 0) != 0) goto done;

    int ph_off_at = is64 ? 32 : 28;
    int ph_sz_at = is64 ? 54 : 42;
    int ph_nm_at = is64 ? 56 : 44;
    int ph_ent_size = is64 ? (int) sizeof(Elf64_Phdr) : (int) sizeof(Elf32_Phdr);
    off_t phoff = (off_t) rd(eh + ph_off_at, is64 ? 8 : 4);
    long phentsize = (long) rd(eh + ph_sz_at, 2);
    long phnum = (long) rd(eh + ph_nm_at, 2);
    if (phoff <= 0 || phnum <= 0 || phnum > 4096) goto done;
    unsigned char ph[64];
    if (phentsize < ph_ent_size || phentsize > (long) sizeof(ph)) goto done;

    for (long i = 0; i < phnum; i++) {
        if (pread_full(fd, ph, (size_t) phentsize, phoff + (off_t) i * phentsize) != 0) goto done;
        if ((unsigned) rd(ph, 4) != PT_INTERP) continue;
        unsigned long long off = rd(ph + (is64 ? 8 : 4), is64 ? 8 : 4);
        unsigned long long sz = rd(ph + (is64 ? 32 : 16), is64 ? 8 : 4);
        if (sz <= 1 || sz >= out_len) goto done;
        if (pread_full(fd, out, (size_t) sz, (off_t) off) != 0) goto done;
        out[sz - 1] = '\0'; /* 末字节应是 NUL，写死防止越界 */
        rc = 0;
        break;
    }
done:
    close(fd);
    return rc;
}

int mc_interp_is_glibc(const char *interp) {
    if (interp == NULL || interp[0] == '\0') return 0;
    return strstr(interp, "ld-linux") != NULL || strstr(interp, "ld.so") != NULL;
}

int mc_needs_glibc(const char *path) {
    char interp[512];
    if (mc_read_interp(path, interp, sizeof interp) != 0) return 0;
    return mc_interp_is_glibc(interp);
}

int mc_resolve_exec(const char *cmd, char *out, size_t out_len) {
    if (cmd == NULL || out == NULL || out_len == 0) return -1;
    if (cmd[0] == '/') {
        if (strlen(cmd) >= out_len) return -1;
        memcpy(out, cmd, strlen(cmd) + 1);
        return 0;
    }
    const char *path = getenv("PATH");
    if (path == NULL || *path == '\0') path = "/system/bin:/system/xbin:/vendor/bin";
    const char *p = path;
    while (*p != '\0') {
        const char *sep = strchr(p, ':');
        size_t dirlen = (sep != NULL) ? (size_t) (sep - p) : strlen(p);
        if (dirlen > 0 && dirlen + 1 + strlen(cmd) < out_len) {
            char cand[PATH_MAX];
            memcpy(cand, p, dirlen);
            cand[dirlen] = '/';
            memcpy(cand + dirlen + 1, cmd, strlen(cmd) + 1);
            if (access(cand, X_OK) == 0) {
                memcpy(out, cand, strlen(cand) + 1);
                return 0;
            }
        }
        if (sep == NULL) break;
        p = sep + 1;
    }
    return -1;
}

/* ------------------------------------------------------------------
 * envp 处理
 * ------------------------------------------------------------------ */

const char *mc_env_value(char *const envp[], const char *key) {
    if (envp == NULL || key == NULL) return NULL;
    size_t n = strlen(key);
    for (int i = 0; envp[i] != NULL; i++) {
        if (strncmp(envp[i], key, n) == 0 && envp[i][n] == '=') return envp[i] + n + 1;
    }
    return NULL;
}

static int key_match(const char *entry, const char *key) {
    size_t n = strlen(key);
    return strncmp(entry, key, n) == 0 && entry[n] == '=';
}

int mc_env_merge(char *const src[], char *dst[], int cap,
                 const char *k1, const char *v1, const char *k2, const char *v2) {
    int n = 0;
    int used1 = 0, used2 = 0;
    if (src != NULL) {
        for (int i = 0; src[i] != NULL && n < cap - 2; i++) {
            char *e = src[i];
            /* 命中要改 / 要删的 key 就丢掉原条目，值不为 NULL 时下面再补一条新的 */
            if (k1 != NULL && key_match(e, k1)) {
                used1 = 1;
                continue;
            }
            if (k2 != NULL && key_match(e, k2)) {
                used2 = 1;
                continue;
            }
            dst[n++] = e;
        }
    }
    if (k1 != NULL && v1 != NULL && !used1 && n < cap - 1) {
        /* 线程局部缓冲：单次 exec 最多补两行，不会跨线程串数据 */
        static __thread char b1[PATH_MAX + 32];
        snprintf(b1, sizeof b1, "%s=%s", k1, v1);
        dst[n++] = b1;
    }
    if (k2 != NULL && v2 != NULL && !used2 && n < cap - 1) {
        static __thread char b2[PATH_MAX + 32];
        snprintf(b2, sizeof b2, "%s=%s", k2, v2);
        dst[n++] = b2;
    }
    dst[n] = NULL;
    return n;
}

/* ------------------------------------------------------------------
 * glibc 运行时定位
 * ------------------------------------------------------------------ */

static int find_loader_in(const char *dir, char *out, size_t n) {
    DIR *d = opendir(dir);
    if (d == NULL) return -1;
    int rc = -1;
    struct dirent *ent;
    while ((ent = readdir(d)) != NULL) {
        if (strncmp(ent->d_name, "ld-linux", 8) != 0) continue;
        char full[PATH_MAX];
        snprintf(full, sizeof full, "%s/%s", dir, ent->d_name);
        struct stat st;
        if (stat(full, &st) != 0 || !S_ISREG(st.st_mode)) continue;
        if (strlen(full) >= n) continue;
        memcpy(out, full, strlen(full) + 1);
        rc = 0;
        break;
    }
    closedir(d);
    return rc;
}

int mc_glibc_paths(char *loader, size_t n, char *libdir, size_t m) {
    const char *lib = getenv("MOBILECODER_GLIBC_LIB");
    if (lib != NULL && *lib != '\0' && find_loader_in(lib, loader, n) == 0) {
        snprintf(libdir, m, "%s", lib);
        return 0;
    }
    const char *root = getenv("MOBILECODER_GLIBC");
    if (root == NULL || *root == '\0') return -1;
    char cand[PATH_MAX];
    snprintf(cand, sizeof cand, "%s/lib", root);
    if (find_loader_in(cand, loader, n) == 0) {
        snprintf(libdir, m, "%s", cand);
        return 0;
    }
    snprintf(cand, sizeof cand, "%s/lib64", root);
    if (find_loader_in(cand, loader, n) == 0) {
        snprintf(libdir, m, "%s", cand);
        return 0;
    }
    return -1;
}

int mc_preload_for(const char *target, char *out, size_t n) {
    const char *want = NULL;
    if (mc_needs_glibc(target)) {
        want = getenv("MOBILECODER_GLIBC_HOOK");
    } else {
        /* 非 ELF（shell 脚本）由内核交给 /system/bin/sh，是 bionic */
        want = getenv("MOBILECODER_BIONIC_HOOK");
    }
    if (want == NULL || *want == '\0') return -1;
    if (strlen(want) >= n) return -1;
    memcpy(out, want, strlen(want) + 1);
    return 0;
}

/* ------------------------------------------------------------------
 * exec
 * ------------------------------------------------------------------ */

int mc_raw_execve(const char *path, char *const argv[], char *const envp[]) {
    return (int) syscall(SYS_execve, path, argv, envp);
}

int mc_patch_interp(const char *path, const char *new_interp) {
    if (path == NULL || new_interp == NULL) return -1;
    int fd = open(path, O_RDWR);
    if (fd < 0) return -1;
    int rc = -1;
    unsigned char eh[64];
    unsigned char ph[64];
    if (pread_full(fd, eh, 16, 0) != 0) goto done;
    if (eh[0] != 0x7f || eh[1] != 'E' || eh[2] != 'L' || eh[3] != 'F') goto done;
    int is64 = (eh[4] == ELFCLASS64);
    size_t eh_size = is64 ? sizeof(Elf64_Ehdr) : sizeof(Elf32_Ehdr);
    if (pread_full(fd, eh, eh_size, 0) != 0) goto done;

    off_t phoff = (off_t) rd(eh + (is64 ? 32 : 28), is64 ? 8 : 4);
    long phentsize = (long) rd(eh + (is64 ? 54 : 42), 2);
    long phnum = (long) rd(eh + (is64 ? 56 : 44), 2);
    if (phoff <= 0 || phnum <= 0 || phentsize <= 0 || phentsize > (long) sizeof(ph)) goto done;

    off_t hit = -1;
    for (long i = 0; i < phnum; i++) {
        off_t base = phoff + (off_t) i * phentsize;
        if (pread_full(fd, ph, (size_t) phentsize, base) != 0) goto done;
        if ((unsigned) rd(ph, 4) == PT_INTERP) {
            hit = base;
            break;
        }
    }
    if (hit < 0) goto done;

    /* 新解释器路径追加到文件末尾：内核只按 p_offset + p_filesz 读，不要求落在 PT_LOAD 内 */
    off_t eof = lseek(fd, 0, SEEK_END);
    if (eof <= 0) goto done;
    size_t len = strlen(new_interp) + 1;
    char *blob = (char *) malloc(len);
    if (blob == NULL) goto done;
    memcpy(blob, new_interp, len);
    if (pwrite(fd, blob, len, eof) != (ssize_t) len) {
        free(blob);
        goto done;
    }
    free(blob);

    if (is64) {
        Elf64_Phdr h;
        if (pread_full(fd, &h, sizeof h, hit) != 0) goto done;
        h.p_offset = (Elf64_Addr) eof;
        h.p_filesz = (Elf64_Xword) len;
        if (pwrite(fd, &h, sizeof h, hit) != (ssize_t) sizeof h) goto done;
    } else {
        Elf32_Phdr h;
        if (pread_full(fd, &h, sizeof h, hit) != 0) goto done;
        h.p_offset = (Elf32_Addr) eof;
        h.p_filesz = (Elf32_Word) len;
        if (pwrite(fd, &h, sizeof h, hit) != (ssize_t) sizeof h) goto done;
    }
    rc = 0;
done:
    close(fd);
    return rc;
}

int mc_exec_via_loader(const char *prog, char *const argv[], char *const envp[]) {
    char loader[PATH_MAX];
    char libdir[PATH_MAX];
    if (mc_glibc_paths(loader, sizeof loader, libdir, sizeof libdir) != 0) return -1;
    int argc = 0;
    while (argv != NULL && argv[argc] != NULL) argc++;
    if (argc <= 0) return -1;
    /* loader --library-path <lib> <prog> <args…>：rtld 会把自己的选项弹掉 */
    char *nargv[256];
    if (argc + 4 >= (int) (sizeof nargv / sizeof nargv[0])) return -1;
    nargv[0] = loader;
    nargv[1] = (char *) "--library-path";
    nargv[2] = libdir;
    nargv[3] = (char *) prog;
    for (int i = 1; i < argc; i++) nargv[4 + i - 1] = argv[i];
    nargv[argc + 3] = NULL;
    return mc_raw_execve(loader, nargv, envp);
}
