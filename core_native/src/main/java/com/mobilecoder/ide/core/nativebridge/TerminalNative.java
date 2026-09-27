package com.mobilecoder.ide.core.nativebridge;

/**
 * 终端 PTY 引擎 JNI 绑定（实现见 core_native/src/main/cpp/terminal_jni.c）。
 *
 * 说明：
 *  - 使用 Java 声明 native 方法，保证 JNI 符号（Java_com_mobilecoder_ide_core_nativebridge_*）
 *    与静态/实例属性与 C 侧完全一致；
 *  - 最多 8 个并发会话（MC_MAX_TERMINALS）。
 */
public final class TerminalNative {

    static {
        System.loadLibrary("mobilecoder");
    }

    private TerminalNative() {
    }

    /**
     * 创建 PTY 会话并拉起 /system/bin/sh。
     *
     * @param cwd      工作目录（可为 null，表示继承）
     * @param cols     列数
     * @param rows     行数
     * @param callback 输出/退出回调
     * @return 会话 id（>=1），失败返回 -1
     */
    public static native int create(String cwd, int cols, int rows, TerminalCallback callback);

    /** 向终端写入数据（键盘输入），返回写入字节数，失败 -1。 */
    public static native int write(int id, byte[] data);

    /** 调整窗口尺寸（TIOCSWINSZ），0 成功 / -1 失败。 */
    public static native int resize(int id, int cols, int rows);

    /** 关闭会话（SIGHUP 整个会话），0 已请求 / -1 无此会话。 */
    public static native int destroy(int id);

    /**
     * 设置进程环境变量（子进程 fork 时继承），用于 HOME / PATH / ANDROID_HOME / JAVA_HOME 等。
     *
     * @return 0 成功 / -1 失败
     */
    public static native int setEnv(String key, String value);
}
