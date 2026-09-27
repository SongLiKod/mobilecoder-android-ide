package com.mobilecoder.ide.core.nativebridge;

/**
 * SSH 连接引擎 JNI 绑定（实现见 core_native/src/main/cpp/ssh_jni.c，libssh2 静态编译）。
 */
public final class SshNative {

    static {
        System.loadLibrary("mobilecoder");
    }

    private SshNative() {
    }

    /** 初始化 libssh2（引用计数），成功 true。 */
    public static native boolean runtimeInit();

    /** 释放 libssh2（引用计数）。 */
    public static native void shutdown();

    /**
     * 测试 SSH 连通性：TCP → 握手 → 主机密钥指纹 → 公钥认证。
     *
     * @param host       主机名
     * @param port       端口（<=0 取 22）
     * @param username   用户名
     * @param privateKey PEM 私钥（可空：仅测连通性）
     * @param passphrase 私钥口令（可空）
     * @param timeoutMs  超时毫秒（<=0 取 10000）
     * @return 帧：code \x01 message \x01 fingerprint \x01 hostKeyType
     *         code: 0 连接+认证成功 / 1 连接成功但认证失败 / -1 连接失败
     */
    public static native String testConnection(
            String host, int port, String username,
            String privateKey, String passphrase, int timeoutMs);
}
