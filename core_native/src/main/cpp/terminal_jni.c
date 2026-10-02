/*
 * MobileCoder —— 内置终端 PTY 引擎（C 层，JNI 封装）
 *
 * 需求对应（PRD 2.3 / TECH 4.2）：
 *  - APP 内嵌独立终端，无需 ROOT、无需跳转第三方应用
 *  - 基于 PTY 伪终端实现独立会话，多窗口多 PTY 实例并发
 *  - 自动绑定当前项目工作目录（cwd 由 Kotlin 层传入）
 *  - 后台任务保活：会话存在于原生层，进程存活即不中断
 */
#define _GNU_SOURCE
#include <jni.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <termios.h>
#include <time.h>
#include <unistd.h>
#include <pty.h>

#include "jni_util.h"

#define MC_MAX_TERMINALS 8
#define MC_TERMINAL_UNUSED 0
#define MC_TERMINAL_ACTIVE 1
#define MC_TERMINAL_CLOSING 2

typedef struct {
    int state;
    int id;
    int master_fd;
    pid_t pid;
    jobject callback;      /* GlobalRef: TerminalCallback */
    jmethodID on_data;     /* onData(I[B)V  */
    jmethodID on_exit;     /* onExit(II)V   */
    int attached;          /* 读线程是否由自身 attach 到 JVM */
} mc_terminal_t;

static mc_terminal_t g_terminals[MC_MAX_TERMINALS];
static pthread_mutex_t g_terminal_lock = PTHREAD_MUTEX_INITIALIZER;
static int g_next_id = 1;

static mc_terminal_t *terminal_by_id(int id) {
    for (int i = 0; i < MC_MAX_TERMINALS; i++) {
        if (g_terminals[i].state != MC_TERMINAL_UNUSED && g_terminals[i].id == id) {
            return &g_terminals[i];
        }
    }
    return NULL;
}

/* 读线程：持续读取 PTY 主端并回调 onData；EOF 后等待子进程退出并回调 onExit */
static void *terminal_reader_thread(void *arg) {
    mc_terminal_t *t = (mc_terminal_t *) arg;
    JNIEnv *env = NULL;
    int attached = 0;

    if (g_vm != NULL) {
        jint rc = (*g_vm)->GetEnv(g_vm, (void **) &env, JNI_VERSION_1_6);
        if (rc == JNI_EDETACHED) {
            if ((*g_vm)->AttachCurrentThread(g_vm, &env, NULL) == JNI_OK) {
                attached = 1;
            } else {
                env = NULL;
            }
        }
    }
    t->attached = attached;

    char buffer[4096];
    for (;;) {
        ssize_t n = read(t->master_fd, buffer, sizeof(buffer));
        if (n > 0) {
            if (env != NULL) {
                jbyteArray data = mc_new_byte_array(env, buffer, (size_t) n);
                if (data != NULL) {
                    (*env)->CallVoidMethod(env, t->callback, t->on_data, t->id, data);
                    (*env)->DeleteLocalRef(env, data);
                }
            }
            continue;
        }
        if (n == 0) {
            break; /* EOF：子进程关闭了从端 */
        }
        if (errno == EINTR) {
            continue;
        }
        if (errno == EAGAIN || errno == EIO) {
            /* EIO：从端已关闭（Android/PTY 常见） */
            break;
        }
        MC_LOGE("terminal %d read 失败: %s", t->id, strerror(errno));
        break;
    }

    /* 等待子进程退出，确保不残留僵尸进程 */
    int status = 0;
    int exit_code = 0;
    for (int i = 0; i < 200; i++) {
        pid_t r = waitpid(t->pid, &status, WNOHANG);
        if (r == t->pid) {
            if (WIFEXITED(status)) {
                exit_code = WEXITSTATUS(status);
            } else if (WIFSIGNALED(status)) {
                exit_code = 128 + WTERMSIG(status);
            }
            goto exited;
        }
        if (r < 0) {
            exit_code = 0;
            goto exited;
        }
        if (i == 100) {
            kill(t->pid, SIGKILL); /* 兜底：强制回收 */
        }
        usleep(50 * 1000);
    }
    waitpid(t->pid, &status, 0);
    if (WIFEXITED(status)) {
        exit_code = WEXITSTATUS(status);
    } else if (WIFSIGNALED(status)) {
        exit_code = 128 + WTERMSIG(status);
    }

exited:
    if (env != NULL) {
        (*env)->CallVoidMethod(env, t->callback, t->on_exit, t->id, exit_code);
    }

    pthread_mutex_lock(&g_terminal_lock);
    if (t->master_fd >= 0) {
        close(t->master_fd);
        t->master_fd = -1;
    }
    if (t->callback != NULL && env != NULL) {
        (*env)->DeleteGlobalRef(env, t->callback);
        t->callback = NULL;
    }
    t->state = MC_TERMINAL_UNUSED;
    t->id = 0;
    pthread_mutex_unlock(&g_terminal_lock);

    if (attached && g_vm != NULL) {
        (*g_vm)->DetachCurrentThread(g_vm);
        t->attached = 0;
    }
    return NULL;
}

/*
 * Class:     com_mobilecoder_ide_core_nativebridge_TerminalNative
 * Method:    create
 * Signature: (Ljava/lang/String;IILcom/mobilecoder/ide/core/nativebridge/TerminalCallback;[Ljava/lang/String;)I
 */
JNIEXPORT jint JNICALL
Java_com_mobilecoder_ide_core_nativebridge_TerminalNative_create(
        JNIEnv *env, jobject thiz, jstring cwd, jint cols, jint rows, jobject callback,
        jobjectArray argv) {
    (void) thiz;
    if (callback == NULL) {
        return -1;
    }

    pthread_mutex_lock(&g_terminal_lock);
    mc_terminal_t *slot = NULL;
    for (int i = 0; i < MC_MAX_TERMINALS; i++) {
        if (g_terminals[i].state == MC_TERMINAL_UNUSED) {
            slot = &g_terminals[i];
            break;
        }
    }
    if (slot == NULL) {
        pthread_mutex_unlock(&g_terminal_lock);
        MC_LOGE("终端会话已达上限 %d", MC_MAX_TERMINALS);
        return -1;
    }

    jclass cls = (*env)->GetObjectClass(env, callback);
    if (cls == NULL) {
        pthread_mutex_unlock(&g_terminal_lock);
        return -1;
    }
    jmethodID on_data = (*env)->GetMethodID(env, cls, "onData", "(I[B)V");
    jmethodID on_exit = (*env)->GetMethodID(env, cls, "onExit", "(II)V");
    (*env)->DeleteLocalRef(env, cls);
    if (on_data == NULL || on_exit == NULL) {
        pthread_mutex_unlock(&g_terminal_lock);
        return -1;
    }

    char *workdir = mc_jstring_to_cstr(env, cwd);

    /* 可选启动命令（Linux 环境就绪时 = proot + guest bash）：父线程构造，fork 后
     * 子进程直接 execv；为空 / argv[0] 为空则回退 /system/bin/sh（bionic 直启，
     * rootfs 未安装时的降级路径，保证装系统前终端仍可用）。 */
    char **child_argv = NULL;
    jsize child_argc = 0;
    if (argv != NULL) {
        jsize n = (*env)->GetArrayLength(env, argv);
        if (n > 0) {
            child_argv = (char **) calloc((size_t) n + 1, sizeof(char *));
            if (child_argv != NULL) {
                for (jsize i = 0; i < n; i++) {
                    jstring item = (jstring) (*env)->GetObjectArrayElement(env, argv, i);
                    child_argv[i] = mc_jstring_to_cstr(env, item);
                    if (item != NULL) {
                        (*env)->DeleteLocalRef(env, item);
                    }
                    if (child_argv[i] == NULL) {
                        child_argv[i] = strdup("");
                    }
                    child_argc++;
                }
                child_argv[n] = NULL;
            }
        }
    }

    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = (unsigned short) (rows > 0 ? rows : 24);
    ws.ws_col = (unsigned short) (cols > 0 ? cols : 80);

    pid_t pid = -1;
    int master_fd = -1;
    pid = forkpty(&master_fd, NULL, NULL, &ws);
    if (pid < 0) {
        MC_LOGE("forkpty 失败: %s", strerror(errno));
        for (jsize i = 0; i < child_argc; i++) free(child_argv[i]);
        free(child_argv);
        free(workdir);
        pthread_mutex_unlock(&g_terminal_lock);
        return -1;
    }

    if (pid == 0) {
        /* ---- 子进程：成为会话首领，绑定工作目录并拉起 shell ---- */
        if (workdir != NULL) {
            if (chdir(workdir) != 0) {
                fprintf(stderr, "sh: 无法进入目录 %s: %s\r\n", workdir, strerror(errno));
            }
        }
        setenv("TERM", "xterm-256color", 1);
        setenv("COLORTERM", "truecolor", 1);
        setenv("LANG", "C.UTF-8", 1);
        setenv("TMPDIR", "/data/data/com.mobilecoder.ide/cache/tmp", 1);
        /* 默认 HOME/PATH（overwrite=0：Kotlin 层 TerminalNative.setEnv 的值优先；
         * PATH 含 guest 标准路径，Linux 环境装好后由 proot 内的 shell 使用） */
        setenv("HOME", "/data/data/com.mobilecoder.ide/files", 0);
        setenv("PATH",
               "/data/data/com.mobilecoder.ide/files/bin:/usr/local/sbin:/usr/local/bin"
               ":/usr/sbin:/usr/bin:/sbin:/bin:/system/bin:/system/xbin",
               0);
        free(workdir);
        /* proot 启动失败（如执行位缺失）时给出一行原因，再回退 bionic sh */
        if (child_argv != NULL && child_argc > 0 &&
            child_argv[0] != NULL && child_argv[0][0] != '\0') {
            execv(child_argv[0], child_argv);
            fprintf(stderr, "sh: 启动 %s 失败: %s\r\n", child_argv[0], strerror(errno));
        }
        execl("/system/bin/sh", "sh", (char *) NULL);
        execlp("sh", "sh", (char *) NULL);
        _exit(127);
    }

    /* 父进程：释放 argv 的 C 拷贝（子进程持有 fork 出的独立副本） */
    for (jsize i = 0; i < child_argc; i++) free(child_argv[i]);
    free(child_argv);
    free(workdir);

    slot->state = MC_TERMINAL_ACTIVE;
    slot->id = g_next_id++;
    slot->master_fd = master_fd;
    slot->pid = pid;
    slot->callback = (*env)->NewGlobalRef(env, callback);
    slot->on_data = on_data;
    slot->on_exit = on_exit;
    slot->attached = 0;
    int session_id = slot->id;

    pthread_t thread;
    if (pthread_create(&thread, NULL, terminal_reader_thread, slot) != 0) {
        MC_LOGE("终端读线程创建失败");
        close(master_fd);
        kill(pid, SIGHUP);
        if (slot->callback != NULL) {
            (*env)->DeleteGlobalRef(env, slot->callback);
            slot->callback = NULL;
        }
        slot->state = MC_TERMINAL_UNUSED;
        pthread_mutex_unlock(&g_terminal_lock);
        return -1;
    }
    pthread_detach(thread);
    pthread_mutex_unlock(&g_terminal_lock);

    MC_LOGI("终端会话 %d 已创建 (pid=%d)", session_id, (int) pid);
    return session_id;
}

/*
 * Method:    write    Signature: (I[B)I
 */
JNIEXPORT jint JNICALL
Java_com_mobilecoder_ide_core_nativebridge_TerminalNative_write(
        JNIEnv *env, jobject thiz, jint id, jbyteArray data) {
    (void) thiz;
    if (data == NULL) {
        return -1;
    }
    jsize len = (*env)->GetArrayLength(env, data);
    if (len <= 0) {
        return 0;
    }
    jbyte *bytes = (*env)->GetByteArrayElements(env, data, NULL);
    if (bytes == NULL) {
        return -1;
    }

    pthread_mutex_lock(&g_terminal_lock);
    mc_terminal_t *t = terminal_by_id(id);
    int fd = (t != NULL && t->state == MC_TERMINAL_ACTIVE) ? t->master_fd : -1;
    pthread_mutex_unlock(&g_terminal_lock);

    int written = 0;
    if (fd >= 0) {
        while (written < len) {
            ssize_t n = write(fd, bytes + written, (size_t) (len - written));
            if (n > 0) {
                written += (int) n;
                continue;
            }
            if (n < 0 && errno == EINTR) {
                continue;
            }
            written = written > 0 ? written : -1;
            break;
        }
    } else {
        written = -1;
    }

    (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);
    return written;
}

/*
 * Method:    resize    Signature: (III)I
 */
JNIEXPORT jint JNICALL
Java_com_mobilecoder_ide_core_nativebridge_TerminalNative_resize(
        JNIEnv *env, jobject thiz, jint id, jint cols, jint rows) {
    (void) env;
    (void) thiz;
    pthread_mutex_lock(&g_terminal_lock);
    mc_terminal_t *t = terminal_by_id(id);
    int fd = (t != NULL && t->state == MC_TERMINAL_ACTIVE) ? t->master_fd : -1;
    pthread_mutex_unlock(&g_terminal_lock);
    if (fd < 0) {
        return -1;
    }
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_col = (unsigned short) (cols > 0 ? cols : 80);
    ws.ws_row = (unsigned short) (rows > 0 ? rows : 24);
    if (ioctl(fd, TIOCSWINSZ, &ws) < 0) {
        return -1;
    }
    return 0;
}

/*
 * Method:    setEnv    Signature: (Ljava/lang/String;Ljava/lang/String;)I
 *
 * 设置**当前进程**环境变量（子进程 fork 时继承）。
 * Kotlin 层在 App 启动时调用，用于注入 HOME / PATH / ANDROID_HOME / JAVA_HOME 等。
 */
JNIEXPORT jint JNICALL
Java_com_mobilecoder_ide_core_nativebridge_TerminalNative_setEnv(
        JNIEnv *env, jobject thiz, jstring key, jstring value) {
    (void) thiz;
    char *k = mc_jstring_to_cstr(env, key);
    char *v = mc_jstring_to_cstr(env, value);
    int rc = -1;

    if (k != NULL && k[0] != '\0' && v != NULL) {
        rc = setenv(k, v, 1) == 0 ? 0 : -1;
    }
    free(k);
    free(v);
    return rc;
}

/*
 * ———————— 关闭会话清扫（孤儿进程兜底）————————
 *
 * 实测（Android 10 / EMUI）：app 进程 SigIgn 含 SIGHUP（继承自 zygote），
 * fork 出的 proot、以及用户后台任务（如 sleep）同样继承 →
 * `kill(pid, SIGHUP)` 永远打不死它们；而 forkpty 子进程 setsid() 自成会话，
 * Android 按进程组杀进程也够不着 → 关闭会话后 proot/bash/后台任务全成
 * 孤儿，读线程还会卡在 read() 永不回收 → 8 个会话槽位被泄漏后无法再建。
 *
 * 解法：destroy 时起一个分离线程按「会话号」全量清扫，先礼后兵：
 *   1) 对会话内每个进程补一发 SIGHUP（bash 会捕获 →善后 history/作业）；
 *   2) 500ms 宽限后 SIGKILL 会话内所有仍存活的同 uid 进程，残余再补刀。
 * /proc/<pid>/stat 的第 6 字段（comm 之后第 4 个）即会话号；他 uid 的
 * stat 读不到（EACCES）天然跳过，只会命中自己 fork 的进程。
 */

/** 读取 /proc/<pid>/stat 的会话号；返回 0 成功，-1 不可读/解析失败。 */
static int mc_read_session(pid_t pid, pid_t *sid_out) {
    char path[64];
    snprintf(path, sizeof(path), "/proc/%d/stat", (int) pid);
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return -1;
    char buf[1024];
    ssize_t n = read(fd, buf, sizeof(buf) - 1);
    close(fd);
    if (n <= 0) return -1;
    buf[n] = '\0';
    /* comm 形如 " (proot)"，可能含空格/右括号 → 以最后一个 ')' 切字段：
     * 其后为 " S ppid pgrp session …"，即 state、ppid、pgrp、session。 */
    char *rp = strrchr(buf, ')');
    if (rp == NULL) return -1;
    char state;
    int ppid, pgrp, sid;
    if (sscanf(rp + 1, " %c %d %d %d", &state, &ppid, &pgrp, &sid) != 4) return -1;
    (void) state;
    (void) ppid;
    (void) pgrp;
    *sid_out = (pid_t) sid;
    return 0;
}

/** 对会话 [sid] 内所有可读（同 uid）进程发 [sig]；返回命中数，-1 表示 /proc 不可遍历。 */
static int mc_signal_session(pid_t sid, int sig) {
    DIR *dir = opendir("/proc");
    if (dir == NULL) return -1;
    int hit = 0;
    struct dirent *e;
    while ((e = readdir(dir)) != NULL) {
        if (e->d_name[0] < '1' || e->d_name[0] > '9') continue;
        pid_t pid = (pid_t) atoi(e->d_name);
        pid_t s = 0;
        if (mc_read_session(pid, &s) != 0) continue;
        if (s != sid) continue;
        if (kill(pid, sig) == 0) hit++;
    }
    closedir(dir);
    return hit;
}

/** 分离线程：关闭会话的收尾清扫（先 HUP 后 KILL，见上）。 */
static void *mc_escalate_thread(void *arg) {
    pid_t sid = (pid_t) (intptr_t) arg;
    struct timespec ts;
    ts.tv_sec = 0;

    int n0 = mc_signal_session(sid, SIGHUP);   /* 礼：直接给全会话 HUP（bash 收到即善后） */
    MC_LOGI("会话 pid=%d 清扫启动：HUP 命中 %d", (int) sid, n0);
    ts.tv_nsec = 500 * 1000 * 1000L;
    nanosleep(&ts, NULL);             /* 宽限：给 bash 收尾时间 */

    for (int round = 1; round <= 3; round++) {   /* 兵：SIGKILL 补刀至净 */
        int n = mc_signal_session(sid, SIGKILL);
        MC_LOGI("会话 pid=%d 清扫第 %d 轮：SIGKILL 命中 %d", (int) sid, round, n);
        if (n <= 0) break;
        ts.tv_nsec = 200 * 1000 * 1000L;
        nanosleep(&ts, NULL);
    }
    MC_LOGI("会话 pid=%d 清扫完成", (int) sid);
    return NULL;
}

/*
 * Method:    destroy    Signature: (I)I
 */
JNIEXPORT jint JNICALL
Java_com_mobilecoder_ide_core_nativebridge_TerminalNative_destroy(
        JNIEnv *env, jobject thiz, jint id) {
    (void) env;
    (void) thiz;
    pthread_mutex_lock(&g_terminal_lock);
    mc_terminal_t *t = terminal_by_id(id);
    if (t == NULL || t->state == MC_TERMINAL_UNUSED) {
        pthread_mutex_unlock(&g_terminal_lock);
        return -1;
    }
    t->state = MC_TERMINAL_CLOSING;
    pid_t pid = t->pid;
    /* 主端 fd 交给读线程回收：shell 死亡 → 从端关闭 → 主端 read 返回
     * EIO/EOF → 读线程清理并回调 onExit（同时回收会话槽位）。 */
    pthread_mutex_unlock(&g_terminal_lock);

    kill(pid, SIGHUP);
    /* SIGHUP 在本环境被继承性忽略（见上方注释）→ 分离线程按会话号清扫兜底 */
    pthread_t th;
    int rc = pthread_create(&th, NULL, mc_escalate_thread, (void *) (intptr_t) pid);
    if (rc == 0) {
        pthread_detach(th);
    } else {
        MC_LOGE("会话 pid=%d 清扫线程创建失败 rc=%d", (int) pid, rc);
    }
    MC_LOGI("终端会话 %d 已请求关闭", (int) id);
    return 0;
}
