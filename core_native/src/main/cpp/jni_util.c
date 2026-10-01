/*
 * MobileCoder —— JNI 公共工具实现
 */
#include "jni_util.h"

JavaVM *g_vm = NULL;

/*
 * mbedTLS 平台熵源改指 /dev/urandom。必须在任何 libgit2/libssh2 初始化前
 * 生效，故放在 JNI_OnLoad（共享库一加载即执行，早于一切 native 调用）。
 *
 * 背景：mbedTLS 的 getrandom 快速路径（library/entropy_poll.c）只在 glibc
 * 下启用，Android/bionic 走 fopen(mbedtls_platform_dev_random) 回退，其
 * 默认值是 "/dev/random"——内核熵池耗尽时该读取会阻塞（本机实测
 * entropy_avail=31 时 dd 读 64B 超时 5s 不返回），git/ssh 引擎初始化随之
 * 挂起数十秒到数分钟。/dev/urandom 与 getrandom 同源：CRNG 就绪后读取
 * 立即返回，SecureRandom 等系统组件同样取自它，不损失熵质量。
 */
extern const char *mbedtls_platform_dev_random;

jint mc_jni_onload(JavaVM *vm, void *reserved) {
    (void) reserved;
    g_vm = vm;
    mbedtls_platform_dev_random = "/dev/urandom";
    return JNI_VERSION_1_6;
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    return mc_jni_onload(vm, reserved);
}

JNIEnv *mc_env(void) {
    JNIEnv *env = NULL;
    if (g_vm == NULL) {
        return NULL;
    }
    jint rc = (*g_vm)->GetEnv(g_vm, (void **) &env, JNI_VERSION_1_6);
    if (rc == JNI_OK) {
        return env;
    }
    if (rc == JNI_EDETACHED) {
#ifdef __cplusplus
        if (g_vm->AttachCurrentThread(&env, NULL) != JNI_OK) {
            return NULL;
        }
#else
        if ((*g_vm)->AttachCurrentThread(g_vm, &env, NULL) != JNI_OK) {
            return NULL;
        }
#endif
        return env;
    }
    return NULL;
}

char *mc_jstring_to_cstr(JNIEnv *env, jstring value) {
    if (env == NULL || value == NULL) {
        return NULL;
    }
    const char *utf = (*env)->GetStringUTFChars(env, value, NULL);
    if (utf == NULL) {
        return NULL;
    }
    char *copy = strdup(utf);
    (*env)->ReleaseStringUTFChars(env, value, utf);
    return copy;
}

jstring mc_cstr_to_jstring(JNIEnv *env, const char *value) {
    if (env == NULL) {
        return NULL;
    }
    if (value == NULL) {
        return (*env)->NewStringUTF(env, "");
    }
    return (*env)->NewStringUTF(env, value);
}

jbyteArray mc_new_byte_array(JNIEnv *env, const void *data, size_t len) {
    jbyteArray array = (*env)->NewByteArray(env, (jsize) len);
    if (array != NULL && data != NULL && len > 0) {
        (*env)->SetByteArrayRegion(env, array, 0, (jsize) len, (const jbyte *) data);
    }
    return array;
}
