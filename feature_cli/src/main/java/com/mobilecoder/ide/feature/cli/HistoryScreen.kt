package com.mobilecoder.ide.feature.cli

import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mobilecoder.ide.core.common.ui.AppAlertDialog
import com.mobilecoder.ide.core.common.ui.EmptyState
import com.mobilecoder.ide.core.common.ui.SectionHeader
import com.mobilecoder.ide.core.storage.AppStorage
import com.mobilecoder.ide.core.storage.OperationRecord
import com.mobilecoder.ide.core.storage.OperationSource
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「历史」页：操作历史记录（原 CLI「日志 + 命令」面板废弃后取而代之）。
 *
 * 记录来自四处，统一存进 [AppStorage.history]：
 *  - 终端里直通 shell 的命令（`TerminalSession.submitLine`）；
 *  - 进程内 `apt …` / `git …` 命令（`AptCli.onExecuted` 钩子）；
 *  - 构建页发起的 Gradle 构建（`BuildRunner.recordHistory`）；
 *  - Git 页 / 首页的克隆、提交、推送、拉取、抓取（`GitController.recordGitHistory`）。
 *
 * 每条记录均可**复制 / 收藏 / 删除**；顶部支持关键词搜索、来源筛选与一键清空
 *（清空默认保留收藏）。历史落盘在 `files/history/operations.tsv`，重启后仍在。
 */
@Composable
fun HistoryScreen(
    modifier: Modifier = Modifier,
) {
    val records by AppStorage.history.records.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    var query by rememberSaveable { mutableStateOf("") }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var sourceFilter by rememberSaveable { mutableStateOf("") }
    var pendingClear by remember { mutableStateOf(false) }

    val favoriteCount = remember(records) { records.count { it.favorite } }
    val visible = remember(records, query, tab, sourceFilter) {
        val keyword = query.trim()
        records.filter { record ->
            (tab == 0 || record.favorite) &&
                (sourceFilter.isEmpty() || record.source.name == sourceFilter) &&
                (keyword.isEmpty() ||
                    record.command.contains(keyword, ignoreCase = true) ||
                    record.project.contains(keyword, ignoreCase = true))
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        SectionHeader(
            title = "操作历史",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            action = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${records.size} 条",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 4.dp),
                    )
                    IconButton(
                        onClick = { pendingClear = true },
                        enabled = records.any { !it.favorite },
                    ) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "清空历史")
                    }
                }
            },
        )

        TabRow(selectedTabIndex = tab) {
            Tab(
                selected = tab == 0,
                onClick = { tab = 0 },
                text = { Text("全部 (${records.size})") },
            )
            Tab(
                selected = tab == 1,
                onClick = { tab = 1 },
                text = { Text("收藏 ($favoriteCount)") },
            )
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            singleLine = true,
            placeholder = { Text("搜索命令 / 项目") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(Icons.Default.Close, contentDescription = "清除搜索")
                    }
                }
            },
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        )

        // 来源筛选：全部 / 终端 / apt 命令 / 构建 / Git
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FilterChip(
                selected = sourceFilter.isEmpty(),
                onClick = { sourceFilter = "" },
                label = { Text("全部来源") },
            )
            OperationSource.entries.forEach { source ->
                FilterChip(
                    selected = sourceFilter == source.name,
                    onClick = {
                        sourceFilter = if (sourceFilter == source.name) "" else source.name
                    },
                    label = { Text(source.label) },
                )
            }
        }

        if (visible.isEmpty()) {
            EmptyState(
                title = if (records.isEmpty()) "暂无历史记录" else "没有匹配的记录",
                subtitle = if (records.isEmpty()) {
                    "在终端执行命令、构建项目或克隆仓库后，记录会显示在这里"
                } else {
                    "换个关键词，或把来源筛选调回「全部来源」"
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(visible, key = { it.id }) { record ->
                    HistoryRecordCard(
                        record = record,
                        onCopy = {
                            clipboard.setText(AnnotatedString(record.command))
                            Toast.makeText(context, "已复制命令", Toast.LENGTH_SHORT).show()
                        },
                        onToggleFavorite = {
                            AppStorage.history.setFavorite(record.id, !record.favorite)
                        },
                        onDelete = { AppStorage.history.remove(record.id) },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
            }
        }
    }

    if (pendingClear) {
        AppAlertDialog(
            title = "清空历史",
            message = "确定清空全部历史记录？收藏的 $favoriteCount 条会保留。",
            confirmLabel = "清空",
            destructive = true,
            onConfirm = {
                pendingClear = false
                AppStorage.history.clear(keepFavorites = true)
            },
            onDismiss = { pendingClear = false },
        )
    }
}

// ---------------------------------------------------------------------------
// 单条记录
// ---------------------------------------------------------------------------

@Composable
private fun HistoryRecordCard(
    record: OperationRecord,
    onCopy: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SourceBadge(record.source)
                Text(
                    text = formatTime(record.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.weight(1f))
                StatusText(record)
            }

            Text(
                text = record.command,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )

            if (record.project.isNotBlank()) {
                Text(
                    text = record.project,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (record.favorite) {
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = "已收藏",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp),
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = onToggleFavorite, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = if (record.favorite) Icons.Default.Star else Icons.Default.StarBorder,
                        contentDescription = if (record.favorite) "取消收藏" else "收藏",
                        tint = if (record.favorite) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                IconButton(onClick = onCopy, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "复制",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "删除",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** 来源标签（配色取自主题，深浅色模式自动适配）。 */
@Composable
private fun SourceBadge(source: OperationSource) {
    val color = when (source) {
        OperationSource.TERMINAL -> MaterialTheme.colorScheme.primary
        OperationSource.CLI -> MaterialTheme.colorScheme.tertiary
        OperationSource.BUILD -> MaterialTheme.colorScheme.secondary
        OperationSource.GIT -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        shape = MaterialTheme.shapes.small,
        color = color.copy(alpha = 0.14f),
    ) {
        Text(
            text = source.label,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

/** 结果 + 耗时（shell 命令拿不到退出码，此时只显示耗时或不显示）。 */
@Composable
private fun StatusText(record: OperationRecord) {
    val duration = record.durationMs?.let { " · ${formatDuration(it)}" }.orEmpty()
    val exitCode = record.exitCode
    val (text, color) = when {
        exitCode == null -> when {
            duration.isEmpty() -> "" to MaterialTheme.colorScheme.onSurfaceVariant
            else -> duration.trimStart(' ', '·') to MaterialTheme.colorScheme.onSurfaceVariant
        }

        exitCode == 0 -> "成功$duration" to MaterialTheme.colorScheme.primary
        exitCode == -1 -> "已取消$duration" to MaterialTheme.colorScheme.error
        else -> "失败($exitCode)$duration" to MaterialTheme.colorScheme.error
    }
    if (text.isNotEmpty()) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            maxLines = 1,
        )
    }
}

// ---------------------------------------------------------------------------
// 文案格式化
// ---------------------------------------------------------------------------

private fun formatTime(epochMs: Long): String = runCatching {
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(epochMs))
}.getOrDefault("")

private fun formatDuration(ms: Long): String = when {
    ms < 1_000 -> "${ms}ms"
    ms < 60_000 -> "%.1fs".format(ms / 1000.0)
    else -> "${ms / 60_000}分${(ms % 60_000) / 1000}秒"
}
