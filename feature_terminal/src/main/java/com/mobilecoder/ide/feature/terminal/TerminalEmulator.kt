package com.mobilecoder.ide.feature.terminal

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** SGR 属性位（供渲染层取用）。 */
object Attr {
    const val BOLD = 1
    const val DIM = 2
    const val ITALIC = 4
    const val UNDERLINE = 8
    const val INVERSE = 16
    const val STRIKE = 32
}

/** 颜色取值约定：-1 = 主题默认色；0..255 = ANSI-256 调色板；>= [TRUECOLOR_FLAG] = 真彩色。 */
const val COLOR_DEFAULT: Int = -1
const val TRUECOLOR_FLAG: Int = 0x2000000

/** 一行文本的样式分段（渲染层据此拼 AnnotatedString）。 */
data class TerminalRun(
    val text: String,
    val fg: Int,
    val bg: Int,
    val attrs: Int,
)

/** 一屏行的快照：样式分段 + 光标所在列（-1 表示该行无光标）。 */
data class TerminalLine(
    val runs: List<TerminalRun>,
    val cursorCol: Int,
)

/**
 * VT100/xterm 子集解析器 + 屏幕缓冲（PRD 2.3 / TECH 4.2）。
 *
 * 线程模型：本类**不是**线程安全的。PTY 读线程的字节先由 [TerminalSession] 缓冲，
 * 再经主线程 16ms 节流后统一调用 [feed]，因此解析只发生在主线程，与 Compose 渲染同线程。
 *
 * 支持范围：
 *  - 控制字符：BEL/BS/TAB/LF/VT/FF/CR/SO/SI/ESC/DEL
 *  - CSI：A B C D E F G H f I J K L M P @ S T X Z ` a b c d e g h l m n p r s u
 *  - ESC：7 8 D E H M c ( ) * + # > = 与未知序列跳过
 *  - SGR：0/1/2/3/4/7/22/23/24/27、30-37/90-97、39、40-47/100-107、49、
 *    38;5;N / 48;5;N（256 色）、38;2;R;G;B / 48;2;R;G;B（真彩色）
 *  - OSC 0/2 窗口标题、OSC 8 超链接跳过；DCS/SOS/APC/PM 字符串跳过
 *  - 滚动区域 DECSTBM、备用屏幕 (?47/?1047/?1049)、插入模式 IRM、自动换行 DECAWM
 */
class TerminalEmulator(initialCols: Int = 80, initialRows: Int = 24) {

    companion object {
        /** scrollback 上限（PRD：日志实时输出 / 可回看） */
        const val MAX_SCROLLBACK = 2000
        private const val DEFAULT_TAB_INTERVAL = 8
    }

    // ---------------------------------------------------------------- 网格

    var cols: Int = initialCols.coerceAtLeast(2)
        private set
    var rows: Int = initialRows.coerceAtLeast(2)
        private set

    private var grid: Array<TerminalRow> = Array(rows) { TerminalRow(cols) }
    private var mainGrid: Array<TerminalRow>? = null // 备用屏幕激活时暂存主屏
    private val scrollback = ArrayDeque<TerminalRow>()

    // ---------------------------------------------------------------- 光标与属性

    var cursorRow: Int = 0
        private set
    var cursorCol: Int = 0
        private set
    var wrapPending: Boolean = false
        private set
    var cursorVisible: Boolean = true
        private set

    private var curFg = COLOR_DEFAULT
    private var curBg = COLOR_DEFAULT
    private var curAttrs = 0

    private var scrollTop = 0
    private var scrollBottom = rows - 1

    private var autowrap = true
    private var insertMode = false
    private var lastPrinted: Char? = null
    private val tabStops = HashSet<Int>()

    private data class SavedCursor(
        val row: Int,
        val col: Int,
        val fg: Int,
        val bg: Int,
        val attrs: Int,
        val wrapPending: Boolean,
    )

    private var savedCursor: SavedCursor? = null

    // ---------------------------------------------------------------- 解析状态机

    private enum class State { GROUND, ESC, ESC_ONE, CSI, OSC, OSC_ESC, STR, STR_ESC }

    private var state = State.GROUND
    private val params = ArrayList<Int>(8)
    private var paramAcc = 0
    private var paramHasDigit = false
    private var privateMark = ' '
    private var csiInter = ' '
    private val oscBuf = StringBuilder(64)

    // ---------------------------------------------------------------- 对外信号

    private val _version = MutableStateFlow(0)

    /** 屏幕版本号：每次解析/交互后递增，Compose 侧读取以触发重绘。 */
    val version: StateFlow<Int> = _version.asStateFlow()

    /** OSC 0/2 设置的窗口标题（会话标签名）。 */
    var onTitle: ((String) -> Unit)? = null

    /** 需要回写给 PTY 的终端应答（DSR / DA）。 */
    var onResponse: ((String) -> Unit)? = null

    init {
        resetTabStops()
    }

    // ---------------------------------------------------------------- 输入

    /** 解析一段已解码文本（PTY 输出 / 本地回显 / CLI 输出共用入口）。 */
    fun feed(text: String) {
        if (text.isEmpty()) return
        var i = 0
        val n = text.length
        while (i < n) {
            val ch = text[i]
            when (state) {
                State.GROUND -> {
                    when {
                        ch.code == 0x1B -> {
                            state = State.ESC
                            i++
                        }
                        ch.code < 0x20 || ch.code == 0x7F -> {
                            executeControl(ch)
                            i++
                        }
                        else -> {
                            printChar(ch)
                            i++
                        }
                    }
                }

                State.ESC -> {
                    i++
                    state = when (ch) {
                        '[' -> {
                            beginCsi()
                            State.CSI
                        }
                        ']' -> {
                            oscBuf.setLength(0)
                            State.OSC
                        }
                        'P', 'X', '^', '_' -> State.STR // DCS/SOS/APC/PM：跳到 ST
                        in '\u0020'..'\u002F' -> {
                            State.ESC_ONE // ESC ( B / ESC ) 0 / ESC # 8 等，消费下一字符后忽略
                        }
                        '7' -> {
                            saveCursor()
                            State.GROUND
                        }
                        '8' -> {
                            restoreCursor()
                            State.GROUND
                        }
                        'D' -> {
                            index()
                            State.GROUND
                        }
                        'E' -> {
                            carriageReturn()
                            index()
                            State.GROUND
                        }
                        'H' -> {
                            tabStops.add(cursorCol)
                            State.GROUND
                        }
                        'M' -> {
                            reverseIndex()
                            State.GROUND
                        }
                        'c' -> {
                            fullReset()
                            State.GROUND
                        }
                        else -> State.GROUND // 未知 ESC：正确跳过
                    }
                }

                State.ESC_ONE -> {
                    i++
                    state = State.GROUND
                }

                State.CSI -> {
                    when {
                        ch == '?' || ch == '>' || ch == '=' || ch == '<' -> {
                            if (csiInter == ' ' && !paramHasDigit && params.isEmpty()) privateMark = ch
                            i++
                        }
                        ch in '0'..'9' -> {
                            paramAcc = paramAcc * 10 + (ch.code - '0'.code)
                            if (paramAcc > 65535) paramAcc = 65535
                            paramHasDigit = true
                            i++
                        }
                        ch == ';' || ch == ':' -> {
                            params.add(if (paramHasDigit) paramAcc else -1)
                            paramAcc = 0
                            paramHasDigit = false
                            i++
                        }
                        ch in '\u0020'..'\u002F' -> {
                            if (csiInter == ' ') csiInter = ch
                            i++
                        }
                        ch in '\u0040'..'\u007E' -> {
                            if (paramHasDigit) params.add(paramAcc)
                            paramAcc = 0
                            paramHasDigit = false
                            executeCsi(ch)
                            i++
                            state = State.GROUND
                        }
                        ch.code < 0x20 -> {
                            // 序列内的控制字符：CAN/SUB 终止，其余忽略
                            if (ch == '\u0018' || ch == '\u001A') state = State.GROUND
                            if (ch.code == 0x1B) state = State.ESC
                            i++
                        }
                        else -> {
                            i++
                            state = State.GROUND
                        }
                    }
                }

                State.OSC -> when {
                    ch == '\u0007' -> {
                        handleOsc()
                        state = State.GROUND
                        i++
                    }
                    ch == '\u001B' -> {
                        state = State.OSC_ESC
                        i++
                    }
                    ch.code < 0x20 -> i++ // OSC 内控制字符忽略
                    else -> {
                        oscBuf.append(ch)
                        i++
                    }
                }

                State.OSC_ESC -> {
                    if (ch == '\\') {
                        handleOsc()
                        state = State.GROUND
                    } else {
                        oscBuf.append('\u001B').append(ch)
                        state = State.OSC
                    }
                    i++
                }

                State.STR -> {
                    if (ch == '\u001B') state = State.STR_ESC
                    i++
                }

                State.STR_ESC -> {
                    if (ch == '\\') state = State.GROUND else state = State.STR
                    i++
                }
            }
        }
        bump()
    }

    /** 光标回退并擦除最近打印的 n 个字符（apt 拦截回滚用，含自动换行场景）。 */
    fun erasePrinted(n: Int) {
        var left = n
        while (left > 0) {
            if (wrapPending) {
                // 最后打印的字符就在光标处，先撤销待换行标志
                wrapPending = false
            } else if (cursorCol > 0) {
                cursorCol--
            } else if (cursorRow > 0) {
                cursorRow--
                cursorCol = cols - 1
            } else {
                break // 已到屏幕左上角，无法继续回退
            }
            val row = grid[cursorRow]
            row.chars[cursorCol] = ' '
            row.fg[cursorCol] = COLOR_DEFAULT
            row.bg[cursorCol] = COLOR_DEFAULT
            row.attrs[cursorCol] = 0
            left--
        }
        bump()
    }

    /** 调整窗口尺寸（保留已有内容，光标裁剪到范围内）。 */
    fun resize(newCols: Int, newRows: Int) {
        val c = newCols.coerceAtLeast(2)
        val r = newRows.coerceAtLeast(2)
        if (c == cols && r == rows) return
        grid = resizeGrid(grid, c, r)
        mainGrid?.let { mainGrid = resizeGrid(it, c, r) }
        cols = c
        rows = r
        scrollTop = 0
        scrollBottom = r - 1
        resetTabStops()
        cursorRow = cursorRow.coerceIn(0, r - 1)
        cursorCol = cursorCol.coerceIn(0, c - 1)
        wrapPending = false
        bump()
    }

    private fun resizeGrid(source: Array<TerminalRow>, c: Int, r: Int): Array<TerminalRow> {
        val target = Array(r) { idx ->
            if (idx < source.size) source[idx].also { it.resize(c) } else TerminalRow(c)
        }
        return target
    }

    /** 清屏 + 清 scrollback + 复位（溢出菜单「清屏」）。 */
    fun clearAll() {
        fullReset()
        scrollback.clear()
        grid.forEach { it.clear() }
        altGridOrNull()?.forEach { it.clear() }
        bump()
    }

    /** 导出纯文本日志：scrollback + 当前屏。 */
    fun plainText(): String = buildString {
        for (row in scrollback) appendLine(row.toText())
        for (row in grid) appendLine(row.toText())
    }

    fun scrollbackSize(): Int = scrollback.size

    /** 可滚动的总行数（scrollback + 屏幕行）。 */
    fun lineCount(): Int = scrollback.size + rows

    /** 光标所在行索引（-1 表示光标不可见）。 */
    fun cursorLineIndex(): Int = if (cursorVisible) scrollback.size + cursorRow else -1

    /** 取第 [index] 行快照（0 = scrollback 最旧一行）。 */
    fun lineAt(index: Int): TerminalLine {
        val row: TerminalRow
        val markCol: Int
        when {
            index < 0 -> return TerminalLine(emptyList(), -1)
            index < scrollback.size -> {
                row = scrollback[index]
                markCol = -1
            }
            else -> {
                val screenRow = index - scrollback.size
                if (screenRow < 0 || screenRow >= grid.size) return TerminalLine(emptyList(), -1)
                row = grid[screenRow]
                markCol = if (cursorVisible && screenRow == cursorRow) cursorCol else -1
            }
        }
        var last = -1
        for (i in row.cols - 1 downTo 0) {
            if (row.chars[i] != ' ' || row.bg[i] != COLOR_DEFAULT) {
                last = i
                break
            }
        }
        val end = maxOf(last, markCol)
        if (end < 0) return TerminalLine(emptyList(), markCol)

        val runs = ArrayList<TerminalRun>(4)
        var start = 0
        while (start <= end) {
            val fg = row.fg[start]
            val bg = row.bg[start]
            val at = row.attrs[start]
            var stop = start + 1
            val sb = StringBuilder()
            sb.append(row.chars[start])
            while (stop <= end && row.fg[stop] == fg && row.bg[stop] == bg && row.attrs[stop] == at) {
                sb.append(row.chars[stop])
                stop++
            }
            runs.add(TerminalRun(sb.toString(), fg, bg, at))
            start = stop
        }
        return TerminalLine(runs, markCol)
    }

    // ---------------------------------------------------------------- 控制字符

    private fun executeControl(ch: Char) {
        when (ch.code) {
            0x07 -> Unit // BEL
            0x08 -> backspace()
            0x09 -> nextTab()
            0x0A, 0x0B, 0x0C -> lineFeed()
            0x0D -> carriageReturn()
            0x0E, 0x0F -> Unit // SO/SI：字符集切换，本实现统一按 ASCII 渲染
            0x7F -> Unit // DEL
            else -> Unit // 其余控制字符忽略
        }
    }

    private fun backspace() {
        if (wrapPending) {
            wrapPending = false
        } else if (cursorCol > 0) {
            cursorCol--
        }
    }

    private fun carriageReturn() {
        cursorCol = 0
        wrapPending = false
    }

    private fun lineFeed() {
        wrapPending = false
        if (cursorRow == scrollBottom) {
            scrollRegionUp(scrollTop, scrollBottom, 1, scrollbackEnabled())
        } else if (cursorRow < rows - 1) {
            cursorRow++
        }
    }

    private fun index() = lineFeed()

    private fun reverseIndex() {
        wrapPending = false
        if (cursorRow == scrollTop) {
            scrollRegionDown(scrollTop, scrollBottom, 1)
        } else if (cursorRow > 0) {
            cursorRow--
        }
    }

    private fun scrollbackEnabled(): Boolean =
        scrollTop == 0 && scrollBottom == rows - 1 && mainGrid == null

    private fun nextTab() {
        for (i in cursorCol + 1 until cols) {
            if (i in tabStops) {
                cursorCol = i
                wrapPending = false
                return
            }
        }
        cursorCol = (cols - 1).coerceAtLeast(0)
        wrapPending = false
    }

    private fun prevTab() {
        for (i in cursorCol - 1 downTo 0) {
            if (i in tabStops) {
                cursorCol = i
                wrapPending = false
                return
            }
        }
        cursorCol = 0
        wrapPending = false
    }

    private fun resetTabStops() {
        tabStops.clear()
        var c = DEFAULT_TAB_INTERVAL
        while (c < cols) {
            tabStops.add(c)
            c += DEFAULT_TAB_INTERVAL
        }
    }

    // ---------------------------------------------------------------- 打印与滚动

    private fun printChar(c: Char) {
        if (wrapPending) {
            wrapPending = false
            doWrap()
        }
        if (cursorCol >= cols) cursorCol = cols - 1
        val row = grid[cursorRow]
        if (insertMode) row.insertBlank(cursorCol, 1)
        row.chars[cursorCol] = c
        row.fg[cursorCol] = curFg
        row.bg[cursorCol] = curBg
        row.attrs[cursorCol] = curAttrs
        lastPrinted = c
        if (cursorCol >= cols - 1) {
            if (autowrap) wrapPending = true
        } else {
            cursorCol++
        }
    }

    private fun doWrap() {
        cursorCol = 0
        if (cursorRow == scrollBottom) {
            scrollRegionUp(scrollTop, scrollBottom, 1, scrollbackEnabled())
        } else if (cursorRow < rows - 1) {
            cursorRow++
        }
    }

    /** 把 [top..bottom] 区域上移 n 行；[saveToScrollback] 时顶行进入 scrollback。 */
    private fun scrollRegionUp(top: Int, bottom: Int, n: Int, saveToScrollback: Boolean) {
        if (top > bottom) return
        val count = n.coerceIn(1, bottom - top + 1)
        repeat(count) {
            val off = grid[top]
            for (r in top until bottom) grid[r] = grid[r + 1]
            if (saveToScrollback) {
                addToScrollback(off)
                grid[bottom] = TerminalRow(cols).also { it.fill(0, cols, curBg) }
            } else {
                off.fill(0, cols, curBg)
                grid[bottom] = off
            }
        }
    }

    private fun scrollRegionDown(top: Int, bottom: Int, n: Int) {
        if (top > bottom) return
        val count = n.coerceIn(1, bottom - top + 1)
        repeat(count) {
            val off = grid[bottom]
            for (r in bottom downTo top + 1) grid[r] = grid[r - 1]
            off.fill(0, cols, curBg)
            grid[top] = off
        }
    }

    private fun addToScrollback(row: TerminalRow) {
        scrollback.addLast(row)
        while (scrollback.size > MAX_SCROLLBACK) scrollback.removeFirst()
    }

    private fun altGridOrNull(): Array<TerminalRow>? = if (mainGrid != null) grid else null

    // ---------------------------------------------------------------- 擦除

    private fun eraseInDisplay(mode: Int) {
        when (mode) {
            0 -> {
                grid[cursorRow].fill(cursorCol, cols, curBg)
                for (r in cursorRow + 1 until rows) grid[r].fill(0, cols, curBg)
            }
            1 -> {
                for (r in 0 until cursorRow) grid[r].fill(0, cols, curBg)
                grid[cursorRow].fill(0, (cursorCol + 1).coerceAtMost(cols), curBg)
            }
            2 -> {
                for (r in 0 until rows) grid[r].fill(0, cols, curBg)
            }
            3 -> {
                for (r in 0 until rows) grid[r].fill(0, cols, curBg)
                scrollback.clear()
            }
        }
    }

    private fun eraseInLine(mode: Int) {
        val row = grid[cursorRow]
        when (mode) {
            0 -> row.fill(cursorCol, cols, curBg)
            1 -> row.fill(0, (cursorCol + 1).coerceAtMost(cols), curBg)
            else -> row.fill(0, cols, curBg)
        }
    }

    private fun eraseChars(n: Int) {
        val row = grid[cursorRow]
        row.fill(cursorCol, (cursorCol + n).coerceAtMost(cols), curBg)
    }

    private fun insertChars(n: Int) {
        grid[cursorRow].insertBlank(cursorCol, n.coerceAtMost(cols - cursorCol))
    }

    private fun deleteChars(n: Int) {
        grid[cursorRow].delete(cursorCol, n.coerceAtMost(cols - cursorCol))
    }

    private fun insertLines(n: Int) {
        if (cursorRow in scrollTop..scrollBottom) {
            scrollRegionDown(cursorRow, scrollBottom, n)
            cursorCol = 0
            wrapPending = false
        }
    }

    private fun deleteLines(n: Int) {
        if (cursorRow in scrollTop..scrollBottom) {
            scrollRegionUp(cursorRow, scrollBottom, n, false)
            cursorCol = 0
            wrapPending = false
        }
    }

    // ---------------------------------------------------------------- 光标移动

    private fun moveVertical(delta: Int) {
        val inRegion = cursorRow in scrollTop..scrollBottom
        val top = if (inRegion) scrollTop else 0
        val bottom = if (inRegion) scrollBottom else rows - 1
        cursorRow = (cursorRow + delta).coerceIn(top, bottom)
        wrapPending = false
    }

    private fun moveHorizontal(delta: Int) {
        cursorCol = (cursorCol + delta).coerceIn(0, cols - 1)
        wrapPending = false
    }

    private fun setCursor(row: Int, col: Int) {
        cursorRow = row.coerceIn(0, rows - 1)
        cursorCol = col.coerceIn(0, cols - 1)
        wrapPending = false
    }

    private fun saveCursor() {
        savedCursor = SavedCursor(cursorRow, cursorCol, curFg, curBg, curAttrs, wrapPending)
    }

    private fun restoreCursor() {
        val s = savedCursor ?: run {
            setCursor(0, 0)
            return
        }
        cursorRow = s.row.coerceIn(0, rows - 1)
        cursorCol = s.col.coerceIn(0, cols - 1)
        curFg = s.fg
        curBg = s.bg
        curAttrs = s.attrs
        wrapPending = s.wrapPending
    }

    // ---------------------------------------------------------------- 模式与复位

    private fun softReset() {
        curFg = COLOR_DEFAULT
        curBg = COLOR_DEFAULT
        curAttrs = 0
        scrollTop = 0
        scrollBottom = rows - 1
        autowrap = true
        insertMode = false
        cursorVisible = true
        wrapPending = false
        savedCursor = null
        setCursor(0, 0)
    }

    private fun fullReset() {
        softReset()
        if (mainGrid != null) {
            // 退出备用屏幕
            mainGrid?.let { grid = it }
            mainGrid = null
        }
        state = State.GROUND
        lastPrinted = null
        resetTabStops()
    }

    private fun enterAltScreen() {
        if (mainGrid != null) return
        saveCursor()
        mainGrid = grid
        grid = Array(rows) { TerminalRow(cols) }
        cursorRow = 0
        cursorCol = 0
        wrapPending = false
    }

    private fun exitAltScreen() {
        if (mainGrid == null) return
        grid = mainGrid ?: grid
        mainGrid = null
        restoreCursor()
    }

    // ---------------------------------------------------------------- CSI 执行

    private fun rawParam(idx: Int, default: Int): Int {
        val v = params.getOrNull(idx) ?: return default
        return if (v < 0) default else v
    }

    /** 计数类参数：缺省或 0 均视为 1。 */
    private fun countParam(idx: Int): Int = rawParam(idx, 1).coerceAtLeast(1)

    private fun beginCsi() {
        params.clear()
        paramAcc = 0
        paramHasDigit = false
        privateMark = ' '
        csiInter = ' '
    }

    private fun executeCsi(final: Char) {
        // DECSTR：CSI ! p
        if (csiInter == '!' && final == 'p') {
            softReset()
            return
        }
        when (final) {
            'A' -> moveVertical(-countParam(0))
            'B', 'e' -> moveVertical(countParam(0))
            'C', 'a' -> moveHorizontal(countParam(0))
            'D' -> moveHorizontal(-countParam(0))
            'E' -> {
                moveVertical(countParam(0))
                cursorCol = 0
            }
            'F' -> {
                moveVertical(-countParam(0))
                cursorCol = 0
            }
            'G', '`' -> setCursor(cursorRow, countParam(0) - 1)
            'H', 'f' -> setCursor(rawParam(0, 1) - 1, rawParam(1, 1) - 1)
            'I' -> repeat(countParam(0)) { nextTab() }
            'J' -> eraseInDisplay(rawParam(0, 0))
            'K' -> eraseInLine(rawParam(0, 0))
            'L' -> insertLines(countParam(0))
            'M' -> deleteLines(countParam(0))
            'P' -> deleteChars(countParam(0))
            '@' -> insertChars(countParam(0))
            'S' -> if (scrollTop <= scrollBottom) scrollRegionUp(scrollTop, scrollBottom, countParam(0), false)
            'T' -> if (scrollTop <= scrollBottom) scrollRegionDown(scrollTop, scrollBottom, countParam(0))
            'X' -> eraseChars(countParam(0))
            'Z' -> repeat(countParam(0)) { prevTab() }
            'b' -> {
                val ch = lastPrinted
                if (ch != null) repeat(countParam(0)) { printChar(ch) }
            }
            'c' -> if (privateMark == '?') onResponse?.invoke("\u001b[?6c") else onResponse?.invoke("\u001b[0;6c")
            'd' -> setCursor(rawParam(0, 1) - 1, cursorCol)
            'g' -> when (rawParam(0, 0)) {
                3 -> tabStops.clear()
                else -> tabStops.remove(cursorCol)
            }
            'h' -> setMode(true)
            'l' -> setMode(false)
            'm' -> applySgr()
            'n' -> when (rawParam(0, 0)) {
                5 -> onResponse?.invoke("\u001b[0n")
                6 -> onResponse?.invoke("\u001b[${cursorRow + 1};${cursorCol + 1}R")
            }
            'r' -> {
                val top = (rawParam(0, 1) - 1).coerceIn(0, rows - 1)
                val bottom = (rawParam(1, rows) - 1).coerceIn(0, rows - 1)
                if (top < bottom) {
                    scrollTop = top
                    scrollBottom = bottom
                    setCursor(0, 0)
                }
            }
            's' -> saveCursor()
            'u' -> restoreCursor()
            else -> Unit // 未知 CSI：正确跳过不乱码
        }
    }

    private fun setMode(enable: Boolean) {
        if (privateMark == '?') {
            for (p in params) {
                when (if (p < 0) 0 else p) {
                    7 -> autowrap = enable
                    25 -> cursorVisible = enable
                    47, 1047, 1049 -> if (enable) enterAltScreen() else exitAltScreen()
                    else -> Unit // 鼠标/括号粘贴等模式：忽略但接受
                }
            }
        } else {
            for (p in params) {
                when (if (p < 0) 0 else p) {
                    4 -> insertMode = enable
                    else -> Unit
                }
            }
        }
    }

    private fun applySgr() {
        val list = if (params.isEmpty()) listOf(0) else params.map { if (it < 0) 0 else it }
        var i = 0
        while (i < list.size) {
            val p = list[i]
            when {
                p == 0 -> {
                    curFg = COLOR_DEFAULT
                    curBg = COLOR_DEFAULT
                    curAttrs = 0
                }
                p == 1 -> curAttrs = curAttrs or Attr.BOLD
                p == 2 -> curAttrs = curAttrs or Attr.DIM
                p == 3 -> curAttrs = curAttrs or Attr.ITALIC
                p == 4 -> curAttrs = curAttrs or Attr.UNDERLINE
                p == 7 -> curAttrs = curAttrs or Attr.INVERSE
                p == 21 || p == 22 -> curAttrs = curAttrs and (Attr.BOLD or Attr.DIM).inv()
                p == 23 -> curAttrs = curAttrs and Attr.ITALIC.inv()
                p == 24 -> curAttrs = curAttrs and Attr.UNDERLINE.inv()
                p == 27 -> curAttrs = curAttrs and Attr.INVERSE.inv()
                p == 29 -> curAttrs = curAttrs and Attr.STRIKE.inv()
                p == 39 -> curFg = COLOR_DEFAULT
                p == 49 -> curBg = COLOR_DEFAULT
                p in 30..37 -> curFg = p - 30
                p in 40..47 -> curBg = p - 40
                p in 90..97 -> curFg = p - 90 + 8
                p in 100..107 -> curBg = p - 100 + 8
                p == 38 || p == 48 -> {
                    val mode = list.getOrNull(i + 1)
                    when {
                        mode == 5 -> {
                            val color = (list.getOrNull(i + 2) ?: 0).coerceIn(0, 255)
                            if (p == 38) curFg = color else curBg = color
                            i += 2
                        }
                        mode == 2 -> {
                            val r = (list.getOrNull(i + 2) ?: 0).coerceIn(0, 255)
                            val g = (list.getOrNull(i + 3) ?: 0).coerceIn(0, 255)
                            val b = (list.getOrNull(i + 4) ?: 0).coerceIn(0, 255)
                            val color = TRUECOLOR_FLAG or (r shl 16) or (g shl 8) or b
                            if (p == 38) curFg = color else curBg = color
                            i += 4
                        }
                        else -> i += 1
                    }
                }
                else -> Unit // 未知 SGR：忽略
            }
            i++
        }
    }

    // ---------------------------------------------------------------- OSC

    private fun handleOsc() {
        val payload = oscBuf.toString()
        oscBuf.setLength(0)
        val sep = payload.indexOf(';')
        if (sep <= 0) return
        val code = payload.substring(0, sep).toIntOrNull() ?: return
        if (code == 0 || code == 2) {
            val title = payload.substring(sep + 1).trim()
            if (title.isNotEmpty()) onTitle?.invoke(title)
        }
        // OSC 8 超链接、OSC 7 等：正确跳过即可
    }

    private fun bump() {
        _version.value = _version.value + 1
    }
}

/**
 * 终端一行：并行数组存储（字符 / 前景 / 背景 / 属性），内存占用低于对象数组。
 */
class TerminalRow(cols: Int) {
    var cols: Int = cols
        private set
    var chars = CharArray(cols) { ' ' }
    var fg = IntArray(cols) { COLOR_DEFAULT }
    var bg = IntArray(cols) { COLOR_DEFAULT }
    var attrs = IntArray(cols)

    fun resize(newCols: Int) {
        if (newCols == cols) return
        chars = chars.copyOf(newCols)
        fg = fg.copyOf(newCols)
        bg = bg.copyOf(newCols)
        attrs = attrs.copyOf(newCols)
        if (newCols > cols) {
            for (i in cols until newCols) {
                chars[i] = ' '
                fg[i] = COLOR_DEFAULT
                bg[i] = COLOR_DEFAULT
                attrs[i] = 0
            }
        }
        cols = newCols
    }

    /** 用空格 + 指定背景填充 [from, to)。 */
    fun fill(from: Int, to: Int, bg: Int) {
        val start = from.coerceIn(0, cols)
        val end = to.coerceIn(0, cols)
        var i = start
        while (i < end) {
            chars[i] = ' '
            fg[i] = COLOR_DEFAULT
            this.bg[i] = bg
            attrs[i] = 0
            i++
        }
    }

    fun clear() = fill(0, cols, COLOR_DEFAULT)

    /** 在 [at] 处插入 n 个空格，尾部溢出丢弃。 */
    fun insertBlank(at: Int, n: Int) {
        if (n <= 0 || at >= cols) return
        val count = n.coerceAtMost(cols - at)
        for (i in cols - 1 downTo at + count) {
            chars[i] = chars[i - count]
            fg[i] = fg[i - count]
            bg[i] = bg[i - count]
            attrs[i] = attrs[i - count]
        }
        fill(at, at + count, COLOR_DEFAULT)
    }

    /** 删除 [at] 起 n 个字符，尾部补空格。 */
    fun delete(at: Int, n: Int) {
        if (n <= 0 || at >= cols) return
        val count = n.coerceAtMost(cols - at)
        for (i in at until cols - count) {
            chars[i] = chars[i + count]
            fg[i] = fg[i + count]
            bg[i] = bg[i + count]
            attrs[i] = attrs[i + count]
        }
        fill(cols - count, cols, COLOR_DEFAULT)
    }

    /** 去掉行尾空白后的纯文本。 */
    fun toText(): String {
        var end = cols
        while (end > 0 && chars[end - 1] == ' ') end--
        return String(chars, 0, end)
    }
}
