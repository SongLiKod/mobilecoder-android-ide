package com.mobilecoder.ide.core.common.cli

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.coroutineContext

/** 一条进程内命令的定义。 */
data class CliCommand(
    /** 命令名（命令行首词），如 `git`。 */
    val name: String,
    /**
     * 是否在终端里拦截同名命令行：Android 设备上没有对应可执行文件，
     * 由进程内实现接管（见 feature_git/GitCli）；其余命令一律直通 shell。
     */
    val terminalIntercept: Boolean = false,
    /**
     * 执行体。
     * @param args 命令名之后的参数（已完成引号切分）
     * @param cwd  项目工作目录
     * @param emit 追加一行输出（终端实时回显）
     * @return 进程式退出码（0 成功）
     */
    val handler: suspend (args: List<String>, cwd: File, emit: (String) -> Unit) -> Int,
)

/**
 * 进程内命令引擎：设备上**没有可执行文件**的命令（目前只有 `git`）在此注册接管，
 * 其余命令（含 `apt …`）一律交给 shell。
 *
 * 设计要点：
 *  - **命令注册表**：各 feature 模块在 init 时注册（feature_git 注册 `git`）；
 *  - **协程任务队列**：Mutex 串行化，防止多指令并发冲突；
 *  - **协作式取消**：终端 Ctrl+C 走 [cancelCurrent]，配合注册方的 [onCancel] 钩子
 *    中止不受协程控制的 native 阻塞调用。
 *
 * 历史：前身为 apt CLI 引擎（`apt` 命令集 + 可视化面板）；apt CLI 已整体移除，
 * 命令记录改由「记录」页承载（core_storage/HistoryStore）。
 *
 * 引擎位于 core_common，保证 feature_terminal / feature_git / feature_build 之间无循环依赖。
 */
object CliEngine {

    private val registry = LinkedHashMap<String, CliCommand>()
    private val mutex = Mutex()

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
    fun find(name: String): CliCommand? = registry[name]

    /**
     * 终端拦截器需要识别的全部命令前缀：注册表里标记了 [CliCommand.terminalIntercept]
     * 的命令（如 `git`）。会话创建时调用，动态反映注册表。
     */
    @Synchronized
    fun interceptTargets(): List<String> =
        registry.values.filter { it.terminalIntercept }.map { it.name }

    /**
     * Enter 时判断这行是否交给**进程内**执行（终端拦截器的 `accept` 二次校验）。
     *
     * 只认注册表：`git …` → true；`apt install curl`、`ls` 这类一律 false，
     * 回滚本地回显、整行交回 shell（Linux 环境就绪时由 rootfs 里的真实命令接管）。
     *
     * 纯字符串逻辑，JVM 单测可直接调用。
     */
    @Synchronized
    fun isInProcessLine(line: String): Boolean {
        val first = tokenize(line.trim()).firstOrNull() ?: return false
        return registry[first]?.terminalIntercept == true
    }

    /** 当前是否空闲。 */
    val isIdle: Boolean get() = currentJob == null

    /**
     * 取消当前正在执行的命令（终端 Ctrl+C）。
     *
     * 取消是协作式的：命令协程在下一个挂起点抛出 CancellationException，
     * 网络类命令（fetch/push/pull/clone）会额外通过 [onCancel] 调用
     * `GitNative.cancelNetwork()` 中止传输，因此不会一直卡住。
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
     * 执行一行命令（串行：同一时刻只有一条命令在跑）。
     *
     * @param line 完整命令行
     * @param cwd  工作目录
     * @param emit 输出回调
     * @return 退出码
     */
    suspend fun run(line: String, cwd: File, emit: (String) -> Unit): Int {
        val tokens = tokenize(line.trim())
        if (tokens.isEmpty()) return 0
        val name = tokens.first()
        val args = tokens.drop(1)

        val command = find(name)
        if (command == null) {
            emit("$name: command not found")
            return 127
        }

        val job = coroutineContext.job
        return mutex.withLock {
            currentJob = job
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
                if (currentJob === job) currentJob = null
            }
        }
    }

    /** 支持引号：`git commit -m "fix: hello world"`。 */
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
}
