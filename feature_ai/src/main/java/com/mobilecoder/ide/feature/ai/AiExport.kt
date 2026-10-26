package com.mobilecoder.ide.feature.ai

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * AI 会话导出为 Markdown（沉淀成文档 / 归档）。
 *
 * [AiSession.toMarkdown] 是纯函数：含提问、回复、**工具调用记录**（工具名 / 目标 /
 * 结果 / 参数 JSON）与快照标记，用 [MarkdownText] 预览、SAF 保存到用户指定位置。
 */

/** 每条消息的工具结果不进文档（读文件回传可能上百 KB）；只保留事件与参数。 */
private const val PREVIEW_MAX_CHARS = 40_000

/** 参数 JSON 的缩进位数。 */
private const val JSON_INDENT = 2

/**
 * 会话 → Markdown 文档。
 *
 * @param projectName    项目名（用于文档头）
 * @param endpointLabel  「接口 / 模型」标签（用于文档头）
 * @param now            导出时间（毫秒；测试可注入固定值）
 */
fun AiSession.toMarkdown(
    projectName: String = "",
    endpointLabel: String = "",
    now: Long = System.currentTimeMillis(),
): String {
    val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(now))
    return buildString {
        append("# AI 会话：").append(title.ifBlank { "未命名会话" }).append("\n\n")
        append("- 导出时间：").append(date).append('\n')
        if (projectName.isNotBlank()) append("- 项目：").append(projectName).append('\n')
        if (endpointLabel.isNotBlank()) append("- 接口 / 模型：").append(endpointLabel).append('\n')
        append("- 消息数：").append(messages.size).append(" 条\n")
        append("\n---\n")

        if (messages.isEmpty()) {
            append("\n_该会话还没有任何消息。_\n")
            return@buildString
        }

        for (message in messages) {
            append('\n')
            append("## ")
            append(if (message.role == "user") "提问" else "回复")
            val clock = formatClock(message.time)
            if (clock.isNotBlank()) append(" · ").append(clock)
            append("\n\n")

            if (message.content.isNotBlank()) {
                append(message.content.trim('\n')).append("\n\n")
            } else if (message.events.isEmpty()) {
                append("_（无文字内容）_\n\n")
            }

            if (message.events.isNotEmpty()) {
                append("**工具调用**\n\n")
                message.events.forEachIndexed { index, event ->
                    append(index + 1).append(". ")
                    append(if (event.ok) "✅" else "❌")
                    append(" `").append(event.name).append('`')
                    if (event.target.isNotBlank()) append(" · `").append(event.target).append('`')
                    if (event.summary.isNotBlank()) append(" — ").append(event.summary)
                    append('\n')
                }
                append('\n')
            }

            if (message.toolCalls.isNotEmpty()) {
                append("**调用参数**\n\n")
                append("```json\n")
                append(argumentsJson(message.toolCalls))
                append("\n```\n\n")
            }

            message.snapshotId?.let { id ->
                append("*本轮修改已创建改前快照 `").append(id).append("`（IDE 内可撤销）*\n\n")
            }
        }
        append("---\n\n_由 MobileCoder IDE 导出。_\n")
    }
}

/** 工具调用参数 → 格式化 JSON 数组（参数解析失败时按字符串原样保留，绝不抛异常）。 */
internal fun argumentsJson(calls: List<ApiToolCall>): String {
    val array = JSONArray()
    calls.forEach { call ->
        val args = runCatching { JSONObject(call.arguments) }.getOrNull()
        array.put(
            JSONObject()
                .put("name", call.name)
                .put("arguments", args ?: call.arguments),
        )
    }
    return runCatching { array.toString(JSON_INDENT) }.getOrDefault(array.toString())
}

/** 消息列表头里的时间（HH:mm；旧数据无时间返回空串）。 */
internal fun formatClock(millis: Long): String =
    if (millis <= 0) "" else SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))

/** 导出文件名：`ai-<标题>-<yyyyMMdd-HHmm>.md`（标题做文件名安全化）。 */
internal fun exportFileName(title: String, time: Long): String {
    val safe = title.trim()
        .replace(Regex("""[\\/:*?"<>|]+"""), "-")
        .replace(Regex("""\s+"""), "-")
        .trim('-')
        .take(40)
        .ifEmpty { "session" }
    val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(time))
    return "ai-$safe-$stamp.md"
}

/** 把 Markdown 写入用户选择的文档（SAF）。返回 null = 成功。 */
internal suspend fun writeMarkdown(context: Context, uri: Uri, text: String): String? =
    withContext(Dispatchers.IO) {
        runCatching {
            val stream = context.contentResolver.openOutputStream(uri, "wt")
                ?: error("无法打开目标文件")
            stream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            null
        }.getOrElse { it.message ?: "写入失败" }
    }

// ---------------------------------------------------------------------------
// 导出对话框（预览 + 保存）
// ---------------------------------------------------------------------------

/**
 * 会话导出对话框：先用 [MarkdownText]（复用 AI 回复渲染器）预览成文效果，
 * 再经 SAF `CreateDocument` 保存为 `.md`。
 */
@Composable
fun AiExportDialog(
    session: AiSession,
    projectName: String = "",
    endpointLabel: String = "",
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("") }

    val full = remember(session, projectName, endpointLabel) {
        session.toMarkdown(projectName, endpointLabel)
    }
    // 预览截断：超长会话全量渲染会卡顿，保存时仍是全文
    val preview = remember(full) {
        if (full.length <= PREVIEW_MAX_CHARS) full
        else full.take(PREVIEW_MAX_CHARS) + "\n\n_…（预览已截断，保存时包含全文）_"
    }
    val fileName = remember(session) { exportFileName(session.title, session.updatedAt) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/markdown"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val error = writeMarkdown(context, uri, full)
            if (error == null) {
                Toast.makeText(context, "已导出 $fileName", Toast.LENGTH_SHORT).show()
                onDismiss()
            } else {
                status = error
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Default.FileDownload,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 12.dp, end = 10.dp),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "导出为 Markdown",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = fileName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "关闭")
                    }
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                ) {
                    MarkdownText(text = preview)
                }

                if (status.isNotBlank()) {
                    Text(
                        text = status,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    OutlinedButton(
                        onClick = { launcher.launch(fileName) },
                        modifier = Modifier.padding(start = 8.dp),
                    ) {
                        Text("保存到…")
                    }
                }
            }
        }
    }
}
