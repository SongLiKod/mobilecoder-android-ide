package com.mobilecoder.ide.feature.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mobilecoder.ide.core.common.ui.AppAlertDialog
import com.mobilecoder.ide.core.common.ui.EmptyState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * AI 助手页（PRD 扩展：接入 AI API Key，通过会话驱动项目修改）。
 *
 * 结构：操作行（历史 / 新会话 / 设置）→ 消息流（含工具执行事件）→ 输入区。
 * 「历史会话」「接口设置」为页内二级视图（顶部返回行），页级标题由外壳统一渲染。
 * 逻辑全部在 [AiController]：流式输出、工具调用循环、会话持久化。
 */
@Composable
fun AiScreen(
    projectPath: String,
    modifier: Modifier = Modifier,
) {
    val sessions by AiController.sessions.collectAsStateWithLifecycle()
    val current by AiController.current.collectAsStateWithLifecycle()
    val busy by AiController.busy.collectAsStateWithLifecycle()
    val streaming by AiController.streaming.collectAsStateWithLifecycle()
    val error by AiController.error.collectAsStateWithLifecycle()
    val config by AiController.config.collectAsStateWithLifecycle()

    var input by rememberSaveable { mutableStateOf("") }
    var settingsView by rememberSaveable { mutableStateOf(false) }
    var historyView by rememberSaveable { mutableStateOf(false) }
    var pendingDeleteId by rememberSaveable { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    LaunchedEffect(projectPath) {
        AiController.ensureLoaded()
        AiController.refreshConfig()
    }

    val messages = current?.messages.orEmpty()
    LaunchedEffect(messages.size, streaming.isNotEmpty(), busy) {
        val total = listState.layoutInfo.totalItemsCount
        if (total > 0) listState.animateScrollToItem(total - 1)
    }

    fun submit() {
        val text = input.trim()
        if (text.isEmpty() || busy) return
        input = ""
        AiController.send(projectPath, text)
    }

    if (settingsView) {
        // 二级视图：接口设置（原 AlertDialog 改为页内视图，隐藏消息流与输入区）
        SettingsView(
            initial = config,
            onBack = { settingsView = false },
            onSave = { baseUrl, model, key ->
                settingsView = false
                scope.launch { AiController.saveConfig(baseUrl, model, key) }
            },
        )
    } else if (historyView) {
        // 二级视图：历史会话（原下拉菜单改为页内视图）
        HistoryView(
            sessions = sessions,
            currentId = current?.id,
            onBack = { historyView = false },
            onSelect = { id ->
                AiController.select(id)
                historyView = false
            },
            onDelete = { pendingDeleteId = it },
        )
    } else {
        ChatView(
            current = current,
            busy = busy,
            streaming = streaming,
            error = error,
            config = config,
            input = input,
            listState = listState,
            onInput = { input = it },
            onSubmit = { submit() },
            onOpenHistory = { historyView = true },
            onOpenSettings = { settingsView = true },
            modifier = modifier,
        )
    }

    if (pendingDeleteId.isNotBlank()) {
        AppAlertDialog(
            title = "删除该会话？",
            message = "删除后该会话的全部对话记录将被移除，且无法恢复。",
            confirmLabel = "删除",
            destructive = true,
            onConfirm = {
                val id = pendingDeleteId
                pendingDeleteId = ""
                AiController.deleteSession(id)
            },
            onDismiss = { pendingDeleteId = "" },
        )
    }
}

/**
 * 聊天主视图：操作行（历史 / 新会话 / 设置）→ 配置提示条 → 消息流 → 输入区。
 *
 * 页级标题由外壳统一渲染（AI 助手），此处只保留当前会话名 + 右侧操作按钮。
 */
@Composable
private fun ChatView(
    current: AiSession?,
    busy: Boolean,
    streaming: String,
    error: String?,
    config: AiConfig,
    input: String,
    listState: LazyListState,
    onInput: (String) -> Unit,
    onSubmit: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val messages = current?.messages.orEmpty()

    Column(modifier = modifier.fillMaxSize()) {
        // ---------------- 操作行 ----------------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = current?.title ?: "尚未创建会话",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onOpenHistory, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.History, contentDescription = "历史会话")
            }
            IconButton(onClick = { AiController.newSession() }, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.Add, contentDescription = "新会话")
            }
            IconButton(onClick = onOpenSettings, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.Settings, contentDescription = "AI 接口设置")
            }
        }

        if (!config.looksReady || config.apiKey.isBlank()) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (!config.looksReady) {
                            "尚未配置 AI 接口：点右上角设置 Base URL / 模型 / API Key"
                        } else {
                            "未填写 API Key（本地 Ollama 可忽略，否则请在设置里填写）"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onOpenSettings) { Text("去设置") }
                }
            }
        }

        // ---------------- 消息流 ----------------
        if (messages.isEmpty() && streaming.isEmpty()) {
            EmptyState(
                title = "开始一个会话",
                subtitle = "AI 可以浏览并直接修改当前项目的文件\n（读取 → 精确替换 → 写回，改动全程可见）",
                modifier = Modifier.weight(1f),
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(messages.size) { index ->
                    MessageRow(message = messages[index])
                }
                if (streaming.isNotEmpty()) {
                    item { AssistantBubble(text = streaming, pending = true) }
                } else if (busy) {
                    item { ThinkingRow() }
                }
            }
        }

        // ---------------- 错误提示 ----------------
        error?.let { message ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ) {
                Row(
                    modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = 4.dp),
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                    IconButton(onClick = { AiController.clearError() }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "关闭提示")
                    }
                }
            }
        }

        // ---------------- 输入区 ----------------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 10.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { onInput(it.take(20_000)) },
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(
                        text = if (busy) "AI 正在修改项目…" else "描述要做的修改，例如：给 README 加一节安装说明",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                minLines = 1,
                maxLines = 5,
                enabled = !busy,
                textStyle = MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.size(8.dp))
            IconButton(
                onClick = {
                    if (busy) AiController.cancel() else onSubmit()
                },
            ) {
                Icon(
                    imageVector = if (busy) Icons.Default.Stop else Icons.Default.Send,
                    contentDescription = if (busy) "停止" else "发送",
                    tint = if (busy) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 二级视图：历史会话
// ---------------------------------------------------------------------------

/** 页内二级视图顶部返回行（40dp 返回箭头 + 标题）。 */
@Composable
private fun BackRow(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 历史会话页内视图：点行切换会话并退出视图；行尾 40dp 删除按钮先确认再删除。 */
@Composable
private fun HistoryView(
    sessions: List<AiSession>,
    currentId: String?,
    onBack: () -> Unit,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        BackRow(title = "历史会话", onBack = onBack)
        if (sessions.isEmpty()) {
            EmptyState(title = "还没有历史会话")
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(sessions.size) { index ->
                    SessionRow(
                        session = sessions[index],
                        isCurrent = sessions[index].id == currentId,
                        onClick = { onSelect(sessions[index].id) },
                        onDelete = { onDelete(sessions[index].id) },
                    )
                }
            }
        }
    }
}

/** 单条会话：标题 + 相对时间 + >=40dp 删除按钮。 */
@Composable
private fun SessionRow(
    session: AiSession,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .clickable(onClick = onClick)
            .padding(end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 6.dp),
        ) {
            Text(
                text = session.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val time = formatRelativeTime(session.updatedAt)
            if (time.isNotBlank()) {
                Text(
                    text = time,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(40.dp)) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = "删除会话",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 会话相对时间（基于 AiSession.updatedAt）。 */
private fun formatRelativeTime(millis: Long): String {
    if (millis <= 0L) return ""
    val diff = System.currentTimeMillis() - millis
    return when {
        diff < 60_000L -> "刚刚"
        diff < 3_600_000L -> "${diff / 60_000L} 分钟前"
        diff < 86_400_000L -> "${diff / 3_600_000L} 小时前"
        diff < 604_800_000L -> "${diff / 86_400_000L} 天前"
        else -> try {
            SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(millis))
        } catch (_: Throwable) {
            ""
        }
    }
}

// ---------------------------------------------------------------------------
// 消息
// ---------------------------------------------------------------------------

@Composable
private fun MessageRow(message: AiMessage) {
    if (message.role == "user") {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = RoundedCornerShape(14.dp, 14.dp, 4.dp, 14.dp),
                modifier = Modifier.widthIn(max = 320.dp),
            ) {
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
        return
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        if (message.events.isNotEmpty()) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                    message.events.forEach { event -> ToolEventRow(event) }
                }
            }
        }
        if (message.content.isNotBlank()) {
            AssistantBubble(text = message.content, pending = false)
        }
    }
}

/** 工具执行事件行：✓ 写入 app/build.gradle.kts —— 已写入 12 行。 */
@Composable
private fun ToolEventRow(event: AiToolEvent) {
    Row(
        modifier = Modifier.padding(vertical = 2.dp),
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
        Column {
            Text(
                text = "${toolLabel(event.name)} ${event.target}".trim(),
                style = MaterialTheme.typography.labelSmall,
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
    }
}

@Composable
private fun AssistantBubble(text: String, pending: Boolean) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            shape = RoundedCornerShape(14.dp, 14.dp, 14.dp, 4.dp),
            modifier = Modifier.widthIn(max = 340.dp),
        ) {
            Text(
                text = if (pending) text + " ▍" else text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun ThinkingRow() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(start = 4.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            strokeWidth = 2.dp,
        )
        Spacer(modifier = Modifier.size(8.dp))
        Text(
            text = "正在请求模型…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun toolLabel(name: String): String = when (name) {
    "list_files" -> "列出"
    "read_file" -> "读取"
    "write_file" -> "写入"
    "search_replace" -> "替换"
    "delete_path" -> "删除"
    else -> name
}

// ---------------------------------------------------------------------------
// 二级视图：接口设置
// ---------------------------------------------------------------------------

/**
 * 接口设置页内视图（替代原 SettingsDialog 弹窗）。
 *
 * 字段、文案、校验与保存行为与弹窗完全一致；顶部返回行退出后回到聊天视图。
 */
@Composable
private fun SettingsView(
    initial: AiConfig,
    onBack: () -> Unit,
    onSave: (baseUrl: String, model: String, apiKey: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var baseUrl by rememberSaveable { mutableStateOf(initial.baseUrl) }
    var model by rememberSaveable { mutableStateOf(initial.model) }
    var apiKey by rememberSaveable { mutableStateOf(initial.apiKey) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState()),
    ) {
        BackRow(title = "接口设置", onBack = onBack)
        Column(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text("Base URL（OpenAI 兼容）") },
                placeholder = { Text("https://api.openai.com/v1") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                label = { Text("模型名") },
                placeholder = { Text("gpt-4o-mini / deepseek-chat / kimi-k2 …") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("API Key（应用内加密存储）") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = "支持任意 OpenAI 兼容服务：OpenAI、DeepSeek、Kimi、通义等；" +
                    "本地 Ollama 可填 http://127.0.0.1:11434/v1（Key 留空）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Start,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onBack) { Text("取消") }
                TextButton(
                    onClick = { onSave(baseUrl.trim(), model.trim(), apiKey.trim()) },
                    enabled = baseUrl.isNotBlank() && model.isNotBlank(),
                ) { Text("保存") }
            }
        }
    }
}
