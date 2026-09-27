package com.mobilecoder.ide.core.nativebridge;

/**
 * Git 网络/传输进度回调（native → Kotlin，签名与 git_jni.c 中的 GetMethodID 严格一致）。
 *
 * stage 取值：clone / fetch / push / transfer / remote 等；
 * current/total 为 -1 表示该阶段没有可量化的进度。
 */
public interface GitProgressCallback {

    void onProgress(String stage, String message, int current, int total);
}
