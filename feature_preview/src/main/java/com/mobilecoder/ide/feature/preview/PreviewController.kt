package com.mobilecoder.ide.feature.preview

import android.content.Context
import android.net.Uri
import com.mobilecoder.ide.core.common.linux.Proot
import com.mobilecoder.ide.core.nativebridge.CliCallback
import com.mobilecoder.ide.core.nativebridge.CliNative
import com.mobilecoder.ide.feature.build.BuildEnvironment
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 静态 HTML / Web 项目实时预览（Issue 13）的进程级单例状态。
 *
 * 职责：识别项目预览形态、维护窗格开关与分屏方向、**后台拉起 / 停止 dev server**
 * （复用 [CliNative] 子进程通道，与构建页同一条执行链路），以及「文件保存 → 让
 * WebView 重新加载」的刷新信号。纯判定逻辑在 `PreviewSupport.kt`，UI 在 `PreviewPane.kt`。
 *
 * 与 EditorController 一样是 object + StateFlow，跨底部导航切换状态天然保留。
 */
object PreviewController {

    /** dev server 生命周期（静态 HTML 模式恒为 [ServerStatus.STOPPED]）。 */
    enum class ServerStatus {
        /** 未启动（或已停止）。 */
        STOPPED,

        /** 环境体检 / `npm install` / 进程已起但还没打印出地址。 */
        STARTING,

        /** 已从输出里识别到回环地址，可加载。 */
        RUNNING,

        /** 环境缺失或进程异常退出（[PreviewState.message] 给出原因）。 */
        FAILED,
    }

    /** 窗格整体状态。 */
    data class PreviewState(
        /** 项目根绝对路径；空 = 尚未打开项目。 */
        val projectRoot: String = "",
        /** 预览形态，决定窗格加载 `file://` 还是 dev server 地址。 */
        val kind: PreviewKind = PreviewKind.NONE,
        /** 窗格是否展开（设置里的总开关由 EditorController.settings.previewEnabled 把关）。 */
        val open: Boolean = false,
        /** 分屏方向：true = 左右分栏（竖分割线），false = 上下分层。 */
        val vertical: Boolean = true,
        /** dev server 状态。 */
        val status: ServerStatus = ServerStatus.STOPPED,
        /** dev server 识别到的地址（`http://127.0.0.1:5173`）；STOPPED/FAILED 时为空。 */
        val serverUrl: String = "",
        /** 人类可读的提示：启动失败原因、环境缺失引导等。 */
        val message: String = "",
        /** 最近的进程输出（截断到 [MAX_LOG_CHARS]），启动阶段回显给用户。 */
        val log: String = "",
        /**
         * 刷新信号：每次保存了会影响预览的文件就 +1，WebView 侧递增即 `reload()`。
         * 静态 HTML 靠它做到「保存即刷新」；dev server 模式下同样重载，作为 HMR
         * 断连时的兜底（两者都由 500ms 防抖合并，连续打字不会刷屏）。
         */
        val reloadTick: Int = 0,
    ) {
        /** dev server 是否处于可用（已识别地址）状态。 */
        val running: Boolean get() = status == ServerStatus.RUNNING

        /**
         * 窗格当前应加载的地址：静态 HTML 走 `file://`（WebView 需开文件访问权限），
         * 开发服务器走回环 HTTP；不可预览 / 尚未起服务时为空串。
         */
        val url: String
            get() = when (kind) {
                PreviewKind.STATIC_HTML ->
                    if (projectRoot.isNotEmpty()) uriOfIndex(File(projectRoot)) else ""
                PreviewKind.DEV_SERVER -> if (running) serverUrl else ""
                PreviewKind.NONE -> ""
            }
    }

    private const val MAX_LOG_CHARS = 4000

    /** 保存后延迟多久发出 reloadTick（合并连续保存；也给 HMR 留出落地时间）。 */
    private const val RELOAD_DEBOUNCE_MS = 500L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(PreviewState())
    val state: StateFlow<PreviewState> = _state.asStateFlow()

    private var appContext: Context? = null
    private var initialized = false

    /** 正在跑的 dev server 进程 pid（-1 = 没有）。 */
    @Volatile
    private var pid = -1

    /** 行缓冲（CliNative 按块回调，需自行切行；\r 覆盖进度条中间态）。 */
    private val lineBuf = StringBuilder()
    private val logBuf = StringBuilder()

    private var reloadJob: Job? = null

    /** URL 已识别后进程又退出时，避免把 RUNNING 状态误刷成 FAILED 的提示语。 */
    @Volatile
    private var sawUrl = false

    fun ensureInit(context: Context) {
        if (initialized) return
        initialized = true
        appContext = context.applicationContext
    }

    // ------------------------------------------------------------------
    // 项目 / 窗格
    // ------------------------------------------------------------------

    /**
     * 绑定当前项目：项目变了就停掉上一个项目的 dev server 并收起窗格，
     * 重新识别 [PreviewKind]。同一项目重复调用是幂等的。
     */
    fun setProject(projectPath: String) {
        val root = projectPath.trimEnd('/', '\\')
        if (_state.value.projectRoot == root) return
        stopDevServer()
        val kind = if (root.isEmpty()) {
            PreviewKind.NONE
        } else {
            runCatching { detectPreviewKind(File(root)) }.getOrDefault(PreviewKind.NONE)
        }
        _state.value = PreviewState(projectRoot = root, kind = kind)
    }

    /** 展开 / 收起窗格；收起时顺带停掉 dev server（移动端内存宝贵，不留后台进程）。 */
    fun setOpen(open: Boolean) {
        if (_state.value.open == open) return
        _state.update { it.copy(open = open) }
        if (!open) stopDevServer()
    }

    fun toggleOpen() = setOpen(!_state.value.open)

    /** 切换分屏方向（左右 ⇄ 上下），小屏浮层模式下不使用。 */
    fun toggleOrientation() = _state.update { it.copy(vertical = !it.vertical) }

    // ------------------------------------------------------------------
    // 刷新
    // ------------------------------------------------------------------

    /**
     * 某个文件保存成功后调用：确属预览会读到的资源才 +1 触发 reload（500ms 防抖）。
     *
     * @return true 表示这次保存确实排进了刷新
     */
    fun notifySaved(path: String): Boolean {
        val current = _state.value
        if (!current.open || current.kind == PreviewKind.NONE) return false
        if (!affectsPreview(path)) return false
        reloadJob?.cancel()
        reloadJob = scope.launch {
            delay(RELOAD_DEBOUNCE_MS)
            _state.update { it.copy(reloadTick = it.reloadTick + 1) }
        }
        return true
    }

    /** 手动刷新（窗格头部「刷新」按钮）。 */
    fun reloadNow() {
        _state.update { it.copy(reloadTick = it.reloadTick + 1) }
    }

    // ------------------------------------------------------------------
    // dev server
    // ------------------------------------------------------------------

    /**
     * 一键启动预览：体检 Node / Linux 环境 → 必要时先 `npm install` → 后台
     * `npm run dev`，从输出里识别到回环地址后把 [PreviewState.status] 置为
     * [ServerStatus.RUNNING]，WebView 即可加载。
     *
     * 复用构建页同一套执行链路：`Proot.wrap` + `CliNative.exec` + 完整 env 数组。
     */
    fun startDevServer() {
        val context = appContext ?: return
        val state = _state.value
        if (state.kind != PreviewKind.DEV_SERVER) return
        if (state.status == ServerStatus.STARTING) return
        if (state.projectRoot.isEmpty()) return

        stopProcessOnly()
        sawUrl = false
        synchronized(logBuf) { logBuf.setLength(0) }
        synchronized(lineBuf) { lineBuf.setLength(0) }
        _state.update {
            it.copy(
                status = ServerStatus.STARTING,
                serverUrl = "",
                message = "",
                log = "",
            )
        }

        scope.launch { runDevServer(context, File(state.projectRoot)) }
    }

    /** 停止 dev server 并复位状态（退出码 / 失败提示清空）。 */
    fun stopDevServer() {
        stopProcessOnly()
        _state.update {
            if (it.status == ServerStatus.STOPPED) it
            else it.copy(status = ServerStatus.STOPPED, serverUrl = "", message = "")
        }
    }

    /** 只杀进程，不动状态（启动前清理 / 收起窗格时复用）。 */
    private fun stopProcessOnly() {
        val running = pid
        if (running > 0) {
            pid = -1
            runCatching { CliNative.killProcess(running, 15) }
            // SIGTERM 后留 800ms 让 npm 透传退出；仍不退再补 SIGKILL（与构建页一致）
            scope.launch {
                delay(800)
                if (pid != running) runCatching { CliNative.killProcess(running, 9) }
            }
        }
        synchronized(lineBuf) { lineBuf.setLength(0) }
    }

    private suspend fun runDevServer(context: Context, projectDir: File) {
        // ---- 1) 环境体检 ----
        // 这里刻意**不走** BuildEnvironment.status()：它按「项目类型」过滤所需组件，
        // 而只写了 dev 脚本（没有 build）的 Vite 工程会被 detectProject 归为源码打包，
        // requiredEnv 为空 → NODE / LINUX 两项根本不出现，直接判定环境缺失。
        // 预览的依赖与项目类型无关（npm 要在 proot 里跑），改成直接探测这两样。
        if (!Proot.isReady(context)) {
            return fail(
                "预览环境未就绪：Linux 环境缺失（proot + Ubuntu rootfs）。" +
                    "npm / node 是 glibc 程序，必须在其中运行。" +
                    "请在「环境中心」在线下载，或导入 ubuntu-base-*.tar.gz 后重试",
            )
        }
        val nodeLine = nodeReadyLine(context)
        if (!nodeLine.startsWith("ok")) {
            return fail(
                "预览环境未就绪：未检测到可用的 Node.js（含 npm）。$nodeLine。" +
                    "请在「环境中心」在线下载 Node.js，或导入 node-v*-linux-*.tar.gz 后重试",
            )
        }
        // 执行位自愈：npm / node 入口缺 x 位时终端会报 Permission denied
        runCatching { BuildEnvironment.repairExecutable(context) }

        // ---- 2) 组装命令：依赖没装先装（一次性），再起 dev server ----
        val nodeModules = File(projectDir, "node_modules")
        val packageJsonText = runCatching {
            File(projectDir, "package.json").readText(Charsets.UTF_8)
        }.getOrDefault("")
        val install = if (nodeModules.isDirectory) {
            ""
        } else {
            "npm install --no-audit --no-fund --loglevel=error && "
        }
        // Vite 默认只绑 localhost（可能落到 IPv6），显式钉死 127.0.0.1 保证 WebView 可达
        val hostFlag = if (usesVite(packageJsonText)) " --host 127.0.0.1" else ""
        val cmd = "${install}npm run dev$hostFlag"

        val envArray = devEnvArray(context)
        BuildEnvironment.tmpDir(context).mkdirs()
        BuildEnvironment.gradleUserHome(context).mkdirs()

        // ---- 3) 起进程（Linux 就绪 → 整条命令在 proot 内执行）----
        val argv = Proot.wrap(context, arrayOf("/bin/sh", "-c", cmd), projectDir.absolutePath)
        val newPid = CliNative.exec(argv, projectDir.absolutePath, envArray, callback())
        if (newPid < 0) {
            return fail("无法启动 dev server：并发进程数已达上限或系统拒绝 fork，请稍后重试")
        }
        pid = newPid
        appendLog("执行：$cmd")
    }

    /**
     * 探测 node 是否可用，返回 `ok …` 或 `缺失原因`。
     *
     * 与 `BuildEnvironment.detect()` 的 NODE 判定同源，但**不经过项目类型过滤**：
     * rootfs 里（软件市场 / 终端 apt 装的）已有 node 时直接视为可用——整条命令本来
     * 就在 proot 内执行，用的正是 rootfs 的 node + npm；否则真 `execve` 一次
     * `files/bin/node --version`，区分「没装」与「缺执行位 / SELinux 拦截」。
     */
    private fun nodeReadyLine(context: Context): String {
        val rootfs = Proot.rootfsDir(context)
        val rootfsNode = listOf("usr/local/bin/node", "usr/bin/node")
            .map { File(rootfs, it) }
            .firstOrNull { it.exists() }
        if (rootfsNode != null) return "ok（rootfs ${rootfsNode.absolutePath}）"
        return runCatching { BuildEnvironment.execProbeLine(context) }.getOrDefault("探测失败")
    }

    /**
     * dev server 子进程的环境变量（[CliNative.exec] 的 envArray 会**整体替换**
     * 子进程环境，必须给全；与 BuildRunner.buildEnvArray 同源，去掉 Gradle 专用项）。
     */
    private fun devEnvArray(context: Context): Array<String> {
        val files = context.filesDir
        return buildList {
            add("HOME=${files.absolutePath}")
            add("TMPDIR=${BuildEnvironment.tmpDir(context).absolutePath}")
            // guest 标准路径排在 bionic 前：proot 内拿到 Linux 工具链
            add("PATH=${Proot.pathFor(BuildEnvironment.binDir(context).absolutePath)}")
            add("LANG=C.UTF-8")
            add("LC_ALL=C.UTF-8")
            add("SHELL=/system/bin/sh")
            add("MOBILECODER_HOME=${files.absolutePath}")
            add("NODE_ENV=development")
        }.toTypedArray<String>() + Proot.envEntries(context)
    }

    private fun fail(message: String) {
        appendLog(message)
        _state.update {
            it.copy(status = ServerStatus.FAILED, serverUrl = "", message = message)
        }
    }

    // ------------------------------------------------------------------
    // 子进程回调（读线程 → 状态流）
    // ------------------------------------------------------------------

    private fun callback(): CliCallback = object : CliCallback {

        override fun onOutput(pid: Int, stream: Int, data: ByteArray?) {
            if (data == null || data.isEmpty()) return
            feed(String(data, Charsets.UTF_8))
        }

        override fun onExit(pid: Int, code: Int) {
            flushLine()
            if (pid == this@PreviewController.pid) this@PreviewController.pid = -1
            val current = _state.value
            if (current.status == ServerStatus.STOPPED) return
            if (sawUrl && current.status == ServerStatus.RUNNING) {
                // 用户手动停止 / 页面崩了：回到 STOPPED，让「启动预览」按钮重新出现
                _state.update {
                    it.copy(status = ServerStatus.STOPPED, serverUrl = "", message = "")
                }
                return
            }
            fail(
                if (code == 0) "dev server 已退出（未输出可用地址）"
                else "dev server 启动失败（退出码 $code）",
            )
        }
    }

    /** 切行喂给 [handleLine]；`\r` 当作进度条覆盖，丢弃当前半行。 */
    private fun feed(text: String) {
        val completed = ArrayList<String>(8)
        synchronized(lineBuf) {
            for (ch in text) {
                when (ch) {
                    '\n' -> {
                        val line = lineBuf.toString().trimEnd('\r')
                        lineBuf.setLength(0)
                        if (line.isNotBlank()) completed.add(line)
                    }
                    '\r' -> lineBuf.setLength(0)
                    else -> lineBuf.append(ch)
                }
            }
        }
        completed.forEach { handleLine(it) }
    }

    private fun flushLine() {
        val rest = synchronized(lineBuf) {
            val text = lineBuf.toString().trimEnd('\r')
            lineBuf.setLength(0)
            text
        }
        if (rest.isNotBlank()) handleLine(rest)
    }

    private fun handleLine(line: String) {
        appendLog(line)
        if (sawUrl) return
        val url = findDevServerUrl(line) ?: return
        sawUrl = true
        appendLog("预览地址：$url")
        _state.update {
            it.copy(status = ServerStatus.RUNNING, serverUrl = url, message = "")
        }
    }

    private fun appendLog(line: String) {
        synchronized(logBuf) {
            logBuf.append(line).append('\n')
            if (logBuf.length > MAX_LOG_CHARS) {
                logBuf.delete(0, logBuf.length - MAX_LOG_CHARS)
            }
        }
        val text = synchronized(logBuf) { logBuf.toString() }
        _state.update { it.copy(log = text) }
    }

    /** 项目根 `index.html` 的 `file://` 地址（[Uri.fromFile] 负责百分号编码）。 */
    private fun uriOfIndex(dir: File): String = Uri.fromFile(File(dir, "index.html")).toString()
}
