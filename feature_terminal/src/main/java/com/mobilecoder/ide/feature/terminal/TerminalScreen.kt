package com.mobilecoder.ide.feature.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardHide
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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onSizeChanged
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
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mobilecoder.ide.core.common.theme.AppPalette
import com.mobilecoder.ide.core.common.theme.LocalAppPalette
import com.mobilecoder.ide.core.common.ui.AppAlertDialog
import com.mobilecoder.ide.core.common.ui.EmptyState
import com.mobilecoder.ide.core.storage.AppStorage
import com.mobilecoder.ide.feature.git.GitController
import kotlinx.coroutines.launch

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
 *  - 溢出菜单：新建 / 重命名 / 清屏 / 导出日志 / 关闭全部 / 字号调节。
 */
@Composable
fun TerminalScreen(
    projectPath: String,
    modifier: Modifier = Modifier,
) {
    val palette = LocalAppPalette.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current

    val sessions by TerminalManager.sessions.collectAsStateWithLifecycle()
    val activeId by TerminalManager.activeId.collectAsStateWithLifecycle()
    val errorMessage by TerminalManager.error.collectAsStateWithLifecycle()
    val noticeMessage by TerminalManager.notice.collectAsStateWithLifecycle()
    val gitHead by GitController.head.collectAsStateWithLifecycle()

    // ---- 绑定仓库：拿到当前分支（非 Git 仓库时 head 为 null，徽标隐藏） ----
    LaunchedEffect(projectPath) {
        runCatching { GitController.bind(projectPath) }
    }

    val active = sessions.firstOrNull { it.id == activeId } ?: sessions.firstOrNull()

    // ---- 字号（AppPreferences 持久化） ----
    var fontSize by remember { mutableIntStateOf(13) }
    LaunchedEffect(Unit) {
        runCatching { fontSize = AppStorage.preferences.terminalFontSize() }
    }

    fun changeFontSize(delta: Int) {
        val next = (fontSize + delta).coerceIn(9, 24)
        fontSize = next
        scope.launch { runCatching { AppStorage.preferences.setTerminalFontSize(next) } }
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
    var historyIndex by remember { mutableIntStateOf(-1) }
    val focusRequester = remember { FocusRequester() }

    fun submitLine() {
        val session = active ?: return
        val text = input
        input = ""
        historyIndex = -1
        val exit = session.exitCode.value
        if (exit != null) {
            TerminalManager.showNotice("会话已退出（code=$exit），请先点击标签上的重启按钮。")
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
            scope.launch { runCatching { AppStorage.preferences.addTerminalHistory(line) } }
        }
        session.submitLine(text)
    }

    fun pickHistory() {
        scope.launch {
            val history = runCatching { AppStorage.preferences.terminalHistory() }.getOrDefault(emptyList())
            if (history.isEmpty()) return@launch
            val next = historyIndex + 1
            if (next >= history.size) return@launch
            historyIndex = next
            input = history[next]
        }
    }

    // ---- 溢出菜单 / 弹窗状态 ----
    var menuOpen by remember { mutableStateOf(false) }
    var renameOpen by remember { mutableStateOf(false) }
    var renameText by rememberSaveable { mutableStateOf("") }
    var closeAllConfirm by remember { mutableStateOf(false) }

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
            gitHead?.branch?.takeIf { it.isNotBlank() }?.let { branch ->
                BranchBadge(
                    branch = branch,
                    ahead = gitHead?.ahead ?: 0,
                    behind = gitHead?.behind ?: 0,
                    detached = gitHead?.detached == true,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
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
                    text = { Text("新建会话") },
                    onClick = {
                        menuOpen = false
                        TerminalManager.create(cols, rows, projectPath)
                    },
                )
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
                    text = { Text("字号（当前 ${fontSize}sp，−）") },
                    onClick = { changeFontSize(-1) },
                )
                DropdownMenuItem(
                    text = { Text("字号（当前 ${fontSize}sp，＋）") },
                    onClick = { changeFontSize(1) },
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
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // ---------------- 底部按键行 ----------------
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
                KeyButton("⌫") { active?.sendBackspace() }
            }
            IconButton(onClick = { keyboard?.hide() }) {
                Icon(
                    imageVector = Icons.Default.KeyboardHide,
                    contentDescription = "隐藏键盘",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ---------------- 命令输入框 ----------------
        val current = active
        if (current != null && current.cliRunning.collectAsStateWithLifecycle().value) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(2.dp),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { pickHistory() }) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowUp,
                    contentDescription = "历史命令",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            BasicTextField(
                value = input,
                onValueChange = { value ->
                    input = value.filter { it != '\n' && it != '\r' }.take(4000)
                },
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester)
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(8.dp),
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    // 长命令软换行：约 4 行内自动换行，更长则在框内滚动，
                    // 不让输入框把上方终端区整个顶掉
                    .heightIn(max = 80.dp)
                    .verticalScroll(rememberScrollState()),
                // 关键：singleLine=true 会把文本压成一屏横向滚动，长命令看不见全貌。
                // 换行靠软换行，真实换行符仍在 onValueChange 里被过滤掉（命令永远是单行）。
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

/** 当前分支徽标（分支图标 + 名称 + 领先/落后计数；非仓库时由调用方隐藏）。 */
@Composable
private fun BranchBadge(
    branch: String,
    ahead: Int,
    behind: Int,
    detached: Boolean,
    modifier: Modifier = Modifier,
) {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.AccountTree,
                contentDescription = "当前分支",
                tint = tint,
                modifier = Modifier
                    .padding(start = 6.dp)
                    .size(12.dp),
            )
            Text(
                text = if (detached) "HEAD ($branch)" else branch,
                style = MaterialTheme.typography.labelSmall,
                color = tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(start = 4.dp, end = 6.dp)
                    .widthIn(max = 110.dp),
            )
            if (ahead > 0 || behind > 0) {
                Text(
                    text = buildString {
                        if (ahead > 0) append("↑$ahead")
                        if (behind > 0) append(" ↓$behind")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    modifier = Modifier.padding(end = 6.dp),
                )
            }
        }
    }
}

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
    modifier: Modifier = Modifier,
) {
    val emulator = session.emulator
    val version by emulator.version.collectAsStateWithLifecycle()
    val total = emulator.lineCount()
    val cursorLine = emulator.cursorLineIndex()
    val listState = rememberLazyListState()

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

    // 选中高亮色由 MobileCoderTheme 全局提供（LocalTextSelectionColors），
    // 这里不再局部覆盖，保证与 CLI 日志 / 输入框 / 编辑器是同一套颜色。
    SelectionContainer(
        modifier = modifier.background(Color(0xFF000000 or palette.terminalBackground.rgb)),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
        ) {
            items(total) { index ->
                val line = remember(version, index, palette) { emulator.lineAt(index) }
                TerminalLineText(
                    line = line,
                    isCursorLine = index == cursorLine,
                    palette = palette,
                    style = style,
                )
            }
        }
    }
}

@Composable
private fun TerminalLineText(
    line: TerminalLine,
    isCursorLine: Boolean,
    palette: AppPalette,
    style: TextStyle,
) {
    val annotated = remember(line, palette, isCursorLine) {
        buildTerminalLine(line, palette, isCursorLine)
    }
    Text(
        text = annotated,
        style = style,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
        modifier = Modifier.fillMaxWidth(),
    )
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
