package com.mobilecoder.ide.feature.ai

import android.content.Context
import com.mobilecoder.ide.core.common.cli.CliEngine
import com.mobilecoder.ide.core.common.linux.Proot
import com.mobilecoder.ide.core.nativebridge.CliCallback
import com.mobilecoder.ide.core.nativebridge.CliNative
import com.mobilecoder.ide.core.storage.AppStorage
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * `run_command` / `git_*` 工具的执行通道（Issue 6：打通「改代码 → 跑命令 → 看结果」）。
 *
 * 两条路径，按命令首词分流：
 *
 *  1. **进程内命令**（[CliEngine.isInProcessLine] 命中，目前是 `git`）：
 *     直接交给 [CliEngine.runCaptured]——与终端里的 `git …` 共用**同一把引擎锁**
 *     与 [com.mobilecoder.ide.feature.git.GitController] 的仓库句柄，天然互斥、无死锁
 *     （两处的加锁顺序都是「引擎锁 → Git 锁」）；
 *  2. **其余命令**：`/bin/sh -c <command>` 经 [Proot.wrap] 包进 proot 执行，
 *     复用 [CliNative.exec]（与构建页 / 预览页同一条子进程链路），
 *     stdout / stderr 分开捕获，超时或取消时 SIGKILL 整个进程组。
 *
 * 为什么不直接跑 shell：Android 设备上没有 `git` 可执行文件（rootfs 默认也没有），
 * `git status` 交给 shell 只会得到 `command not found`，而进程内 libgit2 实现是有的。
 */
object AiCommandRunner {

    /** 单条流（stdout / stderr）的内存上限：最终截断由 [AiTools] 负责。 */
    private const val MAX_STREAM_CHARS = 200_000

    @Volatile
    private var appContext: Context? = null

    /** 由 [com.mobilecoder.ide.feature.ai.AiController.init] 调用（进程级一次）。 */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** 执行环境是否就绪（未初始化时 [runShell] 会直接失败并提示）。 */
    val ready: Boolean get() = appContext != null

    /** Linux（proot + rootfs）是否就绪：未就绪时 glibc 程序无法运行，需要提示模型。 */
    fun linuxReady(): Boolean = appContext?.let { runCatching { Proot.isReady(it) }.getOrDefault(false) } == true

    /**
     * 执行一条命令。
     *
     * @param command  完整命令行
     * @param cwd      工作目录（必须已由调用方约束在项目根内）
     * @param timeoutMs 超时（毫秒），>0 生效
     */
    suspend fun run(command: String, cwd: File, timeoutMs: Long): AiCommandOutput =
        if (CliEngine.isInProcessLine(command)) {
            runInProcess(command, cwd, timeoutMs)
        } else {
            runShell(command, cwd, timeoutMs)
        }

    // ------------------------------------------------------------------
    // 进程内命令（git）：与终端共用 CliEngine 锁
    // ------------------------------------------------------------------

    suspend fun runInProcess(command: String, cwd: File, timeoutMs: Long): AiCommandOutput {
        val capture = CliEngine.runCaptured(command, cwd, timeoutMs, CliEngine.DEFAULT_CAPTURE_CHARS)
        // 进程内命令没有真正的 stdout/stderr 之分：非 0 退出时输出即错误信息
        return if (capture.exitCode == 0) {
            AiCommandOutput(
                exitCode = 0,
                stdout = capture.output,
                stderr = "",
                timedOut = capture.timedOut,
                inProcess = true,
            )
        } else {
            AiCommandOutput(
                exitCode = capture.exitCode,
                stdout = "",
                stderr = capture.output,
                timedOut = capture.timedOut,
                inProcess = true,
            )
        }
    }

    // ------------------------------------------------------------------
    // shell 命令：proot + CliNative.exec
    // ------------------------------------------------------------------

    private suspend fun runShell(command: String, cwd: File, timeoutMs: Long): AiCommandOutput {
        val context = appContext
            ?: return AiCommandOutput(
                exitCode = -1,
                stdout = "",
                stderr = "命令执行环境未初始化（AI 会话尚未就绪），请稍后重试",
                timedOut = false,
                inProcess = false,
            )
        val workdir = cwd.takeIf { it.isDirectory } ?: return AiCommandOutput(
            exitCode = -1,
            stdout = "",
            stderr = "工作目录不存在：${cwd.absolutePath}",
            timedOut = false,
            inProcess = false,
        )
        val hint = if (linuxReady()) {
            null
        } else {
            "提示：Linux 环境（proot + Ubuntu rootfs）未安装，java/gradle 等 glibc 程序无法运行；" +
                "请到「构建环境」页安装后再试"
        }

        val argv = Proot.wrap(context, arrayOf("/bin/sh", "-c", command), workdir.absolutePath)
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val exit = CompletableDeferred<Int>()
        val callback = object : CliCallback {
            override fun onOutput(pid: Int, stream: Int, data: ByteArray?) {
                if (data == null || data.isEmpty()) return
                val text = String(data, Charsets.UTF_8)
                val buffer = if (stream == 2) stderr else stdout
                synchronized(buffer) {
                    if (buffer.length < MAX_STREAM_CHARS) {
                        buffer.append(text, 0, text.length.coerceAtMost(MAX_STREAM_CHARS - buffer.length))
                    }
                }
            }

            override fun onExit(pid: Int, code: Int) {
                exit.complete(code)
            }
        }

        val env = envArray(context)
        runCatching { File(context.cacheDir, "tmp").mkdirs() }
        var pid = -1
        try {
            pid = CliNative.exec(argv, workdir.absolutePath, env, callback)
            if (pid < 0) {
                return AiCommandOutput(
                    exitCode = -1,
                    stdout = "",
                    stderr = "无法启动命令进程：并发进程数已达上限或系统拒绝 fork，请稍后重试",
                    timedOut = false,
                    inProcess = false,
                    hint = hint,
                )
            }
            val code = if (timeoutMs > 0) withTimeoutOrNull(timeoutMs) { exit.await() } else exit.await()
            val out = synchronized(stdout) { stdout.toString() }
            val err = synchronized(stderr) { stderr.toString() }
            if (code == null) {
                return AiCommandOutput(124, out, err, timedOut = true, inProcess = false, hint = hint)
            }
            return AiCommandOutput(code, out, err, timedOut = false, inProcess = false, hint = hint)
        } finally {
            // 超时或协程被取消（用户点了停止）→ 子进程与 proot 一并回收，绝不留孤儿
            if (pid > 0 && !exit.isCompleted) {
                runCatching { CliNative.killProcess(pid, 9) }
            }
        }
    }

    // ------------------------------------------------------------------
    // 子进程环境（与 BuildRunner.buildEnvArray 同口径；feature_ai 不依赖 feature_build）
    // ------------------------------------------------------------------

    /**
     * 子进程环境变量数组（[CliNative.exec] 的 envArr 会**整体替换**继承环境，
     * 因此这里必须把 PATH / HOME / JAVA_HOME 等一次给全）。
     */
    private suspend fun envArray(context: Context): Array<String> {
        val files = context.filesDir
        val sdk = File(files, "sdk")
        val entries = buildList {
            add("HOME=${files.absolutePath}")
            add("TMPDIR=${File(context.cacheDir, "tmp").absolutePath}")
            add("PATH=${Proot.pathFor(File(files, "bin").absolutePath)}")
            add("LANG=C.UTF-8")
            add("LC_ALL=C.UTF-8")
            add("SHELL=/system/bin/sh")
            resolveJavaHome(context)?.let { add("JAVA_HOME=$it") }
            add("ANDROID_HOME=${sdk.absolutePath}")
            add("ANDROID_SDK_ROOT=${sdk.absolutePath}")
            add("GRADLE_USER_HOME=${File(files, ".gradle").absolutePath}")
            add("MOBILECODER_HOME=${files.absolutePath}")
            add("JAVA_OPTS=-Dfile.encoding=UTF-8")
            add("GRADLE_OPTS=-Dorg.gradle.daemon=false")
            add("NODE_OPTIONS=--max-old-space-size=1536")
        }
        // proot 执行通道所需变量（Linux 环境未就绪时为空）
        return entries.toTypedArray<String>() + Proot.envEntries(context)
    }

    /**
     * JDK 根目录（`./gradlew` 没有 JAVA_HOME 就直接报错）。
     *
     * 与 `feature_build/BuildEnvironment.resolveJdk` 同口径的轻量版：
     * 用户配置 → `files/sdk/jdk` → `files/sdk` 下任意含 `bin/java` 的目录。
     */
    private suspend fun resolveJavaHome(context: Context): String? {
        val configured = runCatching { AppStorage.preferences.jdkPath() }.getOrDefault("")
        if (configured.isNotBlank() && File(configured, "bin/java").exists()) return configured
        val sdk = File(context.filesDir, "sdk")
        val direct = File(sdk, "jdk")
        if (File(direct, "bin/java").exists()) return direct.absolutePath
        return sdk.listFiles()?.firstNotNullOfOrNull { dir ->
            when {
                !dir.isDirectory -> null
                File(dir, "bin/java").exists() -> dir.absolutePath
                else -> dir.listFiles()?.firstOrNull { inner ->
                    inner.isDirectory && File(inner, "bin/java").exists()
                }?.absolutePath
            }
        }
    }
}

/**
 * 一条命令的执行结果。
 *
 * @param exitCode 退出码（超时 124，无法启动 -1）
 * @param stdout   标准输出
 * @param stderr   标准错误（进程内命令的失败输出也归这里）
 * @param timedOut 是否超时被终止
 * @param inProcess 是否走了进程内注册命令（git）
 * @param hint     环境提示（Linux 环境未安装时给出，随结果回传模型）
 */
data class AiCommandOutput(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
    val inProcess: Boolean,
    val hint: String? = null,
)
