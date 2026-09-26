/*
 * MobileCoder —— CLI / 外部进程执行引擎（JNI）
 *
 * 需求对应（PRD 2.4 / TECH 4.3）：
 *  - OpenCode CLI 在终端沙箱环境中运行，日志实时回调 UI 层展示
 *  - 协程任务队列在 Kotlin 层串行化，本层提供进程级并发执行能力
 *  - 编译任务独立线程执行、实时流式输出（feature_build 复用）
 */
#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/wait.h>
#include <unistd.h>

#include "jni_util.h"

#define MC_MAX_PROCESSES 8

typedef struct {
    int active;
    int pid;
    int stdin_fd;
    jobject callback;        /* GlobalRef: CliCallback */
    jmethodID on_output;     /* onOutput(II[B)V */
    jmethodID on_exit;       /* onExit(II)V     */
    pthread_t out_thread;
    pthread_t err_thread;
    pthread_t wait_thread;
    int out_started;
    int err_started;
} mc_process_t;

static mc_process_t g_processes[MC_MAX_PROCESSES];
static pthread_mutex_t g_process_lock = PTHREAD_MUTEX_INITIALIZER;

typedef struct {
    mc_process_t *proc;
    int stream; /* 1=stdout 2=stderr */
    int fd;
} mc_reader_task_t;

static void call_on_output(JNIEnv *env, mc_process_t *p, int stream, const char *buf, ssize_t len) {
    if (env == NULL || p->callback == NULL) {
        return;
    }
    jbyteArray data = mc_new_byte_array(env, buf, (size_t) len);
    if (data != NULL) {
        (*env)->CallVoidMethod(env, p->callback, p->on_output, p->pid, stream, data);
        (*env)->DeleteLocalRef(env, data);
    }
}

static void *reader_thread_main(void *arg) {
    mc_reader_task_t *task = (mc_reader_task_t *) arg;
    mc_process_t *p = task->proc;
    int stream = task->stream;
    int fd = task->fd;
    free(task);

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

    char buffer[4096];
    for (;;) {
        ssize_t n = read(fd, buffer, sizeof(buffer));
        if (n > 0) {
            call_on_output(env, p, stream, buffer, n);
            continue;
        }
        if (n == 0) {
            break;
        }
        if (errno == EINTR) {
            continue;
        }
        break;
    }
    close(fd);

    if (attached && g_vm != NULL) {
        (*g_vm)->DetachCurrentThread(g_vm);
    }
    return NULL;
}

static void *process_wait_thread(void *arg) {
    mc_process_t *p = (mc_process_t *) arg;

    int status = 0;
    int exit_code = 0;
    pid_t r;
    do {
        r = waitpid(p->pid, &status, 0);
    } while (r < 0 && errno == EINTR);
    if (r == p->pid) {
        if (WIFEXITED(status)) {
            exit_code = WEXITSTATUS(status);
        } else if (WIFSIGNALED(status)) {
            exit_code = 128 + WTERMSIG(status);
        }
    }

    /* 等待输出线程读完剩余数据后再回调 onExit，保证日志顺序 */
    if (p->out_started) {
        pthread_join(p->out_thread, NULL);
        p->out_started = 0;
    }
    if (p->err_started) {
        pthread_join(p->err_thread, NULL);
        p->err_started = 0;
    }

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

    if (env != NULL && p->callback != NULL) {
        (*env)->CallVoidMethod(env, p->callback, p->on_exit, p->pid, exit_code);
    }

    pthread_mutex_lock(&g_process_lock);
    if (env != NULL && p->callback != NULL) {
        (*env)->DeleteGlobalRef(env, p->callback);
        p->callback = NULL;
    }
    if (p->stdin_fd >= 0) {
        close(p->stdin_fd);
        p->stdin_fd = -1;
    }
    p->active = 0;
    p->pid = 0;
    pthread_mutex_unlock(&g_process_lock);

    if (attached && g_vm != NULL) {
        (*g_vm)->DetachCurrentThread(g_vm);
    }
    return NULL;
}

/* 仅在 fork 之前的父线程调用（可安全 malloc） */
static char **build_envp(JNIEnv *env, jobjectArray env_arr, int *needs_free) {
    *needs_free = 0;
    if (env_arr == NULL) {
        return environ;
    }
    jsize count = (*env)->GetArrayLength(env, env_arr);
    char **envp = (char **) calloc((size_t) count + 1, sizeof(char *));
    if (envp == NULL) {
        return environ;
    }
    *needs_free = 1;
    for (jsize i = 0; i < count; i++) {
        jstring item = (jstring) (*env)->GetObjectArrayElement(env, env_arr, i);
        envp[i] = mc_jstring_to_cstr(env, item);
        if (item != NULL) {
            (*env)->DeleteLocalRef(env, item);
        }
        if (envp[i] == NULL) {
            envp[i] = strdup("");
        }
    }
    envp[count] = NULL;
    return envp;
}

static void free_envp(char **envp, int needs_free) {
    if (!needs_free || envp == NULL) {
        return;
    }
    for (int i = 0; envp[i] != NULL; i++) {
        free(envp[i]);
    }
    free(envp);
}

static void free_argv(char **argv, jsize argc) {
    if (argv == NULL) {
        return;
    }
    for (jsize i = 0; i < argc; i++) {
        free(argv[i]);
    }
    free(argv);
}

/*
 * exec(command, cwd, env, callback) -> pid / -1
 * Signature: ([Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;Lcom/mobilecoder/ide/core/nativebridge/CliCallback;)I
 */
JNIEXPORT jint JNICALL
Java_com_mobilecoder_ide_core_nativebridge_CliNative_exec(
        JNIEnv *env, jobject thiz, jobjectArray command, jstring cwd,
        jobjectArray env_arr, jobject callback) {
    (void) thiz;
    if (command == NULL || callback == NULL) {
        return -1;
    }
    jsize argc = (*env)->GetArrayLength(env, command);
    if (argc <= 0) {
        return -1;
    }

    pthread_mutex_lock(&g_process_lock);
    mc_process_t *slot = NULL;
    for (int i = 0; i < MC_MAX_PROCESSES; i++) {
        if (!g_processes[i].active) {
            slot = &g_processes[i];
            break;
        }
    }
    if (slot == NULL) {
        pthread_mutex_unlock(&g_process_lock);
        MC_LOGE("并发进程数已达上限 %d", MC_MAX_PROCESSES);
        return -1;
    }

    jclass cb_cls = (*env)->GetObjectClass(env, callback);
    jmethodID on_output = (*env)->GetMethodID(env, cb_cls, "onOutput", "(II[B)V");
    jmethodID on_exit = (*env)->GetMethodID(env, cb_cls, "onExit", "(II)V");
    (*env)->DeleteLocalRef(env, cb_cls);
    if (on_output == NULL || on_exit == NULL) {
        pthread_mutex_unlock(&g_process_lock);
        return -1;
    }

    /* argv / envp 构造（父线程 malloc，fork 后子进程只读使用） */
    char **argv = (char **) calloc((size_t) argc + 1, sizeof(char *));
    if (argv == NULL) {
        pthread_mutex_unlock(&g_process_lock);
        return -1;
    }
    for (jsize i = 0; i < argc; i++) {
        jstring item = (jstring) (*env)->GetObjectArrayElement(env, command, i);
        argv[i] = mc_jstring_to_cstr(env, item);
        if (item != NULL) {
            (*env)->DeleteLocalRef(env, item);
        }
        if (argv[i] == NULL) {
            argv[i] = strdup("");
        }
    }
    argv[argc] = NULL;

    char *workdir = mc_jstring_to_cstr(env, cwd);
    int envp_needs_free = 0;
    char **envp = build_envp(env, env_arr, &envp_needs_free);

    int stdin_pipe[2] = {-1, -1};
    int stdout_pipe[2] = {-1, -1};
    int stderr_pipe[2] = {-1, -1};
    if (pipe(stdin_pipe) != 0 || pipe(stdout_pipe) != 0 || pipe(stderr_pipe) != 0) {
        MC_LOGE("pipe 创建失败: %s", strerror(errno));
        close(stdin_pipe[0]); close(stdin_pipe[1]);
        close(stdout_pipe[0]); close(stdout_pipe[1]);
        close(stderr_pipe[0]); close(stderr_pipe[1]);
        free_argv(argv, argc);
        free(workdir);
        free_envp(envp, envp_needs_free);
        pthread_mutex_unlock(&g_process_lock);
        return -1;
    }

    pid_t pid = fork();
    if (pid < 0) {
        MC_LOGE("fork 失败: %s", strerror(errno));
        close(stdin_pipe[0]); close(stdin_pipe[1]);
        close(stdout_pipe[0]); close(stdout_pipe[1]);
        close(stderr_pipe[0]); close(stderr_pipe[1]);
        free_argv(argv, argc);
        free(workdir);
        free_envp(envp, envp_needs_free);
        pthread_mutex_unlock(&g_process_lock);
        return -1;
    }

    if (pid == 0) {
        /* ---- 子进程 ---- */
        close(stdin_pipe[1]);
        close(stdout_pipe[0]);
        close(stderr_pipe[0]);
        if (dup2(stdin_pipe[0], STDIN_FILENO) < 0) _exit(127);
        if (dup2(stdout_pipe[1], STDOUT_FILENO) < 0) _exit(127);
        if (dup2(stderr_pipe[1], STDERR_FILENO) < 0) _exit(127);
        close(stdin_pipe[0]);
        close(stdout_pipe[1]);
        close(stderr_pipe[1]);

        setpgid(0, 0); /* 独立进程组，便于整组终止 */

        if (workdir != NULL && chdir(workdir) != 0) {
            const char *msg = "mobilecoder: 无法进入工作目录\r\n";
            ssize_t ignored = write(STDERR_FILENO, msg, strlen(msg));
            (void) ignored;
            _exit(127);
        }

        execvpe(argv[0], argv, envp);
        const char *msg = "mobilecoder: 命令执行失败 (execvp)\r\n";
        ssize_t ignored = write(STDERR_FILENO, msg, strlen(msg));
        (void) ignored;
        _exit(127);
    }

    /* ---- 父进程 ---- */
    close(stdin_pipe[0]);
    close(stdout_pipe[1]);
    close(stderr_pipe[1]);

    slot->active = 1;
    slot->pid = (int) pid;
    slot->stdin_fd = stdin_pipe[1];
    slot->callback = (*env)->NewGlobalRef(env, callback);
    slot->on_output = on_output;
    slot->on_exit = on_exit;
    slot->out_started = 0;
    slot->err_started = 0;

    /* 读线程启动失败的兜底回收 */
#define MC_PROCESS_FAIL_SPAWN()                                                        \
    do {                                                                               \
        close(stdout_pipe[0]);                                                         \
        close(stderr_pipe[0]);                                                         \
        close(stdin_pipe[1]);                                                          \
        slot->stdin_fd = -1;                                                           \
        kill(-(pid_t) pid, SIGKILL);                                                   \
        waitpid(pid, NULL, 0);                                                         \
        (*env)->DeleteGlobalRef(env, slot->callback);                                  \
        slot->callback = NULL;                                                         \
        slot->active = 0;                                                              \
        slot->pid = 0;                                                                 \
        free_argv(argv, argc);                                                         \
        free(workdir);                                                                 \
        free_envp(envp, envp_needs_free);                                              \
        pthread_mutex_unlock(&g_process_lock);                                         \
        return -1;                                                                     \
    } while (0)

    mc_reader_task_t *out_task = (mc_reader_task_t *) malloc(sizeof(mc_reader_task_t));
    mc_reader_task_t *err_task = (mc_reader_task_t *) malloc(sizeof(mc_reader_task_t));
    if (out_task == NULL || err_task == NULL) {
        free(out_task);
        free(err_task);
        MC_PROCESS_FAIL_SPAWN();
    }
    out_task->proc = slot; out_task->stream = 1; out_task->fd = stdout_pipe[0];
    err_task->proc = slot; err_task->stream = 2; err_task->fd = stderr_pipe[0];

    if (pthread_create(&slot->out_thread, NULL, reader_thread_main, out_task) != 0) {
        free(out_task);
        free(err_task);
        MC_PROCESS_FAIL_SPAWN();
    }
    slot->out_started = 1;

    int err_ok = 1;
    if (pthread_create(&slot->err_thread, NULL, reader_thread_main, err_task) != 0) {
        /* stderr 线程失败不致命：关闭读端，仍由 wait 线程收尾 */
        free(err_task);
        close(stderr_pipe[0]);
        err_ok = 0;
    } else {
        slot->err_started = 1;
    }
    (void) err_ok;

    if (pthread_create(&slot->wait_thread, NULL, process_wait_thread, slot) != 0) {
        MC_LOGE("wait 线程创建失败");
        MC_PROCESS_FAIL_SPAWN();
    }
    pthread_detach(slot->wait_thread);
#undef MC_PROCESS_FAIL_SPAWN

    pthread_mutex_unlock(&g_process_lock);

    MC_LOGI("进程 %d 已启动: %s", (int) pid, argv[0]);
    free_argv(argv, argc);
    free(workdir);
    free_envp(envp, envp_needs_free);
    return (jint) pid;
}

/*
 * writeStdin(pid, data) -> bytes written / -1
 */
JNIEXPORT jint JNICALL
Java_com_mobilecoder_ide_core_nativebridge_CliNative_writeStdin(
        JNIEnv *env, jobject thiz, jint pid, jbyteArray data) {
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

    pthread_mutex_lock(&g_process_lock);
    int fd = -1;
    for (int i = 0; i < MC_MAX_PROCESSES; i++) {
        if (g_processes[i].active && g_processes[i].pid == pid) {
            fd = g_processes[i].stdin_fd;
            break;
        }
    }
    pthread_mutex_unlock(&g_process_lock);

    int written = -1;
    if (fd >= 0) {
        ssize_t n = write(fd, bytes, (size_t) len);
        written = n >= 0 ? (int) n : -1;
    }
    (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);
    return written;
}

/*
 * killProcess(pid, signal) -> 0 / -1（作用于整个进程组）
 */
JNIEXPORT jint JNICALL
Java_com_mobilecoder_ide_core_nativebridge_CliNative_killProcess(
        JNIEnv *env, jobject thiz, jint pid, jint signal) {
    (void) env;
    (void) thiz;
    if (pid <= 0) {
        return -1;
    }
    if (kill(-(pid_t) pid, (int) signal) == 0) {
        return 0;
    }
    return kill((pid_t) pid, (int) signal) == 0 ? 0 : -1;
}
