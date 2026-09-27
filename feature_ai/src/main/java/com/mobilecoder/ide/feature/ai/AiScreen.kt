package com.mobilecoder.ide.feature.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import com.mobilecoder.ide.core.common.ui.EmptyState
import kotlinx.coroutines.launch

/**
 * AI 助手页（PRD 扩展：接入 AI API Key，通过会话驱动项目修改）。
 *
 * 结构：会话标题栏（历史 / 新会话 / 设置）→ 消息流（含工具执行事件）→ 输入区。
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
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var historyOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()

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

    Column(modifier = modifier.fillMaxSize()) {
        // ---------------- 标题栏 ----------------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "AI 助手",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = current?.title ?: "尚未创建会话",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box {
                IconButton(onClick = { historyOpen = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "会话历史")
                }
                SessionMenu(
                    expanded = historyOpen,
                    sessions = sessions,
                    currentId = current?.id,
                    onDismiss = { historyOpen = false },
                    onSelect = { id ->
                        historyOpen = false
                        AiController.select(id)
                    },
                    onDelete = { AiController.deleteSession(it) },
                )
            }
            IconButton(onClick = { AiController.newSession() }) {
                Icon(Icons.Default.Add, contentDescription = "新会话")
            }
            IconButton(onClick = { settingsOpen = true }) {
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
                    TextButton(onClick = { settingsOpen = true }) { Text("去设置") }
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
                onValueChange = { input = it.take(20_000) },
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
                    if (busy) AiController.cancel() else submit()
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

    if (settingsOpen) {
        SettingsDialog(
            initial = config,
            onDismiss = { settingsOpen = false },
            onSave = { baseUrl, model, key ->
                settingsOpen = false
                scope.launch { AiController.saveConfig(baseUrl, model, key) }
            },
        )
    }
}

// ---------------------------------------------------------------------------
// 会话历史菜单
// ---------------------------------------------------------------------------

@Composable
private fun SessionMenu(
    expanded: Boolean,
    sessions: List<AiSession>,
    currentId: String?,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (sessions.isEmpty()) {
            DropdownMenuItem(text = { Text("还没有历史会话") }, onClick = onDismiss)
        }
        sessions.forEach { session ->
            DropdownMenuItem(
                text = {
                    Text(
                        text = session.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = if (session.id == currentId) FontWeight.Bold else null,
                    )
                },
                trailingIcon = {
                    IconButton(onClick = { onDelete(session.id) }, modifier = Modifier.size(28.dp)) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "删除会话",
                            modifier = Modifier.size(16.dp),
                        )
                    }
                },
                onClick = { onSelect(session.id) },
            )
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
// 设置
// ---------------------------------------------------------------------------

@Composable
private fun SettingsDialog(
    initial: AiConfig,
    onDismiss: () -> Unit,
    onSave: (baseUrl: String, model: String, apiKey: String) -> Unit,
) {
    var baseUrl by rememberSaveable { mutableStateOf(initial.baseUrl) }
    var model by rememberSaveable { mutableStateOf(initial.model) }
    var apiKey by rememberSaveable { mutableStateOf(initial.apiKey) }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.imePadding(),
        title = { Text("AI 接口设置") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
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
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(baseUrl.trim(), model.trim(), apiKey.trim()) },
                enabled = baseUrl.isNotBlank() && model.isNotBlank(),
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
