package com.mobilecoder.ide.feature.terminal

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.mobilecoder.ide.core.common.cli.CliEngine
import com.mobilecoder.ide.core.nativebridge.TerminalCallback
import com.mobilecoder.ide.core.nativebridge.TerminalNative
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 底部按键行的特殊键。 */
enum class TerminalSpecialKey { ESC, TAB, UP, DOWN, LEFT, RIGHT, HOME, END, PAGE_UP, PAGE_DOWN }

/**
 * 多行提交块 → PTY 文本（逐行执行语义）：
 *
 *  - 行终止符统一为单个 `\r`（PTY 的 ICRNL 把它映射成 NL 提交一行，与本文件
 *    其余 Enter 写入 BYTE_CR 一致）；**切勿用 `\r\n`** —— CR 已提交一行，
 *    紧跟的 LF 会再提交一个空命令，导致每两行之间冒出多余提示符；
 *  - 末行若无换行则补一个 `\r`；末尾已有换行时不重复补（split 语义下
 *    末段为空串，天然不重复），避免凭空多执行一个空行。
 */
internal fun encodeMultilineSubmit(text: String): String {
    val normalized = text.replace("\r\n", "\n").replace("\n", "\r")
    return if (normalized.endsWith("\r")) normalized else normalized + "\r"
}

/**
 * 一个独立的终端会话（PRD 2.3「多终端窗口并行」）。
 *
 * 每个会话持有：独立 PTY（native 层 sessionId）、独立屏幕缓冲 [emulator]、
 * 独立的命令拦截器与滚动日志；切换标签即切换视图，进程级单例持有故
 * 切走底部导航再回来仍在运行（后台长时间任务不中断）。
 *
 * 线程约定：
 *  - [onData] / [onExit] 由 **PTY 读线程**回调：仅做字节缓冲 + 调度，不触碰 UI/屏幕；
 *  - 解析与状态更新统一在 **主线程**执行（16ms 节流批处理，避免每字节重组 Compose）。
 */
class TerminalSession(
    initialId: Int,
    val cwd: String,
    cols: Int,
    rows: Int,
    private val handler: Handler,
    private val scope: CoroutineScope,
) : TerminalCallback {

    private companion object {
        const val FLUSH_INTERVAL_MS = 16L
        val BYTE_CR = byteArrayOf(0x0D)
    }

    /** native 层会话 id（创建失败时 -1）。 */
    var id: Int = initialId
        internal set

    /** 屏幕缓冲 + VT100 解析器（仅主线程访问）。 */
    val emulator = TerminalEmulator(cols, rows)

    // ---- PTY 输出缓冲（读线程写入，主线程消费） ----
    private val lock = Any()
    private val pendingBytes = ByteArrayOutputStream()
    private val pendingText = StringBuilder()
    private var flushScheduled = false
    private var lastFlushAt = 0L
    private val decoder: CharsetDecoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    private var carry = ByteArray(0)

    // ---- 对外状态 ----
    private val _title = MutableStateFlow(defaultTitle(cwd))
    val title: StateFlow<String> = _title.asStateFlow()

    /** null = 运行中；否则为退出码（显示「进程已退出 code=N」，可重启）。 */
    private val _exitCode = MutableStateFlow<Int?>(null)
    val exitCode: StateFlow<Int?> = _exitCode.asStateFlow()

    private val _cliRunning = MutableStateFlow(false)
    val cliRunning: StateFlow<Boolean> = _cliRunning.asStateFlow()

    /**
     * 命令拦截器（前缀缓冲 + 本地回显 + 回滚；目标来自 CliEngine 注册表：当前为 `git`）。
     *
     * `accept` 做**二次分流**：只有进程内注册表里有的 `git …` 留给进程内引擎；
     * 其余行（含 `apt …` —— apt CLI 已移除）回滚交回 shell，Linux 环境就绪时
     * 由 rootfs 里的真实命令（真 apt / apt-get 等）接管。
     */
    private val interceptor = CommandInterceptor(
        targets = { CliEngine.interceptTargets() },
        accept = { line -> CliEngine.isInProcessLine(line) },
        onWritePty = { bytes -> writeRaw(bytes) },
        onLocalEcho = { text -> onMain { emulator.feed(text) } },
        onRollback = { count -> onMain { emulator.erasePrinted(count) } },
    )

    private val flushTask = Runnable { flushNow() }

    init {
        emulator.onTitle = { title -> if (title.isNotBlank()) _title.value = title }
        emulator.onResponse = { response -> writeRaw(response.toByteArray()) }
    }

    // ------------------------------------------------------------------
    // TerminalCallback（PTY 读线程）
    // ------------------------------------------------------------------

    override fun onData(id: Int, data: ByteArray) {
        synchronized(lock) {
            pendingBytes.write(data)
        }
        scheduleFlush()
    }

    override fun onExit(id: Int, code: Int) {
        handler.post {
            _exitCode.value = code
            emulator.feed("\r\n—— 进程已退出（code=$code），点击标签可重启 ——\r\n")
        }
    }

    // ------------------------------------------------------------------
    // 输入
    // ------------------------------------------------------------------

    /** 写给 PTY 的原始字节（供 shell / 交互程序）。 */
    fun writeRaw(bytes: ByteArray) {
        if (bytes.isEmpty() || id < 0) return
        runCatching { TerminalNative.write(id, bytes) }
    }

    fun writeText(text: String) = writeRaw(text.toByteArray())

    /** 提交一整行（来自底部命令输入框）：先走拦截器，再处理回车。 */
    fun submitLine(text: String) {
        // 多行块（换行键 / 粘贴多行 / 物理回车）：整块直写 PTY，shell 按行执行
        // （粘贴语义）。拦截器是单行状态机（本地回显 / 回滚按行），不参与多行；
        // 因此进程内命令（git）拦截只对单行输入生效，多行块里的 git 交给 shell。
        if (text.contains('\n')) {
            writeText(encodeMultilineSubmit(text))
            return
        }
        if (text.isNotEmpty()) interceptor.feed(text)
        val intercepted = interceptor.enter()
        if (intercepted != null) runCli(intercepted)
    }

    /** ESC 控制字符（0x1B）与 TAB（0x09）：源码不写字面转义序列，避免编码问题。 */
    private val escStr = 27.toChar().toString()
    private val tabStr = 9.toChar().toString()

    /** 按键行特殊键（转义序列 / 控制字符，绕过拦截器）。 */
    fun sendSpecial(key: TerminalSpecialKey, ctrl: Boolean = false) {
        val seq = when (key) {
            TerminalSpecialKey.ESC -> escStr
            TerminalSpecialKey.TAB -> if (ctrl) escStr + "[Z" else tabStr
            TerminalSpecialKey.UP -> if (ctrl) escStr + "[1;5A" else escStr + "[A"
            TerminalSpecialKey.DOWN -> if (ctrl) escStr + "[1;5B" else escStr + "[B"
            TerminalSpecialKey.RIGHT -> if (ctrl) escStr + "[1;5C" else escStr + "[C"
            TerminalSpecialKey.LEFT -> if (ctrl) escStr + "[1;5D" else escStr + "[D"
            TerminalSpecialKey.HOME -> if (ctrl) escStr + "[1;5H" else escStr + "[H"
            TerminalSpecialKey.END -> if (ctrl) escStr + "[1;5F" else escStr + "[F"
            TerminalSpecialKey.PAGE_UP -> if (ctrl) escStr + "[5;5~" else escStr + "[5~"
            TerminalSpecialKey.PAGE_DOWN -> if (ctrl) escStr + "[6;5~" else escStr + "[6~"
        }
        writeText(seq)
    }

    /**
     * 触摸 → 鼠标序列（绕过拦截器直写 PTY），坐标为 1 基字符格。
     *
     * 仅在应用开启鼠标报告（CSI ?1000/1002/1003 h）时发送 ——
     * opencode 这类 TUI 收到滚轮事件后自己滚动面板；未开启时是空操作。
     */
    fun sendMouseButton(button: Int, col: Int, row: Int, press: Boolean = true) {
        val mode = emulator.mouseMode.value
        if (!mode.tracking) return
        val bytes = TerminalMouse.encode(mode.sgr, button, col, row, press)
        if (bytes.isNotEmpty()) writeRaw(bytes)
    }

    /** 左键点击：SGR（?1006）发 press + release，X10 无释放语义只发 press。 */
    fun sendMouseClick(col: Int, row: Int) {
        sendMouseButton(TerminalMouse.LEFT, col, row, press = true)
        if (emulator.mouseMode.value.sgr) {
            sendMouseButton(TerminalMouse.LEFT, col, row, press = false)
        }
    }

    /** Ctrl+字母：写入 0x01..0x1A；Ctrl+C 额外清理拦截状态。 */
    fun sendControlChar(code: Int) {
        val value = code.coerceIn(0, 31)
        if (value == 3) {
            interceptor.interrupt()
            // 进程内命令正在执行（命令没交给 PTY，shell 那边无事可中断）→ 取消它
            if (_cliRunning.value && runCatching { CliEngine.cancelCurrent() }.getOrDefault(false)) {
                postEmit("^C")
                return
            }
        }
        writeRaw(byteArrayOf(value.toByte()))
    }

    /** 退格（0x7f）：拦截态本地删除，直通态发给 PTY。 */
    fun sendBackspace() = interceptor.backspace()

    // ------------------------------------------------------------------
    // 进程内命令（终端手动输入 `git …`）
    // ------------------------------------------------------------------

    /**
     * 进程内命令执行（终端手动输入）。
     *
     * 拦截器保证命令首词是已注册的进程内命令（当前为 `git`），
     * 统一交给 CliEngine 按首词分发。
     */
    private fun runCli(commandLine: String) {
        // 命令本身已本地回显：换行开始输出
        onMain { emulator.feed("\r\n") }

        if (!CliEngine.isIdle) {
            onMain { emulator.feed("已有命令在执行中，请稍候再试\r\n") }
            writeRaw(BYTE_CR) // 让 shell 重新打印提示符
            return
        }

        val cwdFile = File(cwd.ifBlank { "/" })
        _cliRunning.value = true
        scope.launch(Dispatchers.IO) {
            try {
                CliEngine.run(commandLine, cwdFile) { line -> postEmit(line) }
            } catch (e: CancellationException) {
                // Ctrl+C 取消：引擎已回显「命令已取消」，这里不重复报错
                throw e
            } catch (t: Throwable) {
                postEmit("命令执行失败：${t.message ?: t::class.java.simpleName}")
            } finally {
                handler.post {
                    // 先把剩余输出画进屏幕，再让 shell 重绘提示符（保证顺序）
                    flushNow()
                    _cliRunning.value = false
                    writeRaw(BYTE_CR)
                }
            }
        }
    }

    /** CLI 输出 → 屏幕缓冲（IO 线程调用，批处理到主线程）。 */
    private fun postEmit(text: String) {
        val normalized = text.replace("\r\n", "\n").replace("\n", "\r\n")
        synchronized(lock) {
            pendingText.append(normalized).append("\r\n")
        }
        scheduleFlush()
    }

    // ------------------------------------------------------------------
    // 批处理与解码
    // ------------------------------------------------------------------

    private fun scheduleFlush() {
        synchronized(lock) {
            if (flushScheduled) return
            flushScheduled = true
        }
        val now = SystemClock.uptimeMillis()
        val delay = (lastFlushAt + FLUSH_INTERVAL_MS - now).coerceAtLeast(0L)
        handler.postDelayed(flushTask, delay)
    }

    /** 主线程：把缓冲的 PTY 字节与本地文本一次解析进屏幕（16ms 节流）。 */
    private fun flushNow() {
        val bytes: ByteArray
        val text: String
        synchronized(lock) {
            flushScheduled = false
            lastFlushAt = SystemClock.uptimeMillis()
            bytes = pendingBytes.toByteArray()
            pendingBytes.reset()
            text = pendingText.toString()
            pendingText.setLength(0)
        }
        if (bytes.isNotEmpty()) emulator.feed(decode(bytes))
        if (text.isNotEmpty()) emulator.feed(text)
    }

    private fun decode(chunk: ByteArray): String {
        val buffer = ByteBuffer.allocate(carry.size + chunk.size)
        buffer.put(carry)
        buffer.put(chunk)
        buffer.flip()
        val out = CharBuffer.allocate(buffer.remaining() + 16)
        runCatching { decoder.decode(buffer, out, false) }
        carry = if (buffer.hasRemaining()) {
            ByteArray(buffer.remaining()).also { buffer.get(it) }
        } else {
            ByteArray(0)
        }
        out.flip()
        return out.toString()
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else handler.post(block)
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    /** 调整窗口尺寸（同步到 native TIOCSWINSZ 与屏幕缓冲）。 */
    fun resize(cols: Int, rows: Int) {
        if (cols <= 0 || rows <= 0) return
        if (emulator.cols == cols && emulator.rows == rows) return
        emulator.resize(cols, rows)
        if (id >= 0) runCatching { TerminalNative.resize(id, cols, rows) }
    }

    /** 关闭底层 PTY（退出码稍后经 onExit 回调）。 */
    fun destroy() {
        if (id < 0) return
        runCatching { TerminalNative.destroy(id) }
    }

    /** 导出滚动日志文本（scrollback + 当前屏）。 */
    fun exportText(): String = emulator.plainText()

    /** 手动重命名会话标签（溢出菜单「重命名」）。 */
    fun rename(title: String) {
        val trimmed = title.trim()
        if (trimmed.isNotEmpty()) _title.value = trimmed
    }

    private fun defaultTitle(cwd: String): String {
        val trimmed = cwd.trimEnd('/')
        val name = trimmed.substringAfterLast('/')
        return if (name.isBlank()) "终端" else name
    }
}