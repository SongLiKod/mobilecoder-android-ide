/*
 * MobileCoder —— JNI 公共工具实现
 */
#include "jni_util.h"

JavaVM *g_vm = NULL;

jint mc_jni_onload(JavaVM *vm, void *reserved) {
    (void) reserved;
    g_vm = vm;
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
