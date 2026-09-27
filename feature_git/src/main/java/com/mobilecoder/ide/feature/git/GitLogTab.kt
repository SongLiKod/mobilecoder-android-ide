package com.mobilecoder.ide.feature.git

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mobilecoder.ide.core.common.theme.LocalAppPalette
import com.mobilecoder.ide.core.common.ui.EmptyState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * 日志页（PRD 2.5「完整提交时间线」）：时间线样式 + 详情展开 + 下拉加载更多。
 */
@Composable
fun LogTab(
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val commits by GitController.commits.collectAsStateWithLifecycle()
    val hasMore by GitController.logHasMore.collectAsStateWithLifecycle()
    var expandedOid by rememberSaveable { mutableStateOf("") }

    if (commits.isEmpty()) {
        EmptyState(
            title = "暂无提交记录",
            subtitle = "在「变更」页提交后，这里会显示完整时间线",
            modifier = modifier.fillMaxSize(),
        )
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    ) {
        items(commits, key = { it.oid }) { commit ->
            CommitRow(
                commit = commit,
                expanded = expandedOid == commit.oid,
                onToggle = {
                    expandedOid = if (expandedOid == commit.oid) "" else commit.oid
                },
            )
            HorizontalDivider(
                modifier = Modifier.padding(start = 36.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            )
        }

        if (hasMore) {
            item(key = "load-more") {
                OutlinedButton(
                    onClick = {
                        scope.launch { runCatching { GitController.loadMoreLog() } }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                ) {
                    Text("加载更多")
                }
            }
        }
    }
}

@Composable
private fun CommitRow(
    commit: GitCommit,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val palette = LocalAppPalette.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
    ) {
        // 时间线：圆点 + 竖线
        Column(
            modifier = Modifier
                .width(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .padding(top = 16.dp)
                    .size(10.dp)
                    .background(
                        color = if (expanded) {
                            palette.logInfo.toComposeColor()
                        } else {
                            palette.logDebug.toComposeColor()
                        },
                        shape = CircleShape,
                    ),
            )
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .height(36.dp)
                    .background(MaterialTheme.colorScheme.outline),
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 10.dp, top = 10.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                Text(
                    text = commit.shortOid,
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = palette.logInfo.toComposeColor(),
                    fontWeight = FontWeight.SemiBold,
                )
                if (commit.parentCount > 1) {
                    Text(
                        text = "合并提交",
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.logWarn.toComposeColor(),
                    )
                }
            }

            Text(
                text = commit.summary.ifBlank { "（无提交说明）" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            Text(
                text = "${commit.author} · ${relativeTime(commit.time)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            if (expanded) {
                CommitDetail(commit = commit)
            }
        }
    }
}

/** 提交详情：完整 hash、作者邮箱、时间、父提交。 */
@Composable
private fun CommitDetail(commit: GitCommit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        DetailLine(label = "完整 Hash", value = commit.oid, mono = true)
        DetailLine(label = "作者", value = "${commit.author} <${commit.email}>")
        DetailLine(label = "时间", value = formatTime(commit.time))
        DetailLine(label = "父提交", value = "${commit.parentCount} 个")
        DetailLine(label = "说明", value = commit.summary.ifBlank { "（无）" })
    }
}

@Composable
private fun DetailLine(label: String, value: String, mono: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = if (mono) FontFamily.Monospace else null,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 绝对时间。 */
internal fun formatTime(epochSeconds: Long): String {
    if (epochSeconds <= 0L) return "未知时间"
    return runCatching {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(epochSeconds * 1000))
    }.getOrDefault("未知时间")
}

/** 相对时间（中文）。 */
internal fun relativeTime(epochSeconds: Long): String {
    if (epochSeconds <= 0L) return "未知时间"
    val diff = System.currentTimeMillis() / 1000 - epochSeconds
    return when {
        diff < 0 -> formatTime(epochSeconds)
        diff < 60 -> "刚刚"
        diff < 3600 -> "${diff / 60} 分钟前"
        diff < 86400 -> "${diff / 3600} 小时前"
        diff < 86400 * 30L -> "${diff / 86400} 天前"
        else -> formatTime(epochSeconds)
    }
}
