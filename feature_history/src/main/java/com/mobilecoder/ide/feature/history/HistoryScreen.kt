package com.mobilecoder.ide.feature.history

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
 * - 每条**紧凑单行**展示：收藏星 + 内容（单行省略）+ 来源·时间 + 复制/编辑/删除；
 * - 点按整行 = 复制到剪贴板；
 * - 收藏项置顶分组（收藏 / 全部两段）；
 * - 右上角「+」手动新增记录（来源「手动」）。
 */
@Composable
fun HistoryScreen(modifier: Modifier = Modifier) {
    val records by HistoryStore.records.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    // 首次进入时加载持久化记录（并迁移旧终端历史）
    LaunchedEffect(Unit) { runCatching { HistoryStore.ensureLoaded() } }

    var editing by remember { mutableStateOf<HistoryRecord?>(null) }
    var adding by remember { mutableStateOf(false) }

    val copy: (String) -> Unit = { text ->
        clipboard.setText(AnnotatedString(text))
        Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
    }

    val ordered = remember(records) { HistoryStore.displayOrder(records) }
    val favorites = remember(ordered) { ordered.filter { it.favorite } }
    val rest = remember(ordered) { ordered.filterNot { it.favorite } }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // ---------------- 头部：标题 + 条数 + 新增 ----------------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 8.dp, end = 4.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "历史记录",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${records.size} 条",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
            IconButton(onClick = { adding = true }, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "新增记录",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        // ---------------- 列表 ----------------
        if (ordered.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    title = "暂无记录",
                    subtitle = "终端命令、构建等操作会自动记录在这里\n也可点右上角「+」手动新增",
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
                            onDelete = {
                                scope.launch { runCatching { HistoryStore.remove(record.id) } }
                            },
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
                        onDelete = {
                            scope.launch { runCatching { HistoryStore.remove(record.id) } }
                        },
                    )
                }
            }
        }
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
}

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
 * 一条记录的紧凑单行：收藏星 + 内容（单行省略）+ 来源·时间 + 复制/编辑/删除。
 * 点击整行也是复制。
 */
@Composable
private fun RecordRow(
    record: HistoryRecord,
    onCopy: () -> Unit,
    onToggleFavorite: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onCopy)
            .padding(horizontal = 8.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ActionIcon(
            icon = if (record.favorite) Icons.Default.Star else Icons.Default.StarBorder,
            contentDescription = if (record.favorite) "取消收藏" else "收藏",
            tint = if (record.favorite) {
                Color(0xFFFFB300)
            } else {
                MaterialTheme.colorScheme.outlineVariant
            },
            onClick = onToggleFavorite,
        )
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
        ActionIcon(
            icon = Icons.Default.ContentCopy,
            contentDescription = "复制",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            onClick = onCopy,
        )
        ActionIcon(
            icon = Icons.Default.Edit,
            contentDescription = "编辑",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            onClick = onEdit,
        )
        ActionIcon(
            icon = Icons.Default.Delete,
            contentDescription = "删除",
            tint = MaterialTheme.colorScheme.error,
            onClick = onDelete,
        )
    }
}

/** 小尺寸图标按钮（30dp 触控区、17dp 图标，保持行紧凑）。 */
@Composable
private fun ActionIcon(
    icon: ImageVector,
    contentDescription: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(17.dp),
        )
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
