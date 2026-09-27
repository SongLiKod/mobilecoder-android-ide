package com.mobilecoder.ide.feature.build

import android.content.Context
import com.mobilecoder.ide.core.nativebridge.CliCallback
import com.mobilecoder.ide.core.nativebridge.CliNative
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 终端软件安装（`apt tools …`）：以 npm registry 为源，源里有的软件都能装。
 *
 * 一键流程（[install] 不带包名，或带包名安装任意软件）：
 *  1. Node.js 未就绪 → [EnvDownloader.install] 在线下载并生成 `files/bin` 的 node/npm/npx 入口；
 *  2. 写 `files/.npmrc`：`prefix=files` → `npm i -g` 的全局 bin 落在 `files/bin`（PATH 已包含）；
 *  3. `npm i -g <包名…>`（国内镜像源时附加 npmmirror registry）；
 *  4. 修正 npm 生成脚本的 shebang（Android 无 `/bin/sh` 与 `/usr/bin/env`）并补执行位——
 *     所以装任意包都走这里，**不要**绕过本类裸用终端 `npm i -g`（装出来跑不起来）；
 *  5. 校验 `files/lib/node_modules/<包>` 落位，输出可用命令。
 *
 * 其余子命令：[uninstall] / [update] / [search] / [listLines]，均以 npm 为后端。
 * 取消（Ctrl+C / 面板停止）通过 [CompletableDeferred] 取消挂起实现，并在 finally 中
 * 杀掉仍在运行的 npm 子进程，不留孤儿进程。
 */
object ToolInstaller {

    /** 一键默认安装的 AI CLI：npm 包名 → 可执行文件名 → 说明。 */
    data class Tool(val pkg: String, val bin: String, val summary: String)

    val TOOLS = listOf(
        Tool("opencode-ai", "opencode", "OpenCode AI 编程代理"),
        Tool("@anthropic-ai/claude-code", "claude", "Claude Code CLI"),
    )

    /** 常用软件简写 → npm 包名（输入完整包名则原样透传）。 */
    internal val ALIASES = mapOf(
        "opencode" to "opencode-ai",
        "claude" to "@anthropic-ai/claude-code",
        "gemini" to "@google/gemini-cli",
        "codex" to "@openai/codex",
        "qwen" to "qwen-code",
    )

    /** 包名/关键字白名单字符（argv 直传，无 shell；主要防旗标注入与空串）。 */
    private const val PKG_CHARS =
        "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789@/._+-"

    /** 合法且不以 `-` 开头（防 `--registry=evil` 这类旗标注入）。 */
    internal fun validToken(s: String): Boolean =
        s.isNotBlank() && !s.startsWith("-") && s.all { it in PKG_CHARS }

    /** 解析安装目标：先查简写表，否则视为 npm 包名；非法返回 null。 */
    internal fun resolvePackage(input: String): String? =
        (ALIASES[input.lowercase()] ?: input).takeIf { validToken(it) }

    /** Node.js 是否已就绪（`files/sdk/node/bin/node`）。 */
    fun nodeReady(context: Context): Boolean =
        File(BuildEnvironment.sdkDir(context), "node/bin/node").exists()

    /** 内置工具是否已安装（`files/bin/<bin>`）。 */
    fun installed(context: Context, tool: Tool): Boolean =
        File(BuildEnvironment.binDir(context), tool.bin).exists()

    /**
     * 已安装的全局软件（扫 `files/lib/node_modules`，含 `@scope` 嵌套）。
     * 返回 `name@version`（版本取自 package.json，读不到则只返回 name）。
     */
    internal fun installedPackages(nodeModulesDir: File): List<String> {
        if (!nodeModulesDir.isDirectory) return emptyList()
        val out = mutableListOf<String>()
        nodeModulesDir.listFiles()?.forEach { entry ->
            if (!entry.isDirectory || entry.name.startsWith(".")) return@forEach
            if (entry.name.startsWith("@")) {
                entry.listFiles()?.filter { it.isDirectory }?.forEach { scoped ->
                    out += readPkgNameVersion(scoped) ?: "${entry.name}/${scoped.name}"
                }
            } else {
                out += readPkgNameVersion(entry) ?: entry.name
            }
        }
        return out.sorted()
    }

    private fun readPkgNameVersion(dir: File): String? {
        val json = runCatching { File(dir, "package.json").readText() }.getOrNull() ?: return null
        val name = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1)
            ?: return null
        val version = Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1)
        return if (version != null) "$name@$version" else name
    }

    /** 状态明细（`apt tools` 无参输出）。 */
    fun statusLines(context: Context): List<String> {
        val files = context.filesDir
        val node = File(BuildEnvironment.sdkDir(context), "node/bin/node")
        val installed = installedPackages(File(files, "lib/node_modules"))
        return buildList {
            add("—— 终端软件 ——")
            add(
                "  Node.js    " + if (node.exists()) "就绪"
                else "未安装（`apt tools install` 在线下载）",
            )
            add("  npm prefix ${files.absolutePath}（-g 全局包 → files/bin，PATH 已含）")
            if (installed.isEmpty()) {
                add("  全局软件   无")
            } else {
                add("  全局软件   ${installed.size} 个：")
                installed.forEach { add("    $it") }
            }
            add("")
            addUsageLines(this)
        }
    }

    /** `apt tools list` 输出。 */
    fun listLines(context: Context): List<String> {
        val installed = installedPackages(File(context.filesDir, "lib/node_modules"))
        if (installed.isEmpty()) return listOf("（无已安装的全局软件）")
        return installed.map { "  $it" }
    }

    /** 用法（unknown / help 子命令）。 */
    fun usageLines(): List<String> = buildList { addUsageLines(this) }

    private fun addUsageLines(list: MutableList<String>) {
        list += "安装：apt tools install <软件名>…（npm 源里的任意软件，支持简写 gemini/codex/qwen）"
        list += "一键：apt tools install（Node.js + opencode + claude 全套）"
        list += "其他：apt tools uninstall <软件名>… / update / search <关键字> / list"
        list += "源：官方 npm registry（「构建环境」页可切国内 npmmirror，失败自动回退）"
    }

    /**
     * 安装：Node.js 未就绪先在线下载，然后 `npm i -g`。
     *
     * @param source   下载/registry 源（官方 / 国内镜像）
     * @param packages 空 = 一键默认套装（[TOOLS]）；非空 = 从源里安装这些任意包
     * @param onStage  进度阶段文案（下载器回调）
     * @return 0 成功
     */
    suspend fun install(
        context: Context,
        source: EnvSource,
        emit: (String) -> Unit,
        onStage: (String) -> Unit = { },
        packages: List<String> = emptyList(),
    ): Int = withContext(Dispatchers.IO) {
        emit("—— 软件安装（${source.title}） ——")
        val default = packages.isEmpty()
        val targets = if (default) TOOLS.map { it.pkg } else packages

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

        // 3) npm i -g（一键套装全部已装则跳过；任意包总是执行 = 顺带升级到最新）
        if (default && TOOLS.all { installed(context, it) }) {
            emit("AI CLI 已全部安装，跳过 npm 安装")
        } else {
            emit("npm i -g ${targets.joinToString(" ")} …")
            val code = runNpm(context, npmArgs("install", listOf("-g") + targets, source), emit)
            if (code != 0) {
                emit("[ERROR] npm 安装失败（退出码 $code）")
                return@withContext code
            }
        }

        // 4) shebang 修正 + 执行位（任意包都要过这一步）
        val fixed = fixBinScripts(context)
        if (fixed > 0) emit("已修正 $fixed 个启动脚本（shebang 适配 Android）")

        // 5) 结果
        if (default) {
            TOOLS.forEach { tool ->
                emit((if (installed(context, tool)) "✅ " else "❌ ") + "${tool.bin} — ${tool.summary}")
            }
            val missing = TOOLS.filter { !installed(context, it) }
            if (missing.isEmpty()) {
                emit("完成：在终端输入 " + TOOLS.joinToString(" / ") { "`${it.bin}`" } + " 即可使用")
                0
            } else {
                emit("[ERROR] 未安装：${missing.joinToString { it.pkg }}，可重试 `apt tools install`")
                1
            }
        } else {
            val nodeModules = File(context.filesDir, "lib/node_modules")
            val missing = targets.filter { !File(nodeModules, it).exists() }
            if (missing.isNotEmpty()) {
                emit("[ERROR] 未安装：" + missing.joinToString(" "))
                return@withContext 1
            }
            val cmds = BuildEnvironment.binDir(context).list()?.sorted().orEmpty()
            emit("完成：已安装 " + targets.joinToString("、"))
            if (cmds.isNotEmpty()) {
                emit("可用命令：" + cmds.joinToString(" ") { "`$it`" })
            }
            0
        }
    }

    /** 卸载全局软件（`npm uninstall -g`）。 */
    suspend fun uninstall(
        context: Context,
        source: EnvSource,
        emit: (String) -> Unit,
        packages: List<String>,
    ): Int = withContext(Dispatchers.IO) {
        if (!nodeReady(context)) {
            emit("[ERROR] Node.js 未安装，无可卸载的软件")
            return@withContext 1
        }
        if (packages.isEmpty()) {
            emit("用法：apt tools uninstall <软件名>…")
            return@withContext 1
        }
        emit("npm uninstall -g ${packages.joinToString(" ")} …")
        val code = runNpm(context, npmArgs("uninstall", listOf("-g") + packages, source), emit)
        val nodeModules = File(context.filesDir, "lib/node_modules")
        val left = packages.filter { File(nodeModules, it).exists() }
        if (code == 0 && left.isEmpty()) {
            emit("已卸载：" + packages.joinToString("、"))
            0
        } else {
            emit("[ERROR] 卸载失败：" + (left.joinToString(" ") + if (code != 0) "（退出码 $code）" else ""))
            1
        }
    }

    /** 更新：带名字 → 升到 `@latest`；不带 → `npm update -g` 全部。 */
    suspend fun update(
        context: Context,
        source: EnvSource,
        emit: (String) -> Unit,
        packages: List<String>,
    ): Int = withContext(Dispatchers.IO) {
        if (!nodeReady(context)) {
            emit("[ERROR] Node.js 未安装")
            return@withContext 1
        }
        val args = if (packages.isEmpty()) {
            emit("npm update -g …")
            npmArgs("update", listOf("-g"), source)
        } else {
            emit("npm i -g ${packages.joinToString(" ")}@latest …")
            npmArgs("install", listOf("-g") + packages.map { "$it@latest" }, source)
        }
        val code = runNpm(context, args, emit)
        if (code == 0) {
            val fixed = fixBinScripts(context)
            if (fixed > 0) emit("已修正 $fixed 个启动脚本（shebang 适配 Android）")
            emit("更新完成")
        } else {
            emit("[ERROR] 更新失败（退出码 $code）")
        }
        code
    }

    /** 搜索 npm 源（`npm search`，输出原样转发）。 */
    suspend fun search(
        context: Context,
        source: EnvSource,
        emit: (String) -> Unit,
        keywords: List<String>,
    ): Int = withContext(Dispatchers.IO) {
        emit("—— 搜索（${source.title}）：${keywords.joinToString(" ")} ——")
        runNpm(context, npmArgs("search", keywords, source), emit)
    }

    /** 拼 npm 参数：统一附加镜像 registry（如需）。 */
    private fun npmArgs(cmd: String, rest: List<String>, source: EnvSource): List<String> =
        listOf(cmd) + rest + if (source == EnvSource.MIRROR) {
            listOf("--registry=https://registry.npmmirror.com")
        } else {
            emptyList()
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
            emit("[ERROR] 未找到 npm（Node.js 未安装）")
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
     * 修正 `files/bin` 下启动脚本的 shebang，并为**全部**条目补执行位。
     *
     * Android 没有 `/bin/sh` 与 `/usr/bin/env`：npm 生成的 POSIX shim 以 `#!/bin/sh` 开头，
     * 部分包的 bin 直接是 `#!/usr/bin/env node`。补执行位必须在任何早退之前进行——
     * 无 shebang、甚至整个文件没有换行的二进制可执行文件同样要在终端里跑起来，
     * 否则报 `Permission denied`。
     *
     * @return 修正的文件数
     */
    private fun fixBinScripts(context: Context): Int {
        val bin = BuildEnvironment.binDir(context)
        val nodeFile = File(BuildEnvironment.sdkDir(context), "node/bin/node")
        val node = nodeFile.absolutePath
        var fixed = 0
        // node 解释器本体（shim 的 shebang 会指向它）
        runCatching { nodeFile.setExecutable(true, false) }
        bin.listFiles()?.forEach { file ->
            if (!file.isFile) return@forEach
            // 先补执行位：后续内容修正失败或无 shebang 也不能丢执行权限
            grantExecutable(file)
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
        }
        return fixed
    }

    /**
     * 补执行位：文件本身 + 符号链接解析后的真实文件。
     * npm 装到 `files/bin` 的命令常是指向 `lib/node_modules/<包>/bin/<入口>` 的符号链接，
     * 终端执行时用的是目标文件的权限位。
     */
    private fun grantExecutable(file: File) {
        runCatching { file.setExecutable(true, false) }
        runCatching { file.canonicalFile.setExecutable(true, false) }
    }
}
