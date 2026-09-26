/*
 * MobileCoder —— JNI 公共工具（线程附着、字符串转换、日志）
 */
#ifndef MOBILECODER_JNI_UTIL_H
#define MOBILECODER_JNI_UTIL_H

#include <jni.h>
#include <android/log.h>
#include <stdlib.h>
#include <string.h>

#ifdef __cplusplus
extern "C" {
#endif

#define MC_LOG_TAG "MobileCoderNative"
#define MC_LOGI(...) __android_log_print(ANDROID_LOG_INFO, MC_LOG_TAG, __VA_ARGS__)
#define MC_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, MC_LOG_TAG, __VA_ARGS__)

extern JavaVM *g_vm;

/* JNI_OnLoad 中设置；任意共享库入口均可用 */
jint mc_jni_onload(JavaVM *vm, void *reserved);

/* 当前线程附着到 JVM（已附着则直接返回 env），失败返回 NULL */
JNIEnv *mc_env(void);

/* jstring -> 堆上 UTF-8（调用方 free），失败返回 NULL */
char *mc_jstring_to_cstr(JNIEnv *env, jstring value);

/* 堆上 UTF-8 -> jstring（调用方释放 jstring 由 GC 处理） */
jstring mc_cstr_to_jstring(JNIEnv *env, const char *value);

/* 线程内创建 jbyteArray 并拷贝数据 */
jbyteArray mc_new_byte_array(JNIEnv *env, const void *data, size_t len);

#ifdef __cplusplus
}
#endif

#endif /* MOBILECODER_JNI_UTIL_H */
