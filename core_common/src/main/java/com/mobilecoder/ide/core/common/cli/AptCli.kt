package com.mobilecoder.ide.core.common.cli

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.coroutineContext

/** 一条 CLI 命令的定义。 */
data class CliCommand(
    /** 命令名，例如 `build`（用户输入 `apt build`）。 */
    val name: String,
    /** 一句话说明（CLI 面板卡片副标题）。 */
    val summary: String,
    /** 用法示例，例如 `apt build [--release]`。 */
    val usage: String = "apt $name",
    /** 分组（项目 / 代码 / 构建 / 系统），用于面板分栏。 */
    val group: String = "通用",
    /**
     * 是否在终端里拦截同名命令行（如 `git`：Android 设备上没有同名可执行文件，
     * 由进程内实现接管，见 feature_git/GitCli）。`apt` 前缀始终拦截。
     */
    val terminalIntercept: Boolean = false,
    /**
     * 执行体。
     * @param args 命令名之后的参数
     * @param cwd  项目工作目录
     * @param emit 追加一行输出（终端/面板实时回显）
     * @return 进程式退出码（0 成功）
     */
    val handler: suspend (args: List<String>, cwd: File, emit: (String) -> Unit) -> Int,
)

/** 当前正在执行的任务。 */
data class CliTask(
    val commandLine: String,
    val startedAt: Long,
)

/**
 * apt 风格 CLI 引擎（PRD 2.4 / TECH 4.3）。
 *
 * 设计要点：
 *  - **命令注册表**：内置命令与各 feature 注册的命令（`build`/`package` 由 feature_build 注册，
 *    `init`/`format`/`lint`/`clean` 由 feature_cli 注册）统一在此汇聚；
 *  - **协程任务队列**：Mutex 串行化，防止多指令并发冲突；
 *  - **实时日志**：`emit` 逐行回调，终端与 CLI 面板共享同一输出通道；
 *  - **两种使用方式**：终端输入 `apt xxx`（拦截）与可视化面板一键执行。
 *
 * 引擎位于 core_common，保证 feature_terminal / feature_cli / feature_build 之间无循环依赖。
 */
object AptCli {

    private const val PREFIX = "apt"

    private val registry = LinkedHashMap<String, CliCommand>()
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _history = MutableStateFlow<List<String>>(emptyList())
    val history: StateFlow<List<String>> = _history.asStateFlow()

    private val _running = MutableStateFlow<CliTask?>(null)
    val running: StateFlow<CliTask?> = _running.asStateFlow()

    private var bootstrapped = false
    private var sequence = 0L

    /** 正在执行的任务协程（仅在持锁执行期间非空，供 [cancelCurrent] 取消）。 */
    @Volatile
    private var currentJob: Job? = null

    /**
     * 取消时的额外钩子（由注册方设置：feature_git 在此中止进行中的 fetch/push，
     * 因为阻塞的 native 网络调用不受协程取消影响）。任意线程调用，绝不抛异常。
     */
    @Volatile
    var onCancel: (() -> Unit)? = null

    /** 注册命令（同名覆盖）。 */
    @Synchronized
    fun register(command: CliCommand) {
        registry[command.name] = command
    }

    @Synchronized
    fun registerAll(commands: List<CliCommand>) = commands.forEach(::register)

    /** 全部命令（按注册顺序）。 */
    @Synchronized
    fun commands(): List<CliCommand> = registry.values.toList()

    @Synchronized
    fun find(name: String): CliCommand? = registry[name]

    /** 是否为 CLI 命令行（终端据此拦截，避免把 `apt` / `git` 交给 sh）。 */
    fun isCliLine(line: String): Boolean {
        val first = line.trim().split(Regex("\\s+"), limit = 2).firstOrNull() ?: return false
        return first == PREFIX || find(first)?.terminalIntercept == true
    }

    /**
     * 终端拦截器需要识别的全部命令前缀：`apt` + 标记了 [CliCommand.terminalIntercept]
     * 的命令（如 `git`）。会话创建时调用，动态反映注册表。
     */
    @Synchronized
    fun interceptTargets(): List<String> {
        bootstrap()
        return (listOf(PREFIX) + registry.values.filter { it.terminalIntercept }.map { it.name })
            .distinct()
    }

    /** 当前是否空闲。 */
    val isIdle: Boolean get() = _running.value == null

    /**
     * 取消当前正在执行的命令（CLI 面板「停止」按钮 / 终端 Ctrl+C）。
     *
     * 取消是协作式的：命令协程在下一个挂起点抛出 CancellationException，
     * 网络类命令（fetch/push/pull/push/clone）会额外调用 `GitNative.cancelNetwork()`
     * 中止传输，因此不会一直卡在「执行中」。
     *
     * @return true 表示确有任务被取消
     */
    fun cancelCurrent(): Boolean {
        val job = currentJob ?: return false
        if (!job.isActive) return false
        // 先中止可能阻塞在 native 里的网络传输，再取消协程
        runCatching { onCancel?.invoke() }
        job.cancel(CancellationException("用户请求停止"))
        return true
    }

    /**
     * 执行一行命令。
     *
     * @param line 完整命令行（可带或不带 `apt` 前缀）
     * @param cwd  工作目录
     * @param emit 输出回调（已切到 IO 线程）
     * @return 退出码
     */
    suspend fun run(line: String, cwd: File, emit: (String) -> Unit): Int = runInternal(line, cwd, emit)

    /** 异步执行（fire-and-forget），返回任务 id。 */
    fun submit(line: String, cwd: File, emit: (String) -> Unit): Job = scope.launch {
        runInternal(line, cwd, emit)
    }

    /** 中止当前任务（仅置位标记，具体进程由调用方 kill）。 */
    fun currentTask(): CliTask? = _running.value

    // ------------------------------------------------------------------

    private suspend fun runInternal(line: String, cwd: File, emit: (String) -> Unit): Int {
        bootstrap()
        val tokens = tokenize(line.trim())
        if (tokens.isEmpty()) return 0
        val name = if (tokens.first() == PREFIX) tokens.drop(1).firstOrNull() else tokens.first()
        val args = if (tokens.first() == PREFIX) tokens.drop(2) else tokens.drop(1)

        if (name.isNullOrBlank()) {
            emit("MobileCoder apt CLI")
            emit("用法：apt <命令> [参数]，`apt help` 查看全部命令")
            return 0
        }

        val command = find(name)
        if (command == null) {
            emit("apt: 未找到命令 `$name`")
            val close = commands().filter { it.name.startsWith(name.take(1)) }.take(5)
            if (close.isNotEmpty()) {
                emit("你是否想执行：${close.joinToString(" ") { "`apt ${it.name}`" }}")
            }
            emit("输入 `apt help` 查看全部命令")
            return 127
        }

        if (args.any { it == "--help" || it == "-h" }) {
            emit(command.usage)
            emit("  ${command.summary}")
            return 0
        }

        val job = coroutineContext.job
        return try {
            mutex.withLock() {
                _running.value = CliTask(line, System.currentTimeMillis())
                currentJob = job
                pushHistory(line)
                try {
                    if (!cwd.isDirectory) {
                        emit("工作目录不存在：${cwd.absolutePath}")
                        return@withLock 2
                    }
                    val rc = command.handler(args, cwd, emit)
                    // 执行期间收到取消请求 → 不再当作正常退出，按取消收尾
                    coroutineContext.ensureActive()
                    rc
                } catch (e: CancellationException) {
                    emit("命令已取消")
                    throw e
                } catch (t: Throwable) {
                    emit("命令执行失败：${t.message ?: t::class.java.simpleName}")
                    1
                } finally {
                    _running.value = null
                }
            }
        } finally {
            if (currentJob === job) currentJob = null
        }
    }

    private fun pushHistory(line: String) {
        if (line.isBlank()) return
        val next = _history.value.toMutableList().apply {
            remove(line)
            add(0, line)
        }
        _history.value = next.take(100)
    }

    /** 支持引号：`apt init "My App"`。 */
    private fun tokenize(line: String): List<String> {
        val out = ArrayList<String>()
        val current = StringBuilder()
        var quote: Char? = null
        for (ch in line) {
            when {
                quote != null -> {
                    if (ch == quote) quote = null else current.append(ch)
                }
                ch == '"' || ch == '\'' -> quote = ch
                ch.isWhitespace() -> {
                    if (current.isNotEmpty()) {
                        out.add(current.toString())
                        current.clear()
                    }
                }
                else -> current.append(ch)
            }
        }
        if (current.isNotEmpty()) out.add(current.toString())
        return out
    }

    /** 内置命令：help / version（其余命令由各 feature 模块注册）。 */
    @Synchronized
    private fun bootstrap() {
        if (bootstrapped) return
        bootstrapped = true
        register(
            CliCommand(
                name = "help",
                summary = "列出全部命令与用法",
                usage = "apt help [命令名]",
                group = "系统",
            ) { args, _, emit ->
                val target = args.firstOrNull()?.let(::find)
                if (target != null) {
                    emit(target.usage)
                    emit("  ${target.summary}")
                    return@CliCommand 0
                }
                emit("MobileCoder apt CLI — 移动端开发工具链")
                emit("")
                commands().groupBy { it.group }.forEach { (group, list) ->
                    emit("[$group]")
                    list.forEach { emit("  %-12s %s".format(it.name, it.summary)) }
                    emit("")
                }
                emit("提示：任意命令后加 --help 查看用法")
                0
            },
        )
        register(
            CliCommand(
                name = "version",
                summary = "显示 CLI 版本信息",
                usage = "apt version",
                group = "系统",
            ) { _, _, emit ->
                emit("MobileCoder apt CLI 1.0.0")
                emit("宿主：Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
                emit("ABI：${android.os.Build.SUPPORTED_ABIS.joinToString()}")
                0
            },
        )
    }
}
