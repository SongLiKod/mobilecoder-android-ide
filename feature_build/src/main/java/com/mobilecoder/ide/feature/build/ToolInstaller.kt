package com.mobilecoder.ide.feature.build

import android.content.Context
import com.mobilecoder.ide.core.nativebridge.CliCallback
import com.mobilecoder.ide.core.nativebridge.CliNative
import java.io.File
import java.util.concurrent.atomic.AtomicReference
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
 * 第 1 步之后还有一步 [ensureGlibc]：官方 nodejs.org 包链的是 glibc 的
 * `/lib/ld-linux-aarch64.so.1`，Android 内核 exec 时找不到它 → ENOENT(2)、退出码 127。
 * 这里补装 `files/sdk/glibc` 运行时并原地改写 ELF 的 `PT_INTERP`（见 [GlibcCompat]），
 * 否则后面每一步都会拿到一个看不出原因的 127。
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

    /** Node.js 是否已就绪：`files/bin/node` ELF 或 `files/sdk/node/bin/node`。 */
    fun nodeReady(context: Context): Boolean =
        BuildEnvironment.resolveNodeExec(context.filesDir) != null

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
        val installed = installedPackages(File(files, "lib/node_modules"))
        return buildList {
            add("—— 终端软件 ——")
            add(
                "  Node.js    " + if (nodeReady(context)) "就绪"
                else "未安装（`apt tools install` 在线下载）",
            )
            add("  npm prefix ${files.absolutePath}（-g 全局包 → files/bin，PATH 已含）")
            add("  执行位     ${BuildEnvironment.binModesLine(context)}")
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
        //    files/bin 已有可跑的 Android ELF 时仍可能缺 npm-cli.js，需要补装发行包
        val needNpmCli = BuildEnvironment.findNpmCli(context.filesDir) == null
        if (!nodeReady(context) || needNpmCli) {
            emit(
                if (!nodeReady(context)) "Node.js 未安装 → 在线下载 Node v20.18.0 …"
                else "npm 运行时缺失 → 补装 Node.js 发行包 …",
            )
            val path = EnvDownloader.install(
                context,
                EnvKind.NODE,
                source,
                onStage = { stage -> onStage(stage); emit(stage) },
            )
            emit("Node.js 已安装：$path")
        } else {
            val exec = BuildEnvironment.resolveNodeExec(context.filesDir)
            emit("Node.js 已就绪：${exec?.absolutePath ?: File(BuildEnvironment.sdkDir(context), "node").absolutePath}")
        }

        // 1.4) glibc 运行时：官方 nodejs.org 包链的是 /lib/ld-linux-aarch64.so.1，
        //      Android 上没有 → 内核 exec 阶段就 ENOENT(2)、退出码 127。
        //      必须在探测之前补运行时并改写解释器，否则第 1.5 步必然 fail。
        if (!ensureGlibc(context, source, emit, onStage)) return@withContext 1

        // 1.5) 执行探测：上面只判断「文件在不在」，这里真跑一次，
        //      否则 npm 跑到最后只给一个看不出原因的 127。
        //      ok = 继续；skip = 没探成（并发上限），不拦；fail = 真起不来
        val probe = probeNode(context)
        if (probe.startsWith("fail")) {
            val node = BuildEnvironment.resolveNodeExec(context.filesDir)
            emit("[ERROR] node 无法执行：${probe.removePrefix("fail ").trim()}")
            when (GlibcCompat.kind(node)) {
                InterpKind.GLIBC -> {
                    emit("       node 的解释器仍指向 glibc（/lib/ld-linux*）→ glibc 运行时没装好，")
                    emit("       或解释器改写没生效。请在「构建环境」页重装 glibc 运行时后重试。")
                }
                InterpKind.MUSL -> emit("       这是 musl 构建，本项目不提供 musl 运行时，请改用 nodejs.org 的 glibc 构建")
                InterpKind.NOT_ELF -> emit("       node 不是 ELF（多半是 shell shim 指向了不存在的 node）")
                InterpKind.NONE -> emit("       node 是静态链接 ELF，仍起不来 → 架构不匹配或执行位 / SELinux 被拒")
                else -> emit("       文件存在 ≠ 能启动：执行位 / SELinux / 架构不匹配也会是同一副症状")
            }
            emit("       自查：终端里执行 \$HOME/sdk/node/bin/node -v")
            return@withContext 1
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
                if (code == 127) {
                    emit("       127 = exec 失败，具体原因见上方 `mobilecoder: 命令执行失败` 那一行")
                }
                // npm 半途失败也可能已经往 files/bin 写了文件，仍要补 shebang 与执行位，
                // 否则剩下的命令在终端里直接报 Permission denied
                val partial = fixBinScripts(context)
                if (partial > 0) emit("已修正 $partial 个启动脚本（shebang 适配 Android）")
                return@withContext code
            }
        }

        // 4) shebang 修正 + 执行位（任意包都要过这一步）
        val fixed = fixBinScripts(context)
        if (fixed > 0) emit("已修正 $fixed 个启动脚本（shebang 适配 Android）")
        // 4.5) npm 刚解包的原生二进制（esbuild / ripgrep / claude …）也是 glibc ELF，
        //      顺手把它们的解释器一并改写，否则装完在终端里敲还是 127
        val patched = BuildEnvironment.patchGlibcInterps(context)
        if (patched > 0) emit("已改写 $patched 个新装二进制的 glibc 解释器")

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

    /** 输出里的 ANSI 转义（npm 与构建工具都会打，不剥会在 UI 上显示成乱码）。 */
    private val ANSI = Regex("\\u001b\\[[0-9;?]*[A-Za-z]")

    /**
     * 子进程环境：`HOME` 指向 files，npm 因此自动读 `files/.npmrc`；
     * `PATH` 首位是 `files/bin`（node / npm / npx 入口都在这里）。
     *
     * `LD_PRELOAD` 按**实际 exec 的 [target]**（argv[0]）的 ABI 选钩子（阶段 2）：
     * node 是 glibc 构建就装 glibc 版，是自备的 Android node / `npm` 壳脚本
     * （脚本 → 内核拉起 `/system/bin/sh`，bionic）就装 bionic 版，钩子负责把
     * `npm i -g` 解包瞬间那些还没打补丁的二进制也拦下来。
     *
     * 为什么必须传真实目标而不是一律用 node：bionic 进程被塞进 glibc 版 .so
     * 会让 linker 直接 `CANNOT LINK EXECUTABLE` 退出（glibc 反过来只是打一行
     * `cannot be preloaded` 到 stderr），两边都是实打实的故障。
     * 运行时未就绪时整个变量不设——没有阶段 2 也必须能跑阶段 1。
     *
     * @param target 本次真正 exec 的文件（argv[0]；非 ELF / 不存在 → bionic 版）
     */
    private fun execEnv(context: Context, target: File?): Array<String> {
        val files = context.filesDir
        val glibc = BuildEnvironment.glibcEnv(context)
        val preload = if (glibc.any { it.startsWith("MOBILECODER_GLIBC=") }) {
            BuildEnvironment.preloadFor(context, target)
                ?.let { arrayOf("LD_PRELOAD=$it") } ?: emptyArray()
        } else {
            emptyArray()
        }
        return arrayOf(
            "HOME=${files.absolutePath}",
            "TMPDIR=${BuildEnvironment.tmpDir(context).absolutePath}",
            "PATH=${BuildEnvironment.binDir(context).absolutePath}:/system/bin:/system/xbin:/vendor/bin",
            "LANG=C.UTF-8",
            "SHELL=/system/bin/sh",
        ) + glibc + preload
    }

    /**
     * 确保 node 能被内核 exec：补装 glibc 运行时 + 改写解释器（阶段 1 的核心）。
     *
     * 1. node 不是 glibc ELF（用户自备的 Android/bionic node）→ 什么都不用做；
     * 2. 运行时未装 → [EnvDownloader.install] 在线下载 `files/sdk/glibc`；
     *    下载失败时给出**可执行**的替代方案（「构建环境」页导入压缩包），不硬失败在文案上；
     * 3. 运行时就绪 → [BuildEnvironment.patchGlibcInterps] 把解释器原地改写到本机 loader。
     *
     * 改写完成后**子进程不需要任何钩子**：npm 拉起来的命令、终端里直接敲的 node
     * 都按新解释器正常 exec（见 [GlibcCompat]）。
     *
     * @return true = 可以继续探测 node；false = glibc 装不上（错误信息已 emit）
     */
    private suspend fun ensureGlibc(
        context: Context,
        source: EnvSource,
        emit: (String) -> Unit,
        onStage: (String) -> Unit,
    ): Boolean {
        val node = BuildEnvironment.resolveNodeExec(context.filesDir)
        if (node == null || GlibcCompat.kind(node) != InterpKind.GLIBC) return true

        val glibc = BuildEnvironment.glibcDir(context)
        if (!BuildEnvironment.glibcReady(glibc)) {
            emit("node 是 glibc 构建 → 在线下载 glibc 运行时（Android 缺 /lib/ld-linux-aarch64.so.1）…")
            val failure = runCatching {
                EnvDownloader.install(context, EnvKind.GLIBC, source, onStage = { onStage(it) })
            }.exceptionOrNull()
            if (failure is DownloadCancelled) throw failure
            if (failure != null) {
                emit("[ERROR] glibc 运行时安装失败：${failure.message}")
                emit("       官方 nodejs.org / Adoptium 包链的是 glibc（/lib/ld-linux-aarch64.so.1），")
                emit("       Android 没有该文件，内核 exec 阶段直接 ENOENT(2)、退出码 127。")
                emit("       自救（任选其一）：")
                emit("         1) 「构建环境」→ glibc 运行时镜像 → 填自定义镜像源（基址或完整 .tar.gz）重试；")
                emit("         2) 「构建环境」→ glibc 运行时 → 导入 glibc-$archHint-*.tar.gz 后重试，")
                emit("            压缩包由 tools/glibc-runtime/build.sh 生成（见该目录 README 上传到内置源）。")
                return false
            }
            emit("glibc 运行时已安装：${glibc.absolutePath}")
        }

        val patched = BuildEnvironment.patchGlibcInterps(context)
        emit(
            if (patched > 0) "已把 $patched 个 glibc 二进制的解释器改写到本机 loader"
            else "解释器已指向本机 loader，无需改写",
        )
        return true
    }

    /** 报错文案里的架构提示（包名按 aarch64 / x64 区分）。 */
    private val archHint: String get() = EnvDownloader.primaryArch()

    /**
     * 真跑一次 `node --version`，**与 npm 走完全相同的 exec 路径**，
     * 返回：
     *  - `ok <版本>`          —— 能启动；
     *  - `fail <原因>`        —— 启动失败，原因取自 stderr（含内核 errno 文案）；
     *  - `skip <原因>`        —— 没探成（并发上限等），不代表 node 坏了。
     *
     * 为什么必须单独探：[nodeReady] 只看 ELF / 文件存在性。官方 nodejs.org 的
     * linux 构建链的是 glibc，同样「存在 + 有执行位」，但内核解析
     * `/lib/ld-linux-aarch64.so.1` 时就 ENOENT。不先探一次，就得等
     * `npm i -g` 跑完才拿到一个孤零零的 127，看不出任何原因。
     */
    private suspend fun probeNode(context: Context): String {
        val files = context.filesDir
        val node = BuildEnvironment.resolveNodeExec(files)
            ?: return "fail 未找到可执行的 node（files/bin/node 与 files/sdk/node/bin/node 均不可用）"
        val first = AtomicReference<String>()
        val exit = CompletableDeferred<Int>()
        val pid = CliNative.exec(
            arrayOf(node.absolutePath, "--version"),
            files.absolutePath,
            execEnv(context, node),
            object : CliCallback {
                override fun onOutput(pid: Int, stream: Int, data: ByteArray?) {
                    if (data == null || first.get() != null) return
                    val line = String(data, Charsets.UTF_8)
                        .replace(ANSI, "")
                        .lineSequence()
                        .map { it.trim() }
                        .firstOrNull { it.isNotEmpty() } ?: return
                    first.compareAndSet(null, line)
                }

                override fun onExit(pid: Int, code: Int) {
                    exit.complete(code)
                }
            },
        )
        if (pid < 0) return "skip 并发进程数已达上限，未探测"
        val code = try {
            exit.await()
        } finally {
            if (!exit.isCompleted) runCatching { CliNative.killProcess(pid, 15) }
        }
        val out = first.get()
        return when {
            code == 0 -> "ok ${out.orEmpty()}"
            out.isNullOrBlank() -> "fail 无法启动（退出码 $code，无输出）"
            else -> "fail $out（退出码 $code）"
        }
    }

    /**
     * 拼 npm 参数：统一附加镜像 registry（如需）。
     */
    private fun npmArgs(cmd: String, rest: List<String>, source: EnvSource): List<String> =
        listOf(cmd) + rest + if (source == EnvSource.MIRROR) {
            listOf("--registry=https://registry.npmmirror.com")
        } else {
            emptyList()
        }

    /**
     * 以可执行的 node 直跑 `npm-cli.js`（绕过可能仍指向 glibc node 的旧 npm shim）。
     * 输出逐行转发到 [emit]；取消时杀死子进程。
     */
    private suspend fun runNpm(
        context: Context,
        args: List<String>,
        emit: (String) -> Unit,
    ): Int {
        // 终端能用的前提是 files/bin 与 sdk/*/bin 都有执行位；这里统一自愈一次，
        // 覆盖 npm 二进制入口本身，也覆盖 node 解释器与 PATH 上的其他命令。
        val broken = BuildEnvironment.repairExecutable(context)
        if (broken.isNotEmpty()) {
            emit("[WARN] 执行位修复失败（终端里会报 Permission denied）：")
            broken.forEach { emit("       $it") }
        }
        val files = context.filesDir
        val node = BuildEnvironment.resolveNodeExec(files)
        val npmCli = BuildEnvironment.findNpmCli(files)
        val argv = when {
            node != null && npmCli != null ->
                arrayOf(node.absolutePath, npmCli.absolutePath) + args.toTypedArray()
            File(BuildEnvironment.binDir(context), "npm").exists() ->
                arrayOf(File(BuildEnvironment.binDir(context), "npm").absolutePath) + args.toTypedArray()
            else -> {
                emit("[ERROR] 未找到 npm（Node.js 未安装）")
                return 1
            }
        }
        if (node != null) emit("node → ${node.absolutePath}")
        val exit = CompletableDeferred<Int>()
        val pid = CliNative.exec(argv, files.absolutePath, execEnv(context, File(argv[0])), object : CliCallback {
            override fun onOutput(pid: Int, stream: Int, data: ByteArray?) {
                if (data == null) return
                String(data, Charsets.UTF_8)
                    .replace(ANSI, "")
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
                line.startsWith("prefix=") || line.startsWith("fund=") ||
                    line.startsWith("audit=") || line.startsWith("script-shell=")
            }
            val kept = old.lines().filter { it.isNotBlank() && !managed(it) }
            val lines = kept + listOf(
                "prefix=${context.filesDir.absolutePath}",
                "fund=false",
                "audit=false",
                // npm 生命周期脚本默认用 /bin/sh，Android 上是 /system/bin/sh，
                // 不写死的话 `npm i -g` 里跑 postinstall 直接 "No such file or directory"
                "script-shell=/system/bin/sh",
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
        val nodeFile = BuildEnvironment.resolveNodeExec(context.filesDir)
            ?: File(BuildEnvironment.sdkDir(context), "node/bin/node")
        val node = nodeFile.absolutePath
        var fixed = 0
        // node 解释器本体（shim 的 shebang 会指向它）
        runCatching { nodeFile.setExecutable(true, false) }
        bin.listFiles()?.forEach { file ->
            if (!file.isFile) return@forEach
            // 先补执行位：后续内容修正失败或无 shebang 也不能丢执行权限
            grantExecutable(file)
            if (BuildEnvironment.isElfBinary(file)) return@forEach
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
        // 内容修完再整体自愈一次：覆盖 npm 新写入的符号链接目标、目录穿越位，
        // 并对 setExecutable 静默失败的路径回退系统 chmod
        BuildEnvironment.repairExecutable(context)
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
