/*
 * MobileCoder —— Git 引擎（libgit2 静态 JNI 封装）
 *
 * 需求对应（PRD 2.5 / TECH 4.4）：
 *  - 不依赖系统 Git，libgit2 静态编译进 libmobilecoder.so
 *  - init / clone / pull(=fetch+merge) / push / commit
 *  - 分支：增、删、切换、合并；标签：增删；远程：增删
 *  - Diff 可视化（返回 unified patch，Kotlin/前端渲染）
 *  - 冲突可视化解决（三方内容读取 + 写回暂存）
 *  - 提交时间线（revwalk）
 *  - HTTPS 走 mbedTLS + 内置 CA；SSH 走 libssh2 + 内存私钥（不落盘）
 *
 * 返回约定：
 *   布尔型方法   : 1 成功 / 0 失败（错误详情见 lastError()）
 *   整型状态方法 : 0 成功；1 冲突/需人工处理；2 已是最新；-1 错误
 *   字符串列表   : 记录用 '\n' 分隔，字段用 '\x01' 分隔
 */
#include <jni.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <errno.h>
#include <pthread.h>
#include <unistd.h>

#include <git2.h>

#include "jni_util.h"

#define MC_FIELD_SEP  '\x01'
#define MC_REC_SEP    '\n'

/* 引用名缓冲上限（与 libgit2 GIT_REFNAME_MAX 对齐；公开头文件未导出该宏） */
#define MC_REFNAME_MAX 1024

/* 状态位掩码（git_status_t） */
#define MC_STATUS_INDEX_MASK 0x001Fu   /* bit0..bit4 */
#define MC_STATUS_WT_MASK    0x1F80u   /* bit7..bit12 */

/* ------------------------------------------------------------------ */
/* 全局状态                                                            */
/* ------------------------------------------------------------------ */

static git_repository *g_repo = NULL;
static int g_libgit2_refcount = 0;
static char g_err[1024];

static jobject  g_progress_cb = NULL;
static jmethodID g_progress_on = NULL;

static char g_cred_user[256];
static char g_cred_pass[1024];
static char g_cred_ssh_user[128];
static char g_cred_privkey[64 * 1024];
static char g_cred_pubkey[16 * 1024];
static char g_cred_passphrase[512];
static char g_cert_file[1024];

/* 进行中的网络操作句柄：供跨线程 cancelNetwork() 中止 fetch / push */
static git_remote *g_active_remote = NULL;
static pthread_mutex_t g_active_remote_lock = PTHREAD_MUTEX_INITIALIZER;

/* 登记 / 注销进行中的网络传输（先注销再 free，保证取消方看不到悬空指针） */
static void mc_remote_begin(git_remote *remote) {
    pthread_mutex_lock(&g_active_remote_lock);
    g_active_remote = remote;
    pthread_mutex_unlock(&g_active_remote_lock);
}

static void mc_remote_end(void) {
    pthread_mutex_lock(&g_active_remote_lock);
    g_active_remote = NULL;
    pthread_mutex_unlock(&g_active_remote_lock);
}

static void mc_clear_error(void) {
    g_err[0] = '\0';
}

static void mc_set_error(const char *fallback) {
    const git_error *e = git_error_last();
    if (e != NULL && e->message != NULL && e->message[0] != '\0') {
        snprintf(g_err, sizeof(g_err), "%s", e->message);
    } else {
        snprintf(g_err, sizeof(g_err), "%s", fallback != NULL ? fallback : "unknown error");
    }
    MC_LOGE("git error: %s", g_err);
}

static void mc_set_errorf(const char *fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(g_err, sizeof(g_err), fmt, ap);
    va_end(ap);
    MC_LOGE("git error: %s", g_err);
}

/* ------------------------------------------------------------------ */
/* 字符串缓冲（构建返回值）                                            */
/* ------------------------------------------------------------------ */

typedef struct {
    char  *d;
    size_t len;
    size_t cap;
} mc_sb;

static void sb_init(mc_sb *b) {
    b->d = NULL;
    b->len = 0;
    b->cap = 0;
}

static int sb_reserve(mc_sb *b, size_t extra) {
    size_t need = b->len + extra + 1;
    if (need <= b->cap) {
        return 1;
    }
    size_t cap = b->cap != 0 ? b->cap : 512;
    while (cap < need) {
        if (cap > ((size_t) 1 << 30)) {
            return 0;
        }
        cap *= 2;
    }
    char *nd = (char *) realloc(b->d, cap);
    if (nd == NULL) {
        return 0;
    }
    b->d = nd;
    b->cap = cap;
    return 1;
}

static void sb_appendn(mc_sb *b, const char *s, size_t n) {
    if (s == NULL || n == 0) {
        return;
    }
    if (!sb_reserve(b, n)) {
        return;
    }
    memcpy(b->d + b->len, s, n);
    b->len += n;
    b->d[b->len] = '\0';
}

static void sb_puts(mc_sb *b, const char *s) {
    if (s != NULL) {
        sb_appendn(b, s, strlen(s));
    }
}

static void sb_putc(mc_sb *b, char c) {
    sb_appendn(b, &c, 1);
}

static void sb_printf(mc_sb *b, const char *fmt, ...) {
    char tmp[1024];
    va_list ap;
    int n;

    va_start(ap, fmt);
    n = vsnprintf(tmp, sizeof(tmp), fmt, ap);
    va_end(ap);

    if (n < 0) {
        return;
    }
    if ((size_t) n < sizeof(tmp)) {
        sb_appendn(b, tmp, (size_t) n);
        return;
    }

    char *big = (char *) malloc((size_t) n + 1);
    if (big == NULL) {
        return;
    }
    va_start(ap, fmt);
    vsnprintf(big, (size_t) n + 1, fmt, ap);
    va_end(ap);
    sb_appendn(b, big, (size_t) n);
    free(big);
}

/* 追加单行文本（去掉换行/字段分隔符，避免破坏帧格式） */
static void sb_line(mc_sb *b, const char *s) {
    if (s == NULL) {
        return;
    }
    for (const char *p = s; *p != '\0'; p++) {
        char c = *p;
        if (c == '\n' || c == '\r' || c == MC_FIELD_SEP) {
            c = ' ';
        }
        sb_putc(b, c);
    }
}

/* 取出缓冲（脱离所有权）；空缓冲返回堆上空串 */
static char *sb_detach(mc_sb *b) {
    if (b->d == NULL) {
        char *s = (char *) malloc(1);
        if (s != NULL) {
            s[0] = '\0';
        }
        return s;
    }
    char *s = b->d;
    b->d = NULL;
    b->len = 0;
    b->cap = 0;
    return s;
}

/* ------------------------------------------------------------------ */
/* 进度回调                                                            */
/* ------------------------------------------------------------------ */

static char *mc_ascii_dup(const char *s, size_t n) {
    char *o = (char *) malloc(n + 1);
    if (o == NULL) {
        return NULL;
    }
    for (size_t i = 0; i < n; i++) {
        unsigned char c = (unsigned char) s[i];
        if (c == '\n' || c == '\r' || c == '\t') {
            o[i] = ' ';
        } else if (c >= 0x20 && c < 0x7f) {
            o[i] = (char) c;
        } else {
            o[i] = '?';
        }
    }
    o[n] = '\0';
    return o;
}

static void mc_emit_progress(const char *stage, const char *message, int current, int total) {
    JNIEnv *env;
    jstring jstage, jmsg;
    char *msg;

    if (g_progress_cb == NULL || g_progress_on == NULL) {
        return;
    }
    env = mc_env();
    if (env == NULL) {
        return;
    }

    msg = mc_ascii_dup(message != NULL ? message : "",
                       message != NULL ? strlen(message) : 0);
    if (msg == NULL) {
        return;
    }

    jstage = (*env)->NewStringUTF(env, stage != NULL ? stage : "");
    jmsg = (*env)->NewStringUTF(env, msg);
    free(msg);

    if (jstage != NULL && jmsg != NULL) {
        (*env)->CallVoidMethod(env, g_progress_cb, g_progress_on,
                               jstage, jmsg, (jint) current, (jint) total);
    }
    if (jstage != NULL) {
        (*env)->DeleteLocalRef(env, jstage);
    }
    if (jmsg != NULL) {
        (*env)->DeleteLocalRef(env, jmsg);
    }
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
    }
}

/* ------------------------------------------------------------------ */
/* libgit2 网络回调                                                    */
/* ------------------------------------------------------------------ */

static int mc_transfer_progress_cb(const git_indexer_progress *stats, void *payload) {
    (void) payload;
    if (stats == NULL) {
        return 0;
    }
    mc_emit_progress("transfer", "", (int) stats->received_objects,
                     (int) stats->total_objects);
    return 0;
}

static int mc_sideband_cb(const char *str, int len, void *payload) {
    (void) payload;
    if (str == NULL || len <= 0) {
        return 0;
    }
    mc_emit_progress("remote", str, -1, -1);
    return 0;
}

static int mc_push_progress_cb(unsigned int current, unsigned int total,
                               size_t bytes, void *payload) {
    (void) payload;
    (void) bytes;
    mc_emit_progress("push", "", (int) current, (int) total);
    return 0;
}

static int mc_push_update_cb(const char *refname, const char *status, void *payload) {
    (void) payload;
    if (status != NULL && status[0] != '\0') {
        mc_set_errorf("%s: %s", refname != NULL ? refname : "ref", status);
        return -1;
    }
    return 0;
}

static int mc_credentials_cb(git_credential **out, const char *url,
                             const char *username_from_url, unsigned int allowed_types,
                             void *payload) {
    const char *user;
    (void) url;
    (void) payload;

    if ((allowed_types & GIT_CREDENTIAL_SSH_KEY) != 0 && g_cred_privkey[0] != '\0') {
        user = g_cred_ssh_user[0] != '\0' ? g_cred_ssh_user : username_from_url;
        if (user == NULL || user[0] == '\0') {
            mc_set_error("SSH 用户名为空");
            return -1;
        }
        return git_credential_ssh_key_memory_new(
                out, user,
                g_cred_pubkey[0] != '\0' ? g_cred_pubkey : NULL,
                g_cred_privkey,
                g_cred_passphrase[0] != '\0' ? g_cred_passphrase : NULL);
    }

    if ((allowed_types & GIT_CREDENTIAL_USERPASS_PLAINTEXT) != 0 && g_cred_user[0] != '\0') {
        return git_credential_userpass_plaintext_new(out, g_cred_user, g_cred_pass);
    }

    if ((allowed_types & GIT_CREDENTIAL_USERNAME) != 0 && g_cred_ssh_user[0] != '\0') {
        return git_credential_username_new(out, g_cred_ssh_user);
    }

    mc_set_error("缺少可用凭据（未配置账号或 SSH 私钥）");
    return -1;
}

static int mc_certificate_check_cb(git_cert *cert, int valid, const char *host, void *payload) {
    (void) payload;
    if (cert == NULL) {
        return -1;
    }
    if (cert->cert_type == GIT_CERT_X509) {
        if (valid) {
            return 0;
        }
        mc_set_errorf("HTTPS 证书校验失败：%s", host != NULL ? host : "");
        return -1;
    }
    /* SSH 主机密钥：本地无 known_hosts，采用 TOFU 策略放行（Kotlin 层展示指纹） */
    return 0;
}

static git_remote_callbacks mc_remote_callbacks(void) {
    git_remote_callbacks cb = GIT_REMOTE_CALLBACKS_INIT;
    cb.credentials = mc_credentials_cb;
    cb.certificate_check = mc_certificate_check_cb;
    cb.sideband_progress = mc_sideband_cb;
    cb.transfer_progress = mc_transfer_progress_cb;
    cb.push_transfer_progress = mc_push_progress_cb;
    cb.push_update_reference = mc_push_update_cb;
    return cb;
}

/* ------------------------------------------------------------------ */
/* 工具                                                                */
/* ------------------------------------------------------------------ */

static int mc_require_repo(void) {
    if (g_repo == NULL) {
        mc_set_error("尚未打开仓库");
        return 0;
    }
    return 1;
}

static void mc_strlcpy(char *dst, const char *src, size_t dstsize) {
    if (dstsize == 0) {
        return;
    }
    if (src == NULL) {
        dst[0] = '\0';
        return;
    }
    size_t n = strlen(src);
    if (n >= dstsize) {
        n = dstsize - 1;
    }
    memcpy(dst, src, n);
    dst[n] = '\0';
}

static char *mc_join_path(const char *base, const char *rel) {
    size_t nb = strlen(base), nr = strlen(rel);
    int need_slash = nb > 0 && base[nb - 1] != '/';
    char *p = (char *) malloc(nb + (need_slash ? 1 : 0) + nr + 1);
    if (p == NULL) {
        return NULL;
    }
    memcpy(p, base, nb);
    if (need_slash) {
        p[nb] = '/';
        nb++;
    }
    memcpy(p + nb, rel, nr);
    p[nb + nr] = '\0';
    return p;
}

static int mc_mkdir_parents(const char *path) {
    char *tmp = strdup(path);
    if (tmp == NULL) {
        return -1;
    }
    for (char *p = tmp + 1; *p != '\0'; p++) {
        if (*p == '/') {
            *p = '\0';
            if (mkdir(tmp, 0755) != 0 && errno != EEXIST) {
                free(tmp);
                return -1;
            }
            *p = '/';
        }
    }
    free(tmp);
    return 0;
}

static int mc_write_workdir_file(const char *relpath, const char *data, size_t len) {
    const char *wd = git_repository_workdir(g_repo);
    char *full;
    FILE *fp;
    size_t written;

    if (wd == NULL) {
        mc_set_error("裸仓库没有工作区");
        return -1;
    }
    full = mc_join_path(wd, relpath);
    if (full == NULL) {
        mc_set_error("路径分配失败");
        return -1;
    }
    if (mc_mkdir_parents(full) != 0) {
        mc_set_errorf("创建目录失败：%s", full);
        free(full);
        return -1;
    }
    fp = fopen(full, "wb");
    if (fp == NULL) {
        mc_set_errorf("写文件失败：%s", full);
        free(full);
        return -1;
    }
    written = len > 0 ? fwrite(data, 1, len, fp) : 0;
    fclose(fp);
    free(full);
    if (written != len) {
        mc_set_error("写文件不完整（磁盘空间不足？）");
        return -1;
    }
    return 0;
}

static int mc_file_exists(const char *relpath) {
    const char *wd = git_repository_workdir(g_repo);
    char *full;
    int ok;

    if (wd == NULL) {
        return 0;
    }
    full = mc_join_path(wd, relpath);
    if (full == NULL) {
        return 0;
    }
    ok = access(full, F_OK) == 0;
    free(full);
    return ok;
}

/* HEAD 是否可解析；成功时 oid 写入 out */
static int mc_head_oid(git_oid *out) {
    if (g_repo == NULL) {
        return -1;
    }
    return git_reference_name_to_id(out, g_repo, "HEAD");
}

static void mc_oid_str(char *out, size_t outsize, const git_oid *oid) {
    if (oid == NULL) {
        out[0] = '\0';
        return;
    }
    git_oid_tostr(out, outsize, oid);
}

/* ------------------------------------------------------------------ */
/*  JNI —— 生命周期                                                    */
/* ------------------------------------------------------------------ */

JNIEXPORT void JNICALL
Java_com_mobilecoder_ide_core_nativebridge_GitNative_registerProgressCallback(
        JNIEnv *env, jclass clazz, jobject callback) {
    (void) clazz;
    if (g_progress_cb != NULL) {
        (*env)->DeleteGlobalRef(env, g_progress_cb);
        g_progress_cb = NULL;
        g_progress_on = NULL;
    }
    if (callback == NULL) {
        return;
    }
    jclass cls = (*env)->GetObjectClass(env, callback);
    if (cls == NULL) {
        return;
    }
    jmethodID m = (*env)->GetMethodID(env, cls, "onProgress",
                                      "(Ljava/lang/String;Ljava/lang/String;II)V");
    (*env)->DeleteLocalRef(env, cls);
    if (m == NULL) {
        (*env)->ExceptionClear(env);
        return;
    }
    g_progress_cb = (*env)->NewGlobalRef(env, callback);
    g_progress_on = m;
}

JNIEXPORT jboolean JNICALL
Java_com_mobilecoder_ide_core_nativebridge_GitNative_runtimeInit(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    if (g_libgit2_refcount <= 0) {
        if (git_libgit2_init() < 0) {
            mc_set_error("libgit2 初始化失败");
            return JNI_FALSE;
        }
        g_libgit2_refcount = 1;
    } else {
        g_libgit2_refcount++;
    }
    mc_clear_error();
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_mobilecoder_ide_core_nativebridge_GitNative_setCertificateFile(
        JNIEnv *env, jclass clazz, jstring path) {
    (void) clazz;
    char *p = mc_jstring_to_cstr(env, path);
    if (p == NULL) {
        return;
    }
    mc_strlcpy(g_cert_file, p, sizeof(g_cert_file));
    free(p);
    if (g_cert_file[0] == '\0') {
        return;
    }
    if (git_libgit2_opts(GIT_OPT_SET_SSL_CERT_LOCATIONS, g_cert_file, (const char *) NULL) < 0) {
        mc_set_error("设置 CA 证书失败");
    } else {
        mc_clear_error();
    }
}

JNIEXPORT void JNICALL
Java_com_mobilecoder_ide_core_nativebridge_GitNative_setCredentials(
        JNIEnv *env, jclass clazz,
        jstring username, jstring password,
        jstring sshUsername, jstring privateKey, jstring publicKey, jstring passphrase) {
    (void) clazz;
    char *u = mc_jstring_to_cstr(env, username);
    char *p = mc_jstring_to_cstr(env, password);
    char *su = mc_jstring_to_cstr(env, sshUsername);
    char *pk = mc_jstring_to_cstr(env, privateKey);
    char *pub = mc_jstring_to_cstr(env, publicKey);
    char *pp = mc_jstring_to_cstr(env, passphrase);

    mc_strlcpy(g_cred_user, u != NULL ? u : "", sizeof(g_cred_user));
    mc_strlcpy(g_cred_pass, p != NULL ? p : "", sizeof(g_cred_pass));
    mc_strlcpy(g_cred_ssh_user, su != NULL ? su : "", sizeof(g_cred_ssh_user));
    mc_strlcpy(g_cred_privkey, pk != NULL ? pk : "", sizeof(g_cred_privkey));
    mc_strlcpy(g_cred_pubkey, pub != NULL ? pub : "", sizeof(g_cred_pubkey));
    mc_strlcpy(g_cred_passphrase, pp != NULL ? pp : "", sizeof(g_cred_passphrase));

    free(u);
    free(p);
    free(su);
    free(pk);
    free(pub);
    free(pp);
    mc_clear_error();
}

JNIEXPORT void JNICALL
Java_com_mobilecoder_ide_core_nativebridge_GitNative_clearCredentials(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    memset(g_cred_user, 0, sizeof(g_cred_user));
    memset(g_cred_pass, 0, sizeof(g_cred_pass));
    memset(g_cred_ssh_user, 0, sizeof(g_cred_ssh_user));
    memset(g_cred_privkey, 0, sizeof(g_cred_privkey));
    memset(g_cred_pubkey, 0, sizeof(g_cred_pubkey));
    memset(g_cred_passphrase, 0, sizeof(g_cred_passphrase));
}

JNIEXPORT void JNICALL
Java_com_mobilecoder_ide_core_nativebridge_GitNative_shutdown(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    if (g_repo != NULL) {
        git_repository_free(g_repo);
        g_repo = NULL;
    }
    if (g_progress_cb != NULL) {
        JNIEnv *e = mc_env();
        if (e != NULL) {
            (*e)->DeleteGlobalRef(e, g_progress_cb);
        }
        g_progress_cb = NULL;
        g_progress_on = NULL;
    }
    if (g_libgit2_refcount > 0) {
        g_libgit2_refcount--;
        git_libgit2_shutdown();
    }
}

JNIEXPORT void JNICALL
Java_com_mobilecoder_ide_core_nativebridge_GitNative_closeRepo(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    if (g_repo != NULL) {
        git_repository_free(g_repo);
        g_repo = NULL;
    }
    mc_clear_error();
}

JNIEXPORT jstring JNICALL
Java_com_mobilecoder_ide_core_nativebridge_GitNative_lastError(JNIEnv *env, jclass clazz) {
    (void) clazz;
    return mc_cstr_to_jstring(env, g_err);
}

/* ------------------------------------------------------------------ */
/*  JNI —— 仓库打开 / 初始化                                           */
/* ------------------------------------------------------------------ */

JNIEXPORT jboolean JNICALL
Java_com_mobilecoder_ide_core_nativebridge_GitNative_isRepository(
        JNIEnv *env, jclass clazz, jstring path) {
    (void) clazz;
    char *p = mc_jstring_to_cstr(env, path);
    int result = 0;
    git_repository *repo = NULL;

    if (p == NULL) {
        return JNI_FALSE;
    }
    if (git_repository_open(&repo, p) == 0 && repo != NULL) {
        result = 1;
        git_repository_free(repo);
    }
    free(p);
    mc_clear_error();
    return result ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_mobilecoder_ide_core_nativebridge_GitNative_initRepository(
        JNIEnv *env, jclass clazz, jstring path) {
    (void) clazz;
    char *p = mc_jstring_to_cstr(env, path);
    git_repository *repo = NULL;
    int rc;

    if (p == NULL) {
        return JNI_FALSE;
    }
    mc_clear_error();
    rc = git_repository_init(&repo, p, 0);
    free(p);
    if (rc < 0) {
        mc_set_error("初始化仓库失败");
        return JNI_FALSE;
    }
    if (g_repo != NULL) {
        git_repository_free(g_repo);
    }
    g_repo = repo;
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_mobilecoder_ide_core_nativebridge_GitNative_openRepository(
        JNIEnv *env, jclass clazz, jstring path) {
    (void) clazz;
    char *p = mc_jstring_to_cstr(env, path);
    git_repository *repo = NULL;
    int rc;

    if (p == NULL) {
        return JNI_FALSE;
    }
    mc_clear_error();
    rc = git_repository_open(&repo, p);
    free(p);
    if (rc < 0) {
        mc_set_error("打开仓库失败");
        return JNI_FALSE;
    }
    if (g_repo != NULL) {
        git_repository_free(g_repo);
    }
    g_repo = repo;
    return JNI_TRUE;
}

JNIEXPORT jstring JNICALL
Java_com_mobilecoder_ide_core_nativebridge_GitNative_workdirPath(JNIEnv *env, jclass clazz) {
    (void) clazz;
    const char *wd = NULL;
    if (g_repo != NULL) {
        wd = git_repository_workdir(g_repo);
    }
    return mc_cstr_to_jstring(env, wd != NULL ? wd : "");
}

/* ------------------------------------------------------------------ */
/*  JNI —— 克隆                                                        */
/* ------------------------------------------------------------------ */

JNIEXPORT jint JNICALL
Java_com_mobilecoder_ide_core_nativebridge_GitNative_clone(
        JNIEnv *env, jclass clazz, jstring url, jstring path, jstring branch) {
    (void) clazz;
    char *c_url = mc_jstring_to_cstr(env, url);
    char *c_path = mc_jstring_to_cstr(env, path);
    char *c_branch = mc_jstring_to_cstr(env, branch);
    git_clone_options opts = GIT_CLONE_OPTIONS_INIT;
    git_repository *cloned = NULL;
    int rc;

    if (c_url == NULL || c_path == NULL) {
        free(c_url);
        free(c_path);
        free(c_branch);
        mc_set_error("参数为空");
        return -1;
    }

    mc_clear_error();
    opts.fetch_opts.callbacks = mc_remote_callbacks();
    opts.checkout_opts.checkout_strategy = GIT_CHECKOUT_SAFE;
    if (c_branch != NULL && c_branch[0] != '\0') {
        opts.checkout_branch = c_branch;
    }

    mc_emit_progress("clone", c_url, -1, -1);
    rc = git_clone(&cloned, c_url, c_path, &opts);
    if (rc < 0) {
        mc_set_error("克隆失败");
        free(c_url);
        free(c_path);
        free(c_branch);
        return -1;
    }

    if (g_repo != NULL) {
        git_repository_free(g_repo);
        g_repo = NULL;
    }
    g_repo = cloned;

    free(c_url);
    free(c_path);
    free(c_branch);
    mc_emit_progress("clone", "done", -1, -1);
    return 0;
}

/* ------------------------------------------------------------------ */
/*  JNI —— 状态                                                        */
/* ------------------------------------------------------------------ */

static int mc_status_options(git_status_options *opts) {
    int rc = git_status_options_init(opts, GIT_STATUS_OPTIONS_VERSION);
    if (rc < 0) {
        return rc;
    }
    opts->show = GIT_STATUS_SHOW_INDEX_AND_WORKDIR;
    opts->flags = GIT_STATUS_OPT_INCLUDE_UNTRACKED |
                  GIT_STATUS_OPT_RECURSE_UNTRACKED_DIRS |
                  GIT_STATUS_OPT_SORT_CASE_SENSITIVELY;
    return 0;
}

/* 从状态项取出相对路径 */
static const char *mc_status_path(const git_status_entry *e) {
    if (e == NULL) {
        return NULL;
    }
    if (e->head_to_index != NULL && e->head_to_index->new_file.path != NULL) {
        return e->head_to_index->new_file.path;
    }
    if (e->index_to_workdir != NULL && e->index_to_workdir->new_file.path != NULL) {
        return e->index_to_workdir->new_file.path;
    }
    return NULL;
}

JNIEXPORT jstring JNICALL
Java_com_mobilecoder_ide_core_nativebridge_GitNative_statusList(JNIEnv *env, jclass clazz) {
    (void) clazz;
    git_status_options opts;
    git_status_list *list = NULL;
    mc_sb sb;
    size_t i, count;

    if (!mc_require_repo()) {
        return mc_cstr_to_jstring(env, "");
    }
    mc_clear_error();

    if (mc_status_options(&opts) < 0) {
        mc_set_error("读取状态失败");
        return mc_cstr_to_jstring(env, "");
    }
    if (git_status_list_new(&list, g_repo, &opts) < 0) {
        mc_set_error("读取状态失败");
        return mc_cstr_to_jstring(env, "");
    }

    sb_init(&sb);
    count = git_status_list_entrycount(list);
    for (i = 0; i < count; i++) {
        const git_status_entry *e = git_status_byindex(list, i);
        const char *path = mc_status_path(e);
        unsigned int idx_status;
        unsigned int wt_status;

        if (path == NULL || path[0] == '\0') {
            continue;
        }
        if ((e->status & GIT_STATUS_CONFLICTED) != 0) {
            idx_status = (unsigned int) GIT_STATUS_CONFLICTED;
            wt_status = (unsigned int) GIT_STATUS_CONFLICTED;
        } else {
            idx_status = e->status & MC_STATUS_INDEX_MASK;
            wt_status = e->status & MC_STATUS_WT_MASK;
        }

        sb_puts(&sb, path);
        sb_putc(&sb, MC_FIELD_SEP);
        sb_printf(&sb, "%u", idx_status);
        sb_putc(&sb, MC_FIELD_SEP);
        sb_printf(&sb, "%u", wt_status);
        sb_putc(&sb, MC_FIELD_SEP);
        sb_printf(&sb, "%u", (e->status & GIT_STATUS_IGNORED) != 0 ? 1u : 0u);
        sb_putc(&sb, MC_REC_SEP);
    }
    git_status_list_free(list);

    {
        char *out = sb_detach(&sb);
        jstring result = mc_cstr_to_jstring(env, out != NULL ? out : "");
        free(out);
        return result;
    }
}

/* 暂存单个文件 */
JNIEXPORT jboolean JNICALL
Java_com_mobilecoder_ide_core_nativebridge_GitNative_stage(
        JNIEnv *env, jclass clazz, jstring path) {
    (void) clazz;
    char *p = mc_jstring_to_cstr(env, path);
    git_index *index = NULL;
    int rc = 0;

    if (p == NULL || p[0] == '\0' || !mc_require_repo()) {
        free(p);
        return JNI_FALSE;
    }
    mc_clear_error();

    if (git_repository_index(&index, g_repo) < 0) {
        mc_set_error("读取索引失败");
        free(p);
        return JNI_FALSE;
    }

    if (mc_file_exists(p)) {
        rc = git_index_add_bypath(index, p);
    } else {
        /* 文件已删除：从索引移除（本身不在索引时忽略） */
        rc = git_index_remove_bypath(index, p);
        if (rc == GIT_ENOTFOUND) {
            rc = 0;
        }
    }

    if (rc == 0) {
        rc = git_index_write(index);
    }
    git_index_free(index);
    free(p);

    if (rc < 0) {
        mc_set_error("暂存失败");
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_mobilecoder_ide_core_nativebridge_GitNative_stageAll(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    git_index *index = NULL;
    git_status_options opts;
    git_status_list *list = NULL;
    size_t i, count;
    int ok = 1;

    if (!mc_require_repo()) {
        return JNI_FALSE;
    }
    mc_clear_error();

    if (git_repository_index(&index, g_repo) < 0) {
        mc_set_error("读取索引失败");
        return JNI_FALSE;
    }
    if (mc_status_options(&opts) < 0 ||
        git_status_list_new(&list, g_repo, &opts) < 0) {
        mc_set_error("读取状态失败");
        git_index_free(index);
        return JNI_FALSE;
    }

    count = git_status_list_entrycount(list);
    for (i = 0; i < count; i++) {
        const git_status_entry *e = git_status_byindex(list, i);
        const char *path = mc_status_path(e);
        int rc = 0;

        if (path == NULL || path[0] == '\0') {
            continue;
        }
        if (mc_file_exists(path)) {
            rc = git_index_add_bypath(index, path);
        } else if ((e->status & MC_STATUS_WT_MASK) != 0 ||
                   (e->status & GIT_STATUS_INDEX_DELETED) != 0) {
            rc = git_index_remove_bypath(index, path);
            if (rc == GIT_ENOTFOUND) {
                rc = 0;
            }
        }
        if (rc < 0) {
            ok = 0;
            mc_set_errorf("暂存失败：%s", path);
        }
    }

    git_status_list_free(list);
    if (ok && git_index_write(index) < 0) {
        ok = 0;
        mc_set_error("写入索引失败");
    }
    git_index_free(index);
    return ok ? JNI_TRUE : JNI_FALSE;
}

/* 取消暂存单个文件 */
JNIEXPORT jboolean JNICALL
Java_com_mobilecoder_ide_core_nativebridge_GitNative_unstage(
        JNIEnv *env, jclass clazz, jstring path) {
    (void) clazz;
    char *p = mc_jstring_to_cstr(env, path);
    git_oid head;
    int has_head;

    if (p == NULL || p[0] == '\0' || !mc_require_repo()) {
        free(p);
        return JNI_FALSE;
    }
    mc_clear_error();

    has_head = (mc_head_oid(&head) == 0);

    if (has_head) {
        git_object *target = NULL;
        git_strarray pathspec;
        char *strings[1];
        int rc;

        if (git_object_lookup(&target, g_repo, &head, GIT_OBJECT_COMMIT) < 0) {
            mc_set_error("读取 HEAD 失败");
            free(p);
            return JNI_FALSE;
        }
        strings[0] = p;
        pathspec.strings = strings;
        pathspec.count = 1;

        rc = git_reset_default(g_repo, target, &pathspec);
        git_object_free(target);
        free(p);
        if (rc < 0) {
            mc_set_error("取消暂存失败");
            return JNI_FALSE;
        }
        return JNI_TRUE;
    }

    /* 尚无首次提交：直接从索引移除 */
    {
        git_index *index = NULL;
        int rc;
        if (git_repository_index(&index, g_repo) < 0) {
            mc_set_error("读取索引失败");
            free(p);
            return JNI_FALSE;
        }
        rc = git_index_remove_bypath(index, p);
        if (rc == 0) {
            rc = git_index_write(index);
        }
        git_index_free(index);
        free(p);
        if (rc < 0 && rc != GIT_ENOTFOUND) {
            mc_set_error("取消暂存失败");
            return JNI_FALSE;
        }
        return JNI_TRUE;
    }
}

JNIEXPORT jboolean JNICALL
Java_com_mobilecoder_ide_core_nativebridge_GitNative_unstageAll(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    git_index *index = NULL;
    git_oid head;
    int rc;

    if (!mc_require_repo()) {
        return JNI_FALSE;
    }
    mc_clear_error();

    if (git_repository_index(&index, g_repo) < 0) {
        mc_set_error("读取索引失败");
        return JNI_FALSE;
    }

    if (mc_head_oid(&head) == 0) {
        git_commit *commit = NULL;
        git_tree *tree = NULL;

        if (git_commit_lookup(&commit, g_repo, &head) < 0 ||
            git_commit_tree(&tree, commit) < 0) {
            git_commit_free(commit);
            git_index_free(index);
            mc_set_error("读取 HEAD 树失败");
            return JNI_FALSE;
        }
        rc = git_index_read_tree(index, tree);
        git_tree_free(tree);
        git_commit_free(commit);
        if (rc == 0) {
            rc = git_index_write(index);
        }
    } else {
        rc = git_index_clear(index);
        if (rc == 0) {
            rc = git_index_write(index);
        }
    }

    git_index_free(index);
    if (rc < 0) {
        mc_set_error("取消暂存失败");
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

/* ------------------------------------------------------------------ */
/*  JNI —— 还原（丢弃改动）                                            */
/* ------------------------------------------------------------------ */

/* 组装单路径 pathspec（精确匹配，不做通配解释） */
static void mc_single_pathspec(git_strarray *paths, char **storage, char *path) {
    storage[0] = path;
    paths->strings = storage;
    paths->count = 1;
}

/* 还原工作区文件为索引内容（等价 `git restore <path>`）：只动工作区，不动索引 */
JNIEXPORT jboolean JNICALL
Java_com_mobilecoder_ide_core_nativebridge_GitNative_restoreWorktree(
        JNIEnv *env, jclass clazz, jstring path) {
    (void) clazz;
    char *p = mc_jstring_to_cstr(env, path);
    git_checkout_options opts = GIT_CHECKOUT_OPTIONS_INIT;
    git_strarray paths;
    char *storage[1];
    int rc;

    if (p == NULL || p[0] == '\0' || !mc_require_repo()) {
        free(p);
        return JNI_FALSE;
    }
    mc_clear_error();

    mc_single_pathspec(&paths, storage, p);
    opts.checkout_strategy = GIT_CHECKOUT_FORCE | GIT_CHECKOUT_DISABLE_PATHSPEC_MATCH;
    opts.paths = paths;

    /* 目标 = 索引，基线 = 工作区 → 把索引内容写回工作区 */
    rc = git_checkout_index(g_repo, NULL, &opts);
    free(p);
    if (rc < 0) {
        mc_set_error("还原工作区失败");
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

/* 还原索引与工作区为 HEAD 内容（等价 `git reset --hard -- <path>`） */
JNIEXPORT jboolean JNICALL
Java_com_mobilecoder_ide_core_nativebridge_GitNative_restoreToHead(
        JNIEnv *env, jclass clazz, jstring path) {
    (void) clazz;
    char *p = mc_jstring_to_cstr(env, path);
    git_oid head;
    git_object *target = NULL;
    git_checkout_options opts = GIT_CHECKOUT_OPTIONS_INIT;
    git_strarray paths;
    char *storage[1];
    int rc;

    if (p == NULL || p[0] == '\0' || !mc_require_repo()) {
        free(p);
        return JNI_FALSE;
    }
    mc_clear_error();

    if (mc_head_oid(&head) < 0) {
        mc_set_errorf("尚无首次提交，无法还原到 HEAD");
        free(p);
        return JNI_FALSE;
    }
    if (git_object_lookup(&target, g_repo, &head, GIT_OBJECT_COMMIT) < 0) {
        mc_set_error("读取 HEAD 失败");
        free(p);
        return JNI_FALSE;
    }

    mc_single_pathspec(&paths, storage, p);
    opts.checkout_strategy = GIT_CHECKOUT_FORCE | GIT_CHECKOUT_DISABLE_PATHSPEC_MATCH;
    opts.paths = paths;

    /* 目标 = HEAD 树，基线 = 索引 → 索引与工作区一并写回 HEAD 内容 */
    rc = git_checkout_tree(g_repo, target, &opts);
    git_object_free(target);
    free(p);
    if (rc < 0) {
        mc_set_error("还原到 HEAD 失败");
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

/* ------------------------------------------------------------------ */
/*  JNI —— 提交                                                        */
/* ------------------------------------------------------------------ */

static int mc_staged_count(void) {
    git_status_options opts;
    git_status_list *list = NULL;
    size_t i, count, n = 0;

    if (mc_status_options(&opts) < 0) {
        return -1;
    }
    opts.show = GIT_STATUS_SHOW_INDEX_ONLY;
    if (git_status_list_new(&list, g_repo, &opts) < 0) {
        return -1;
    }
    count = git_status_list_entrycount(list);
    for (i = 0; i < count; i++) {
        const git_status_entry *e = git_status_byindex(list, i);
        if (e == NULL) {
            continue;
        }
        if ((e->status & MC_STATUS_INDEX_MASK) != 0) {
            n++;
        }
    }
    git_status_list_free(list);
    return (int) n;
}

JNIEXPORT jint JNICALL
Java_com_mobilecoder_ide_core_nativebridge_GitNative_commit(
        JNIEnv *env, jclass clazz,
        jstring message, jstring name, jstring email) {
    (void) clazz;
    char *c_msg = mc_jstring_to_cstr(env, message);
    char *c_name = mc_jstring_to_cstr(env, name);
    char *c_email = mc_jstring_to_cstr(env, email);
    git_index *index = NULL;
    git_oid tree_id, commit_id, head_oid;
    git_tree *tree = NULL;
    git_signature *sig = NULL;
    git_commit *parents[1];
    unsigned int parent_count = 0;
    int staged, rc;

    if (!mc_require_repo()) {
        free(c_msg);
        free(c_name);
        free(c_email);
        return -1;
    }
    if (c_msg == NULL || c_msg[0] == '\0') {
        mc_set_error("提交信息不能为空");
        free(c_msg);
        free(c_name);
        free(c_email);
        return -1;
    }
    mc_clear_error();

    staged = mc_staged_count();
    if (staged < 0) {
        mc_set_error("读取状态失败");
        goto fail;
    }
    if (staged == 0) {
        free(c_msg);
        free(c_name);
        free(c_email);
        return 2;
    }

    if (git_repository_index(&index, g_repo) < 0) {
        mc_set_error("读取索引失败");
        goto fail;
    }
    if (git_index_write_tree(&tree_id, index) < 0) {
        mc_set_error("写入树对象失败");
        goto fail;
    }
    git_index_free(index);
    index = NULL;

    if (git_tree_lookup(&tree, g_repo, &tree_id) < 0) {
        mc_set_error("读取树对象失败");
        goto fail;
    }

    if (git_signature_now(&sig,
                          (c_name != NULL && c_name[0] != '\0') ? c_name : "MobileCoder",
                          (c_email != NULL && c_email[0] != '\0') ? c_email : "mobilecoder@local") < 0) {
        mc_set_error("创建签名失败");
        goto fail;
    }

    if (mc_head_oid(&head_oid) == 0) {
        git_commit *parent = NULL;
        if (git_commit_lookup(&parent, g_repo, &head_oid) < 0) {
            mc_set_error("读取父提交失败");
            goto fail;
        }
        parents[0] = parent;
        parent_count = 1;
    }

    rc = git_commit_create(&commit_id, g_repo, "HEAD", sig, sig, NULL, c_msg, tree,
                           parent_count, parent_count > 0 ? parents : NULL);
    if (rc < 0) {
        mc_set_error("创建提交失败");
        goto fail;
    }

    git_signature_free(sig);
    git_tree_free(tree);
    free(c_msg);
    free(c_name);
    free(c_email);
    return 0;

fail:
    git_index_free(index);
    git_tree_free(tree);
    git_signature_free(sig);
    free(c_msg);
    free(c_name);
    free(c_email);
    return -1;
}

/* ------------------------------------------------------------------ */
/*  JNI —— 提交时间线                                                  */
/* ------------------------------------------------------------------ */

JNIEXPORT jstring JNICALL
Java_com_mobilecoder_ide_core_nativebridge_GitNative_log(
        JNIEnv *env, jclass clazz, jint limit) {
    (void) clazz;
    git_revwalk *walk = NULL;
    git_oid oid;
    mc_sb sb;
    int remaining = limit > 0 ? (int) limit : 500;
    git_oid head;

    if (!mc_require_repo()) {
        return mc_cstr_to_jstring(env, "");
    }
    mc_clear_error();

    if (mc_head_oid(&head) < 0) {
        /* 空仓库：没有提交 */
        return mc_cstr_to_jstring(env, "");
    }

    if (git_revwalk_new(&walk, g_repo) < 0) {
        mc_set_error("创建提交遍历器失败");
        return mc_cstr_to_jstring(env, "");
    }
    git_revwalk_sorting(walk, GIT_SORT_TOPOLOGICAL | GIT_SORT_TIME);
    if (git_revwalk_push(walk, &head) < 0) {
        git_revwalk_free(walk);
        mc_set_error("遍历提交失败");
        return mc_cstr_to_jstring(env, "");
    }

    sb_init(&sb);
    while (remaining > 0 && git_revwalk_next(&oid, walk) == 0) {
        git_commit *commit = NULL;
        const git_signature *author;
        const char *summary;
        char oid_str[GIT_OID_MAX_HEXSIZE + 1];
        char short_str[GIT_OID_MAX_HEXSIZE + 1];
        size_t hexsize;
        unsigned int parents;

        if (git_commit_lookup(&commit, g_repo, &oid) < 0) {
            continue;
        }

        mc_oid_str(oid_str, sizeof(oid_str), git_commit_id(commit));
        hexsize = strlen(oid_str);
        if (hexsize > 10) {
            hexsize = 10;
        }
        memcpy(short_str, oid_str, hexsize);
        short_str[hexsize] = '\0';

        summary = git_commit_summary(commit);
        author = git_commit_author(commit);
        parents = git_commit_parentcount(commit);

        sb_puts(&sb, oid_str);
        sb_putc(&sb, MC_FIELD_SEP);
        sb_puts(&sb, short_str);
        sb_putc(&sb, MC_FIELD_SEP);
        sb_line(&sb, summary != NULL ? summary : "");
        sb_putc(&sb, MC_FIELD_SEP);
        sb_line(&sb, author != NULL ? author->name : "");
        sb_putc(&sb, MC_FIELD_SEP);
        sb_line(&sb, author != NULL ? author->email : "");
        sb_putc(&sb, MC_FIELD_SEP);
        sb_printf(&sb, "%lld", (long long) git_commit_time(commit));
        sb_putc(&sb, MC_FIELD_SEP);
        sb_printf(&sb, "%u", parents);
        sb_putc(&sb, MC_REC_SEP);

        git_commit_free(commit);
        remaining--;
    }

    git_revwalk_free(walk);

    {
        char *out = sb_detach(&sb);
        jstring result = mc_cstr_to_jstring(env, out != NULL ? out : "");
        free(out);
        return result;
    }
}

/* ------------------------------------------------------------------ */
/*  JNI —— 分支                                                        */
/* ------------------------------------------------------------------ */

static void mc_append_branch(mc_sb *sb, git_reference *ref) {
    const char *name = git_reference_shorthand(ref);
    const git_oid *local_id;
    char upstream_name[MC_REFNAME_MAX];
    size_t ahead = 0, behind = 0;
    int is_head;
    git_reference *upstream = NULL;

    upstream_name[0] = '\0';
    is_head = git_branch_is_head(ref);

    if (git_branch_upstream(&upstream, ref) == 0 && upstream != NULL) {
        /* shorthand 指向 upstream 内部内存，必须先拷贝再 free */
        snprintf(upstream_name, sizeof(upstream_name), "%s",
                 git_reference_shorthand(upstream));
        local_id = git_reference_target(ref);
        if (local_id != NULL && git_reference_target(upstream) != NULL) {
            git_graph_ahead_behind(&ahead, &behind, g_repo,
                                   local_id, git_reference_target(upstream));
        }
        git_reference_free(upstream);
    }

    sb_puts(sb, name != NULL ? name : "");
    sb_putc(sb, MC_FIELD_SEP);
    sb_printf(sb, "%d", is_head ? 1 : 0);
    sb_putc(sb, MC_FIELD_SEP);
    sb_puts(sb, upstream_name);
    sb_putc(sb, MC_FIELD_SEP);
    sb_printf(sb, "%zu", ahead);
    sb_putc(sb, MC_FIELD_SEP);
    sb_printf(sb, "%zu", behind);
    sb_putc(sb, MC_REC_SEP);
}

JNIEXPORT jstring JNICALL
Java_com_mobilecoder_ide_core_nativebridge_GitNative_branches(JNIEnv *env, jclass clazz) {
    (void) clazz;
    git_branch_iterator *iter = NULL;
    git_reference *ref = NULL;
    git_branch_t type;
    mc_sb sb;

    if (!mc_require_repo()) {
        return mc_cstr_to_jstring(env, "");
    }
    mc_clear_error();

    if (git_branch_iterator_new(&iter, g_repo, GIT_BRANCH_LOCAL) < 0) {
        mc_set_error("读取分支失败");
        return mc_cstr_to_jstring(env, "");
    }

    sb_init(&sb);
    while (git_branch_next(&ref, &type, iter) == 0) {
        mc_append_branch(&sb, ref);
        git_reference_free(ref);
        ref = NULL;
    }
    git_branch_iterator_free(iter);

    {
        char *out = sb_detach(&sb);
        jstring result = mc_cstr_to_jstring(env, out != NULL ? out : "");
        free(out);
        return result;
    }
}

JNIEXPORT jstring JNICALL
Java_com_mobilecoder_ide_core_nativebridge_GitNative_currentBranch(JNIEnv *env, jclass clazz) {
    (void) clazz;
    git_reference *head = NULL;
    const char *name = "";

    if (!mc_require_repo()) {
        return mc_cstr_to_jstring(env, "");
    }
    mc_clear_error();

    if (git_repository_head(&head, g_repo) == 0 && head != NULL) {
        /* shorthand 指向 head 内部内存，必须先拷贝再 free */
        char buf[MC_REFNAME_MAX];
        snprintf(buf, sizeof(buf), "%s", git_reference_shorthand(head));
        git_reference_free(head);
        return mc_cstr_to_jstring(env, buf);
    }
    if (git_repository_head_detached(g_repo) == 1) {
        name = "HEAD";
    } else {
        name = "";
    }
    return mc_cstr_to_jstring(env, name != NULL ? name : "");
}

JNIEXPORT jstring JNICALL
Java_com_mobilecoder_ide_core_nativebridge_GitNative_headInfo(JNIEnv *env, jclass clazz) {
    (void) clazz;
    git_reference *head = NULL;
    char name[MC_REFNAME_MAX];
    int detached = 0, unborn = 0;
    size_t ahead = 0, behind = 0;
    mc_sb sb;

    name[0] = '\0';
    sb_init(&sb);

    if (!mc_require_repo()) {
        return mc_cstr_to_jstring(env, "");
    }
    mc_clear_error();

    if (git_repository_head(&head, g_repo) == 0 && head != NULL) {
        /* shorthand 指向 head 内部内存，必须在 free 之前拷贝 */
        snprintf(name, sizeof(name), "%s", git_reference_shorthand(head));
        detached = git_repository_head_detached(g_repo) == 1;
        if (!detached && git_reference_target(head) != NULL) {
            git_reference *upstream = NULL;
            if (git_branch_upstream(&upstream, head) == 0 && upstream != NULL) {
                if (git_reference_target(upstream) != NULL) {
                    git_graph_ahead_behind(&ahead, &behind, g_repo,
                                           git_reference_target(head),
                                           git_reference_target(upstream));
                }
                git_reference_free(upstream);
            }
        }
        git_reference_free(head);
    } else {
        unborn = 1;
        detached = git_repository_head_detached(g_repo) == 1;
    }

    sb_puts(&sb, name);
    sb_putc(&sb, MC_FIELD_SEP);
    sb_printf(&sb, "%d", detached);
    sb_putc(&sb, MC_FIELD_SEP);
    sb_printf(&sb, "%d", unborn);
    sb_putc(&sb, MC_FIELD_SEP);
    sb_printf(&sb, "%zu", ahead);
    sb_putc(&sb, MC_FIELD_SEP);
    sb_printf(&sb, "%zu", behind);

    {
        char *out = sb_detach(&sb);
        jstring result = mc_cstr_to_jstring(env, out != NULL ? out : "");
        free(out);
        return result;
    }
}

JNIEXPORT jboolean JNICALL
Java_com_mobilecoder_ide_core_nativebridge_GitNative_createBranch(
        JNIEnv *env, jclass clazz, jstring name) {
    (void) clazz;
    char *c_name = mc_jstring_to_cstr(env, name);
    git_oid head;
    git_commit *commit = NULL;
    git_reference *ref = NULL;
    int rc;

    if (c_name == NULL || c_name[0] == '\0' || !mc_require_repo()) {
        free(c_name);
        return JNI_FALSE;
    }
    mc_clear_error();

    if (mc_head_oid(&head) < 0) {
        mc_set_error("当前没有可分支的提交");
        free(c_name);
        return JNI_FALSE;
    }
    if (git_commit_lookup(&commit, g_repo, &head) < 0) {
        mc_set_error("读取 HEAD 提交失败");
        free(c_name);
        return JNI_FALSE;
    }

    rc = git_branch_create(&ref, g_repo, c_name, commit, 0);
    git_commit_free(commit);
    git_reference_free(ref);
    free(c_name);

    if (rc < 0) {
        mc_set_error("创建分支失败");
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

JNIEXPORT jint JNICALL
Java_com_mobilecoder_ide_core_nativebridge_GitNative_checkoutBranch(
        JNIEnv *env, jclass clazz, jstring name) {
    (void) clazz;
    char *c_name = mc_jstring_to_cstr(env, name);
    git_reference *ref = NULL;
    git_annotated_commit *ac = NULL;
    git_checkout_options opts = GIT_CHECKOUT_OPTIONS_INIT;
    char refname[512];
    int rc;

    if (c_name == NULL || c_name[0] == '\0' || !mc_require_repo()) {
        free(c_name);
        return -1;
    }
    mc_clear_error();

    if (git_branch_lookup(&ref, g_repo, c_name, GIT_BRANCH_LOCAL) < 0) {
        mc_set_errorf("分支不存在：%s", c_name);
        free(c_name);
        return -1;
    }
    snprintf(refname, sizeof(refname), "%s", git_reference_name(ref));

    if (git_annotated_commit_from_ref(&ac, g_repo, ref) < 0) {
        git_reference_free(ref);
        free(c_name);
        mc_set_error("读取分支提交失败");
        return -1;
    }
    git_reference_free(ref);

    /* 先移动工作区/索引，再更新 HEAD 指向 */
    opts.checkout_strategy = GIT_CHECKOUT_SAFE;
    {
        git_commit *target = NULL;
        if (git_commit_lookup(&target, g_repo, git_annotated_commit_id(ac)) < 0) {
            git_annotated_commit_free(ac);
            free(c_name);
            mc_set_error("读取分支提交失败");
            return -1;
        }
        rc = git_checkout_tree(g_repo, (const git_object *) target, &opts);
        git_commit_free(target);
    }
    if (rc == 0) {
        rc = git_repository_set_head(g_repo, refname);
    }

    git_annotated_commit_free(ac);
    free(c_name);

    if (rc < 0) {
        mc_set_error("切换分支失败（工作区有未提交的冲突改动？）");
        return -1;
    }
    return 0;
}

JNIEXPORT jboolean JNICALL
Java_com_mobilecoder_ide_core_nativebridge_GitNative_deleteBranch(
        JNIEnv *env, jclass clazz, jstring name) {
    (void) clazz;
    char *c_name = mc_jstring_to_cstr(env, name);
    git_reference *ref = NULL;
    int rc;

    if (c_name == NULL || c_name[0] == '\0' || !mc_require_repo()) {
        free(c_name);
        return JNI_FALSE;
    }
    mc_clear_error();

    if (git_branch_lookup(&ref, g_repo, c_name, GIT_BRANCH_LOCAL) < 0) {
        mc_set_errorf("分支不存在：%s", c_name);
        free(c_name);
        return JNI_FALSE;
    }
    if (git_branch_is_head(ref)) {
        git_reference_free(ref);
        free(c_name);
        mc_set_error("不能删除当前所在分支");
        return JNI_FALSE;
    }

    rc = git_branch_delete(ref); /* 内部释放 ref */
    free(c_name);
    if (rc < 0) {
        mc_set_error("删除分支失败");
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

/* ------------------------------------------------------------------ */
/*  JNI —— 合并                                                        */
/* ------------------------------------------------------------------ */

typedef struct {
    git_oid oids[16];
    size_t count;
} mc_mergeheads_t;

static int mc_collect_mergehead(const git_oid *oid, void *payload) {
    mc_mergeheads_t *mh = (mc_mergeheads_t *) payload;
    if (mh->count < sizeof(mh->oids) / sizeof(mh->oids[0])) {
        git_oid_cpy(&mh->oids[mh->count], oid);
        mh->count++;
    }
    return 0;
}

/* 冲突已全部解决后：以 HEAD + MERGE_HEAD 创建合并提交 */
static int mc_finish_merge(const char *message) {
    git_index *index = NULL;
    git_oid tree_id, commit_id, head_oid;
    git_tree *tree = NULL;
    git_signature *sig = NULL;
    git_commit *parents[17];
    unsigned int parent_count = 0;
    mc_mergeheads_t mh;
    size_t i;
    int rc = -1;

    mh.count = 0;
    if (git_repository_index(&index, g_repo) < 0) {
        mc_set_error("读取索引失败");
        return -1;
    }
    if (git_index_write_tree(&tree_id, index) < 0) {
        mc_set_error("写入树对象失败");
        git_index_free(index);
        return -1;
    }
    git_index_free(index);

    if (git_tree_lookup(&tree, g_repo, &tree_id) < 0) {
        mc_set_error("读取树对象失败");
        return -1;
    }

    if (git_signature_now(&sig, "MobileCoder", "mobilecoder@local") < 0) {
        mc_set_error("创建签名失败");
        git_tree_free(tree);
        return -1;
    }

    if (mc_head_oid(&head_oid) == 0) {
        git_commit *parent = NULL;
        if (git_commit_lookup(&parent, g_repo, &head_oid) < 0) {
            mc_set_error("读取父提交失败");
            goto done;
        }
        parents[parent_count++] = parent;
    }

    if (git_repository_mergehead_foreach(g_repo, mc_collect_mergehead, &mh) < 0) {
        mc_set_error("读取 MERGE_HEAD 失败");
        goto done;
    }
    for (i = 0; i < mh.count; i++) {
        git_commit *parent = NULL;
        if (git_commit_lookup(&parent, g_repo, &mh.oids[i]) < 0) {
            continue;
        }
        parents[parent_count++] = parent;
    }

    if (parent_count == 0) {
        mc_set_error("没有可合并的父提交");
        goto done;
    }

    rc = git_commit_create(&commit_id, g_repo, "HEAD", sig, sig, NULL, message, tree,
                           parent_count, parents);
    if (rc < 0) {
        mc_set_error("创建合并提交失败");
        goto done;
    }
    rc = 0;

done:
    for (i = 0; i < parent_count; i++) {
        git_commit_free(parents[i]);
    }
    git_signature_free(sig);
    git_tree_free(tree);
    return rc;
}

JNIEXPORT jint JNICALL
Java_com_mobilecoder_ide_core_nativebridge_GitNative_merge(
        JNIEnv *env, jclass clazz, jstring name) {
    (void) clazz;
    char *c_name = mc_jstring_to_cstr(env, name);
    git_reference *ref = NULL;
    git_annotated_commit *ac = NULL;
    const git_annotated_commit *their[1];
    git_merge_analysis_t analysis = GIT_MERGE_ANALYSIS_NONE;
    git_merge_preference_t preference = GIT_MERGE_PREFERENCE_NONE;
    git_merge_options merge_opts = GIT_MERGE_OPTIONS_INIT;
    git_checkout_options checkout_opts = GIT_CHECKOUT_OPTIONS_INIT;
    char msg[512];
    int rc;

    if (c_name == NULL || c_name[0] == '\0' || !mc_require_repo()) {
        free(c_name);
        return -1;
    }
    mc_clear_error();

    if (git_branch_lookup(&ref, g_repo, c_name, GIT_BRANCH_LOCAL) < 0) {
        mc_set_errorf("分支不存在：%s", c_name);
        free(c_name);
        return -1;
    }
    if (git_annotated_commit_from_ref(&ac, g_repo, ref) < 0) {
        git_reference_free(ref);
        free(c_name);
        mc_set_error("读取分支提交失败");
        return -1;
    }
    git_reference_free(ref);

    if (git_merge_analysis(&analysis, &preference, g_repo,
                           (const git_annotated_commit **) &ac, 1) < 0) {
        git_annotated_commit_free(ac);
        free(c_name);
        mc_set_error("合并分析失败");
        return -1;
    }

    if ((analysis & GIT_MERGE_ANALYSIS_UP_TO_DATE) != 0) {
        git_annotated_commit_free(ac);
        free(c_name);
        return 2;
    }

    snprintf(msg, sizeof(msg), "Merge branch '%s'", c_name);

    if ((analysis & GIT_MERGE_ANALYSIS_FASTFORWARD) != 0 &&
        (preference & GIT_MERGE_PREFERENCE_NO_FASTFORWARD) == 0) {
        /* 快进合并：工作区/索引更新到目标提交，并把当前分支前移 */
        git_checkout_options co = GIT_CHECKOUT_OPTIONS_INIT;
        git_commit *target_commit = NULL;
        git_reference *head_ref = NULL;
        const git_oid *target = git_annotated_commit_id(ac);

        rc = git_commit_lookup(&target_commit, g_repo, target);
        if (rc == 0) {
            co.checkout_strategy = GIT_CHECKOUT_SAFE;
            rc = git_checkout_tree(g_repo, (const git_object *) target_commit, &co);
        }
        git_commit_free(target_commit);

        if (rc == 0) {
            if (git_repository_head(&head_ref, g_repo) == 0 && head_ref != NULL) {
                git_reference *updated = NULL;
                rc = git_reference_set_target(&updated, head_ref, target, "fast-forward merge");
                git_reference_free(updated);
                git_reference_free(head_ref);
            } else {
                /* HEAD 未出生（空仓库首次合并）：按 HEAD 符号目标建立分支 */
                char refname[512];
                git_reference *head_sym = NULL;
                git_reference *created = NULL;

                snprintf(refname, sizeof(refname), "refs/heads/main");
                if (git_reference_lookup(&head_sym, g_repo, "HEAD") == 0 && head_sym != NULL) {
                    const char *target_name = git_reference_symbolic_target(head_sym);
                    if (target_name != NULL && target_name[0] != '\0') {
                        snprintf(refname, sizeof(refname), "%s", target_name);
                    }
                    git_reference_free(head_sym);
                }
                rc = git_reference_create(&created, g_repo, refname, target, 1,
                                          "fast-forward merge");
                git_reference_free(created);
                if (rc == 0) {
                    rc = git_repository_set_head(g_repo, refname);
                }
            }
        }

        git_annotated_commit_free(ac);
        free(c_name);
        if (rc < 0) {
            mc_set_error("快进合并失败");
            return -1;
        }
        mc_emit_progress("merge", msg, -1, -1);
        return 0;
    }

    /* 三方合并（可能产生冲突） */
    their[0] = ac;
    checkout_opts.checkout_strategy = GIT_CHECKOUT_SAFE;
    rc = git_merge(g_repo, their, 1, &merge_opts, &checkout_opts);

    if (rc == 0) {
        git_index *index = NULL;
        int conflicted = 0;
        if (git_repository_index(&index, g_repo) == 0 && index != NULL) {
            conflicted = git_index_has_conflicts(index);
            git_index_free(index);
        }
        if (conflicted) {
            git_annotated_commit_free(ac);
            free(c_name);
            mc_clear_error();
            return 1;
        }
        rc = mc_finish_merge(msg);
    }

    git_annotated_commit_free(ac);
    free(c_name);

    if (rc != 0) {
        if (g_err[0] == '\0') {
            mc_set_error("合并失败");
        }
        return -1;
    }
    mc_emit_progress("merge", msg, -1, -1);
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_mobilecoder_ide_core_nativebridge_GitNative_mergeAbort(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    git_checkout_options opts = GIT_CHECKOUT_OPTIONS_INIT;
    git_index *index = NULL;
    int rc;

    if (!mc_require_repo()) {
        return -1;
    }
    mc_clear_error();

    if (git_repository_index(&index, g_repo) == 0 && index != NULL) {
        git_index_read(index, 0);
        git_index_free(index);
    }

    opts.checkout_strategy = GIT_CHECKOUT_FORCE;
    rc = git_checkout_head(g_repo, &opts);
    if (rc < 0) {
        mc_set_error("放弃合并失败");
        return -1;
    }

    if (git_repository_state_cleanup(g_repo) < 0) {
        mc_set_error("清理合并状态失败");
        return -1;
    }
    return 0;
}

/* ------------------------------------------------------------------ */
/*  JNI —— 标签                                                        */
/* ------------------------------------------------------------------ */

JNIEXPORT jstring JNICALL
Java_com_mobilecoder_ide_core_nativebridge_GitNative_tags(JNIEnv *env, jclass clazz) {
    (void) clazz;
    git_strarray tags = {NULL, 0};
    mc_sb sb;
    size_t i;

    if (!mc_require_repo()) {
        return mc_cstr_to_jstring(env, "");
    }
    mc_clear_error();

    if (git_tag_list(&tags, g_repo) < 0) {
        mc_set_error("读取标签失败");
        return mc_cstr_to_jstring(env, "");
    }

    sb_init(&sb);
    for (i = 0; i < tags.count; i++) {
        const char *name = tags.strings[i];
        char refname[512];
        git_reference *ref = NULL;
        git_object *obj = NULL;
        char oid_str[GIT_OID_MAX_HEXSIZE + 1];
        const char *message = "";
        int annotated = 0;

        snprintf(refname, sizeof(refname), "refs/tags/%s", name);
        if (git_reference_lookup(&ref, g_repo, refname) < 0) {
            continue;
        }
        if (git_reference_peel(&obj, ref, GIT_OBJECT_ANY) < 0) {
            git_reference_free(ref);
            continue;
        }
        mc_oid_str(oid_str, sizeof(oid_str), git_object_id(obj));
        if (git_object_type(obj) == GIT_OBJECT_TAG) {
            annotated = 1;
            message = git_tag_message((const git_tag *) obj);
        }
        git_object_free(obj);
        git_reference_free(ref);

        sb_puts(&sb, name);
        sb_putc(&sb, MC_FIELD_SEP);
        sb_puts(&sb, oid_str);
        sb_putc(&sb, MC_FIELD_SEP);
        sb_printf(&sb, "%d", annotated);
        sb_putc(&sb, MC_FIELD_SEP);
        sb_line(&sb, message != NULL ? message : "");
        sb_putc(&sb, MC_REC_SEP);
    }
    git_strarray_free(&tags);

    {
        char *out = sb_detach(&sb);
        jstring result = mc_cstr_to_jstring(env, out != NULL ? out : "");
        free(out);
        return result;
    }
}

JNIEXPORT jboolean JNICALL
Java_com_mobilecoder_ide_core_nativebridge_GitNative_createTag(
        JNIEnv *env, jclass clazz, jstring name, jstring message) {
    (void) clazz;
    char *c_name = mc_jstring_to_cstr(env, name);
    char *c_msg = mc_jstring_to_cstr(env, message);
    git_oid head;
    git_object *obj = NULL;
    git_signature *sig = NULL;
    int rc;

    if (c_name == NULL || c_name[0] == '\0' || !mc_require_repo()) {
        free(c_name);
        free(c_msg);
        return JNI_FALSE;
    }
    mc_clear_error();

    if (mc_head_oid(&head) < 0 ||
        git_object_lookup(&obj, g_repo, &head, GIT_OBJECT_COMMIT) < 0) {
        mc_set_error("没有可打标签的提交");
        free(c_name);
        free(c_msg);
        return JNI_FALSE;
    }

    if (c_msg != NULL && c_msg[0] != '\0') {
        git_oid tag_oid;
        if (git_signature_now(&sig, "MobileCoder", "mobilecoder@local") < 0) {
            mc_set_error("创建签名失败");
            git_object_free(obj);
            free(c_name);
            free(c_msg);
            return JNI_FALSE;
        }
        rc = git_tag_create(&tag_oid, g_repo, c_name, obj, sig, c_msg, 0);
        git_signature_free(sig);
    } else {
        /* 轻量标签 */
        char refname[512];
        git_reference *ref = NULL;
        snprintf(refname, sizeof(refname), "refs/tags/%s", c_name);
        rc = git_reference_create(&ref, g_repo, refname, git_object_id(obj), 0, NULL);
        git_reference_free(ref);
    }

    git_object_free(obj);
    free(c_name);
    free(c_msg);

    if (rc < 0) {
        mc_set_error("创建标签失败");
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_mobilecoder_ide_core_nativebridge_GitNative_deleteTag(
        JNIEnv *env, jclass clazz, jstring name) {
    (void) clazz;
    char *c_name = mc_jstring_to_cstr(env, name);
    int rc;

    if (c_name == NULL || c_name[0] == '\0' || !mc_require_repo()) {
        free(c_name);
        return JNI_FALSE;
    }
    mc_clear_error();

    rc = git_tag_delete(g_repo, c_name);
    free(c_name);
    if (rc < 0) {
        mc_set_error("删除标签失败");
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

/* ------------------------------------------------------------------ */
/*  JNI —— Diff                                                        */
/* ------------------------------------------------------------------ */

JNIEXPORT jstring JNICALL
Java_com_mobilecoder_ide_core_nativebridge_GitNative_diffPatch(
        JNIEnv *env, jclass clazz, jboolean staged) {
    (void) clazz;
    git_diff *diff = NULL;
    git_diff_options dopts = GIT_DIFF_OPTIONS_INIT;
    git_buf buf = {NULL, 0, 0};
    int rc;

    if (!mc_require_repo()) {
        return mc_cstr_to_jstring(env, "");
    }
    mc_clear_error();

    dopts.flags |= GIT_DIFF_SHOW_BINARY;
    dopts.context_lines = 3;

    if (staged) {
        rc = git_diff_tree_to_index(&diff, g_repo, NULL, NULL, &dopts);
    } else {
        dopts.flags |= GIT_DIFF_INCLUDE_UNTRACKED | GIT_DIFF_SHOW_UNTRACKED_CONTENT;
        rc = git_diff_index_to_workdir(&diff, g_repo, NULL, &dopts);
    }

    if (rc < 0) {
        mc_set_error("生成差异失败");
        return mc_cstr_to_jstring(env, "");
    }

    rc = git_diff_to_buf(&buf, diff, GIT_DIFF_FORMAT_PATCH);
    git_diff_free(diff);
    if (rc < 0) {
        git_buf_dispose(&buf);
        mc_set_error("生成补丁失败");
        return mc_cstr_to_jstring(env, "");
    }

    {
        jstring result = mc_cstr_to_jstring(env, buf.ptr != NULL ? buf.ptr : "");
        git_buf_dispose(&buf);
        return result;
    }
}

/* 差异统计：新增/删除/修改文件数、增删行数 */
JNIEXPORT jstring JNICALL
Java_com_mobilecoder_ide_core_nativebridge_GitNative_diffStats(JNIEnv *env, jclass clazz) {
    (void) clazz;
    git_diff *diff = NULL;
    git_diff_options dopts = GIT_DIFF_OPTIONS_INIT;
    git_diff_stats *stats = NULL;
    size_t i, count, lines_add = 0, lines_del = 0;
    int added = 0, deleted = 0, modified = 0;
    mc_sb sb;

    if (!mc_require_repo()) {
        return mc_cstr_to_jstring(env, "");
    }
    mc_clear_error();

    dopts.flags |= GIT_DIFF_INCLUDE_UNTRACKED | GIT_DIFF_SHOW_UNTRACKED_CONTENT;
    if (git_diff_index_to_workdir(&diff, g_repo, NULL, &dopts) < 0) {
        mc_set_error("生成差异失败");
        return mc_cstr_to_jstring(env, "");
    }

    count = git_diff_num_deltas(diff);
    for (i = 0; i < count; i++) {
        const git_diff_delta *d = git_diff_get_delta(diff, i);
        if (d == NULL) {
            continue;
        }
        if (d->status == GIT_DELTA_ADDED) {
            added++;
        } else if (d->status == GIT_DELTA_DELETED) {
            deleted++;
        } else {
            modified++;
        }
    }

    if (git_diff_get_stats(&stats, diff) == 0 && stats != NULL) {
        lines_add = git_diff_stats_insertions(stats);
        lines_del = git_diff_stats_deletions(stats);
        git_diff_stats_free(stats);
    }
    git_diff_free(diff);

    sb_init(&sb);
    sb_printf(&sb, "%d", added);
    sb_putc(&sb, MC_FIELD_SEP);
    sb_printf(&sb, "%d", deleted);
    sb_putc(&sb, MC_FIELD_SEP);
    sb_printf(&sb, "%d", modified);
    sb_putc(&sb, MC_FIELD_SEP);
    sb_printf(&sb, "%zu", lines_add);
    sb_putc(&sb, MC_FIELD_SEP);
    sb_printf(&sb, "%zu", lines_del);

    {
        char *out = sb_detach(&sb);
        jstring result = mc_cstr_to_jstring(env, out != NULL ? out : "");
        free(out);
        return result;
    }
}

/* ------------------------------------------------------------------ */
/*  JNI —— 冲突                                                        */
/* ------------------------------------------------------------------ */

JNIEXPORT jstring JNICALL
Java_com_mobilecoder_ide_core_nativebridge_GitNative_conflicts(JNIEnv *env, jclass clazz) {
    (void) clazz;
    git_index *index = NULL;
    mc_sb sb;
    size_t i, count;
    char prev[2048];

    if (!mc_require_repo()) {
        return mc_cstr_to_jstring(env, "");
    }
    mc_clear_error();

    if (git_repository_index(&index, g_repo) < 0) {
        mc_set_error("读取索引失败");
        return mc_cstr_to_jstring(env, "");
    }

    sb_init(&sb);
    prev[0] = '\0';
    count = git_index_entrycount(index);
    for (i = 0; i < count; i++) {
        const git_index_entry *e = git_index_get_byindex(index, i);
        const git_index_entry *ancestor = NULL;
        const git_index_entry *ours = NULL;
        const git_index_entry *theirs = NULL;
        char oid_base[GIT_OID_MAX_HEXSIZE + 1];
        char oid_ours[GIT_OID_MAX_HEXSIZE + 1];
        char oid_theirs[GIT_OID_MAX_HEXSIZE + 1];

        if (e == NULL || e->path == NULL) {
            continue;
        }
        if (GIT_INDEX_ENTRY_STAGE(e) == 0) {
            continue;
        }
        if (strcmp(prev, e->path) == 0) {
            continue; /* 同一文件的其它 stage 已处理 */
        }
        snprintf(prev, sizeof(prev), "%s", e->path);

        if (git_index_conflict_get(&ancestor, &ours, &theirs, index, e->path) < 0) {
            continue;
        }
        mc_oid_str(oid_base, sizeof(oid_base), ancestor != NULL ? &ancestor->id : NULL);
        mc_oid_str(oid_ours, sizeof(oid_ours), ours != NULL ? &ours->id : NULL);
        mc_oid_str(oid_theirs, sizeof(oid_theirs), theirs != NULL ? &theirs->id : NULL);

        sb_puts(&sb, e->path);
        sb_putc(&sb, MC_FIELD_SEP);
        sb_puts(&sb, oid_base);
        sb_putc(&sb, MC_FIELD_SEP);
        sb_puts(&sb, oid_ours);
        sb_putc(&sb, MC_FIELD_SEP);
        sb_puts(&sb, oid_theirs);
        sb_putc(&sb, MC_REC_SEP);
    }

    git_index_free(index);
    {
        char *out = sb_detach(&sb);
        jstring result = mc_cstr_to_jstring(env, out != NULL ? out : "");
        free(out);
        return result;
    }
}

/* 读取冲突三方中某一侧的内容；side: 1=祖先 2=我方 3=对方 */
JNIEXPORT jbyteArray JNICALL
Java_com_mobilecoder_ide_core_nativebridge_GitNative_conflictContent(
        JNIEnv *env, jclass clazz, jstring path, jint side) {
    (void) clazz;
    char *p = mc_jstring_to_cstr(env, path);
    git_index *index = NULL;
    const git_index_entry *ancestor = NULL;
    const git_index_entry *ours = NULL;
    const git_index_entry *theirs = NULL;
    const git_index_entry *entry = NULL;
    git_blob *blob = NULL;
    jbyteArray result = NULL;

    if (p == NULL || p[0] == '\0' || !mc_require_repo()) {
        free(p);
        return NULL;
    }
    mc_clear_error();

    if (git_repository_index(&index, g_repo) < 0 ||
        git_index_conflict_get(&ancestor, &ours, &theirs, index, p) < 0) {
        mc_set_error("该文件没有冲突");
        free(p);
        git_index_free(index);
        return NULL;
    }

    switch (side) {
        case 1: entry = ancestor; break;
        case 3: entry = theirs; break;
        case 2:
        default: entry = ours; break;
    }

    if (entry == NULL) {
        /* 该侧被删除 */
        git_index_free(index);
        free(p);
        return NULL;
    }

    if (git_blob_lookup(&blob, g_repo, &entry->id) < 0) {
        mc_set_error("读取冲突内容失败");
        git_index_free(index);
        free(p);
        return NULL;
    }

    {
        const char *raw = (const char *) git_blob_rawcontent(blob);
        size_t len = (size_t) git_blob_rawsize(blob);
        result = mc_new_byte_array(env, raw != NULL ? raw : "", len);
    }

    git_blob_free(blob);
    git_index_free(index);
    free(p);
    return result;
}

JNIEXPORT jboolean JNICALL
Java_com_mobilecoder_ide_core_nativebridge_GitNative_resolveConflictContent(
        JNIEnv *env, jclass clazz, jstring path, jbyteArray content);

JNIEXPORT jboolean JNICALL
Java_com_mobilecoder_ide_core_nativebridge_GitNative_resolveConflict(
        JNIEnv *env, jclass clazz, jstring path, jint side) {
    jbyteArray content;
    jboolean ok;

    content = Java_com_mobilecoder_ide_core_nativebridge_GitNative_conflictContent(
            env, clazz, path, side);
    if (content == NULL) {
        return JNI_FALSE;
    }
    ok = Java_com_mobilecoder_ide_core_nativebridge_GitNative_resolveConflictContent(
            env, clazz, path, content);
    (*env)->DeleteLocalRef(env, content);
    return ok;
}

JNIEXPORT jboolean JNICALL
Java_com_mobilecoder_ide_core_nativebridge_GitNative_resolveConflictContent(
        JNIEnv *env, jclass clazz, jstring path, jbyteArray content) {
    (void) clazz;
    char *p = mc_jstring_to_cstr(env, path);
    git_index *index = NULL;
    jbyte *bytes = NULL;
    jsize len = 0;
    int rc;

    if (p == NULL || p[0] == '\0' || !mc_require_repo()) {
        free(p);
        return JNI_FALSE;
    }
    mc_clear_error();

    if (content != NULL) {
        len = (*env)->GetArrayLength(env, content);
        bytes = (*env)->GetByteArrayElements(env, content, NULL);
    }

    if (mc_write_workdir_file(p, bytes != NULL ? (const char *) bytes : "",
                              (size_t) (len > 0 ? len : 0)) != 0) {
        if (bytes != NULL) {
            (*env)->ReleaseByteArrayElements(env, content, bytes, JNI_ABORT);
        }
        free(p);
        return JNI_FALSE;
    }

    if (bytes != NULL) {
        (*env)->ReleaseByteArrayElements(env, content, bytes, JNI_ABORT);
    }

    if (git_repository_index(&index, g_repo) < 0) {
        mc_set_error("读取索引失败");
        free(p);
        return JNI_FALSE;
    }

    rc = git_index_conflict_remove(index, p);
    if (rc == 0 || rc == GIT_ENOTFOUND) {
        rc = git_index_add_bypath(index, p);
    }
    if (rc == 0) {
        rc = git_index_write(index);
    }
    git_index_free(index);
    free(p);

    if (rc < 0) {
        mc_set_error("标记冲突已解决失败");
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

/* 是否仍有未解决的冲突 */
JNIEXPORT jboolean JNICALL
Java_com_mobilecoder_ide_core_nativebridge_GitNative_hasConflicts(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    git_index *index = NULL;
    int has;

    if (!mc_require_repo()) {
        return JNI_FALSE;
    }
    if (git_repository_index(&index, g_repo) < 0) {
        return JNI_TRUE;
    }
    has = git_index_has_conflicts(index);
    git_index_free(index);
    return has ? JNI_TRUE : JNI_FALSE;
}

/* 冲突全部解决后调用，写入合并提交 */
JNIEXPORT jint JNICALL
Java_com_mobilecoder_ide_core_nativebridge_GitNative_finishMerge(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    if (!mc_require_repo()) {
        return -1;
    }
    mc_clear_error();
    return mc_finish_merge("Merge (resolved conflicts)");
}

/* ------------------------------------------------------------------ */
/*  JNI —— 远程                                                        */
/* ------------------------------------------------------------------ */

JNIEXPORT jstring JNICALL
Java_com_mobilecoder_ide_core_nativebridge_GitNative_remotes(JNIEnv *env, jclass clazz) {
    (void) clazz;
    git_strarray names = {NULL, 0};
    mc_sb sb;
    size_t i;

    if (!mc_require_repo()) {
        return mc_cstr_to_jstring(env, "");
    }
    mc_clear_error();

    if (git_remote_list(&names, g_repo) < 0) {
        mc_set_error("读取远程仓库失败");
        return mc_cstr_to_jstring(env, "");
    }

    sb_init(&sb);
    for (i = 0; i < names.count; i++) {
        git_remote *remote = NULL;
        const char *url = "";
        if (git_remote_lookup(&remote, g_repo, names.strings[i]) == 0) {
            url = git_remote_url(remote);
            git_remote_free(remote);
        }
        sb_puts(&sb, names.strings[i]);
        sb_putc(&sb, MC_FIELD_SEP);
        sb_puts(&sb, url != NULL ? url : "");
        sb_putc(&sb, MC_REC_SEP);
    }
    git_strarray_free(&names);

    {
        char *out = sb_detach(&sb);
        jstring result = mc_cstr_to_jstring(env, out != NULL ? out : "");
        free(out);
        return result;
    }
}

JNIEXPORT jboolean JNICALL
Java_com_mobilecoder_ide_core_nativebridge_GitNative_addRemote(
        JNIEnv *env, jclass clazz, jstring name, jstring url) {
    (void) clazz;
    char *c_name = mc_jstring_to_cstr(env, name);
    char *c_url = mc_jstring_to_cstr(env, url);
    git_remote *remote = NULL;
    int rc;

    if (c_name == NULL || c_name[0] == '\0' ||
        c_url == NULL || c_url[0] == '\0' || !mc_require_repo()) {
        free(c_name);
        free(c_url);
        return JNI_FALSE;
    }
    mc_clear_error();

    rc = git_remote_create(&remote, g_repo, c_name, c_url);
    git_remote_free(remote);
    free(c_name);
    free(c_url);

    if (rc < 0) {
        mc_set_error("添加远程仓库失败");
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_mobilecoder_ide_core_nativebridge_GitNative_removeRemote(
        JNIEnv *env, jclass clazz, jstring name) {
    (void) clazz;
    char *c_name = mc_jstring_to_cstr(env, name);
    int rc;

    if (c_name == NULL || c_name[0] == '\0' || !mc_require_repo()) {
        free(c_name);
        return JNI_FALSE;
    }
    mc_clear_error();

    rc = git_remote_delete(g_repo, c_name);
    free(c_name);
    if (rc < 0) {
        mc_set_error("删除远程仓库失败");
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_mobilecoder_ide_core_nativebridge_GitNative_setRemoteUrl(
        JNIEnv *env, jclass clazz, jstring name, jstring url) {
    (void) clazz;
    char *c_name = mc_jstring_to_cstr(env, name);
    char *c_url = mc_jstring_to_cstr(env, url);
    int rc;

    if (c_name == NULL || c_url == NULL || !mc_require_repo()) {
        free(c_name);
        free(c_url);
        return JNI_FALSE;
    }
    mc_clear_error();

    rc = git_remote_set_url(g_repo, c_name, c_url);
    free(c_name);
    free(c_url);
    if (rc < 0) {
        mc_set_error("修改远程地址失败");
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

JNIEXPORT jint JNICALL
Java_com_mobilecoder_ide_core_nativebridge_GitNative_fetch(
        JNIEnv *env, jclass clazz, jstring name) {
    (void) clazz;
    char *c_name = mc_jstring_to_cstr(env, name);
    git_remote *remote = NULL;
    git_fetch_options opts = GIT_FETCH_OPTIONS_INIT;
    int rc;

    if (c_name == NULL || c_name[0] == '\0' || !mc_require_repo()) {
        free(c_name);
        return -1;
    }
    mc_clear_error();

    if (git_remote_lookup(&remote, g_repo, c_name) < 0) {
        mc_set_errorf("远程仓库不存在：%s", c_name);
        free(c_name);
        return -1;
    }

    opts.callbacks = mc_remote_callbacks();
    opts.prune = GIT_FETCH_PRUNE_UNSPECIFIED;

    mc_emit_progress("fetch", c_name, -1, -1);
    mc_remote_begin(remote);
    rc = git_remote_fetch(remote, NULL, &opts, "mobilecoder fetch");
    mc_remote_end();
    git_remote_free(remote);
    free(c_name);

    if (rc < 0) {
        if (g_err[0] == '\0') {
            mc_set_error("拉取失败");
        }
        return -1;
    }
    mc_emit_progress("fetch", "done", -1, -1);
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_mobilecoder_ide_core_nativebridge_GitNative_push(
        JNIEnv *env, jclass clazz, jstring name, jstring branch) {
    (void) clazz;
    char *c_name = mc_jstring_to_cstr(env, name);
    char *c_branch = mc_jstring_to_cstr(env, branch);
    git_remote *remote = NULL;
    git_push_options opts = GIT_PUSH_OPTIONS_INIT;
    git_strarray refspecs = {NULL, 0};
    char *owned[1] = {NULL};
    int rc;

    if (c_name == NULL || c_name[0] == '\0' || !mc_require_repo()) {
        free(c_name);
        free(c_branch);
        return -1;
    }
    mc_clear_error();

    if (git_remote_lookup(&remote, g_repo, c_name) < 0) {
        mc_set_errorf("远程仓库不存在：%s", c_name);
        free(c_name);
        free(c_branch);
        return -1;
    }

    if (c_branch != NULL && c_branch[0] != '\0') {
        char spec[600];
        snprintf(spec, sizeof(spec), "refs/heads/%s:refs/heads/%s", c_branch, c_branch);
        owned[0] = strdup(spec);
        if (owned[0] == NULL) {
            git_remote_free(remote);
            free(c_name);
            free(c_branch);
            mc_set_error("内存分配失败");
            return -1;
        }
        refspecs.strings = owned;
        refspecs.count = 1;
    }

    opts.callbacks = mc_remote_callbacks();

    mc_emit_progress("push", c_name, -1, -1);
    mc_remote_begin(remote);
    rc = git_remote_push(remote, refspecs.count > 0 ? &refspecs : NULL, &opts);
    mc_remote_end();

    free(owned[0]);
    git_remote_free(remote);
    free(c_name);
    free(c_branch);

    if (rc < 0) {
        if (g_err[0] == '\0') {
            mc_set_error("推送失败");
        }
        return -1;
    }
    mc_emit_progress("push", "done", -1, -1);
    return 0;
}

/* 取消进行中的 fetch / push（可在任意线程调用；无进行中操作时为空操作） */
JNIEXPORT void JNICALL
Java_com_mobilecoder_ide_core_nativebridge_GitNative_cancelNetwork(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    pthread_mutex_lock(&g_active_remote_lock);
    if (g_active_remote != NULL) {
        git_remote_stop(g_active_remote);
    }
    pthread_mutex_unlock(&g_active_remote_lock);
}

/* ------------------------------------------------------------------ */
/*  JNI —— 忽略规则                                                    */
/* ------------------------------------------------------------------ */

JNIEXPORT jboolean JNICALL
Java_com_mobilecoder_ide_core_nativebridge_GitNative_addIgnoreRule(
        JNIEnv *env, jclass clazz, jstring rule) {
    (void) clazz;
    char *c_rule = mc_jstring_to_cstr(env, rule);
    int rc;

    if (c_rule == NULL || c_rule[0] == '\0' || !mc_require_repo()) {
        free(c_rule);
        return JNI_FALSE;
    }
    mc_clear_error();

    rc = git_ignore_add_rule(g_repo, c_rule);
    free(c_rule);

    if (rc < 0) {
        mc_set_error("添加忽略规则失败");
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_mobilecoder_ide_core_nativebridge_GitNative_clearIgnoreRules(
        JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    if (g_repo != NULL) {
        git_ignore_clear_internal_rules(g_repo);
    }
}
