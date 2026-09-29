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

        val path = buildString {
            append(bin.absolutePath)
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

    /**
     * glibc 运行时（`MOBILECODER_GLIBC` + `LD_LIBRARY_PATH`）注入进程环境，
     * 由终端 PTY 与 CLI 子进程继承；未安装时清空，避免残留一个不存在的目录。
     *
     * Android 没有 `/lib`，glibc 程序全靠 `LD_LIBRARY_PATH` 找到 `libc.so.6`。
     *
     * 两套 ABI 的钩子路径（[glibcHook] / [bionicHook]）也一起下发：
     * `mc_exec_hook.c` 在 exec 前按**子目标**的 loader 二选一写进 `LD_PRELOAD`，
     * 异架构的 .so 一旦被装载，`ld.so` 会报 `cannot be preloaded`。
     * 这里**不**设 `LD_PRELOAD` 本身——只有直接目标是 glibc 的场景
     * （工具安装 / 终端）才需要钩子，进程级注入会波及所有子进程。
     */
    fun setGlibcEnv(
        context: Context,
        root: String?,
        lib: String?,
        glibcHook: String?,
        bionicHook: String?,
    ) {
        runCatching {
            TerminalNative.setEnv("MOBILECODER_GLIBC", root.orEmpty())
            TerminalNative.setEnv("MOBILECODER_GLIBC_LIB", lib.orEmpty())
            TerminalNative.setEnv("MOBILECODER_GLIBC_HOOK", glibcHook.orEmpty())
            TerminalNative.setEnv("MOBILECODER_BIONIC_HOOK", bionicHook.orEmpty())
            TerminalNative.setEnv("LD_LIBRARY_PATH", lib.orEmpty())
        }
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
