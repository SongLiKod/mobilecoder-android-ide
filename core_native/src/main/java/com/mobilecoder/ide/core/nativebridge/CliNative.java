package com.mobilecoder.ide.core.nativebridge;

/**
 * CLI / 外部进程执行引擎 JNI 绑定（实现见 core_native/src/main/cpp/cli_jni.c）。
 *
 * 用于 feature_cli（OpenCode 命令）与 feature_build（Gradle 编译）的子进程执行，
 * stdout/stderr 实时回调，最多 8 个并发进程（MC_MAX_PROCESSES）。
 */
public final class CliNative {

    static {
        System.loadLibrary("mobilecoder");
    }

    private CliNative() {
    }

    /**
     * fork + exec 执行命令。
     *
     * @param command  argv，command[0] 必须是可执行文件（支持 PATH 查找）
     * @param cwd      工作目录
     * @param envArr   环境变量（"KEY=VALUE"），null 表示继承当前进程环境
     * @param callback 输出回调
     * @return pid（>0），失败 -1
     */
    public static native int exec(String[] command, String cwd, String[] envArr, CliCallback callback);

    /** 向子进程 stdin 写入，返回写入字节数，失败 -1。 */
    public static native int writeStdin(int pid, byte[] data);

    /** 终止进程组：signal 通常为 15(SIGTERM)/9(SIGKILL)，0 成功 / -1 失败。 */
    public static native int killProcess(int pid, int signal);
}
