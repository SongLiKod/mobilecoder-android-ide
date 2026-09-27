package com.mobilecoder.ide.core.nativebridge;

/**
 * PTY 终端回调（native → Kotlin，方法签名与 terminal_jni.c 中的 GetMethodID 严格一致）。
 *
 * 注意：方法名与签名不可修改，否则 JNI 查找失败。
 */
public interface TerminalCallback {

    /** 收到终端输出字节流（读线程回调）。 */
    void onData(int id, byte[] data);

    /** 会话结束（子进程退出），code 为退出码：常规退出 0..255，信号退出 128+signo。 */
    void onExit(int id, int code);
}
