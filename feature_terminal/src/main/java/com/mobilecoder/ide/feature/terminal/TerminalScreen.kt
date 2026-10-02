package com.mobilecoder.ide.feature.terminal

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mobilecoder.ide.core.common.theme.AppPalette
import com.mobilecoder.ide.core.common.theme.LocalAppPalette
import com.mobilecoder.ide.core.common.ui.AppAlertDialog
import com.mobilecoder.ide.core.common.ui.EmptyState
import com.mobilecoder.ide.core.common.ui.isImeVisible
import com.mobilecoder.ide.core.storage.AppStorage
import com.mobilecoder.ide.core.storage.HistoryStore
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 手势长按阈值（ms，Android/Compose 默认值）：超过即视为选区拖动而非滚轮。 */
private const val LONG_PRESS_TIMEOUT_MS = 500L

/**
 * 内联瞬时提示（替代 Toast / 弹窗）：显示在命令输入行正上方，
 * [durationMs] 毫秒后自动消失；[seq] 只用于让重复文案也能重触发计时。
 */
private data class InlineHint(
    val text: String,
    val isError: Boolean,
    val durationMs: Long,
    val seq: Int,
)

/**
 * PRD 2.3「内置终端」页面。
 *
 * 布局：会话标签行 → 终端渲染区（weight 1）→ 按键行 → 命令输入框。
 * 不添加 bottomBar（外层 app 已有底部导航）。
 *
 *  - 多终端并行：标签切换（TerminalManager 进程级单例持有，导航切换不丢数据）；
 *  - 自动绑定项目路径：进入页面即把会话 cwd 建到 projectPath；
 *  - 终端配色全部来自 [LocalAppPalette] 的 terminal* 字段 / MaterialTheme（深浅色自动切换）；
 *  - 长按选中复制（SelectionContainer）、scrollback 竖向滚动 + 自动贴底；
 *  - 鼠标报告模式（TUI 开 `?1000/1002/1006`）下触摸转滚轮/点击发给程序，
 *    双指竖滑仍滚本地历史；未开启时手势行为不变；
 *  - 溢出菜单：重命名 / 清屏 / 导出日志 / 关闭全部 / 显示或隐藏按键行。
 */
@Composable
fun TerminalScreen(
    projectPath: String,
    modifier: Modifier = Modifier,
) {
    val palette = LocalAppPalette.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val clipboard = LocalClipboardManager.current

    val sessions by TerminalManager.sessions.collectAsStateWithLifecycle()
    val activeId by TerminalManager.activeId.collectAsStateWithLifecycle()
    val errorMessage by TerminalManager.error.collectAsStateWithLifecycle()
    val noticeMessage by TerminalManager.notice.collectAsStateWithLifecycle()

    val active = sessions.firstOrNull { it.id == activeId } ?: sessions.firstOrNull()

    // ---- 字号（AppPreferences 持久化；修改入口在全局「设置」页） ----
    var fontSize by remember { mutableIntStateOf(13) }
    LaunchedEffect(Unit) {
        runCatching { fontSize = AppStorage.preferences.terminalFontSize() }
    }

    // ---- 终端度量：字符宽 / 行高 → cols/rows ----
    val textMeasurer = rememberTextMeasurer()
    val termStyle = remember(fontSize) {
        TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = fontSize.sp,
            lineHeight = (fontSize * 1.2f).sp,
        )
    }
    val metrics = remember(textMeasurer, termStyle) {
        val size = textMeasurer.measure("M", style = termStyle).size
        Pair(size.width.coerceAtLeast(1), size.height.coerceAtLeast(1))
    }
    var areaSize by remember { mutableStateOf(IntSize.Zero) }
    val cols = if (areaSize.width > 0) {
        (areaSize.width / metrics.first).coerceIn(10, 400)
    } else {
        TerminalManager.DEFAULT_COLS
    }
    val rows = if (areaSize.height > 0) {
        (areaSize.height / metrics.second).coerceIn(4, 200)
    } else {
        TerminalManager.DEFAULT_ROWS
    }

    // ---- 自动绑定项目路径（PRD 2.3） ----
    LaunchedEffect(projectPath) {
        val target = projectPath.trim().trimEnd('/')
        if (target.isBlank()) return@LaunchedEffect
        val forPath = TerminalManager.sessions.value.filter { it.cwd.trimEnd('/') == target }
        val alive = forPath.firstOrNull { it.exitCode.value == null }
        when {
            alive != null -> TerminalManager.select(alive.id)
            forPath.isNotEmpty() -> TerminalManager.select(forPath.last().id)
            else -> TerminalManager.create(cols, rows, target)
        }
    }

    // ---- 尺寸变化 → TIOCSWINSZ ----
    LaunchedEffect(active?.id, cols, rows) { active?.resize(cols, rows) }

    // ---- 输入框状态 ----
    var input by rememberSaveable { mutableStateOf("") }
    var ctrlOn by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    // 软键盘可见性：驱动按键行自动收起（④）+ 键盘图标方向
    val imeVisible = isImeVisible()
    // 按键行显隐：默认跟随键盘（弹出即收起，把终端渲染区让出来）；溢出菜单可手动固定，
    // 手动值用 rememberSaveable 持久化 —— 旋转 / 进程重建后仍然有效。
    var keysForced by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val showKeys = keysForced ?: !imeVisible

    // ---- 内联瞬时提示：显示在输入行正上方，到时自动消失（不弹窗、不用 Toast） ----
    var inlineHint by remember { mutableStateOf<InlineHint?>(null) }
    var inlineHintSeq by remember { mutableIntStateOf(0) }
    LaunchedEffect(inlineHint) {
        val hint = inlineHint ?: return@LaunchedEffect
        delay(hint.durationMs)
        inlineHint = null
    }

    /** 触发一条内联提示：[isError] 用错误色，[durationMs] 后自动消失。 */
    fun showInlineHint(text: String, isError: Boolean = false, durationMs: Long = 2500L) {
        inlineHintSeq += 1
        inlineHint = InlineHint(
            text = text,
            isError = isError,
            durationMs = durationMs,
            seq = inlineHintSeq,
        )
    }

    fun submitLine() {
        val session = active ?: return
        val text = input
        input = ""
        val exit = session.exitCode.value
        if (exit != null) {
            showInlineHint("会话已退出（code=$exit），请先点击标签上的重启按钮。", isError = true)
            return
        }
        if (ctrlOn) {
            ctrlOn = false
            if (text.isNotEmpty()) {
                val c = text[0]
                val code = when {
                    c in 'a'..'z' -> c - 'a' + 1
                    c in 'A'..'Z' -> c - 'A' + 1
                    c == '[' -> 27
                    c == '\\' -> 28
                    c == ']' -> 29
                    c == '^' -> 30
                    c == ' ' || c == '@' || c == '_' -> 0
                    else -> c.code and 0x1F
                }
                session.sendControlChar(code)
            }
            val rest = if (text.isEmpty()) "" else text.substring(1)
            if (rest.isNotEmpty()) session.submitLine(rest)
            return
        }
        if (text.isNotBlank()) {
            val line = text
            scope.launch { runCatching { HistoryStore.add(line, HistoryStore.SOURCE_TERMINAL) } }
        }
        session.submitLine(text)
    }

    // ---- 历史命令下拉：点 ↑ 展开最近命令列表，点选回填输入框（不自动发送） ----
    var historyOpen by remember { mutableStateOf(false) }
    var historyItems by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(historyOpen) {
        if (!historyOpen) return@LaunchedEffect
        runCatching { HistoryStore.ensureLoaded() }
        historyItems = HistoryStore.terminalCommands()
    }

    // ---- 粘贴：读剪贴板并追加到输入框（不自动发送）；空剪贴板给内联提示 ----
    fun pasteFromClipboard() {
        val text = clipboard.getText()?.text
        if (text.isNullOrEmpty()) {
            showInlineHint("剪贴板为空", durationMs = 2000L)
            return
        }
        // 换行保留（多行命令），只剥回车符
        input = (input + text).filter { it != '\r' }.take(4000)
    }

    // ---- 溢出菜单 / 弹窗状态 ----
    var menuOpen by remember { mutableStateOf(false) }
    var renameOpen by remember { mutableStateOf(false) }
    var renameText by rememberSaveable { mutableStateOf("") }
    var closeAllConfirm by remember { mutableStateOf(false) }

    // 命令输入行实测宽度：历史下拉按整行宽度展开（≈ matchParent）
    var inputRowWidthPx by remember { mutableStateOf(0) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .imePadding(),
    ) {
        // ---------------- 会话标签行 ----------------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                sessions.forEach { session ->
                    key(session.id) {
                        TerminalTab(
                            session = session,
                            selected = active?.id == session.id,
                            onSelect = { TerminalManager.select(session.id) },
                            onClose = { TerminalManager.close(session.id) },
                            onRestart = { TerminalManager.restart(session.id) },
                        )
                    }
                }
                IconButton(onClick = { TerminalManager.create(cols, rows, projectPath) }) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "新建会话",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            IconButton(onClick = { menuOpen = true }) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "更多操作",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("重命名") },
                    onClick = {
                        menuOpen = false
                        renameText = active?.title?.value ?: ""
                        renameOpen = true
                    },
                )
                DropdownMenuItem(
                    text = { Text("清屏") },
                    onClick = {
                        menuOpen = false
                        active?.emulator?.clearAll()
                    },
                )
                DropdownMenuItem(
                    text = { Text("导出日志") },
                    onClick = {
                        menuOpen = false
                        val file = active?.let { TerminalManager.exportLog(it) }
                        if (file != null) {
                            TerminalManager.showNotice("日志已导出：\n${file.absolutePath}")
                        } else {
                            TerminalManager.showNotice("导出失败：没有可导出的终端日志或写入失败。")
                        }
                    },
                )
                DropdownMenuItem(
                    text = { Text("关闭全部") },
                    onClick = {
                        menuOpen = false
                        closeAllConfirm = true
                    },
                )
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(if (showKeys) "隐藏按键行" else "显示按键行") },
                    onClick = { keysForced = !showKeys },
                )
            }
        }

        // ---------------- 终端渲染区 ----------------
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .onSizeChanged { areaSize = it }
                .background(Color(0xFF000000 or palette.terminalBackground.rgb)),
        ) {
            val session = active
            if (session == null) {
                EmptyState(
                    title = "暂无终端会话",
                    subtitle = "点击上方 + 新建会话（最多 ${TerminalManager.MAX_SESSIONS} 个）",
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                TerminalViewport(
                    session = session,
                    palette = palette,
                    style = termStyle,
                    availableWidthPx = areaSize.width,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // ---------------- 底部按键行 ----------------
        // ④ 键盘弹出时自动收起（终端渲染区立刻多出整行），溢出菜单可手动固定显隐
        AnimatedVisibility(
            visible = showKeys,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    KeyButton("Esc") {
                        active?.sendSpecial(TerminalSpecialKey.ESC, ctrlOn)
                        ctrlOn = false
                    }
                    KeyButton("Tab") {
                        active?.sendSpecial(TerminalSpecialKey.TAB, ctrlOn)
                        ctrlOn = false
                    }
                    KeyButton("Ctrl", selected = ctrlOn) { ctrlOn = !ctrlOn }
                    KeyButton("←") {
                        active?.sendSpecial(TerminalSpecialKey.LEFT, ctrlOn)
                        ctrlOn = false
                    }
                    KeyButton("↑") {
                        active?.sendSpecial(TerminalSpecialKey.UP, ctrlOn)
                        ctrlOn = false
                    }
                    KeyButton("→") {
                        active?.sendSpecial(TerminalSpecialKey.RIGHT, ctrlOn)
                        ctrlOn = false
                    }
                    KeyButton("↓") {
                        active?.sendSpecial(TerminalSpecialKey.DOWN, ctrlOn)
                        ctrlOn = false
                    }
                    KeyButton("Home") {
                        active?.sendSpecial(TerminalSpecialKey.HOME, ctrlOn)
                        ctrlOn = false
                    }
                    KeyButton("End") {
                        active?.sendSpecial(TerminalSpecialKey.END, ctrlOn)
                        ctrlOn = false
                    }
                    KeyButton("PgUp") {
                        active?.sendSpecial(TerminalSpecialKey.PAGE_UP, ctrlOn)
                        ctrlOn = false
                    }
                    KeyButton("PgDn") {
                        active?.sendSpecial(TerminalSpecialKey.PAGE_DOWN, ctrlOn)
                        ctrlOn = false
                    }
                    KeyButton("⌫") { active?.sendBackspace() }
                    // 换行：往输入框追加真实换行符（多行命令）。输入框不记录光标位置，
                    // 追加到末尾 —— 与粘贴多行、物理回车的行为一致。
                    KeyButton("换行") { input = input + "\n" }
                }
            }
        }

        // ---------------- 命令输入框 ----------------
        val current = active
        if (current != null && current.cliRunning.collectAsStateWithLifecycle().value) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(2.dp),
            )
        }
        // 内联瞬时提示：紧贴输入行上方，到时自动消失（替代弹窗 / Toast）
        inlineHint?.let { hint ->
            Text(
                text = hint.text,
                style = MaterialTheme.typography.bodySmall,
                color = if (hint.isError) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 8.dp)
                .onSizeChanged { inputRowWidthPx = it.width },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // ↑ 历史命令：展开最近命令列表，点选回填输入框（不自动发送）
            Box {
                IconButton(onClick = { historyOpen = true }) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowUp,
                        contentDescription = "历史命令",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DropdownMenu(
                    expanded = historyOpen,
                    onDismissRequest = { historyOpen = false },
                    // 菜单宽度 ≈ 整个输入行（首次布局前退化为最小宽度）
                    modifier = if (inputRowWidthPx > 0) {
                        Modifier.width(with(LocalDensity.current) { inputRowWidthPx.toDp() })
                    } else {
                        Modifier.widthIn(min = 280.dp)
                    },
                ) {
                    if (historyItems.isEmpty()) {
                        Text(
                            text = "暂无历史命令",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    } else {
                        // ⚠ 这里不能用 LazyColumn：DropdownMenu 打开时会按 IntrinsicSize 对菜单
                        // 内容做内在测量（intrinsic），LazyColumn 是 SubcomposeLayout 不支持
                        // intrinsic，一展开就抛 IllegalStateException（"Asking for intrinsic
                        // measurements of SubcomposeLayout layouts is not supported"）闪退。
                        // 换成普通 Column 自己滚（上限 280dp，与原 UX 一致）。
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 280.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            historyItems.forEach { command ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            // 多行记录：换行符显示成 ⏎，单行内看清块结构
                                            text = command.replace("\n", " ⏎ "),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            fontFamily = FontFamily.Monospace,
                                        )
                                    },
                                    onClick = {
                                        input = command
                                        historyOpen = false
                                    },
                                )
                            }
                        }
                    }
                }
            }
            // 粘贴：剪贴板内容追加到输入框（不自动发送）
            IconButton(onClick = { pasteFromClipboard() }) {
                Icon(
                    imageVector = Icons.Default.ContentPaste,
                    contentDescription = "粘贴",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            BasicTextField(
                value = input,
                onValueChange = { value ->
                    // 多行命令：\n 保留（换行键 / 粘贴 / 物理回车），只剥 \r；
                    // 发送时整块交给 shell 逐行执行（见 TerminalSession.submitLine）
                    input = value.filter { it != '\r' }.take(4000)
                },
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester)
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(8.dp),
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    // 多行命令可视区：上限 120dp（约 6 行），超出在框内滚动，
                    // 不让输入框把上方终端区整个顶掉
                    .heightIn(max = 120.dp)
                    .verticalScroll(rememberScrollState()),
                // 关键：singleLine=true 会把文本压成一屏横向滚动，多行命令看不见全貌。
                // 换行来自「换行」键 / 粘贴 / 物理回车，onValueChange 只剥 \r，\n 保留；
                // 回车（ImeAction.Send）= 把整块（含换行）发给 shell 逐行执行。
                singleLine = false,
                textStyle = TextStyle(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Send,
                ),
                keyboardActions = KeyboardActions(onSend = { submitLine() }),
                decorationBox = { inner ->
                    Box {
                        if (input.isEmpty()) {
                            Text(
                                text = "输入命令，发送（回车）执行…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        inner()
                    }
                },
            )
            IconButton(
                onClick = {
                    keyboard?.show()
                    submitLine()
                },
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "发送",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }

    // ---------------- 弹窗 ----------------
    if (errorMessage != null) {
        AppAlertDialog(
            title = "终端错误",
            message = errorMessage.orEmpty(),
            onDismiss = { TerminalManager.clearError() },
            confirmLabel = "重试",
            onConfirm = { TerminalManager.retry() },
            dismissLabel = "关闭",
        )
    }
    if (noticeMessage != null) {
        AppAlertDialog(
            title = "提示",
            message = noticeMessage.orEmpty(),
            onDismiss = { TerminalManager.clearNotice() },
            confirmLabel = "确定",
        )
    }
    if (renameOpen) {
        AlertDialog(
            onDismissRequest = { renameOpen = false },
            modifier = Modifier.imePadding(),
            title = { Text("重命名会话") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    label = { Text("标签名") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    active?.rename(renameText)
                    renameOpen = false
                }) {
                    Text("确定", color = MaterialTheme.colorScheme.primary)
                }
            },
            dismissButton = {
                TextButton(onClick = { renameOpen = false }) {
                    Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
        )
    }
    if (closeAllConfirm) {
        AppAlertDialog(
            title = "关闭全部会话",
            message = "确定关闭所有终端会话吗？正在运行的后台任务将被终止。",
            onDismiss = { closeAllConfirm = false },
            confirmLabel = "关闭全部",
            onConfirm = {
                closeAllConfirm = false
                TerminalManager.closeAll()
            },
            dismissLabel = "取消",
            destructive = true,
        )
    }
}

// ---------------------------------------------------------------------------
// 会话标签
// ---------------------------------------------------------------------------

@Composable
private fun TerminalTab(
    session: TerminalSession,
    selected: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit,
    onRestart: () -> Unit,
) {
    val title by session.title.collectAsStateWithLifecycle()
    val exitCode by session.exitCode.collectAsStateWithLifecycle()

    Surface(
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 4.dp, end = 2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(
                modifier = Modifier
                    .clickable(onClick = onSelect)
                    .padding(start = 10.dp, top = 4.dp, bottom = 4.dp, end = 2.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 120.dp),
                )
                if (exitCode != null) {
                    Text(
                        text = "已退出 code=$exitCode",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 1,
                    )
                }
            }
            if (exitCode != null) {
                IconButton(onClick = onRestart, modifier = Modifier.size(30.dp)) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "重启会话",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            IconButton(onClick = onClose, modifier = Modifier.size(30.dp)) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "关闭会话",
                    modifier = Modifier.size(16.dp),
                    tint = if (selected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 终端渲染区
// ---------------------------------------------------------------------------

/**
 * 终端视口：SelectionContainer（长按选中复制）+ LazyColumn（scrollback 竖向滚动）。
 *
 * 读取 [TerminalEmulator.version] 触发行级重绘；每帧最多重建一次可见行
 * （16ms 节流的解析在 TerminalSession 完成）。
 */
@Composable
private fun TerminalViewport(
    session: TerminalSession,
    palette: AppPalette,
    style: TextStyle,
    availableWidthPx: Int,
    modifier: Modifier = Modifier,
) {
    val emulator = session.emulator
    val version by emulator.version.collectAsStateWithLifecycle()
    val total = emulator.lineCount()
    val cursorLine = emulator.cursorLineIndex()
    val listState = rememberLazyListState()

    // 鼠标报告模式：单指滑动改为发滚轮给 TUI，本地滚动（userScrollEnabled）停用
    val mouseOn = emulator.mouseMode.collectAsStateWithLifecycle().value.tracking

    // 贴底跟随：仅当用户本就在底部时自动滚到最新
    val followBottom = remember {
        androidx.compose.runtime.derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            last < 0 || last >= info.totalItemsCount - 2
        }
    }
    var prevTotal by remember { mutableIntStateOf(total) }
    LaunchedEffect(version, total) {
        val delta = total - prevTotal
        prevTotal = total
        if (total <= 0) return@LaunchedEffect
        if (followBottom.value) {
            listState.scrollToItem(total - 1)
        } else if (delta != 0) {
            // scrollback 顶部新增/清空时保持视觉位置稳定
            listState.scrollToItem((listState.firstVisibleItemIndex + delta).coerceIn(0, total - 1))
        }
    }

    // 进入鼠标报告模式先对齐屏底：触点 → 字符格的换算才与屏幕行一一对应
    LaunchedEffect(mouseOn) {
        if (mouseOn) listState.scrollToItem(emulator.lineCount() - 1)
    }

    // 选中高亮色由 MobileCoderTheme 全局提供（LocalTextSelectionColors），
    // 这里不再局部覆盖，保证与 CLI 日志 / 输入框 / 编辑器是同一套颜色。
    SelectionContainer(
        modifier = modifier.background(Color(0xFF000000 or palette.terminalBackground.rgb)),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .terminalMouseGestures(session, mouseOn, listState, availableWidthPx),
            userScrollEnabled = !mouseOn,
        ) {
            items(total) { index ->
                val line = remember(version, index, palette) { emulator.lineAt(index) }
                TerminalLineText(
                    line = line,
                    isCursorLine = index == cursorLine,
                    palette = palette,
                    style = style,
                    availableWidthPx = availableWidthPx,
                )
            }
        }
    }
}

/**
 * 鼠标报告模式（DECSET `?1000/1002/1003` 开启时）下的触摸手势。
 *
 * 手机上竖向滑动只有这一个手势，按桌面终端的约定分流：
 *  - 单指竖滑 → 滚轮事件发给 TUI（每滑过一行发一次），opencode / lazygit
 *    这类程序收到后自己滚动面板；
 *  - 双指竖滑 → 照旧滚本地 scrollback（TUI 运行期间回看 shell 历史的通道）；
 *  - 短按不移 → 左键点击（SGR 模式补发 release）。
 *
 * 全程不消费事件：长按选中复制（SelectionContainer）不受影响；
 * 起手静止超过长按阈值即判定为选区拖动，本手势不再发滚轮。
 */
private fun Modifier.terminalMouseGestures(
    session: TerminalSession,
    enabled: Boolean,
    listState: LazyListState,
    viewportWidthPx: Int,
): Modifier = this.pointerInput(enabled, session, listState, viewportWidthPx) {
    if (!enabled) return@pointerInput
    val emulator = session.emulator

    /** 行高（px）：相邻两个可视行的 offset 差；不足两行时返回 0。 */
    fun measuredRowHeight(): Int {
        val items = listState.layoutInfo.visibleItemsInfo
        return if (items.size >= 2) items[1].offset - items[0].offset else 0
    }

    /** 触点 → 1 基字符格（行索引 = firstVisible + 行内偏移，行高按可视行实测）。 */
    fun cellAt(x: Float, y: Float): Pair<Int, Int> {
        val rowH = measuredRowHeight()
        val index = if (rowH > 0) {
            listState.firstVisibleItemIndex +
                ((y + listState.firstVisibleItemScrollOffset) / rowH).toInt()
        } else {
            listState.firstVisibleItemIndex
        }
        val row = (index - emulator.scrollbackSize() + 1).coerceIn(1, emulator.rows)
        val col = if (viewportWidthPx > 0) {
            (x * emulator.cols / viewportWidthPx).toInt().coerceIn(0, emulator.cols - 1) + 1
        } else {
            1
        }
        return col to row
    }

    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val start = down.position
        val startUptime = down.uptimeMillis
        var endPos = start
        var endUptime = startUptime
        var maxDist = 0f
        var multi = false
        var selectMode = false
        var prevAvgY = start.y
        var converted = 0f // 已换算成滚轮的位移（向下为正）

        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            endPos = change.position
            endUptime = change.uptimeMillis
            maxDist = maxOf(maxDist, abs(endPos.x - start.x), abs(endPos.y - start.y))
            if (!change.pressed) break

            // 起手静止超过长按阈值（Android 默认 500ms）= 长按选中已触发：
            // 本手势是选区拖动，不再发滚轮
            if (!selectMode && maxDist < viewConfiguration.touchSlop &&
                endUptime - startUptime >= LONG_PRESS_TIMEOUT_MS
            ) {
                selectMode = true
            }

            val pressed = event.changes.filter { it.pressed }
            if (pressed.size >= 2) {
                // 双指：滚本地 scrollback（手指上滑 → 内容上移 → delta 为正）
                val avgY = pressed.map { it.position.y }.average().toFloat()
                if (multi) listState.dispatchRawDelta(prevAvgY - avgY)
                multi = true
                prevAvgY = avgY
                continue
            }
            if (multi || selectMode) continue

            val rowH = measuredRowHeight().toFloat()
            if (rowH <= 0f) continue
            val dy = endPos.y - start.y
            var batch = 0
            while (dy - converted >= rowH && batch < 8) {
                val (col, row) = cellAt(endPos.x, endPos.y)
                session.sendMouseButton(TerminalMouse.WHEEL_DOWN, col, row)
                converted += rowH
                batch++
            }
            while (dy - converted <= -rowH && batch < 8) {
                val (col, row) = cellAt(endPos.x, endPos.y)
                session.sendMouseButton(TerminalMouse.WHEEL_UP, col, row)
                converted -= rowH
                batch++
            }
        }

        // 短按不移 → 左键点击（长按是选中复制，走不到这里）
        if (!multi && !selectMode && maxDist < viewConfiguration.touchSlop &&
            endUptime - startUptime < 300
        ) {
            val (col, row) = cellAt(start.x, start.y)
            session.sendMouseClick(col, row)
        }
    }
}

/**
 * 单行终端文本。
 *
 * 网格里 1 个单元格按 1 个字符算，但**字形未必等宽**：中文 / 制表符 / emoji 等
 * 缺字时走回退字体，明显比测宽用的 `M` 宽；滚动区里按旧列数存留的行也可能比
 * 当前视口宽。这些行若直接交给容器约束，超出部分会被裁掉 —— 表现就是
 * 「换行后行尾缺字符」。
 *
 * 这里改用**自然宽度**（不给宽度上限）测量该行，测量结果放不下时整体 `scaleX`
 * 压到刚好铺满可视宽度：一个字符都不会丢，且宽度本来就够的行完全不受影响。
 */
@Composable
private fun TerminalLineText(
    line: TerminalLine,
    isCursorLine: Boolean,
    palette: AppPalette,
    style: TextStyle,
    availableWidthPx: Int,
) {
    val annotated = remember(line, palette, isCursorLine) {
        buildTerminalLine(line, palette, isCursorLine)
    }
    Layout(
        content = {
            Text(
                text = annotated,
                style = style,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
            )
        },
    ) { measurables, constraints ->
        val placeable = measurables.first().measure(
            constraints.copy(minWidth = 0, maxWidth = Constraints.Infinity),
        )
        val natural = placeable.width
        // 可视宽度：优先用父约束（LazyColumn 行约束），退化时用视口实测宽度
        val maxWidth = if (constraints.maxWidth == Constraints.Infinity) {
            availableWidthPx.coerceAtLeast(1)
        } else {
            constraints.maxWidth
        }
        val fits = natural <= maxWidth
        val width = when {
            constraints.maxWidth == Constraints.Infinity -> maxWidth.coerceAtLeast(constraints.minWidth)
            else -> constraints.maxWidth
        }
        layout(width, placeable.height) {
            if (fits) {
                placeable.place(0, 0)
            } else {
                // 以左上角为锚点横向压缩：scaleY 保持 1，行高、行距都不变
                placeable.placeWithLayer(0, 0) {
                    scaleX = maxWidth.toFloat() / natural
                    transformOrigin = TransformOrigin(0f, 0f)
                }
            }
        }
    }
}

/** 把一屏行转成 AnnotatedString（SGR 样式 + 光标反色块）。 */
private fun buildTerminalLine(
    line: TerminalLine,
    palette: AppPalette,
    isCursorLine: Boolean,
): AnnotatedString = buildAnnotatedString {
    var col = 0
    line.runs.forEach { run ->
        val start = length
        val len = run.text.length
        append(run.text)

        var fg = resolveColor(run.fg, palette, true)
        var bg = resolveColor(run.bg, palette, false)
        if (run.attrs and Attr.INVERSE != 0) {
            val swap = fg
            fg = bg
            bg = swap
        }

        // 默认底色不画：容器已经铺了 terminalBackground，再画一遍只是遮挡。
        // 更关键的是 Compose 的选中高亮画在文本节点**之下**（SelectionController.modifier
        // 在 selectableTextModifier 之前进链），整行铺一层不透明 background 会把它
        // 完全盖住 —— 表现为「选中了却没有高亮」。
        // 显式底色（SGR / 反色 / 光标块）本来就该盖住高亮，照常画。
        val spanBg =
            if (run.bg == COLOR_DEFAULT && (run.attrs and Attr.INVERSE) == 0) {
                Color.Transparent
            } else {
                bg
            }

        addStyle(
            SpanStyle(
                color = if (run.attrs and Attr.DIM != 0) fg.copy(alpha = 0.72f) else fg,
                background = spanBg,
                fontWeight = if (run.attrs and Attr.BOLD != 0) FontWeight.Bold else null,
                fontStyle = if (run.attrs and Attr.ITALIC != 0) FontStyle.Italic else null,
                textDecoration = when {
                    run.attrs and Attr.UNDERLINE != 0 -> TextDecoration.Underline
                    run.attrs and Attr.STRIKE != 0 -> TextDecoration.LineThrough
                    else -> null
                },
            ),
            start,
            start + len,
        )

        val cursorAt = line.cursorCol
        if (isCursorLine && cursorAt >= col && cursorAt < col + len) {
            val at = start + (cursorAt - col)
            // 光标：与单元格底色互换的反色块
            addStyle(SpanStyle(color = bg, background = fg), at, at + 1)
        }
        col += len
    }
}

// ---------------------------------------------------------------------------
// 终端配色（全部派生自 LocalAppPalette 的 terminal* 字段，深浅色自动切换）
// ---------------------------------------------------------------------------

private fun defaultFg(palette: AppPalette): Color = Color(0xFF000000 or palette.terminalForeground.rgb)

private fun defaultBg(palette: AppPalette): Color = Color(0xFF000000 or palette.terminalBackground.rgb)

/** 颜色值 → Compose Color（-1 默认 / 0..255 ANSI-256 / >=TRUECOLOR_FLAG 真彩）。 */
private fun resolveColor(value: Int, palette: AppPalette, isFg: Boolean): Color = when {
    value == COLOR_DEFAULT -> if (isFg) defaultFg(palette) else defaultBg(palette)
    value >= TRUECOLOR_FLAG -> Color(0xFF000000L or (value and 0xFFFFFF).toLong())
    value in 0..255 -> indexedColor(value, palette)
    else -> if (isFg) defaultFg(palette) else defaultBg(palette)
}

/** ANSI-256 调色板：0..15 主题派生，16..231 6x6x6 立方，232..255 灰阶。 */
private fun indexedColor(index: Int, palette: AppPalette): Color {
    if (index <= 15) {
        val fg = defaultFg(palette)
        val bg = defaultBg(palette)
        val base = when (index) {
            0 -> lerp(bg, fg, 0.30f) // 黑：主题背景与前景之间派生
            1 -> Color(0xFF000000 or palette.terminalRed.rgb)
            2 -> Color(0xFF000000 or palette.terminalGreen.rgb)
            3 -> Color(0xFF000000 or palette.terminalYellow.rgb)
            4 -> Color(0xFF000000 or palette.terminalBlue.rgb)
            5 -> Color(0xFF000000 or palette.terminalMagenta.rgb)
            6 -> Color(0xFF000000 or palette.terminalCyan.rgb)
            7 -> Color(0xFF000000 or palette.terminalWhite.rgb)
            8 -> lerp(bg, fg, 0.50f) // 亮黑
            in 9..14 -> {
                val normal = when (index - 8) {
                    1 -> palette.terminalRed
                    2 -> palette.terminalGreen
                    3 -> palette.terminalYellow
                    4 -> palette.terminalBlue
                    5 -> palette.terminalMagenta
                    else -> palette.terminalCyan
                }
                lerp(Color(0xFF000000 or normal.rgb), fg, 0.35f)
            }
            else -> fg // 15：亮白 = 前景色
        }
        return base
    }
    if (index in 16..231) {
        val offset = index - 16
        val levels = intArrayOf(0, 95, 135, 175, 215, 255)
        val r = levels[offset / 36]
        val g = levels[(offset / 6) % 6]
        val b = levels[offset % 6]
        return Color(0xFF000000L or (r.toLong() shl 16) or (g.toLong() shl 8) or b.toLong())
    }
    val gray = (8 + (index - 232) * 10).coerceIn(0, 255)
    return Color(0xFF000000L or (gray.toLong() shl 16) or (gray.toLong() shl 8) or gray.toLong())
}

// ---------------------------------------------------------------------------
// 按键行
// ---------------------------------------------------------------------------

@Composable
private fun KeyButton(
    label: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center,
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
            )
        }
    }
}
