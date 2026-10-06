package com.mobilecoder.ide.feature.ai

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 消息流的渲染单元（P1 改造）：
 *  - 用户气泡：纯文本 + 复制 + 时间
 *  - 助手消息：可折叠工具卡片（点文件名跳编辑器）→ Markdown 气泡 + 复制
 *  - 带快照的消息尾部：「本轮修改 N 个文件 · 查看 Diff · 撤销本轮」（P0 写保护）
 */

@Composable
fun MessageRow(
    message: AiMessage,
    busy: Boolean,
    onOpenFile: (String) -> Unit,
    onUndo: (String) -> Unit,
    onShowDiff: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (message.role == "user") {
        UserBubble(text = message.content, time = message.time, modifier = modifier)
        return
    }

    Column(modifier = modifier.fillMaxWidth()) {
        if (message.events.isNotEmpty()) {
            ToolEventsCard(
                events = message.events,
                onOpenFile = onOpenFile,
            )
        }
        if (message.content.isNotBlank()) {
            AssistantBubble(text = message.content, time = message.time, pending = false)
        }
        val snapshotId = message.snapshotId
        if (snapshotId != null && !busy) {
            RoundActions(
                changedCount = message.events.size,
                onUndo = { onUndo(snapshotId) },
                onDiff = { onShowDiff(snapshotId) },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 气泡
// ---------------------------------------------------------------------------

@Composable
private fun UserBubble(text: String, time: Long, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboardManager.current
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Column(horizontalAlignment = Alignment.End) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = RoundedCornerShape(14.dp, 14.dp, 4.dp, 14.dp),
                modifier = Modifier.widthIn(max = 320.dp),
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            BubbleFooter(time = time, onCopy = { clipboard.setText(AnnotatedString(text)) })
        }
    }
}

@Composable
fun AssistantBubble(
    text: String,
    time: Long = 0L,
    pending: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    Row(modifier = modifier.fillMaxWidth()) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            shape = RoundedCornerShape(14.dp, 14.dp, 14.dp, 4.dp),
            modifier = Modifier.widthIn(max = 340.dp),
        ) {
            if (pending) {
                // 流式中：纯文本 + 光标（避免每 delta 重新解析 Markdown）
                Text(
                    text = text + " ▍",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            } else {
                Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp)) {
                    MarkdownText(text = text)
                    BubbleFooter(
                        time = time,
                        onCopy = { clipboard.setText(AnnotatedString(text)) },
                    )
                }
            }
        }
    }
}

/** 气泡脚注：时间 + 复制按钮。 */
@Composable
private fun BubbleFooter(time: Long, onCopy: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (time > 0) {
            Text(
                text = formatTime(time),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.padding(start = 4.dp, top = 2.dp),
            )
        }
        IconButton(
            onClick = onCopy,
            modifier = Modifier.size(26.dp),
        ) {
            Icon(
                imageVector = Icons.Default.ContentCopy,
                contentDescription = "复制",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.size(13.dp),
            )
        }
    }
}

private fun formatTime(millis: Long): String = try {
    SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(millis))
} catch (_: Throwable) {
    ""
}

// ---------------------------------------------------------------------------
// 工具执行卡片
// ---------------------------------------------------------------------------

/** 可折叠的工具执行卡片：折叠时显示最近一条操作，展开逐条可点（跳编辑器）。 */
@Composable
private fun ToolEventsCard(
    events: List<AiToolEvent>,
    onOpenFile: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable(events) { mutableStateOf(false) }
    val failed = events.count { !it.ok }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(10.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (failed > 0) Icons.Default.ErrorOutline else Icons.Default.Check,
                    contentDescription = null,
                    tint = if (failed > 0) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    modifier = Modifier.size(15.dp),
                )
                Spacer(modifier = Modifier.size(6.dp))
                Text(
                    text = "已执行 ${events.size} 项操作",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (failed > 0) {
                    Text(
                        text = "（$failed 项失败）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            if (expanded) {
                events.forEach { event ->
                    ToolEventRow(event = event, onOpenFile = onOpenFile)
                }
            } else {
                val last = events.last()
                Text(
                    text = "${AiTools.displayName(last.name)} ${last.target} —— ${last.summary}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 21.dp, top = 2.dp),
                )
            }
        }
    }
}

/** 单条工具事件：✓ 写入 app/build.gradle.kts —— 已写入 12 行（点行跳编辑器）。 */
@Composable
private fun ToolEventRow(
    event: AiToolEvent,
    onOpenFile: (String) -> Unit,
) {
    val clickableFile = event.target.isNotBlank() && event.name != "list_files"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (clickableFile) {
                    Modifier.clickable { onOpenFile(event.target) }
                } else {
                    Modifier
                },
            )
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = if (event.ok) Icons.Default.Check else Icons.Default.Close,
            contentDescription = null,
            tint = if (event.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier
                .size(14.dp)
                .padding(top = 2.dp),
        )
        Spacer(modifier = Modifier.size(6.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${AiTools.displayName(event.name)} ${event.target}".trim(),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = if (event.target.isNotBlank()) FontFamily.Monospace else null,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = event.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (clickableFile) {
            Icon(
                imageVector = Icons.Default.OpenInNew,
                contentDescription = "在编辑器打开",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier
                    .size(14.dp)
                    .padding(top = 3.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 撤销本轮 / 查看 Diff
// ---------------------------------------------------------------------------

/** 快照操作条：本轮修改 N 个文件 · 查看 Diff · 撤销本轮（P0 写保护入口）。 */
@Composable
private fun RoundActions(
    changedCount: Int,
    onUndo: () -> Unit,
    onDiff: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "本轮修改 $changedCount 个文件",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDiff) { Text("查看 Diff") }
            TextButton(onClick = onUndo) {
                Text("撤销本轮", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 进度行
// ---------------------------------------------------------------------------

/** 请求/工具执行进度行（替代原 ThinkingRow，带阶段描述与轮次）。 */
@Composable
fun ThinkingRow(
    progress: AiProgress,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.padding(start = 4.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            strokeWidth = 2.dp,
        )
        Spacer(modifier = Modifier.size(8.dp))
        val text = when (progress.stage) {
            AiStage.REQUESTING -> "正在请求 ${progress.detail}（第 ${progress.round} 轮）…"
            AiStage.TOOL -> "正在${progress.detail}…"
            AiStage.STREAMING -> ""
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 流式文本气泡（仅当前会话的进度才渲染，防止切会话串台）。 */
@Composable
fun StreamingRow(text: String) {
    AnimatedVisibility(
        visible = text.isNotEmpty(),
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        AssistantBubble(text = text, pending = true)
    }
}
