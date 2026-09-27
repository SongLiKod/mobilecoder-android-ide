package com.mobilecoder.ide.feature.build

import android.content.Context
import com.mobilecoder.ide.core.nativebridge.CliCallback
import com.mobilecoder.ide.core.nativebridge.CliNative
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 终端工具安装（`opencode tools install`），复用构建环境在线下载器。
 *
 * 流程：
 *  1. Node.js 未就绪 → [EnvDownloader.install] 在线下载并生成 `files/bin` 的 node/npm/npx shims；
 *  2. 写 `files/.npmrc`：`prefix=files` → `npm i -g` 的全局 bin 落在 `files/bin`（PATH 已包含）；
 *  3. `npm i -g opencode-ai @anthropic-ai/claude-code`（国内镜像源时附加 npmmirror registry）；
 *  4. 修正 npm 生成脚本的 shebang（Android 无 `/bin/sh` 与 `/usr/bin/env`）并补执行位。
 *
 * 取消（Ctrl+C / 面板停止）通过 [CompletableDeferred] 取消挂起实现，并在 finally 中
 * 杀掉仍在运行的 npm 子进程，不留孤儿进程。
 */
object ToolInstaller {

    /** 可安装的 AI CLI：npm 包名 → 可执行文件名 → 说明。 */
    data class Tool(val pkg: String, val bin: String, val summary: String)

    val TOOLS = listOf(
        Tool("opencode-ai", "opencode", "OpenCode AI 编程代理"),
        Tool("@anthropic-ai/claude-code", "claude", "Claude Code CLI"),
    )

    /** Node.js 是否已就绪（`files/sdk/node/bin/node`）。 */
    fun nodeReady(context: Context): Boolean =
        File(BuildEnvironment.sdkDir(context), "node/bin/node").exists()

    /** 工具是否已安装（`files/bin/<bin>`）。 */
    fun installed(context: Context, tool: Tool): Boolean =
        File(BuildEnvironment.binDir(context), tool.bin).exists()

    /** 状态明细（`opencode tools` 无参输出）。 */
    fun statusLines(context: Context): List<String> {
        val files = context.filesDir
        val node = File(BuildEnvironment.sdkDir(context), "node/bin/node")
        return buildList {
            add("—— 终端工具 ——")
            add("  Node.js   ${if (node.exists()) "就绪 ${node.absolutePath}" else "未安装（`opencode tools install` 在线下载）"}")
            add("  npm prefix  ${files.absolutePath}（-g 全局包 bin → files/bin）")
            TOOLS.forEach { tool ->
                val state = if (installed(context, tool)) "已安装" else "未安装"
                add("  ${tool.bin.padEnd(9)} $state  ${tool.pkg} — ${tool.summary}")
            }
            add("安装：opencode tools install（静默下载安装，完成即可用）")
            add("使用：安装后在终端直接运行 " + TOOLS.joinToString(" / ") { "`${it.bin}`" })
        }
    }

    /**
     * 完整安装流程（幂等：已就绪/已安装的步骤自动跳过）。
     *
     * @param onStage 进度阶段文案（下载器回调）
     * @return 0 成功
     */
    suspend fun install(
        context: Context,
        source: EnvSource,
        emit: (String) -> Unit,
        onStage: (String) -> Unit = { },
    ): Int = withContext(Dispatchers.IO) {
        emit("—— 工具安装（${source.title}） ——")

        // 1) Node.js（复用构建环境下载器：官方源 / 国内镜像 + 自动回退）
        if (!nodeReady(context)) {
            emit("Node.js 未安装 → 在线下载 Node v20.18.0 …")
            val path = EnvDownloader.install(
                context,
                EnvKind.NODE,
                source,
                onStage = { stage -> onStage(stage); emit(stage) },
            )
            emit("Node.js 已安装：$path")
        } else {
            emit("Node.js 已就绪：${File(BuildEnvironment.sdkDir(context), "node").absolutePath}")
        }

        // 2) npm 全局 prefix → files（bin 落入 files/bin）
        writeNpmPrefix(context)
        emit("npm prefix → ${context.filesDir.absolutePath}")

        // 3) npm i -g（全部已装则跳过）
        val packages = TOOLS.map { it.pkg }
        if (TOOLS.all { installed(context, it) }) {
            emit("AI CLI 已全部安装，跳过 npm 安装")
        } else {
            emit("npm i -g ${packages.joinToString(" ")} …")
            val code = runNpm(
                context,
                buildList {
                    add("install")
                    add("-g")
                    addAll(packages)
                    add("--no-fund")
                    add("--no-audit")
                    if (source == EnvSource.MIRROR) add("--registry=https://registry.npmmirror.com")
                },
                emit,
            )
            if (code != 0) {
                emit("[ERROR] npm 安装失败（退出码 $code）")
                return@withContext code
            }
        }

        // 4) shebang 修正 + 执行位
        val fixed = fixBinScripts(context)
        if (fixed > 0) emit("已修正 $fixed 个启动脚本（shebang 适配 Android）")

        // 5) 结果与用法
        TOOLS.forEach { tool ->
            emit((if (installed(context, tool)) "✅ " else "❌ ") + "${tool.bin} — ${tool.summary}")
        }
        val missing = TOOLS.filter { !installed(context, it) }
        if (missing.isEmpty()) {
            emit("完成：在终端输入 " + TOOLS.joinToString(" / ") { "`${it.bin}`" } + " 即可使用")
            0
        } else {
            emit("[ERROR] 未安装：${missing.joinToString { it.pkg }}，可重试 `opencode tools install`")
            1
        }
    }

    /**
     * 以 `files/bin/npm` 执行（npm shim 内部用 node 直跑 npm-cli.js，不依赖 shebang）。
     * 输出逐行转发到 [emit]；取消时杀死子进程。
     */
    private suspend fun runNpm(
        context: Context,
        args: List<String>,
        emit: (String) -> Unit,
    ): Int {
        val npm = File(BuildEnvironment.binDir(context), "npm")
        if (!npm.exists()) {
            emit("[ERROR] 未找到 npm（Node.js 安装异常）")
            return 1
        }
        runCatching { npm.setExecutable(true, false) }
        val files = context.filesDir
        val env = arrayOf(
            "HOME=${files.absolutePath}",
            "TMPDIR=${BuildEnvironment.tmpDir(context).absolutePath}",
            "PATH=${BuildEnvironment.binDir(context).absolutePath}:/system/bin:/system/xbin:/vendor/bin",
            "LANG=C.UTF-8",
            "SHELL=/system/bin/sh",
        )
        val exit = CompletableDeferred<Int>()
        val ansi = Regex("\\u001b\\[[0-9;?]*[A-Za-z]")
        val argv = arrayOf(npm.absolutePath) + args.toTypedArray()
        val pid = CliNative.exec(argv, files.absolutePath, env, object : CliCallback {
            override fun onOutput(pid: Int, stream: Int, data: ByteArray?) {
                if (data == null) return
                String(data, Charsets.UTF_8)
                    .replace(ansi, "")
                    .split('\n')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .forEach { emit(it) }
            }

            override fun onExit(pid: Int, code: Int) {
                exit.complete(code)
            }
        })
        if (pid < 0) {
            emit("[ERROR] 无法启动 npm：并发进程数已达上限")
            return 1
        }
        return try {
            exit.await()
        } finally {
            // Ctrl+C / 面板停止 → 杀掉仍在运行的 npm 子进程，不留孤儿
            if (!exit.isCompleted) runCatching { CliNative.killProcess(pid, 15) }
        }
    }

    /**
     * 写 `files/.npmrc`：全局 prefix 指向 files（bin → `files/bin`），
     * 并关闭 fund/audit 噪声提示。HOME=files，npm 自动读取该文件。
     */
    private fun writeNpmPrefix(context: Context) {
        runCatching {
            val rc = File(context.filesDir, ".npmrc")
            val old = if (rc.exists()) rc.readText() else ""
            val managed = { line: String ->
                line.startsWith("prefix=") || line.startsWith("fund=") || line.startsWith("audit=")
            }
            val kept = old.lines().filter { it.isNotBlank() && !managed(it) }
            val lines = kept + listOf(
                "prefix=${context.filesDir.absolutePath}",
                "fund=false",
                "audit=false",
            )
            rc.writeText(lines.joinToString("\n") + "\n")
        }
    }

    /**
     * 修正 `files/bin` 下启动脚本的 shebang：Android 没有 `/bin/sh` 与 `/usr/bin/env`。
     * npm 生成的 POSIX shim 以 `#!/bin/sh` 开头，部分包的 bin 直接是 `#!/usr/bin/env node`。
     *
     * @return 修正的文件数
     */
    private fun fixBinScripts(context: Context): Int {
        val bin = BuildEnvironment.binDir(context)
        val node = File(BuildEnvironment.sdkDir(context), "node/bin/node").absolutePath
        var fixed = 0
        bin.listFiles()?.forEach { file ->
            if (!file.isFile) return@forEach
            val bytes = runCatching { file.readBytes() }.getOrNull() ?: return@forEach
            var nl = -1
            for (i in bytes.indices) {
                if (bytes[i] == 10.toByte()) {
                    nl = i
                    break
                }
            }
            if (nl <= 0) return@forEach
            val firstLine = String(bytes, 0, nl, Charsets.UTF_8)
            val replacement = when {
                firstLine.startsWith("#!/bin/sh") -> "#!/system/bin/sh"
                firstLine.startsWith("#!/usr/bin/env node") -> "#!$node"
                firstLine.startsWith("#!/usr/bin/node") -> "#!$node"
                else -> null
            }
            if (replacement != null) {
                val rest = String(bytes, nl + 1, bytes.size - nl - 1, Charsets.UTF_8)
                runCatching {
                    file.writeText(replacement + "\n" + rest)
                    fixed++
                }
            }
            runCatching { file.setExecutable(true, false) }
        }
        return fixed
    }
}
