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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
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
    LazyRow(
        modifier = modifier
            .fillMaxWidth()
            .height(42.dp),
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 5.dp),
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
                    .height(32.dp)
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
                        modifier = Modifier.size(26.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "关闭",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(15.dp),
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
    problemsOpen: Boolean,
    onToggleProblems: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onToggleProblems)
            .padding(horizontal = 10.dp, vertical = 5.dp),
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
            )
            Text(
                text = "共 ${tab.totalLines} 行",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "UTF-8",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = tab.language.label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                modifier = Modifier
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
                    .padding(horizontal = 5.dp, vertical = 1.dp),
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
// 问题面板（实时代码报错结果）
// ---------------------------------------------------------------------------

@Composable
fun ProblemsPanel(
    tab: EditorController.EditorTab,
    onJump: (EditorIssue) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(190.dp)
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionHeader(
                title = "问题（${tab.analysis.issues.size}）",
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "收起",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
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
    onToggleLineNumbers: () -> Unit,
    onToggleWordWrap: () -> Unit,
    onToggleAutoSave: () -> Unit,
    onFontSize: (Int) -> Unit,
    onFoldAll: () -> Unit,
    onUnfoldAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, modifier = modifier) {
        DropdownMenuItem(
            text = { Text("字体大小 ${settings.fontSize}") },
            leadingIcon = {
                Icon(Icons.Default.FormatSize, contentDescription = null)
            },
            trailingIcon = {
                Row {
                    androidx.compose.material3.TextButton(onClick = { onFontSize(-1) }) {
                        Text("A-")
                    }
                    androidx.compose.material3.TextButton(onClick = { onFontSize(+1) }) {
                        Text("A+")
                    }
                }
            },
            onClick = { },
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
