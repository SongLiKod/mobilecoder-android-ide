package com.mobilecoder.ide.feature.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mobilecoder.ide.core.common.theme.LocalAppPalette
import com.mobilecoder.ide.core.common.ui.AppAlertDialog
import com.mobilecoder.ide.core.common.ui.EmptyState
import com.mobilecoder.ide.core.common.ui.isImeVisible
import com.mobilecoder.ide.core.storage.AppStorage
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * PRD 2.2 代码编辑模块入口（app 模块按此签名调用）。
 *
 * 顶部 = 工具行 + 文件 Tab；中部 = 编辑器（含行号槽 / 文件树抽屉 / 查找 / 检索）；
 * 底部 = 问题面板（可收起）+ 状态栏。不自带 Scaffold，外层 app 已提供顶栏与底部导航。
 */
@Composable
fun EditorScreen(projectPath: String, onOpenBuild: () -> Unit = {}, modifier: Modifier = Modifier) {
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        AppStorage.init(context)
        EditorController.ensureInit(context)
    }
    LaunchedEffect(projectPath) {
        EditorController.setProject(projectPath)
    }

    val tabs by EditorController.tabs.collectAsStateWithLifecycle()
    val activePath by EditorController.activePath.collectAsStateWithLifecycle()
    val settings by EditorController.settings.collectAsStateWithLifecycle()
    val find by EditorController.find.collectAsStateWithLifecycle()
    val message by EditorController.message.collectAsStateWithLifecycle()
    val history by EditorController.history.collectAsStateWithLifecycle()
    val palette = LocalAppPalette.current
    val colors = remember(palette) { highlightColorsOf(palette) }

    val activeTab = tabs.firstOrNull { it.path == activePath }

    var treeOpen by rememberSaveable { mutableStateOf(false) }
    var problemsOpen by rememberSaveable { mutableStateOf(false) }
    var panelTab by rememberSaveable { mutableStateOf(EditorPanelTab.PROBLEMS) }
    var gotoOpen by rememberSaveable { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var pendingClosePath by remember { mutableStateOf<String?>(null) }

    // 软键盘弹出时底部问题面板隐藏（保持 open 状态，收起键盘后自动恢复）
    val imeVisible = isImeVisible()

    val text = activeTab?.value?.text ?: ""
    val cursor = activeTab?.value?.selection?.start ?: 0
    val cursorLine = remember(text, cursor) { EditorController.lineOfOffset(text, cursor) }
    val cursorColumn = remember(text, cursor, cursorLine) {
        cursor - EditorController.offsetOfLine(text, cursorLine) + 1
    }

    val matchCount = remember(text, find.query) { findMatchesOf(text, find.query).size }

    Column(modifier = modifier.fillMaxSize()) {
        // ---------------- 工具行：文件树开关 + Tab + 查找 / 检索 / 保存 / 设置 ----------------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp)
                .background(MaterialTheme.colorScheme.surface),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { treeOpen = !treeOpen }) {
                Icon(
                    imageVector = if (treeOpen) Icons.Default.Close else Icons.Default.Menu,
                    contentDescription = "文件树",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            EditorTabRow(
                tabs = tabs,
                activePath = activePath,
                onSelect = { EditorController.activate(it) },
                onClose = { path ->
                    val closing = tabs.firstOrNull { it.path == path }
                    if (closing != null && closing.dirty) {
                        pendingClosePath = path
                    } else {
                        EditorController.closeTab(path)
                    }
                },
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = {
                searchOpen = true
                EditorController.setSearchOpen(true)
            }) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "检索",
                    tint = if (searchOpen) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
            IconButton(onClick = { EditorController.saveActive() }, enabled = activeTab != null) {
                Icon(
                    imageVector = Icons.Default.Save,
                    contentDescription = "保存",
                    tint = if (activeTab?.dirty == true) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            IconButton(onClick = onOpenBuild) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "构建与运行",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "编辑器设置",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                EditorOptionsMenu(
                    expanded = menuOpen,
                    settings = settings,
                    onDismiss = { menuOpen = false },
                    canUndo = history.canUndo,
                    canRedo = history.canRedo,
                    onUndo = {
                        menuOpen = false
                        activeTab?.let { EditorController.undo(it.path) }
                    },
                    onRedo = {
                        menuOpen = false
                        activeTab?.let { EditorController.redo(it.path) }
                    },
                    onGotoLine = {
                        menuOpen = false
                        if (activeTab != null) gotoOpen = true
                    },
                    onOpenOutline = {
                        menuOpen = false
                        if (activeTab != null && activeTab.editable) {
                            problemsOpen = true
                            panelTab = EditorPanelTab.OUTLINE
                        }
                    },
                    onToggleLineNumbers = { EditorController.toggleLineNumbers() },
                    onToggleWordWrap = { EditorController.toggleWordWrap() },
                    onToggleAutoSave = { EditorController.toggleAutoSave() },
                    onFoldAll = {
                        menuOpen = false
                        activeTab?.let { EditorController.setAllFolds(it.path, collapse = true) }
                    },
                    onUnfoldAll = {
                        menuOpen = false
                        activeTab?.let { EditorController.setAllFolds(it.path, collapse = false) }
                    },
                )
            }
        }

        // ---------------- 编辑区 ----------------
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            when {
                activeTab == null -> EmptyState(
                    title = "没有打开的文件",
                    subtitle = "点按左上角文件树图标浏览项目，或用检索查找文件",
                    modifier = Modifier.align(Alignment.Center),
                )

                !activeTab.editable -> EmptyState(
                    title = "文件读取失败",
                    subtitle = activeTab.relativePath,
                    modifier = Modifier.align(Alignment.Center),
                )

                else -> EditorBody(
                    tab = activeTab,
                    settings = settings,
                    colors = colors,
                    find = find,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            if (find.open && activeTab != null && activeTab.editable) {
                FindBar(
                    query = find.query,
                    total = matchCount,
                    current = find.index,
                    onQueryChange = { EditorController.setFindQuery(it) },
                    onNext = { EditorController.stepFind(1, matchCount) },
                    onPrev = { EditorController.stepFind(-1, matchCount) },
                    onClose = { EditorController.setFindOpen(false) },
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }

            if (searchOpen) {
                ProjectSearchOverlay(
                    onDismiss = {
                        searchOpen = false
                        EditorController.setSearchOpen(false)
                    },
                    onFindInFile = { query ->
                        searchOpen = false
                        EditorController.setSearchOpen(false)
                        if (activeTab != null && activeTab.editable) {
                            if (query.isNotBlank()) EditorController.setFindQuery(query.trim())
                            EditorController.setFindOpen(true)
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            if (treeOpen) {
                FileTreeDrawer(onDismiss = { treeOpen = false })
            }

            if (message != null) {
                MessageBar(
                    message = message,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 8.dp),
                )
            }
        }

        // ---------------- 底部面板（问题 / 大纲）+ 状态栏 ----------------
        // 软键盘弹出期间仅隐藏显示，problemsOpen 状态保留，键盘收起后自动恢复
        if (problemsOpen && !imeVisible && activeTab != null && activeTab.editable) {
            EditorBottomPanel(
                tab = activeTab,
                active = panelTab,
                onSwitch = { panelTab = it },
                onJumpToIssue = { issue ->
                    EditorController.jumpTo(activeTab.path, issue.line, issue.column)
                },
                onJumpToSymbol = { symbol ->
                    EditorController.jumpTo(activeTab.path, symbol.line, 1)
                    EditorController.requestEditorFocus()
                },
                onClose = { problemsOpen = false },
            )
        }

        EditorStatusBar(
            tab = activeTab,
            line = cursorLine,
            column = cursorColumn,
            issueCount = activeTab?.analysis?.issues?.size ?: 0,
            symbolCount = activeTab?.analysis?.symbols?.size ?: 0,
            problemsOpen = problemsOpen && panelTab == EditorPanelTab.PROBLEMS,
            outlineOpen = problemsOpen && panelTab == EditorPanelTab.OUTLINE,
            onToggleProblems = {
                if (activeTab != null && activeTab.editable) {
                    if (problemsOpen && panelTab == EditorPanelTab.PROBLEMS) {
                        problemsOpen = false
                    } else {
                        problemsOpen = true
                        panelTab = EditorPanelTab.PROBLEMS
                    }
                }
            },
            onToggleOutline = {
                if (activeTab != null && activeTab.editable) {
                    if (problemsOpen && panelTab == EditorPanelTab.OUTLINE) {
                        problemsOpen = false
                    } else {
                        problemsOpen = true
                        panelTab = EditorPanelTab.OUTLINE
                    }
                }
            },
            onGotoLine = {
                if (activeTab != null && activeTab.editable) gotoOpen = true
            },
        )
    }

    if (gotoOpen && activeTab != null) {
        GotoLineDialog(
            currentLine = cursorLine,
            totalLines = activeTab.totalLines,
            onConfirm = { target ->
                gotoOpen = false
                EditorController.jumpTo(activeTab.path, target, 1)
                EditorController.requestEditorFocus()
            },
            onDismiss = { gotoOpen = false },
        )
    }

    // 关闭未保存文件的二次确认（干净 Tab 直接关闭，不弹窗）
    pendingClosePath?.let { path ->
        val closing = tabs.firstOrNull { it.path == path }
        AppAlertDialog(
            title = "关闭未保存的文件？",
            message = "「${closing?.name ?: path}」存在未保存的修改，确定要关闭吗？",
            confirmLabel = "仍要关闭",
            onConfirm = {
                pendingClosePath = null
                EditorController.closeTab(path)
            },
            onDismiss = { pendingClosePath = null },
        )
    }
}

// ---------------------------------------------------------------------------
// 编辑器本体
// ---------------------------------------------------------------------------

@Composable
private fun EditorBody(
    tab: EditorController.EditorTab,
    settings: EditorController.EditorSettings,
    colors: HighlightColors,
    find: EditorController.FindState,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val palette = LocalAppPalette.current
    val text = tab.value.text
    val fontSize = settings.fontSize

    // 行高固定为整数像素，保证行号槽与正文逐行对齐
    val lineHeightPx = (
        fontSize * 1.45f * density.density * density.fontScale
        ).roundToInt().coerceAtLeast(1).toFloat()
    val style = remember(fontSize, colors.base) {
        editorTextStyle(fontSize, colors.base).copy(
            lineHeight = with(density) { lineHeightPx.toSp() },
        )
    }

    val vScroll = rememberScrollState()
    val hScroll = rememberScrollState()

    // 每个文件独立的滚动位置：切 Tab / 返回页面时恢复
    LaunchedEffect(tab.path) {
        val saved = EditorController.scrollOf(tab.path)
        if (saved > 0) vScroll.scrollTo(saved)
    }
    DisposableEffect(tab.path) {
        onDispose { EditorController.setScroll(tab.path, vScroll.value) }
    }
    var boxWidth by remember { mutableIntStateOf(0) }
    var viewportHeight by remember { mutableIntStateOf(0) }
    val textMeasurer = rememberTextMeasurer()
    val heightCache = remember { HashMap<String, Float>() }

    val analysis = tab.analysis
    val appliedFolds = remember(text, analysis.folds, tab.collapsedFolds) {
        buildAppliedFolds(text, analysis.folds, tab.collapsedFolds)
    }
    val cells = remember(text, analysis, appliedFolds) {
        buildGutterCells(text, analysis, appliedFolds)
    }
    val foldableStarts = remember(analysis) {
        analysis.folds.mapTo(HashSet()) { it.startLine }
    }
    val cellIndexByLine = remember(cells) {
        if (cells == null) null else HashMap<Int, Int>().also { map ->
            cells.forEachIndexed { index, cell -> if (cell.line > 0 && cell.line !in map) map[cell.line] = index }
        }
    }

    val highlighter = remember(colors, tab.language) { SyntaxHighlighter(colors) }
    val matches = remember(text, find.query) { findMatchesOf(text, find.query) }
    val matchIndex = if (matches.isEmpty()) -1 else find.index.coerceIn(0, matches.size - 1)
    val transformation = remember(highlighter, appliedFolds, matches, matchIndex, find.query) {
        EditorVisualTransformation(
            highlighter = highlighter,
            language = tab.language,
            colors = colors,
            folds = appliedFolds,
            findMatches = matches,
            findCurrent = matchIndex,
            findQueryLength = find.query.length,
        )
    }

    // 自动换行时按实际折行高度测量每一可视行
    val displayLines: List<String>? = if (settings.wordWrap) {
        remember(cells, text) { cells?.map { it.content } ?: text.split('\n') }
    } else {
        null
    }
    val heights: List<Float>? = if (
        settings.wordWrap && boxWidth > 0 && displayLines != null
    ) {
        remember(displayLines, boxWidth, fontSize, lineHeightPx) {
            displayLines.map { line ->
                val cached = heightCache[line]
                if (cached != null) {
                    cached
                } else {
                    val measured = textMeasurer.measure(
                        text = AnnotatedString(line),
                        style = style,
                        constraints = Constraints(maxWidth = boxWidth),
                    )
                    val lines = (measured.size.height / lineHeightPx).roundToInt().coerceAtLeast(1)
                    val height = lines * lineHeightPx
                    if (heightCache.size > 20_000) heightCache.clear()
                    heightCache[line] = height
                    height
                }
            }
        }
    } else {
        null
    }
    val tops: FloatArray? = heights?.let { list ->
        remember(list) {
            FloatArray(list.size).also { array ->
                var acc = 0f
                for (i in list.indices) {
                    array[i] = acc
                    acc += list[i]
                }
            }
        }
    }

    val currentLine = remember(text, tab.value.selection) {
        EditorController.lineOfOffset(text, tab.value.selection.start)
    }

    // 跳行 / 大纲跳转后把焦点还给正文（光标可见、可继续输入）
    val focusRequester = remember { FocusRequester() }
    val focusTick by EditorController.focusTick.collectAsStateWithLifecycle()
    var focusTickSeen by remember(tab.path) { mutableIntStateOf(focusTick) }
    LaunchedEffect(focusTick) {
        if (focusTick != focusTickSeen) {
            focusTickSeen = focusTick
            runCatching { focusRequester.requestFocus() }
        }
    }

    // 查找跳转：切换命中时把光标移到该命中处
    LaunchedEffect(matchIndex, find.query) {
        if (find.query.isNotEmpty() && matches.isNotEmpty()) {
            val start = matches[matchIndex]
            val target = TextRange(start, start + find.query.length)
            if (tab.value.selection != target) {
                EditorController.onEditorValue(
                    tab.path,
                    tab.value.copy(selection = target, composition = null),
                )
            }
        }
    }

    // 光标跟随滚动（输入 / 点击 / 跳转时保持光标可见）
    LaunchedEffect(tab.value.selection.start) {
        if (viewportHeight <= 0 || lineHeightPx <= 0f) return@LaunchedEffect
        val line = EditorController.lineOfOffset(text, tab.value.selection.start)
        val cellIndex = cellIndexByLine?.get(line) ?: (line - 1)
        if (cellIndex < 0) return@LaunchedEffect
        val y = tops?.getOrNull(cellIndex) ?: (cellIndex * lineHeightPx)
        if (y > vScroll.value + viewportHeight - 2 * lineHeightPx) {
            vScroll.animateScrollTo(
                (y - viewportHeight + 3 * lineHeightPx).roundToInt().coerceAtLeast(0),
            )
        } else if (y < vScroll.value) {
            vScroll.animateScrollTo((y - lineHeightPx).roundToInt().coerceAtLeast(0))
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { size -> viewportHeight = size.height }
            .pointerInput(tab.path) {
                detectTransformGestures { _, _, zoom, _ ->
                    if (abs(zoom - 1f) > 0.01f) {
                        val base = EditorController.settings.value.fontSize
                        val next = (base * zoom).roundToInt().coerceIn(10, 28)
                        if (next != base) EditorController.setFontSize(next)
                    }
                }
            },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(vScroll),
        ) {
            if (settings.lineNumbers) {
                LineGutter(
                    cells = cells,
                    totalLines = tab.totalLines,
                    foldableLines = foldableStarts,
                    collapsed = tab.collapsedFolds,
                    issueLines = analysis.issueLines,
                    currentLine = currentLine,
                    lineHeightPx = lineHeightPx,
                    heights = heights,
                    tops = tops,
                    scrollValue = vScroll.value,
                    viewportHeight = viewportHeight,
                    fontSize = fontSize,
                    onToggleFold = { EditorController.toggleFold(tab.path, it) },
                    modifier = Modifier,
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .then(if (settings.wordWrap) Modifier.fillMaxWidth() else Modifier.horizontalScroll(hScroll)),
            ) {
                BasicTextField(
                    value = tab.value,
                    onValueChange = { newValue ->
                        EditorController.onEditorValue(
                            tab.path,
                            applySmartEdit(tab.value, newValue),
                        )
                    },
                    enabled = tab.editable,
                    readOnly = false,
                    textStyle = style,
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    visualTransformation = transformation,
                    modifier = Modifier
                        .focusRequester(focusRequester)
                        .then(if (settings.wordWrap) Modifier.fillMaxWidth() else Modifier),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 全局项目检索
// ---------------------------------------------------------------------------

@Composable
private fun ProjectSearchOverlay(
    onDismiss: () -> Unit,
    onFindInFile: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val search by EditorController.search.collectAsStateWithLifecycle()
    val root by EditorController.projectRoot.collectAsStateWithLifecycle()
    // 检索范围（本地状态）：全项目 = 项目内容检索；当前文件 = 转入文件内查找
    var searchWholeProject by remember { mutableStateOf(true) }

    Column(
        modifier = modifier.background(MaterialTheme.colorScheme.background),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = search.query,
                onValueChange = { EditorController.setSearchQuery(it) },
                singleLine = true,
                placeholder = { Text("搜索整个项目的文件内容") },
                textStyle = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "关闭",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // 检索范围切换（触控高度 >= 40dp）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = searchWholeProject,
                onClick = { searchWholeProject = true },
                label = { Text("全项目") },
                modifier = Modifier.heightIn(min = 40.dp),
            )
            FilterChip(
                selected = !searchWholeProject,
                onClick = {
                    searchWholeProject = false
                    onFindInFile(search.query)
                },
                label = { Text("当前文件") },
                modifier = Modifier.heightIn(min = 40.dp),
            )
        }

        when {
            search.query.isBlank() -> EmptyState(
                title = "输入关键词检索项目",
                subtitle = "匹配文件名与文件内容，最多返回 200 条",
            )

            search.running -> EmptyState(title = "正在检索…")

            search.hits.isEmpty() -> EmptyState(title = "没有匹配结果")

            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(search.hits) { hit ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(
                                    onClick = { EditorController.openHit(hit) },
                                )
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Default.InsertDriveFile,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 10.dp),
                            ) {
                                Text(
                                    text = root?.let { hit.file.absolutePath.removePrefix(it).removePrefix("/") }
                                        ?: hit.file.name,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = if (hit.line > 0) {
                                        "${hit.line}: ${hit.preview}"
                                    } else {
                                        hit.preview
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 工具
// ---------------------------------------------------------------------------

/** 查找文件内全部命中下标（大小写不敏感，最多 5000 个）。 */
internal fun findMatchesOf(text: String, query: String): List<Int> {
    if (query.isEmpty() || text.isEmpty()) return emptyList()
    val out = ArrayList<Int>()
    var index = text.indexOf(query, 0, ignoreCase = true)
    while (index >= 0 && out.size < 5000) {
        out.add(index)
        index = text.indexOf(query, index + query.length, true)
    }
    return out
}
