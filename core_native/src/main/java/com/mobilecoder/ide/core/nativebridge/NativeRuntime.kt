package com.mobilecoder.ide.core.nativebridge

import android.content.Context
import java.io.File

/**
 * Native 层运行时初始化（进程内只做一次，幂等）。
 *
 * 覆盖：
 *  - libgit2 / libssh2 初始化与 CA 证书路径（assets → cache，供 mbedTLS 校验 HTTPS 仓库）
 *  - 进程环境变量（HOME / PATH / ANDROID_HOME / JAVA_HOME / GRADLE_USER_HOME 等），
 *    由终端 PTY 与 CLI 子进程继承（TECH.md 4.2 / 4.3）
 */
object NativeRuntime {

    @Volatile
    private var gitReady = false

    @Volatile
    private var sshReady = false

    @Volatile
    private var envReady = false

    /** 初始化 Git 引擎并安装 CA 证书，成功返回 true。 */
    @Synchronized
    fun ensureGitReady(context: Context): Boolean {
        if (gitReady) return true
        val ok = runCatching {
            if (!GitNative.runtimeInit()) return@runCatching false
            val cert = extractCaBundle(context)
            if (cert != null) GitNative.setCertificateFile(cert.absolutePath)
            true
        }.getOrDefault(false)
        gitReady = ok
        return ok
    }

    /** 初始化 SSH 引擎（libssh2），成功返回 true。 */
    @Synchronized
    fun ensureSshReady(): Boolean {
        if (sshReady) return true
        sshReady = runCatching { SshNative.runtimeInit() }.getOrDefault(false)
        return sshReady
    }

    /**
     * 向进程注入子进程环境变量。
     * 必须在创建终端会话 / 执行 CLI 命令之前调用一次。
     */
    @Synchronized
    fun ensureProcessEnvironment(context: Context): Boolean {
        if (envReady) return true
        val files = context.filesDir
        val cache = context.cacheDir
        val sdk = File(files, "sdk")
        val bin = File(files, "bin")
        listOf(bin, sdk).forEach { if (!it.exists()) it.mkdirs() }

        // PATH：files/bin（node / npm 入口）→ guest 标准路径（rootfs 的 /usr/bin 等，
        // Linux 环境装好后由 proot 看见）→ bionic 的 /system/bin（rootfs 未装时兜底，
        // 不存在的目录在 PATH 搜索中自动跳过）
        val path = buildString {
            append(bin.absolutePath)
            append(":/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
            append(":/system/bin:/system/xbin:/vendor/bin")
        }
        val result = runCatching {
            TerminalNative.setEnv("HOME", files.absolutePath)
            TerminalNative.setEnv("TMPDIR", File(cache, "tmp").absolutePath)
            TerminalNative.setEnv("PATH", path)
            TerminalNative.setEnv("ANDROID_HOME", sdk.absolutePath)
            TerminalNative.setEnv("ANDROID_SDK_ROOT", sdk.absolutePath)
            TerminalNative.setEnv("GRADLE_USER_HOME", File(files, ".gradle").absolutePath)
            TerminalNative.setEnv("MOBILECODER_HOME", files.absolutePath)
        }.getOrDefault(-1)
        envReady = true
        return result == 0
    }

    /** JAVA_HOME（用户在「构建环境」中导入 JDK 后由调用方写入）。 */
    fun setJavaHome(context: Context, jdkDir: String?) {
        if (jdkDir.isNullOrBlank()) {
            runCatching { TerminalNative.setEnv("JAVA_HOME", "") }
            return
        }
        runCatching { TerminalNative.setEnv("JAVA_HOME", jdkDir) }
    }

    /** assets/certs/cacert.pem → cache/certs/cacert.pem（返回 null 表示失败）。 */
    private fun extractCaBundle(context: Context): File? = runCatching {
        val target = File(context.cacheDir, "certs/cacert.pem")
        if (target.exists() && target.length() > 0) return target
        target.parentFile?.mkdirs()
        context.assets.open("certs/cacert.pem").use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        target
    }.getOrNull()
}
