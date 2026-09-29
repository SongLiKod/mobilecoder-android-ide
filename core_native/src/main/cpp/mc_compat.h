/*
 * MobileCoder glibc 兼容层（阶段 2：exec 层改写 + LD_PRELOAD 钩子的公共实现）。
 *
 * 本文件同时被两套目标编译：
 *   - bionic 版（NDK，`libmcexechook.so`，随 APK 打包）；
 *   - glibc 版（`tools/glibc-runtime/build.sh` 交叉编译，放进 glibc 运行时压缩包）。
 * 所以只允许用 POSIX + Linux syscall，不能出现任何一侧专有的 API。
 *
 * 要解决的两件事：
 *  1. **exec**：官方 nodejs.org / Adoptium 包的 `PT_INTERP` 是 `/lib/ld-linux-aarch64.so.1`，
 *     Android 上没有 → 内核 exec 阶段 ENOENT(2)、退出码 127。这里在 exec 前把目标的
 *     解释器**原地改写**到本机 loader（与 Kotlin 侧 GlibcCompat 同一套算法），
 *     改不动时退回「用 loader 直接启动」。
 *  2. **DNS**：Android 没有 `/etc/resolv.conf`，glibc 的 stub resolver 会退到
 *     `127.0.0.1:53`（Android 上没人监听）→ 所有域名解析超时。这里覆盖 `getaddrinfo`，
 *     系统解析失败时自己发一次裸 DNS 查询。
 */
#ifndef MOBILECODER_MC_COMPAT_H
#define MOBILECODER_MC_COMPAT_H

#include <netdb.h>
#include <stddef.h>
#include <sys/types.h>

/* 读 ELF 的 PT_INTERP 字符串；成功返回 0，非 ELF / 无解释器返回 -1。 */
int mc_read_interp(const char *path, char *out, size_t out_len);

/* 解释器是否是 glibc（`ld-linux*` / `ld.so`）。 */
int mc_interp_is_glibc(const char *interp);

/* 目标文件是不是「需要本机 glibc loader 才能跑」的 ELF；返回 1 是。 */
int mc_needs_glibc(const char *path);

/* 把非绝对路径的 [cmd] 按 PATH 解析成绝对路径；已是绝对路径直接拷贝。返回 0 成功。 */
int mc_resolve_exec(const char *cmd, char *out, size_t out_len);

/* 取 envp 中 `key=` 的值（不含 key 与等号），没有返回 NULL。 */
const char *mc_env_value(char *const envp[], const char *key);

/*
 * 组装新 envp：以 [src] 为底，覆盖 / 追加 [k1]=[v1] 与 [k2]=[v2]（value 为 NULL 表示删掉该项）。
 * 写入容量为 [cap] 的 [dst]（元素均为指向 [src] 或 [const] 的指针，不新分配）。
 * 返回条数（不含结尾 NULL）。
 */
int mc_env_merge(char *const src[], char *dst[], int cap,
                 const char *k1, const char *v1, const char *k2, const char *v2);

/* 就地改写 glibc ELF 的 PT_INTERP（追加新路径 + 改 p_offset / p_filesz）。0 成功。 */
int mc_patch_interp(const char *path, const char *new_interp);

/* 本机 glibc 的 loader 与库目录（读 MOBILECODER_GLIBC_LIB / MOBILECODER_GLIBC）。0 成功。 */
int mc_glibc_paths(char *loader, size_t n, char *libdir, size_t m);

/* 与 [target] 的解释器 ABI 匹配的 LD_PRELOAD 路径（读 MOBILECODER_*_HOOK）。0 成功。 */
int mc_preload_for(const char *target, char *out, size_t n);

/* 直接走 syscall 的 execve（本模块自己也导出 execve，必须绕开以免递归）。 */
int mc_raw_execve(const char *path, char *const argv[], char *const envp[]);

/*
 * PT_INTERP 改写失败时的兜底：`loader --library-path <libdir> <prog> <args…>`。
 * glibc 的 rtld 会把自己的选项从 argv 里弹掉，目标程序拿到的仍是 `<prog> <args…>`。
 */
int mc_exec_via_loader(const char *prog, char *const argv[], char *const envp[]);

/* `getaddrinfo` 的裸 DNS 兜底：系统解析失败后自己查 A / AAAA。返回 0 成功。 */
int mc_getaddrinfo_raw(const char *node, const char *service,
                       const struct addrinfo *hints, struct addrinfo **res);

#endif /* MOBILECODER_MC_COMPAT_H */
