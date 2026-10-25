package com.mobilecoder.ide.core.common.linux

import android.content.Context
import com.mobilecoder.ide.core.nativebridge.CliCallback
import com.mobilecoder.ide.core.nativebridge.CliNative
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * proot 子进程执行通道：跑**一条命令**的通用原语。
 *
 * 终端、构建、预览 dev server、AI 命令、编辑器格式化此前各自拼了一遍
 * `Proot.wrap + CliNative.exec + 完整 env`，本类把这套配方收敛成一处：
 *
 *  - [baseEnv]   组装子进程环境变量（`CliNative.exec` 的 envArr 会**整体替换**
 *    继承环境，因此 HOME / PATH / LANG 等必须一次给全，末尾追加 [Proot.envEntries]）；
 *  - [spawn]     起一个后台子进程（长驻型：dev server 用它自己收流 + 自己杀）；
 *  - [run]       起进程并**等到结束**，捕获 stdout / stderr、支持超时与截断
 *    （AI 命令、格式化等一次性命令用它）。
 *
 * Linux 环境未就绪时 [Proot.wrap] 原样返回，命令按 bionic 路径直跑——
 * 调用方负责在此之前判断 [Proot.isReady] 或提供回退提示。
 */
object ProotRunner {

    /** 单条流的内存捕获上限（超限截断，已捕获部分不丢）。 */
    const val DEFAULT_MAX_CHARS = 200_000

    /** 超时退出码（对齐 `timeout(1)` 的 124）。 */
    const val EXIT_TIMEOUT = 124

    /** 无法启动（并发进程数达上限 / 系统拒绝 fork）。 */
    const val EXIT_SPAWN_FAILED = -1

    /** 超时阈值：一次性命令的常用默认值。 */
    const val DEFAULT_TIMEOUT_MS = 60_000L

    /**
     * 子进程环境变量数组。
     *
     * @param path    PATH 值；null 用 [Proot.pathFor]（files/bin 优先 + guest 标准路径）
     * @param tmpDir  TMPDIR；null 用 `cache/tmp`（Android 无 /tmp，proot 依赖它）
     * @param extra   附加变量（JAVA_HOME / NODE_ENV / GRADLE_OPTS …），同名覆盖基础项
     */
    fun baseEnv(
        context: Context,
        path: String? = null,
        tmpDir: File? = null,
        extra: Map<String, String> = emptyMap(),
    ): Array<String> {
        val files = context.filesDir
        val tmp = tmpDir ?: File(context.cacheDir, "tmp")
        runCatching { tmp.mkdirs() }
        val entries = buildList {
            add("HOME=${files.absolutePath}")
            add("TMPDIR=${tmp.absolutePath}")
            add("PATH=${path ?: Proot.pathFor(File(files, "bin").absolutePath)}")
            add("LANG=C.UTF-8")
            add("LC_ALL=C.UTF-8")
            add("SHELL=/system/bin/sh")
            extra.forEach { (key, value) -> add("$key=$value") }
        }
        return entries.toTypedArray<String>() + Proot.envEntries(context)
    }

    /**
     * 起一个 `/bin/sh -c [command]` 子进程（Linux 就绪即包进 proot）。
     *
     * 输出按**原始块**回调（调用方自行切行 / 累积），退出回调带上 pid
     * 以便长驻型调用方比对自己记录的进程。
     *
     * @return pid（>0）；-1 表示无法启动
     */
    fun spawn(
        context: Context,
        command: String,
        cwd: File,
        env: Array<String>,
        onChunk: (stream: Int, text: String) -> Unit,
        onExit: (pid: Int, code: Int) -> Unit,
    ): Int {
        val argv = Proot.wrap(context, arrayOf("/bin/sh", "-c", command), cwd.absolutePath)
        val callback = object : CliCallback {
            override fun onOutput(pid: Int, stream: Int, data: ByteArray?) {
                if (data == null || data.isEmpty()) return
                onChunk(stream, String(data, Charsets.UTF_8))
            }

            override fun onExit(pid: Int, code: Int) {
                onExit(pid, code)
            }
        }
        return CliNative.exec(argv, cwd.absolutePath, env, callback)
    }

    /**
     * 跑一条命令到结束，捕获 stdout / stderr。
     *
     * 超时（[timeoutMs] > 0 且到期）或协程取消时 SIGKILL 整个进程组，绝不留孤儿；
     * 返回 [ShellResult.started] = false 表示根本没起进程（并发上限 / 目录不存在）。
     */
    suspend fun run(
        context: Context,
        command: String,
        cwd: File,
        env: Array<String>,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        maxChars: Int = DEFAULT_MAX_CHARS,
        onChunk: ((stream: Int, text: String) -> Unit)? = null,
    ): ShellResult {
        if (!cwd.isDirectory) {
            return ShellResult(
                exitCode = EXIT_SPAWN_FAILED,
                stdout = "",
                stderr = "工作目录不存在：${cwd.absolutePath}",
                timedOut = false,
                started = false,
            )
        }
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val exit = CompletableDeferred<Int>()
        val pid = spawn(
            context = context,
            command = command,
            cwd = cwd,
            env = env,
            onChunk = { stream, text ->
                onChunk?.invoke(stream, text)
                val buffer = if (stream == 2) stderr else stdout
                synchronized(buffer) {
                    if (buffer.length < maxChars) {
                        buffer.append(text, 0, text.length.coerceAtMost(maxChars - buffer.length))
                    }
                }
            },
            onExit = { _, code -> exit.complete(code) },
        )
        if (pid < 0) {
            return ShellResult(
                exitCode = EXIT_SPAWN_FAILED,
                stdout = "",
                stderr = "无法启动命令进程：并发进程数已达上限或系统拒绝 fork，请稍后重试",
                timedOut = false,
                started = false,
            )
        }
        try {
            val code = if (timeoutMs > 0) {
                withTimeoutOrNull(timeoutMs) { exit.await() }
            } else {
                exit.await()
            }
            val out = synchronized(stdout) { stdout.toString() }
            val err = synchronized(stderr) { stderr.toString() }
            return if (code == null) {
                ShellResult(EXIT_TIMEOUT, out, err, timedOut = true, started = true)
            } else {
                ShellResult(code, out, err, timedOut = false, started = true)
            }
        } finally {
            // 超时或协程被取消 → 子进程与 proot 一并回收
            if (!exit.isCompleted) runCatching { CliNative.killProcess(pid, 9) }
        }
    }
}

/**
 * 一条命令的执行结果（[ProotRunner.run]）。
 *
 * @param exitCode 退出码（超时 124，无法启动 -1）
 * @param stdout   标准输出
 * @param stderr   标准错误
 * @param timedOut 是否超时被终止
 * @param started  是否真的起过进程（false 时 stdout/stderr 为失败原因）
 */
data class ShellResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
    val started: Boolean,
) {
    /** 尾部若干行（失败提示用，过滤空行）。 */
    fun tail(lines: Int = 6): String =
        (if (stderr.isNotBlank()) stderr else stdout)
            .lineSequence()
            .filter { it.isNotBlank() }
            .toList()
            .takeLast(lines)
            .joinToString("\n")
}
