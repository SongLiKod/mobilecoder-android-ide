package com.mobilecoder.ide.feature.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mobilecoder.ide.core.common.ui.AppAlertDialog
import com.mobilecoder.ide.core.common.ui.EmptyState
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AI 助手页（PRD 扩展：接入 AI API Key，通过会话驱动项目修改）。
 *
 * 结构：操作行（会话名 + 当前接口/模型 + 历史/新会话/设置）→ 配置/切换/通知/错误条
 * → 消息流（Markdown + 工具卡片 + 撤销/Diff + 贴底跟随）→ 进度行 → 输入区。
 * 「历史会话」「接口设置」为页内二级视图；写操作确认弹窗由 [AiController.confirmHandler] 挂载。
 * 逻辑全部在 [AiController]：流式输出、工具调用循环、多接口容灾轮换、会话持久化。
 */
@Composable
fun AiScreen(
    projectPath: String,
    modifier: Modifier = Modifier,
    /** 当前编辑器打开的文件（用于输入区「引用文件」）。 */
    currentFilePath: String? = null,
    /** 点工具事件里的文件名：跳转编辑器打开（参数为相对项目根的路径、行号）。 */
    onOpenFile: (path: String, line: Int) -> Unit = { _, _ -> },
) {
    val sessions by AiController.sessions.collectAsStateWithLifecycle()
    val current by AiController.current.collectAsStateWithLifecycle()
    val busy by AiController.busy.collectAsStateWithLifecycle()
    val progress by AiController.progress.collectAsStateWithLifecycle()
    val switch by AiController.switch.collectAsStateWithLifecycle()
    val error by AiController.error.collectAsStateWithLifecycle()
    val notice by AiController.notice.collectAsStateWithLifecycle()
    val providers by AiController.providers.collectAsStateWithLifecycle()
    val confirmWrites by AiController.confirmWrites.collectAsStateWithLifecycle()
    val systemPrompt by AiController.systemPrompt.collectAsStateWithLifecycle()
    val roundLimit by AiController.roundLimit.collectAsStateWithLifecycle()

    var input by rememberSaveable { mutableStateOf("") }
    var settingsView by rememberSaveable { mutableStateOf(false) }
    var historyView by rememberSaveable { mutableStateOf(false) }
    var pendingDeleteId by rememberSaveable { mutableStateOf("") }
    var diffSnapshotId by rememberSaveable { mutableStateOf("") }
    var confirmRequest by remember { mutableStateOf<AiConfirmRequest?>(null) }
    var confirmDeferred by remember { mutableStateOf<CompletableDeferred<Boolean>?>(null) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    LaunchedEffect(projectPath) {
        AiController.ensureLoaded()
        AiController.refreshConfig()
    }

    // 项目文件索引：供输入框 @ 引用做模糊匹配（IO 线程扫描一次，排除构建/版本目录）
    var fileIndex by remember(projectPath) { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(projectPath) {
        fileIndex = withContext(Dispatchers.IO) { scanProjectIndex(File(projectPath)) }
    }

    // 写操作确认弹窗挂载（拒绝 → 本轮写类操作跳过，读操作照常）
    DisposableEffect(Unit) {
        AiController.confirmHandler = { req ->
            val deferred = CompletableDeferred<Boolean>()
            confirmRequest = req
            confirmDeferred = deferred
            deferred.await()
        }
        onDispose { AiController.confirmHandler = null }
    }

    fun submit() {
        val text = input.trim()
        if (text.isEmpty() || busy) return
        input = ""
        AiController.send(projectPath, text)
    }

    if (settingsView) {
        ProviderSettingsView(
            config = providers,
            confirmWrites = confirmWrites,
            systemPrompt = systemPrompt,
            roundLimit = roundLimit,
            onBack = { settingsView = false },
            onSave = { cfg -> scope.launch { AiController.saveProviders(cfg) } },
            onConfirmWritesChange = { v -> scope.launch { AiController.setConfirmWrites(v) } },
            onSaveSystemPrompt = { v -> scope.launch { AiController.saveSystemPrompt(v) } },
            onSaveRoundLimit = { v -> scope.launch { AiController.setRoundLimit(v) } },
            onTest = { endpoint -> AiController.test(endpoint) },
            modifier = modifier,
        )
    } else if (historyView) {
        HistoryView(
            sessions = sessions,
            currentId = current?.id,
            canInteract = !busy,
            onBack = { historyView = false },
            onSelect = { id ->
                AiController.select(id)
                historyView = false
            },
            onDelete = { pendingDeleteId = it },
        )
    } else {
        ChatView(
            projectPath = projectPath,
            fileIndex = fileIndex,
            current = current,
            busy = busy,
            progress = progress,
            switch = switch,
            error = error,
            notice = notice,
            providers = providers,
            input = input,
            currentFilePath = currentFilePath,
            listState = listState,
            onInput = { input = it },
            onSubmit = { submit() },
            onOpenHistory = { historyView = true },
            onOpenSettings = { settingsView = true },
            onOpenFile = { path -> onOpenFile(path, 0) },
            onUndo = { snapshotId ->
                current?.id?.let { AiController.undoRound(projectPath, it, snapshotId) }
            },
            onShowDiff = { diffSnapshotId = it },
            onRetry = {
                val last = current?.messages?.lastOrNull()
                if (last?.role == "user") AiController.retryLast(projectPath)
            },
            modifier = modifier,
        )
    }

    // 删除会话确认
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

    // 写操作确认（delete 恒确认 / 开启“写入前确认”后写类操作也走这里）
    confirmRequest?.let { req ->
        AppAlertDialog(
            title = req.title,
            message = req.items.joinToString("\n") + "\n\n允许后将修改/删除项目文件，本轮改动可在消息下方一键撤销。",
            confirmLabel = "允许",
            dismissLabel = "拒绝",
            destructive = true,
            onConfirm = {
                confirmDeferred?.complete(true)
                confirmRequest = null
                confirmDeferred = null
            },
            onDismiss = {
                confirmDeferred?.complete(false)
                confirmRequest = null
                confirmDeferred = null
            },
        )
    }

    // 本轮修改 Diff 预览
    if (diffSnapshotId.isNotBlank()) {
        AiDiffDialog(
            projectRoot = projectPath,
            snapshotId = diffSnapshotId,
            onDismiss = { diffSnapshotId = "" },
        )
    }
}

// ---------------------------------------------------------------------------
// 聊天主视图
// ---------------------------------------------------------------------------

@Composable
private fun ChatView(
    projectPath: String,
    fileIndex: List<String>,
    current: AiSession?,
    busy: Boolean,
    progress: AiProgress?,
    switch: AiSwitchInfo?,
    error: String?,
    notice: String?,
    providers: AiProvidersConfig,
    input: String,
    currentFilePath: String?,
    listState: LazyListState,
    onInput: (String) -> Unit,
    onSubmit: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenFile: (String) -> Unit,
    onUndo: (String) -> Unit,
    onShowDiff: (String) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val messages = current?.messages.orEmpty()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    // 进度按会话归属：切走的会话不再显示他人的流式文本（P0 串台修复）
    val mine = progress?.takeIf { it.sessionId == current?.id }
    val endpointLabel = providers.activeEndpoint()?.label ?: "未配置接口"
    val changedCount = remember(messages) {
        messages.sumOf { m -> m.events.count { it.ok && AiTools.isMutating(it.name) } }
    }

    // 贴底跟随：在底部（或距底仅差一条）时新内容自动滚最新；用户上滑离开底部则不打扰。
    // 判据取 total-2 而非 total-1：新 item 刚加入、尚未进入视口时 last.index 为 total-2，
    // 若按 total-1 判会误判「不在底部」导致新回复不跟随。
    val nearBottom = remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            last == null || last.index >= info.totalItemsCount - 2
        }
    }
    val showJump = remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            last != null && info.totalItemsCount > 0 && last.index < info.totalItemsCount - 2
        }
    }
    LaunchedEffect(messages.size, mine?.stage, mine?.text) {
        withFrameNanos { } // 等新内容完成一帧布局，避免读到上一帧的 layoutInfo
        if (nearBottom.value) {
            val total = listState.layoutInfo.totalItemsCount
            if (total > 0) {
                // 大 offset 让末条对齐到底部（滚动到列表末端）
                listState.scrollToItem(total - 1, scrollOffset = 100_000)
            }
        }
    }

    // 切换提示不自动消失（直到下次发送或手动关闭）：用户需要能随时看到
    // “首选模型为何被切走”，否则会误以为请求没走自己选的模型。
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(5_000)
            AiController.clearNotice()
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // ---------------- 操作行 ----------------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = current?.title ?: "尚未创建会话",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = buildString {
                            append(endpointLabel)
                            if (changedCount > 0) append(" · 已改 $changedCount 个文件")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (providers.activeEndpoint() == null) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = onOpenHistory, enabled = !busy, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.History, contentDescription = "历史会话")
            }
            IconButton(
                onClick = { AiController.newSession() },
                enabled = !busy,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(Icons.Default.Add, contentDescription = "新会话")
            }
            IconButton(onClick = onOpenSettings, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.Settings, contentDescription = "AI 接口设置")
            }
        }

        // ---------------- 未配置提示（中性信息色，不再满屏红） ----------------
        if (providers.candidates().isEmpty()) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "尚未配置 AI 接口：支持多接口、每接口多模型，额度用尽自动切换下一个",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onOpenSettings) { Text("去设置") }
                }
            }
        }

        // ---------------- 自动切换提示 ----------------
        switch?.let { info ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                shape = RoundedCornerShape(8.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.WarningAmber,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.size(6.dp))
                    Text(
                        text = "${info.reason}：${info.from} → 已自动切换到 ${info.to}（上下文保持）",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = { AiController.clearSwitch() },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "关闭", modifier = Modifier.size(14.dp))
                    }
                }
            }
        }

        // ---------------- 中性通知 ----------------
        notice?.let { text ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ) {
                Row(
                    modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = 4.dp),
                    )
                    IconButton(onClick = { AiController.clearNotice() }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "关闭提示")
                    }
                }
            }
        }

        // ---------------- 消息流 ----------------
        if (messages.isEmpty() && mine == null) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                EmptyChatState(onPick = { onInput(it) })
            }
        } else {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(messages.size) { index ->
                        val message = messages[index]
                        MessageRow(
                            message = message,
                            busy = busy,
                            onOpenFile = onOpenFile,
                            onUndo = onUndo,
                            onShowDiff = onShowDiff,
                        )
                    }
                    when (mine?.stage) {
                        // REQUESTING/TOOL 只由底部固定进度条渲染，流内不重复
                        AiStage.STREAMING -> if (mine.text.isNotEmpty()) {
                            item { StreamingRow(mine.text) }
                        }

                        else -> Unit
                    }
                }
                if (showJump.value) {
                    FloatingActionButton(
                        onClick = {
                            scope.launch {
                                listState.scrollToItem(
                                    listState.layoutInfo.totalItemsCount - 1,
                                    scrollOffset = 100_000,
                                )
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(12.dp)
                            .size(40.dp),
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ) {
                        Icon(Icons.Default.ArrowDownward, contentDescription = "回到最新")
                    }
                }
            }
        }

        // ---------------- 错误提示（可展开 / 复制 / 重试） ----------------
        error?.let { message ->
            ErrorBar(
                message = message,
                canRetry = current?.messages?.lastOrNull()?.role == "user" && !busy,
                onRetry = onRetry,
                onDismiss = { AiController.clearError() },
                onCopy = { clipboard.setText(AnnotatedString(message)) },
            )
        }

        // ---------------- 进度行 ----------------
        if (mine != null && mine.stage != AiStage.STREAMING && busy) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
            ) {
                ThinkingRow(progress = mine, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            }
        }

        // ---------------- 输入区 ----------------
        InputRow(
            input = input,
            busy = busy,
            currentFilePath = currentFilePath,
            projectPath = projectPath,
            fileIndex = fileIndex,
            onInput = onInput,
            onSubmit = onSubmit,
            onCancel = { AiController.cancel() },
            onQuoteFile = { relative ->
                val cursorText = if (input.isBlank()) "@$relative " else "$input\n@$relative "
                onInput(cursorText)
            },
        )
    }
}

// ---------------------------------------------------------------------------
// 错误条 / 空态 / 输入区
// ---------------------------------------------------------------------------

/** 错误条：默认 3 行截断，可展开、复制、对未回复的请求重试。 */
@Composable
private fun ErrorBar(
    message: String,
    canRetry: Boolean,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
) {
    var expanded by rememberSaveable(message) { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Column(modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 2.dp)) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                maxLines = if (expanded) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (canRetry) {
                    TextButton(onClick = onRetry) { Text("重试") }
                }
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "收起" else "展开")
                }
                TextButton(onClick = onCopy) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(modifier = Modifier.size(4.dp))
                    Text("复制")
                }
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "关闭提示")
                }
            }
        }
    }
}

/** 空态：说明 + 快捷提示词 chips（点击填入输入框）。 */
@Composable
private fun EmptyChatState(onPick: (String) -> Unit) {
    val prompts = listOf(
        "总结这个项目",
        "列出主要文件并说明作用",
        "给 README 补充使用说明",
        "检查并修复明显的代码问题",
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "开始一个会话",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.size(6.dp))
        Text(
            text = "AI 可以浏览并直接修改当前项目的文件\n（读取 → 精确替换 → 写回，改动全程可见、可撤销）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.size(16.dp))
        prompts.forEach { prompt ->
            AssistChip(
                onClick = { onPick(prompt) },
                label = { Text(prompt, style = MaterialTheme.typography.bodySmall) },
                modifier = Modifier.padding(vertical = 3.dp),
            )
        }
    }
}

/** 输入区：busy 只禁发送不禁输入（可先打草稿）；支持「引用当前文件」与 `@` 模糊匹配引用。 */
@Composable
private fun InputRow(
    input: String,
    busy: Boolean,
    currentFilePath: String?,
    projectPath: String,
    fileIndex: List<String>,
    onInput: (String) -> Unit,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
    onQuoteFile: (String) -> Unit,
) {
    // `@` 触发：行首/空白后的 @xxx（到文本末尾无空格）视为引用查询
    val mention = remember(input) { extractMention(input) }
    val suggestions = remember(mention, fileIndex) {
        if (mention == null) emptyList() else suggestPaths(fileIndex, mention.second)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        if (suggestions.isNotEmpty()) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 180.dp),
            ) {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                ) {
                    suggestions.forEach { path ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val (at, query) = mention ?: return@clickable
                                    onInput(
                                        input.substring(0, at) +
                                            "@$path " +
                                            input.substring(at + 1 + query.length),
                                    )
                                }
                                .padding(horizontal = 14.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Default.AttachFile,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.size(8.dp))
                            Text(
                                text = path,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 10.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            if (currentFilePath != null && !busy) {
                IconButton(
                    onClick = { onQuoteFile(relativeOf(projectPath, currentFilePath)) },
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        Icons.Default.AttachFile,
                        contentDescription = "引用当前文件",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            OutlinedTextField(
                value = input,
                onValueChange = { onInput(it.take(20_000)) },
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(
                        text = if (busy) {
                            "AI 正在执行，可先输入下一条…"
                        } else {
                            "描述要做的修改，输入 @ 可引用项目文件"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                minLines = 1,
                maxLines = 5,
                textStyle = MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.size(8.dp))
            IconButton(
                onClick = { if (busy) onCancel() else onSubmit() },
                enabled = busy || input.isNotBlank(),
            ) {
                Icon(
                    imageVector = if (busy) Icons.Default.Stop else Icons.Default.Send,
                    contentDescription = if (busy) "停止" else "发送",
                    tint = if (busy) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
        }
    }
}

private fun relativeOf(projectPath: String, filePath: String): String {
    if (projectPath.isBlank()) return File(filePath).name
    return filePath.removePrefix(projectPath).removePrefix("/")
}

// ---------------------------------------------------------------------------
// @ 引用：项目文件索引与模糊匹配
// ---------------------------------------------------------------------------

/** 扫描项目生成相对路径索引（文件 + 目录），排除构建/版本控制等噪音目录，上限 4000 条。 */
private fun scanProjectIndex(root: File): List<String> {
    if (!root.isDirectory) return emptyList()
    val skip = setOf(
        ".git", ".gradle", ".idea", ".kotlin", ".cache", ".cxx",
        "build", "captures", "node_modules", "ai-snapshots",
    )
    val out = ArrayList<String>(512)

    fun walk(dir: File, prefix: String) {
        if (out.size >= 4000) return
        val children = dir.listFiles() ?: return
        children.sortWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        for (f in children) {
            if (out.size >= 4000) return
            if (f.isDirectory) {
                if (f.name in skip) continue
                val rel = prefix + f.name
                out.add(rel)
                walk(f, "$rel/")
            } else {
                out.add(prefix + f.name)
            }
        }
    }

    walk(root, "")
    return out
}

/**
 * 提取行首/空白后的 `@查询`（查询延续到文本末尾，含空格即结束）。
 *
 * @return `@` 的下标与查询串；无活跃引用返回 null。
 */
internal fun extractMention(text: String): Pair<Int, String>? {
    val idx = text.lastIndexOf('@')
    if (idx < 0) return null
    if (idx > 0 && !text[idx - 1].isWhitespace()) return null
    val query = text.substring(idx + 1)
    if (query.any { it == '@' || it.isWhitespace() }) return null
    return idx to query
}

/** 模糊匹配：文件名前缀 > 路径前缀 > 文件名包含 > 路径包含 > 子序列匹配；取前 8 条。 */
internal fun suggestPaths(index: List<String>, query: String): List<String> {
    if (index.isEmpty()) return emptyList()
    val q = query.lowercase()
    return index.mapNotNull { path ->
        mentionScore(path, q).let { score -> if (score == null) null else path to score }
    }
        .sortedWith(compareBy({ it.second }, { it.first.length }, { it.first }))
        .take(8)
        .map { it.first }
}

internal fun mentionScore(path: String, query: String): Int? {
    if (query.isEmpty()) return 5
    val p = path.lowercase()
    val name = p.substringAfterLast('/')
    return when {
        name.startsWith(query) -> 0
        p.startsWith(query) -> 1
        name.contains(query) -> 2
        p.contains(query) -> 3
        isSubsequence(query, p) -> 4
        else -> null
    }
}

internal fun isSubsequence(query: String, target: String): Boolean {
    var i = 0
    for (ch in target) {
        if (i < query.length && ch == query[i]) i++
    }
    return i == query.length
}

// ---------------------------------------------------------------------------
// 二级视图：历史会话
// ---------------------------------------------------------------------------

/** 页内二级视图顶部返回行（40dp 返回箭头 + 标题）。 */
@Composable
fun BackRow(title: String, onBack: () -> Unit) {
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
    canInteract: Boolean,
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
                        enabled = canInteract,
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
    enabled: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .clickable(enabled = enabled, onClick = onClick)
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
        IconButton(onClick = onDelete, enabled = enabled, modifier = Modifier.size(40.dp)) {
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
