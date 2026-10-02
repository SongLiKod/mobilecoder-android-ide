package com.mobilecoder.ide.feature.history

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mobilecoder.ide.core.common.ui.AppAlertDialog
import com.mobilecoder.ide.core.common.ui.EmptyState
import com.mobilecoder.ide.core.storage.HistoryRecord
import com.mobilecoder.ide.core.storage.HistoryStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * 「记录」页（原 CLI 面板位置）：终端命令与其它操作的统一流水。
 *
 * 路由级返回箭头与大标题由外壳统一渲染，本页不重复绘制。
 *
 * - 首行 = 条数（随来源筛选变化）+「⋮」菜单（新增记录 / 清除全部，清除需确认）；
 * - 来源筛选 chips：全部 / 终端 / 构建 / 市场 / 手动；
 * - 每条**紧凑单行**：收藏星 + 内容（单行省略）+ 来源·时间 +「⋯」菜单（复制 / 编辑 / 删除）；
 * - 点按整行 = 复制到剪贴板（撤销删除经 Snackbar「已删除 · 撤销」）；
 * - 收藏项置顶分组（收藏 / 全部两段）。
 */
@Composable
fun HistoryScreen(modifier: Modifier = Modifier) {
    val records by HistoryStore.records.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }

    // 首次进入时加载持久化记录（并迁移旧终端历史）
    LaunchedEffect(Unit) { runCatching { HistoryStore.ensureLoaded() } }

    var editing by remember { mutableStateOf<HistoryRecord?>(null) }
    var adding by remember { mutableStateOf(false) }
    var confirmClearAll by remember { mutableStateOf(false) }
    var headerMenuOpen by remember { mutableStateOf(false) }
    var sourceFilter by rememberSaveable { mutableStateOf(SOURCE_ALL) }

    val copy: (String) -> Unit = { text ->
        clipboard.setText(AnnotatedString(text))
        scope.launch { snackbarHostState.showSnackbar("已复制到剪贴板") }
    }

    // 删除：立即移除 + Snackbar「已删除 · 撤销」，点「撤销」按原样恢复该条记录
    val delete: (HistoryRecord) -> Unit = { record ->
        scope.launch {
            runCatching { HistoryStore.remove(record.id) }
            val result = runCatching {
                snackbarHostState.showSnackbar(
                    message = "已删除",
                    actionLabel = "撤销",
                    duration = SnackbarDuration.Short,
                    withDismissAction = true,
                )
            }.getOrNull()
            if (result == SnackbarResult.ActionPerformed) {
                runCatching { HistoryStore.restore(record) }
            }
        }
    }

    val filtered = remember(records, sourceFilter) {
        if (sourceFilter == SOURCE_ALL) {
            records
        } else {
            records.filter { it.source == sourceFilter }
        }
    }
    val ordered = remember(filtered) { HistoryStore.displayOrder(filtered) }
    val favorites = remember(ordered) { ordered.filter { it.favorite } }
    val rest = remember(ordered) { ordered.filterNot { it.favorite } }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ---------------- 首行：条数 + 「⋮」菜单 ----------------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, top = 8.dp, end = 4.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (sourceFilter == SOURCE_ALL) {
                        "共 ${records.size} 条"
                    } else {
                        "${filtered.size} 条 / 共 ${records.size} 条"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.weight(1f),
                )
                Box {
                    IconButton(
                        onClick = { headerMenuOpen = true },
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "更多操作",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    DropdownMenu(
                        expanded = headerMenuOpen,
                        onDismissRequest = { headerMenuOpen = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("新增记录") },
                            onClick = {
                                headerMenuOpen = false
                                adding = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("清除全部") },
                            onClick = {
                                headerMenuOpen = false
                                confirmClearAll = true
                            },
                        )
                    }
                }
            }

            // ---------------- 来源筛选 chips ----------------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SOURCE_FILTERS.forEach { (value, label) ->
                    FilterChip(
                        selected = sourceFilter == value,
                        onClick = { sourceFilter = value },
                        modifier = Modifier.height(40.dp),
                        label = {
                            Text(text = label, style = MaterialTheme.typography.labelSmall)
                        },
                    )
                }
            }

            // ---------------- 列表 ----------------
            if (ordered.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    EmptyState(
                        title = "暂无记录",
                        subtitle = "终端命令、构建等操作会自动记录在这里\n也可点右上角「⋮」手动新增",
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 12.dp),
                ) {
                    if (favorites.isNotEmpty()) {
                        item(key = "section-favorites") { SectionLabel("收藏") }
                        items(favorites, key = { "fav-${it.id}" }) { record ->
                            RecordRow(
                                record = record,
                                onCopy = { copy(record.text) },
                                onToggleFavorite = {
                                    scope.launch {
                                        runCatching { HistoryStore.setFavorite(record.id, false) }
                                    }
                                },
                                onEdit = { editing = record },
                                onDelete = { delete(record) },
                            )
                        }
                        if (rest.isNotEmpty()) {
                            item(key = "section-all") { SectionLabel("全部") }
                        }
                    }
                    items(rest, key = { it.id }) { record ->
                        RecordRow(
                            record = record,
                            onCopy = { copy(record.text) },
                            onToggleFavorite = {
                                scope.launch {
                                    runCatching { HistoryStore.setFavorite(record.id, true) }
                                }
                            },
                            onEdit = { editing = record },
                            onDelete = { delete(record) },
                        )
                    }
                }
            }
        }

        // 撤销删除 / 复制提示（本地 Snackbar，不使用 Toast）
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp),
        )
    }

    // ---------------- 编辑 / 新增弹窗 ----------------
    editing?.let { record ->
        RecordDialog(
            title = "编辑记录",
            initial = record.text,
            confirmLabel = "保存",
            onConfirm = { value ->
                scope.launch { runCatching { HistoryStore.update(record.id, value) } }
                editing = null
            },
            onDismiss = { editing = null },
        )
    }

    if (adding) {
        RecordDialog(
            title = "新增记录",
            initial = "",
            confirmLabel = "添加",
            onConfirm = { value ->
                scope.launch { runCatching { HistoryStore.add(value, HistoryStore.SOURCE_MANUAL) } }
                adding = false
            },
            onDismiss = { adding = false },
        )
    }

    // ---------------- 清除全部确认 ----------------
    if (confirmClearAll) {
        AppAlertDialog(
            title = "清除全部记录？",
            message = "此操作不可撤销",
            onDismiss = { confirmClearAll = false },
            confirmLabel = "清除",
            dismissLabel = "取消",
            destructive = true,
            onConfirm = {
                confirmClearAll = false
                scope.launch { runCatching { HistoryStore.clearAll() } }
            },
        )
    }
}

/** 「全部」筛选值（空串，与具体来源区分）。 */
private const val SOURCE_ALL = ""

/**
 * 来源筛选 chips：全部 / 终端 / 构建 / 市场 / 手动。
 * 仅列出 [HistoryStore] 已有来源常量，不臆造新来源。
 */
private val SOURCE_FILTERS: List<Pair<String, String>> = listOf(
    SOURCE_ALL to "全部",
    HistoryStore.SOURCE_TERMINAL to HistoryStore.SOURCE_TERMINAL,
    HistoryStore.SOURCE_BUILD to HistoryStore.SOURCE_BUILD,
    HistoryStore.SOURCE_MARKET to HistoryStore.SOURCE_MARKET,
    HistoryStore.SOURCE_MANUAL to HistoryStore.SOURCE_MANUAL,
)

/** 分组小标题（收藏 / 全部）。 */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 10.dp, bottom = 2.dp),
    )
}

/**
 * 一条记录的紧凑单行：收藏星（40dp）+ 内容（单行省略）+ 来源·时间 +「⋯」菜单（复制 / 编辑 / 删除）。
 * 点击整行仍是复制；收藏星与「⋯」各自独立可点，不触发整行复制。
 */
@Composable
private fun RecordRow(
    record: HistoryRecord,
    onCopy: () -> Unit,
    onToggleFavorite: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onCopy)
            .padding(horizontal = 8.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onToggleFavorite,
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                imageVector = if (record.favorite) Icons.Default.Star else Icons.Default.StarBorder,
                contentDescription = if (record.favorite) "取消收藏" else "收藏",
                tint = if (record.favorite) {
                    Color(0xFFFFB300)
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
            )
        }
        Text(
            text = record.text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = 2.dp, end = 6.dp),
        )
        Text(
            text = "${record.source} · ${formatTime(record.time)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 96.dp),
        )
        Box {
            IconButton(
                onClick = { menuOpen = true },
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "更多操作",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
            ) {
                DropdownMenuItem(
                    text = { Text("复制") },
                    onClick = {
                        menuOpen = false
                        onCopy()
                    },
                )
                DropdownMenuItem(
                    text = { Text("编辑") },
                    onClick = {
                        menuOpen = false
                        onEdit()
                    },
                )
                DropdownMenuItem(
                    text = { Text("删除") },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    },
                )
            }
        }
    }
}

/** 编辑 / 新增共用弹窗（多行文本，空内容不可保存）。 */
@Composable
private fun RecordDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { value -> text = value },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 8,
                placeholder = { Text("记录内容") },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

private val TIME_FORMAT = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

private fun formatTime(time: Long): String =
    runCatching { TIME_FORMAT.format(Date(time)) }.getOrDefault("")
