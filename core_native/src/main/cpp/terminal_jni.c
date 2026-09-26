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
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <termios.h>
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
 * Signature: (Ljava/lang/String;IILcom/mobilecoder/ide/core/nativebridge/TerminalCallback;)I
 */
JNIEXPORT jint JNICALL
Java_com_mobilecoder_ide_core_nativebridge_TerminalNative_create(
        JNIEnv *env, jobject thiz, jstring cwd, jint cols, jint rows, jobject callback) {
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

    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = (unsigned short) (rows > 0 ? rows : 24);
    ws.ws_col = (unsigned short) (cols > 0 ? cols : 80);

    pid_t pid = -1;
    int master_fd = -1;
    pid = forkpty(&master_fd, NULL, NULL, &ws);
    if (pid < 0) {
        MC_LOGE("forkpty 失败: %s", strerror(errno));
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
        free(workdir);
        execl("/system/bin/sh", "sh", (char *) NULL);
        execlp("sh", "sh", (char *) NULL);
        _exit(127);
    }

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
    /* 主端 fd 交给读线程回收：kill SIGHUP 终止 shell → 从端关闭 →
     * 主端 read 返回 EIO/EOF → 读线程清理并回调 onExit。 */
    pthread_mutex_unlock(&g_terminal_lock);

    kill(pid, SIGHUP);
    MC_LOGI("终端会话 %d 已请求关闭", (int) id);
    return 0;
}
