package com.mobilecoder.ide.feature.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobilecoder.ide.core.common.theme.LocalAppPalette
import com.mobilecoder.ide.core.common.ui.EmptyState
import com.mobilecoder.ide.core.common.ui.SectionHeader
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------
// 文件 Tab 行（多文件分页）
// ---------------------------------------------------------------------------

@Composable
fun EditorTabRow(
    tabs: List<EditorController.EditorTab>,
    activePath: String?,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    // 活动文件变化（新打开 / 切换 / 关闭后换挡）时把它的 Tab 滚进可视区：
    // 多文件横排常超出一行宽度，打开文件后必须能看到「当前打开的是哪个 tab」。
    LaunchedEffect(activePath, tabs) {
        val index = tabs.indexOfFirst { it.path == activePath }
        if (index < 0) return@LaunchedEffect
        val info = listState.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == index }
        val fullyVisible = item != null &&
            item.offset >= info.viewportStartOffset &&
            item.offset + item.size <= info.viewportEndOffset
        if (!fullyVisible) listState.animateScrollToItem(index)
    }
    LazyRow(
        state = listState,
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp),
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(tabs, key = { it.path }) { tab ->
            val active = tab.path == activePath
            Surface(
                color = if (active) {
                    MaterialTheme.colorScheme.surfaceVariant
                } else {
                    Color.Transparent
                },
                shape = MaterialTheme.shapes.small,
                modifier = Modifier
                    .height(36.dp)
                    .clickable { onSelect(tab.path) },
            ) {
                Row(
                    modifier = Modifier.padding(start = 10.dp, end = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = tab.name,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (active) {
                            MaterialTheme.colorScheme.onBackground
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 130.dp),
                    )
                    if (tab.dirty) {
                        Box(
                            modifier = Modifier
                                .padding(start = 5.dp)
                                .size(6.dp)
                                .background(
                                    color = MaterialTheme.colorScheme.primary,
                                    shape = MaterialTheme.shapes.small,
                                ),
                        )
                    }
                    IconButton(
                        onClick = { onClose(tab.path) },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "关闭",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 状态栏
// ---------------------------------------------------------------------------

@Composable
fun EditorStatusBar(
    tab: EditorController.EditorTab?,
    line: Int,
    column: Int,
    issueCount: Int,
    symbolCount: Int,
    problemsOpen: Boolean,
    outlineOpen: Boolean,
    onToggleProblems: () -> Unit,
    onToggleOutline: () -> Unit,
    onGotoLine: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (tab == null) {
            Text(
                text = "未打开文件",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = tab.relativePath.ifEmpty { tab.name },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "$line:$column",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(MaterialTheme.shapes.small)
                    .clickable(onClick = onGotoLine)
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
            Text(
                text = "共 ${tab.totalLines} 行",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Text(
                text = "UTF-8",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Text(
                text = tab.language.label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                modifier = Modifier
                    .height(40.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(
                        color = if (outlineOpen) {
                            MaterialTheme.colorScheme.surfaceVariant
                        } else {
                            Color.Transparent
                        },
                        shape = MaterialTheme.shapes.small,
                    )
                    .clickable(onClick = onToggleOutline)
                    .padding(horizontal = 8.dp),
            ) {
                Text(
                    text = if (symbolCount > 0) "大纲 $symbolCount" else "大纲",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (outlineOpen) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                modifier = Modifier
                    .height(40.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(
                        color = if (issueCount > 0) {
                            MaterialTheme.colorScheme.error.copy(alpha = 0.14f)
                        } else if (problemsOpen) {
                            MaterialTheme.colorScheme.surfaceVariant
                        } else {
                            Color.Transparent
                        },
                        shape = MaterialTheme.shapes.small,
                    )
                    .clickable(onClick = onToggleProblems)
                    .padding(horizontal = 8.dp),
            ) {
                if (issueCount > 0) {
                    Icon(
                        imageVector = Icons.Default.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(12.dp),
                    )
                }
                Text(
                    text = if (issueCount > 0) "问题 $issueCount" else "问题",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (issueCount > 0) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 底部面板：问题 / 大纲（方法导航）两个分页
// ---------------------------------------------------------------------------

/** 底部面板分页。 */
enum class EditorPanelTab { PROBLEMS, OUTLINE }

@Composable
fun EditorBottomPanel(
    tab: EditorController.EditorTab,
    active: EditorPanelTab,
    onSwitch: (EditorPanelTab) -> Unit,
    onJumpToIssue: (EditorIssue) -> Unit,
    onJumpToSymbol: (CodeSymbol) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(140.dp)
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 4.dp, top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PanelTabChip(
                label = "问题（${tab.analysis.issues.size}）",
                selected = active == EditorPanelTab.PROBLEMS,
                onClick = { onSwitch(EditorPanelTab.PROBLEMS) },
            )
            PanelTabChip(
                label = "大纲（${tab.analysis.symbols.size}）",
                selected = active == EditorPanelTab.OUTLINE,
                onClick = { onSwitch(EditorPanelTab.OUTLINE) },
            )
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "收起",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        when (active) {
            EditorPanelTab.PROBLEMS -> ProblemsList(
                tab = tab,
                onJump = onJumpToIssue,
            )

            EditorPanelTab.OUTLINE -> OutlineList(
                tab = tab,
                onJump = onJumpToSymbol,
            )
        }
    }
}

/** 面板分页芯片。 */
@Composable
private fun PanelTabChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                } else {
                    Color.Transparent
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

/** 问题列表（实时代码报错结果）。 */
@Composable
private fun ProblemsList(
    tab: EditorController.EditorTab,
    onJump: (EditorIssue) -> Unit,
) {
    if (tab.analysis.issues.isEmpty()) {
        EmptyState(
            title = "没有发现问题",
            subtitle = "括号配对、字符串、合并冲突标记会在此列出",
        )
    } else {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(tab.analysis.issues) { issue ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onJump(issue) }
                        .padding(horizontal = 16.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = if (issue.isError) {
                            Icons.Default.ErrorOutline
                        } else {
                            Icons.Default.WarningAmber
                        },
                        contentDescription = null,
                        tint = if (issue.isError) {
                            MaterialTheme.colorScheme.error
                        } else {
                            LocalAppPalette.current.warning.toComposeColor()
                        },
                        modifier = Modifier.size(15.dp),
                    )
                    Text(
                        text = "${tab.relativePath.ifEmpty { tab.name }}:${issue.line}:${issue.column}  ${issue.message}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}

/** 大纲列表（类 / 方法 / 标题导航）。 */
@Composable
private fun OutlineList(
    tab: EditorController.EditorTab,
    onJump: (CodeSymbol) -> Unit,
) {
    val symbols = tab.analysis.symbols
    if (symbols.isEmpty()) {
        EmptyState(
            title = "没有可导航的符号",
            subtitle = "类、方法、函数、标题等声明会在此列出，点按跳转",
        )
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(symbols) { symbol ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onJump(symbol) }
                    .padding(
                        start = (16 + symbol.depth * 14).dp,
                        end = 16.dp,
                        top = 6.dp,
                        bottom = 6.dp,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = symbol.kind.badge,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .background(
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                            shape = MaterialTheme.shapes.small,
                        )
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
                Text(
                    text = symbol.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 8.dp),
                )
                Text(
                    text = "${symbol.kind.label} ${symbol.line}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 跳转到行
// ---------------------------------------------------------------------------

@Composable
fun GotoLineDialog(
    currentLine: Int,
    totalLines: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var input by remember { mutableStateOf(currentLine.coerceIn(1, totalLines).toString()) }
    val parsed = input.trim().toIntOrNull()
    val valid = parsed != null && parsed in 1..totalLines
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.imePadding(),
        title = { Text("跳转到行") },
        text = {
            OutlinedTextField(
                value = input,
                onValueChange = { value -> input = value.filter { it.isDigit() }.take(9) },
                label = { Text("行号（1 ~ $totalLines）") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Go,
                ),
                keyboardActions = KeyboardActions(
                    onGo = {
                        val line = parsed
                        if (line != null && line in 1..totalLines) onConfirm(line)
                    },
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = { if (parsed != null) onConfirm(parsed.coerceIn(1, totalLines)) },
            ) { Text("跳转") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

// ---------------------------------------------------------------------------
// 文件内查找条
// ---------------------------------------------------------------------------

@Composable
fun FindBar(
    query: String,
    total: Int,
    current: Int,
    onQueryChange: (String) -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            placeholder = { Text("在文件中查找", style = MaterialTheme.typography.bodySmall) },
            textStyle = MaterialTheme.typography.bodyMedium,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onNext() }),
            modifier = Modifier.weight(1f),
        )
        Text(
            text = if (query.isBlank()) {
                ""
            } else if (total > 0) {
                "${current + 1}/$total"
            } else {
                "无结果"
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (total > 0) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.error
            },
        )
        IconButton(onClick = onPrev, enabled = total > 0) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowUp,
                contentDescription = "上一个",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onNext, enabled = total > 0) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = "下一个",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onClose) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "关闭查找",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 编辑器设置菜单
// ---------------------------------------------------------------------------

@Composable
fun EditorOptionsMenu(
    expanded: Boolean,
    settings: EditorController.EditorSettings,
    onDismiss: () -> Unit,
    canUndo: Boolean,
    canRedo: Boolean,
    onOpenFile: () -> Unit,
    onSearch: () -> Unit,
    canSave: Boolean,
    saveDirty: Boolean,
    onSave: () -> Unit,
    onBuild: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onGotoLine: () -> Unit,
    onOpenOutline: () -> Unit,
    onToggleLineNumbers: () -> Unit,
    onToggleWordWrap: () -> Unit,
    onToggleAutoSave: () -> Unit,
    onFoldAll: () -> Unit,
    onUnfoldAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, modifier = modifier) {
        // 常用动作（原工具行按钮收纳进 ⋮，见 EditorScreen 工具行）
        DropdownMenuItem(
            text = { Text("打开文件") },
            leadingIcon = {
                Icon(Icons.Default.FileOpen, contentDescription = null)
            },
            onClick = onOpenFile,
        )
        DropdownMenuItem(
            text = { Text("搜索项目") },
            leadingIcon = {
                Icon(Icons.Default.Search, contentDescription = null)
            },
            onClick = onSearch,
        )
        DropdownMenuItem(
            text = {
                Text(
                    text = "保存",
                    color = if (saveDirty && canSave) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        Color.Unspecified
                    },
                )
            },
            leadingIcon = {
                Icon(Icons.Default.Save, contentDescription = null)
            },
            enabled = canSave,
            onClick = onSave,
        )
        DropdownMenuItem(
            text = { Text("构建与运行") },
            leadingIcon = {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
            },
            onClick = onBuild,
        )
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text("撤销上一步") },
            leadingIcon = {
                Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = null)
            },
            enabled = canUndo,
            onClick = onUndo,
        )
        DropdownMenuItem(
            text = { Text("重做") },
            leadingIcon = {
                Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = null)
            },
            enabled = canRedo,
            onClick = onRedo,
        )
        DropdownMenuItem(
            text = { Text("跳转到行…") },
            leadingIcon = {
                Icon(Icons.Default.Tag, contentDescription = null)
            },
            onClick = onGotoLine,
        )
        DropdownMenuItem(
            text = { Text("大纲 / 方法导航") },
            leadingIcon = {
                Icon(Icons.AutoMirrored.Filled.FormatListBulleted, contentDescription = null)
            },
            onClick = onOpenOutline,
        )
        DropdownMenuItem(
            text = { Text("行号") },
            trailingIcon = {
                if (settings.lineNumbers) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = "已开启",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            },
            onClick = onToggleLineNumbers,
        )
        DropdownMenuItem(
            text = { Text("自动换行") },
            trailingIcon = {
                if (settings.wordWrap) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = "已开启",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            },
            onClick = onToggleWordWrap,
        )
        DropdownMenuItem(
            text = { Text("自动保存") },
            trailingIcon = {
                if (settings.autoSave) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = "已开启",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            },
            onClick = onToggleAutoSave,
        )
        DropdownMenuItem(
            text = { Text("折叠全部") },
            leadingIcon = { Icon(Icons.Default.UnfoldLess, contentDescription = null) },
            onClick = onFoldAll,
        )
        DropdownMenuItem(
            text = { Text("展开全部") },
            leadingIcon = { Icon(Icons.Default.UnfoldMore, contentDescription = null) },
            onClick = onUnfoldAll,
        )
    }
}

// ---------------------------------------------------------------------------
// 轻提示
// ---------------------------------------------------------------------------

@Composable
fun MessageBar(message: String?, modifier: Modifier = Modifier) {
    if (message.isNullOrBlank()) return
    Surface(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
        shadowElevation = 4.dp,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
        )
    }
}

// ---------------------------------------------------------------------------
// 行号槽（含折叠开关 / 报错图标）
// ---------------------------------------------------------------------------

@Composable
fun LineGutter(
    cells: List<GutterCell>?,
    totalLines: Int,
    foldableLines: Set<Int>,
    collapsed: Set<Int>,
    issueLines: Set<Int>,
    currentLine: Int,
    lineHeightPx: Float,
    heights: List<Float>?,
    tops: FloatArray?,
    scrollValue: Int,
    viewportHeight: Int,
    fontSize: Int,
    onToggleFold: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val total = cells?.size ?: totalLines
    if (total <= 0 || lineHeightPx <= 0f) return
    val density = LocalDensity.current

    val topOf: (Int) -> Float = if (tops != null) {
        { index -> tops.getOrElse(index) { 0f } }
    } else {
        { index -> index * lineHeightPx }
    }
    val heightOf: (Int) -> Float = if (heights != null) {
        { index -> heights.getOrElse(index) { lineHeightPx } }
    } else {
        { _ -> lineHeightPx }
    }

    var start = 0
    if (heights == null) {
        start = (scrollValue / lineHeightPx).toInt().coerceIn(0, total - 1)
        while (start > 0 && start * lineHeightPx > scrollValue) start--
    } else {
        var acc = 0f
        while (start < total && acc + heightOf(start) <= scrollValue) {
            acc += heightOf(start)
            start++
        }
    }
    val bottom = scrollValue + viewportHeight
    val digits = totalLines.toString().length
    val gutterWidth = (digits * 8 + 32).dp
    val gutterHeight = viewportHeight.coerceAtLeast(0)

    Box(
        modifier = modifier
            .width(gutterWidth)
            .height(with(density) { gutterHeight.toDp() })
            .offset { IntOffset(0, scrollValue) }
            .clipToBounds()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)),
    ) {
        var index = start
        while (index < total) {
            val cellTop = topOf(index)
            if (cellTop > bottom) break
            val cell = cells?.getOrNull(index)
            val line = cell?.line ?: (index + 1)
            val localTop = cellTop - scrollValue
            val cellHeight = heightOf(index).roundToInt().coerceAtLeast(1)
            val cellHeightDp = with(density) { cellHeight.toDp() }
            val isCurrent = line == currentLine
            val hasIssue = line in issueLines && line > 0
            val isMarker = cell?.markerFold != null
            val isFoldable = isMarker ||
                (cell?.foldable == true) ||
                (cell == null && line in foldableLines)
            val isCollapsed = isMarker ||
                (line in collapsed && (cell == null || cell.foldable == true))

            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset { IntOffset(0, localTop.roundToInt()) }
                    .height(cellHeightDp)
                    .fillMaxWidth()
                    .background(
                        if (isCurrent) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                        } else {
                            Color.Transparent
                        },
                    ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(cellHeightDp)
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (isFoldable) {
                        Icon(
                            imageVector = if (isCollapsed) {
                                Icons.Default.ChevronRight
                            } else {
                                Icons.Default.KeyboardArrowDown
                            },
                            contentDescription = if (isCollapsed) "展开" else "折叠",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .size(16.dp)
                                .clickable {
                                    val target = cell?.markerFold?.startLine ?: line
                                    onToggleFold(target)
                                },
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    if (hasIssue) {
                        Icon(
                            imageVector = Icons.Default.ErrorOutline,
                            contentDescription = "存在错误",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(13.dp),
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                    }
                    Text(
                        text = cell?.numberText ?: line.toString(),
                        color = if (isMarker) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else if (isCurrent) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        fontSize = (fontSize - 2).coerceAtLeast(8).sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                    )
                }
            }
            index++
        }
    }
}

/** 编辑器正文通用文字样式（等宽）。 */
fun editorTextStyle(fontSize: Int, color: Color): TextStyle = TextStyle(
    fontSize = fontSize.sp,
    lineHeight = (fontSize * 1.45f).sp,
    fontFamily = FontFamily.Monospace,
    color = color,
)
