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

/**
 * 双宽字符（中文 / 全角 / emoji …）的**续格**标记：双宽字占两格，首格存字符、
 * 次格存本标记（占位但不渲染）。
 *
 * 为什么必须占两格：TUI（opencode / lazygit …）内部按 `wcwidth` 计算列宽 ——
 * 中文算 2 列。若模拟器只推进 1 格，程序用绝对定位（CSI H）画下一段内容时
 * 落到的格子与它自己记录的列号错位，旧内容残留在缝隙里 —— 表现就是
 * 「中英文交错的文字重叠」。对齐到 2 格后，程序写出的列号与网格格号一致，
 * 重绘才能正确覆盖。
 */
const val CELL_CONTINUATION: Char = '\u0000'

internal fun isHighSurrogate(ch: Char): Boolean = ch in '\uD800'..'\uDBFF'
internal fun isLowSurrogate(ch: Char): Boolean = ch in '\uDC00'..'\uDFFF'

/** BMP 双宽字符（存 1 个 Char + 1 个续格）。 */
internal fun isWideChar(ch: Char): Boolean =
    !isHighSurrogate(ch) && !isLowSurrogate(ch) && isWideCodePoint(ch.code)

/**
 * 码点显示宽度（0 / 1 / 2），对齐 xterm / `wcwidth` 约定。
 *
 * - 0：组合标记、零宽格式符（ZWJ / ZWNJ …）—— 网格按 1 格存不下，打印时跳过；
 * - 2：东亚全角（W/F）与常见 emoji —— 占 2 格；
 * - 其余（含歧义宽度）按 1 格 —— 与 go-runewidth 默认（ambiguous=1）一致。
 *
 * 范围取自 Unicode EastAsianWidth 的 W/F 段 + emoji 主区，够覆盖终端实际输出。
 */
internal fun isWideCodePoint(cp: Int): Boolean = when (cp) {
    in 0x1100..0x115F, // 谚文字母
    in 0x2E80..0x303E, // CJK 部首 / 康熙部首 / CJK 符号（U+303F 半填充是 1 宽）
    in 0x3041..0x33FF, // 假名 / 注音 / 拼音 / 汉字部件 / 制表兼容 / CJK 兼容
    in 0x3400..0x4DBF, // CJK 扩展 A
    in 0x4E00..0x9FFF, // CJK 统一汉字
    in 0xA000..0xA4CF, // 彝文
    in 0xA960..0xA97F, // 谚文字母扩展-A
    in 0xAC00..0xD7A3, // 谚文音节
    in 0xF900..0xFAFF, // CJK 兼容汉字
    in 0xFE10..0xFE19, // 竖排形式
    in 0xFE30..0xFE6F, // CJK 兼容形式 / 小写变体
    in 0xFF00..0xFF60, // 全角形式
    in 0xFFE0..0xFFE6, // 全角符号
    in 0x17000..0x18AFF, // 西夏文等
    in 0x1B000..0x1BFFF, // 假名补充 / 上千假名
    in 0x1F200..0x1F2FF, // 封闭表意文字补充
    in 0x1F300..0x1F64F, // 杂项符号与表情
    in 0x1F680..0x1F6FF, // 交通与地图符号
    in 0x1F900..0x1F9FF, // 补充符号与表情
    in 0x1FA00..0x1FAFF, // 符号与象形文字扩展-A
    in 0x20000..0x3FFFD -> true // CJK 扩展 B 及以后
    else -> false
}

/**
 * 码点占格数：0 = 零宽（组合标记 / 零宽格式符），1 = 半角，2 = 双宽。
 *
 * 零宽字符网格按 1 格存不下、按 0 格渲染层又会对不齐，打印时直接跳过
 * （与 TUI 侧 wcwidth=0 的光标推进一致，不会产生列错位）。
 */
internal fun cellWidthOf(cp: Int): Int {
    if (cp < 0x0300) return 1 // ASCII / Latin 快路径（控制字符走不到这里）
    val type = Character.getType(cp)
    val nonSpacingMark = Character.NON_SPACING_MARK.toInt()
    val enclosingMark = Character.ENCLOSING_MARK.toInt()
    val format = Character.FORMAT.toInt()
    if (type == nonSpacingMark || type == enclosingMark || type == format) return 0
    return if (isWideCodePoint(cp)) 2 else 1
}

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
 * 鼠标报告状态（DECSET `?1000/1002/1003` + SGR 编码 `?1006`）。
 *
 * TUI（opencode / lazygit / htop …）开启后期望滚轮/点击以鼠标序列发给自己
 * 处理 —— 此时触摸滑动应转发给 TUI，而不是滚本地 scrollback。
 */
data class TerminalMouseMode(
    /** 任一鼠标报告模式（1000/1002/1003）处于开启状态 */
    val tracking: Boolean = false,
    /** SGR 编码（1006）：坐标无上限且支持释放（`m`）事件 */
    val sgr: Boolean = false,
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
 *  - OSC 0/2 窗口标题、OSC 10/11 前景/背景查询（应答当前主题色）、OSC 8 超链接跳过；
 *    DCS/SOS/APC/PM 字符串跳过
 *  - 滚动区域 DECSTBM、备用屏幕 (?47/?1047/?1049)、插入模式 IRM、自动换行 DECAWM
 *  - 鼠标报告 (?1000/1002/1003/1006)：只记录状态供触摸手势分流，不参与渲染
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

    /** 需要回写给 PTY 的终端应答（DSR / DA / OSC 颜色查询）。 */
    var onResponse: ((String) -> Unit)? = null

    /**
     * OSC 10 / 11 前景 / 背景查询应答色（0xRRGGBB；-1 = 未知不作答）。
     * 由渲染层随当前主题写入：bubbletea / lipgloss 系 TUI（opencode 等）启动时
     * 查询终端背景色来选明暗主题，应答真实底色后它们不再按「深色终端」假设
     * 渲染，浅色主题下行尾未刷背景的部分不会再露出白底。
     */
    var queriedForeground: Int = -1
    var queriedBackground: Int = -1

    private val mouseModes = HashSet<Int>() // 已开启的鼠标报告模式（1000/1002/1003）
    private var mouseSgr = false // SGR 编码（1006）
    private val _mouseMode = MutableStateFlow(TerminalMouseMode())

    /** 鼠标报告状态：Compose 侧读取以分流触摸手势（发 TUI 滚轮还是滚 scrollback）。 */
    val mouseMode: StateFlow<TerminalMouseMode> = _mouseMode.asStateFlow()

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
                            // 代理对（emoji 等非 BMP 双宽字）必须成对处理：
                            // 两个 Char 分别落入连续两格，正好等于 2 列显示宽度
                            i += if (isHighSurrogate(ch) && i + 1 < n && isLowSurrogate(text[i + 1])) {
                                printSurrogatePair(ch, text[i + 1])
                            } else {
                                printChar(ch)
                            }
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

    /**
     * 光标回退并擦除最近打印的 n 个**字符**（命令拦截回滚用，含自动换行场景）。
     *
     * 双宽字 / 代理对按**整字**回退一次、只计 1 个字符（拦截器对中文回滚 1、
     * emoji 回滚 2，各自与显示宽度解耦），避免回滚后留出半截字或多余空格。
     */
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
            val ch = row.chars[cursorCol]
            var start = cursorCol
            var cost = 1
            var span = 1
            when {
                ch == CELL_CONTINUATION -> {
                    if (start > 0) start-- // 光标停在续格：整字回退
                    span = if (start != cursorCol) 2 else 1
                }

                isHighSurrogate(ch) && start + 1 < cols && isLowSurrogate(row.chars[start + 1]) -> {
                    cost = 2 // 代理对按整对擦（拦截器按 Char 计数 = 2）
                    span = 2
                }

                isLowSurrogate(ch) && start > 0 && isHighSurrogate(row.chars[start - 1]) -> {
                    start--
                    cost = 2
                    span = 2
                }

                isWideChar(ch) -> {
                    // 光标落在双宽字首格上：连同下一格（续格/孤儿残留）一并清掉
                    span = 2
                }
            }
            row.fill(start, (start + span).coerceAtMost(cols), curBg)
            cursorCol = start
            left -= cost
        }
        bump()
    }

    /**
     * 调整窗口尺寸（保留已有内容，光标始终留在屏内）。
     *
     * 两处最容易「丢字 / 丢行」的地方在这里兜住：
     *  1. **列数变小**：直接 [TerminalRow.resize] 会把行尾截掉 —— 先按新列宽把超宽的
     *     行拆成多行（只拆不合），行尾字符一个都不丢；
     *  2. **行数变小**：不能无脑「保留顶部」—— 光标行可能正好在要被丢掉的底部（键盘
     *     弹出、顶/底栏显隐动画都会触发），此时从**顶部**让位，让出的行进 scrollback。
     */
    fun resize(newCols: Int, newRows: Int) {
        val c = newCols.coerceAtLeast(2)
        val r = newRows.coerceAtLeast(2)
        if (c == cols && r == rows) return
        if (c != cols) normalizeColumns(c)
        grid = fitRowCount(grid, c, r, keepCursorVisible = true)
        mainGrid?.let { mainGrid = fitRowCount(it, c, r, keepCursorVisible = false) }
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

    /** 列宽变为 [c]：scrollback / 主屏 / 备用屏的行统一规整到 [c] 列宽。 */
    private fun normalizeColumns(c: Int) {
        if (scrollback.isNotEmpty()) {
            val rebuilt = ArrayList<TerminalRow>(scrollback.size + 2)
            for (row in scrollback) appendNormalized(row, c, rebuilt)
            scrollback.clear()
            rebuilt.forEach { scrollback.addLast(it) }
        }
        grid = normalizeRows(grid, c, cursorAware = true)
        mainGrid?.let { mainGrid = normalizeRows(it, c, cursorAware = false) }
    }

    /** 规整一屏行；[cursorAware] 时按拆分情况修正光标（当前屏用，备用屏不涉及光标）。 */
    private fun normalizeRows(
        source: Array<TerminalRow>,
        c: Int,
        cursorAware: Boolean,
    ): Array<TerminalRow> {
        if (source.isEmpty()) return source
        val out = ArrayList<TerminalRow>(source.size + 2)
        var shift = 0
        for (idx in source.indices) {
            val before = out.size
            appendNormalized(source[idx], c, out)
            val parts = out.size - before
            if (cursorAware && parts > 1) {
                when {
                    // 光标在拆出来的行之前：整体下移
                    idx < cursorRow -> shift += parts - 1
                    // 光标就在本行：落进它所属的那一段
                    idx == cursorRow -> {
                        shift += cursorCol / c
                        cursorCol %= c
                    }
                }
            }
        }
        if (cursorAware) cursorRow += shift
        return out.toTypedArray()
    }

    /**
     * 把 [row] 按 [c] 列宽规整后追加进 [out]：
     * 超宽且**行尾有内容**的行拆成多行（单元格样式整段搬过去）；其余情况只补空格或
     * 截掉尾部纯空格（不算丢字）。
     */
    private fun appendNormalized(row: TerminalRow, c: Int, out: MutableList<TerminalRow>) {
        row.sanitize() // 裁剪/滚动可能产生孤儿宽字或孤儿续格
        if (row.cols <= c) {
            if (row.cols != c) row.resize(c)
            out.add(row)
            return
        }
        var last = -1
        for (i in row.cols - 1 downTo 0) {
            if (row.chars[i] != ' ' || row.bg[i] != COLOR_DEFAULT) {
                last = i
                break
            }
        }
        if (last < c) {
            row.resize(c)
            out.add(row)
            return
        }
        var from = 0
        while (from < row.cols) {
            val part = TerminalRow(c)
            var to = minOf(from + c, row.cols)
            // 拆行点不许落在双宽字首格与续格（或代理对两半）之间，否则整字会被截断
            if (to < row.cols && to - 1 > from) {
                val atCut = row.chars[to - 1]
                val pairSplit = (isWideChar(atCut) && row.chars[to] == CELL_CONTINUATION) ||
                    (isHighSurrogate(atCut) && isLowSurrogate(row.chars[to]))
                if (pairSplit) to--
            }
            val n = to - from
            System.arraycopy(row.chars, from, part.chars, 0, n)
            System.arraycopy(row.fg, from, part.fg, 0, n)
            System.arraycopy(row.bg, from, part.bg, 0, n)
            System.arraycopy(row.attrs, from, part.attrs, 0, n)
            part.sanitize()
            out.add(part)
            from = to
        }
    }

    /**
     * 让一屏行数适配 [r]：不足补空行；超出且 [keepCursorVisible] 时先从顶部让位
     * （主屏常规滚动区让出的行进 scrollback，不丢内容），仍超出才丢底部（通常为空行）。
     */
    private fun fitRowCount(
        source: Array<TerminalRow>,
        c: Int,
        r: Int,
        keepCursorVisible: Boolean,
    ): Array<TerminalRow> {
        val list = ArrayList<TerminalRow>(maxOf(source.size, r) + 2)
        source.forEach { row ->
            row.resize(c)
            list.add(row)
        }
        if (list.size > r) {
            val overflow = if (keepCursorVisible) (cursorRow - (r - 1)).coerceAtLeast(0) else 0
            val saveToScrollback = keepCursorVisible &&
                mainGrid == null && scrollTop == 0 && scrollBottom == rows - 1
            repeat(overflow) {
                val top = list.removeAt(0)
                if (saveToScrollback) addToScrollback(top)
            }
            while (list.size > r) list.removeAt(list.size - 1)
        }
        while (list.size < r) list.add(TerminalRow(c))
        return list.toTypedArray()
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

    /**
     * 打印一个 BMP 字符：双宽字（中文 / 全角 …）占 2 格（次格写续格标记），
     * 半角占 1 格，零宽字符跳过。
     *
     * @return 消耗的 Char 数（恒为 1；代理对走 [printSurrogatePair]）。
     */
    private fun printChar(c: Char): Int {
        if (wrapPending) {
            wrapPending = false
            doWrap()
        }
        if (cursorCol >= cols) cursorCol = cols - 1
        when {
            isHighSurrogate(c) || isLowSurrogate(c) -> placeNarrow(c) // 孤立代理（几乎不出现）
            else -> when (cellWidthOf(c.code)) {
                0 -> Unit // 零宽：跳过，保持列对齐
                2 -> placeWide(c)
                else -> placeNarrow(c)
            }
        }
        return 1
    }

    /** 打印代理对（非 BMP 双宽字，如 emoji）：两个 Char 各占一格，合计 2 格 = 2 列。 */
    private fun printSurrogatePair(hi: Char, lo: Char): Int {
        if (wrapPending) {
            wrapPending = false
            doWrap()
        }
        if (cursorCol >= cols) cursorCol = cols - 1
        if (cellWidthOf(Character.toCodePoint(hi, lo)) == 0) return 2 // 零宽：跳过
        if (cursorCol >= cols - 1) {
            // 末列放不下 2 格：自动换行关闭时压到倒数第二列
            if (autowrap || cols < 2) doWrap() else cursorCol = cols - 2
        }
        val row = grid[cursorRow]
        if (insertMode) {
            row.insertBlank(cursorCol, 2)
            row.sanitize() // 插入点可能落在双宽字/代理对中间
        }
        clearOverwritten(row, cursorCol)
        clearOverwritten(row, cursorCol + 1)
        writeCell(row, cursorCol, hi)
        writeCell(row, cursorCol + 1, lo)
        lastPrinted = null // 代理对不支持 REP（CSI b）重印
        advanceAfterWide()
        return 2
    }

    /** 双宽字打印（首格字符 + 次格续格），并处理行尾换行。 */
    private fun placeWide(c: Char) {
        if (cursorCol >= cols - 1) {
            if (autowrap || cols < 2) doWrap() else cursorCol = cols - 2
        }
        val row = grid[cursorRow]
        if (insertMode) {
            row.insertBlank(cursorCol, 2)
            row.sanitize() // 插入点可能落在双宽字中间
        }
        clearOverwritten(row, cursorCol)
        clearOverwritten(row, cursorCol + 1)
        writeCell(row, cursorCol, c)
        writeCell(row, cursorCol + 1, CELL_CONTINUATION)
        lastPrinted = c
        advanceAfterWide()
    }

    private fun placeNarrow(c: Char) {
        val row = grid[cursorRow]
        if (insertMode) {
            row.insertBlank(cursorCol, 1)
            row.sanitize() // 插入点可能落在双宽字中间
        }
        clearOverwritten(row, cursorCol)
        writeCell(row, cursorCol, c)
        lastPrinted = c
        if (cursorCol >= cols - 1) {
            if (autowrap) wrapPending = true
        } else {
            cursorCol++
        }
    }

    /** 双宽字写完后的光标推进：占到行尾时按约定停在最后一格并挂起待换行。 */
    private fun advanceAfterWide() {
        cursorCol += 2
        if (cursorCol > cols - 1) {
            cursorCol = cols - 1
            if (autowrap) wrapPending = true
        }
    }

    private fun writeCell(row: TerminalRow, col: Int, ch: Char) {
        row.chars[col] = ch
        row.fg[col] = curFg
        row.bg[col] = curBg
        row.attrs[col] = curAttrs
    }

    /**
     * 覆盖清理：写入 [col] 前，把会被拆散的双宽结构补干净 ——
     *
     *  - 目标格是某双宽字的**续格** → 其首格一并清掉（不留半截字）；
     *  - 目标格是某双宽字的**首格** → 其续格一并清掉；
     *  - 代理对同理按整对处理。
     *
     * 这样网格里永远不会出现「宽字没有续格 / 续格没有宽字」的错位残片。
     */
    private fun clearOverwritten(row: TerminalRow, col: Int) {
        if (col < 0 || col >= row.cols) return
        when (val ch = row.chars[col]) {
            CELL_CONTINUATION -> if (col > 0) blankCell(row, col - 1)
            else -> when {
                isHighSurrogate(ch) && col + 1 < row.cols && isLowSurrogate(row.chars[col + 1]) ->
                    blankCell(row, col + 1)

                isLowSurrogate(ch) && col > 0 && isHighSurrogate(row.chars[col - 1]) ->
                    blankCell(row, col - 1)

                isWideChar(ch) && col + 1 < row.cols && row.chars[col + 1] == CELL_CONTINUATION ->
                    blankCell(row, col + 1)
            }
        }
    }

    private fun blankCell(row: TerminalRow, col: Int) {
        row.chars[col] = ' '
        row.fg[col] = COLOR_DEFAULT
        row.bg[col] = curBg
        row.attrs[col] = 0
    }

    /**
     * 擦除起点：光标停在双宽字续格上时回退一格，
     * 避免擦除时把宽字首格留成残影（表现就是重叠出来的半截字）。
     */
    private fun eraseStartCol(): Int {
        val col = cursorCol
        return if (col > 0 && col < cols && grid[cursorRow].chars[col] == CELL_CONTINUATION) {
            col - 1
        } else {
            col
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
        // 起点在双宽字续格上时回退一格，避免留半截宽字
        val start = eraseStartCol()
        when (mode) {
            0 -> {
                grid[cursorRow].fill(start, cols, curBg)
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
            0 -> row.fill(eraseStartCol(), cols, curBg)
            1 -> row.fill(0, (cursorCol + 1).coerceAtMost(cols), curBg)
            else -> row.fill(0, cols, curBg)
        }
    }

    private fun eraseChars(n: Int) {
        val row = grid[cursorRow]
        row.fill(eraseStartCol(), (cursorCol + n).coerceAtMost(cols), curBg)
    }

    private fun insertChars(n: Int) {
        grid[cursorRow].insertBlank(cursorCol, n.coerceAtMost(cols - cursorCol))
        grid[cursorRow].sanitize() // 插入点可能落在双宽字中间
    }

    private fun deleteChars(n: Int) {
        grid[cursorRow].delete(cursorCol, n.coerceAtMost(cols - cursorCol))
        grid[cursorRow].sanitize() // 删除点可能落在双宽字中间
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
        mouseModes.clear()
        mouseSgr = false
        publishMouseMode()
    }

    private fun publishMouseMode() {
        _mouseMode.value = TerminalMouseMode(tracking = mouseModes.isNotEmpty(), sgr = mouseSgr)
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
                    1000, 1002, 1003 -> {
                        if (enable) mouseModes.add(p) else mouseModes.remove(p)
                        publishMouseMode()
                    }
                    1006 -> {
                        mouseSgr = enable
                        publishMouseMode()
                    }
                    else -> Unit // 括号粘贴等模式：忽略但接受
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
        val arg = payload.substring(sep + 1).trim()
        when (code) {
            0, 2 -> {
                if (arg.isNotEmpty()) onTitle?.invoke(arg)
            }
            // OSC 10 / 11 颜色查询（"?"/"?" + BEL/ST）：应答当前主题前景 / 背景
            10, 11 -> if (arg == "?") {
                val rgb = if (code == 10) queriedForeground else queriedBackground
                if (rgb >= 0) onResponse?.invoke(
                    "\u001b]$code;rgb:${hex16(rgb shr 16 and 0xFF)}" +
                        "/${hex16(rgb shr 8 and 0xFF)}/${hex16(rgb and 0xFF)}\u0007",
                )
            }
            // OSC 8 超链接、OSC 7 等：正确跳过即可
        }
    }

    /** 8 位分量 → OSC 答复的 16 位十六进制（x*257 保持纯色不失真）。 */
    private fun hex16(v: Int): String = (v * 257).toString(16).padStart(4, '0')

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
        val shrinking = newCols < cols
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
        if (shrinking) sanitize() // 截断可能把双宽字/代理对劈成两半
    }

    /**
     * 修补被截断 / 插删拆散的双宽结构，保证「续格必有首格、首格必有续格」：
     *
     *  - 续格前面没有双宽字首格 → 清掉续格；
     *  - 双宽字首格后面没有续格（或代理对两半不相邻）→ 清掉残缺的一半。
     *
     * 只清不移位，不影响其余内容。渲染层依赖这条不变式。
     */
    fun sanitize() {
        var i = 0
        while (i < cols) {
            val ch = chars[i]
            when {
                ch == CELL_CONTINUATION -> {
                    if (i == 0 || !isWideChar(chars[i - 1])) blankCell(i)
                    i++ // 首格已在上一轮校验过
                }

                isHighSurrogate(ch) -> {
                    if (i + 1 < cols && isLowSurrogate(chars[i + 1])) {
                        i += 2 // 成对有效，低半区随行校验
                    } else {
                        blankCell(i)
                        i++ // 继续检查下一格（可能是孤儿续格）
                    }
                }

                isLowSurrogate(ch) -> blankCell(i) // 高半区缺失

                isWideChar(ch) -> {
                    if (i + 1 >= cols || chars[i + 1] != CELL_CONTINUATION) blankCell(i)
                    i += 2
                }

                else -> i++
            }
        }
    }

    private fun blankCell(i: Int) {
        chars[i] = ' '
        fg[i] = COLOR_DEFAULT
        attrs[i] = 0
        // 背景色保留：避免把整行底色带捅出一个透明洞
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

    /** 去掉行尾空白后的纯文本（双宽字续格是占位，不计入文本）。 */
    fun toText(): String {
        var end = cols
        while (end > 0 && (chars[end - 1] == ' ' || chars[end - 1] == CELL_CONTINUATION)) end--
        val sb = StringBuilder(end)
        for (i in 0 until end) {
            if (chars[i] != CELL_CONTINUATION) sb.append(chars[i])
        }
        return sb.toString()
    }
}
