package com.mobilecoder.ide.core.nativebridge;

/**
 * 外部进程输出回调（native → Kotlin，签名与 cli_jni.c 中的 GetMethodID 严格一致）。
 */
public interface CliCallback {

    /** 进程输出。stream: 1=stdout，2=stderr。 */
    void onOutput(int pid, int stream, byte[] data);

    /** 进程退出，code 为退出码。 */
    void onExit(int pid, int code);
}
