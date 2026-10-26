package com.mobilecoder.ide.feature.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mobilecoder.ide.core.common.theme.LocalAppPalette
import com.mobilecoder.ide.core.storage.FileRepository
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 项目级「AI 重构」：文件树 / 编辑器 ⋮ 菜单入口 → 发给 AI（附文件上下文）
 * → **先看 diff** → 用户确认 → 再落盘。
 *
 * 安全闭环（复用既有设施）：
 *  1. 改前先 [AiSnapshotStore.capture] 建快照，快照失败则**拒绝写入**（保护优先）；
 *  2. 预览用 [diffLines] + [DiffRowRow]（与 [AiDiffDialog] 同一套行级渲染）；
 *  3. 落盘后仍可「撤销」（[AiSnapshotStore.undo] 回写）或「查看 Diff」（[AiDiffDialog]）。
 *
 * 输入源用**编辑器内存态**（`currentText`，含未保存修改）而非磁盘，避免与自动保存打架；
 * 落盘后通过 [onApplied] 回灌编辑器 Tab。
 */
@Composable
fun AiRefactorDialog(
    projectRoot: String,
    /** 目标文件，相对项目根（`src/Main.kt`）。 */
    filePath: String,
    language: String,
    /** 编辑器内存态正文（null = 该文件没打开，直接读磁盘）。 */
    currentText: String? = null,
    /** 选区起止下标（相对 [currentText]；负数或空选区 = 整文件重构）。 */
    selectionStart: Int = -1,
    selectionEnd: Int = -1,
    /** 快照归属会话（null = 当前会话，便于和会话里的撤销一起清理）。 */
    sessionId: String? = null,
    /** 落盘成功回调（编辑器据此刷新 Tab；撤销时会再回调一次旧内容）。 */
    onApplied: (newText: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var phase by remember { mutableStateOf(Phase.LOADING) }
    var oldText by remember { mutableStateOf("") }
    var newText by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf("") }
    var warning by remember { mutableStateOf("") }
    var applyError by remember { mutableStateOf("") }
    var snapshotId by remember { mutableStateOf<String?>(null) }
    var task by remember { mutableStateOf("") }
    // 换「重构要求」后重新生成 = 让 LaunchedEffect 的 key 变化重跑一次
    var tick by remember { mutableIntStateOf(0) }
    var showDiff by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val sid = sessionId ?: AiController.currentId.value ?: "refactor"

    // 选区（每次请求时按源文本重新校验，防止下标越界）
    val hasSelection = selectionStart >= 0 && selectionEnd > selectionStart

    LaunchedEffect(tick) {
        phase = Phase.LOADING
        errorText = ""
        warning = ""
        applyError = ""
        try {
            val source = withContext(Dispatchers.IO) {
                currentText ?: runCatching {
                    File(projectRoot, filePath).readText(Charsets.UTF_8)
                }.getOrNull()
            }
            if (source == null) {
                errorText = "无法读取文件：$filePath"
                phase = Phase.ERROR
                return@LaunchedEffect
            }
            oldText = source
            val start = selectionStart.coerceIn(0, source.length)
            val end = selectionEnd.coerceIn(start, source.length)
            val sliced = hasSelection && end > start
            if (!sliced && source.isBlank()) {
                errorText = "空文件没有可重构的内容"
                phase = Phase.ERROR
                return@LaunchedEffect
            }
            val raw = AiOneShot.ask(
                system = systemPrompt(),
                user = userPrompt(
                    task = task,
                    language = language,
                    path = filePath,
                    source = source,
                    start = if (sliced) start else -1,
                    end = if (sliced) end else -1,
                ),
                maxTokens = REFACOR_MAX_TOKENS,
                temperature = 0.2,
            )
            val replacement = extract(raw)
            if (replacement.isEmpty()) {
                errorText = "AI 没有返回可用的代码，请调整重构要求后重试"
                phase = Phase.ERROR
                return@LaunchedEffect
            }
            val result = if (sliced) splice(source, start, end, replacement) else replacement
            if (result == source) {
                errorText = "AI 返回的内容与原文相同，没有可应用的改动"
                phase = Phase.ERROR
                return@LaunchedEffect
            }
            newText = result
            warning = warningOf(source, result, sliced)
            phase = Phase.READY
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            errorText = t.message ?: "请求失败"
            phase = Phase.ERROR
        }
    }

    /** 确认应用：先建快照 → 写盘 → 回灌编辑器。 */
    fun applyChange() {
        scope.launch {
            applyError = ""
            val target = File(projectRoot, filePath)
            val existed = withContext(Dispatchers.IO) { target.exists() }
            val manifest = withContext(Dispatchers.IO) {
                runCatching { AiSnapshotStore.capture(File(projectRoot), sid, listOf(filePath)) }
                    .getOrNull()
            }
            if (existed && manifest == null) {
                applyError = "改前快照创建失败，已取消写入（保护优先）"
                return@launch
            }
            val ok = withContext(Dispatchers.IO) { FileRepository.writeText(target, newText) }
            if (!ok) {
                applyError = "写入失败：$filePath"
                return@launch
            }
            snapshotId = manifest?.id
            phase = Phase.APPLIED
            onApplied(newText)
        }
    }

    /** 撤销刚才的写入（快照回写），回到预览态。 */
    fun undoApply() {
        val id = snapshotId ?: return
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { AiSnapshotStore.undo(File(projectRoot), id) }.getOrNull()
            }
            if (result == null) {
                applyError = "快照已清理，无法自动撤销"
                return@launch
            }
            snapshotId = null
            phase = Phase.READY
            onApplied(oldText)
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
                RefactorHeader(
                    filePath = filePath,
                    applied = phase == Phase.APPLIED,
                    onClose = onDismiss,
                )

                when (phase) {
                    Phase.LOADING -> LoadingBody(onCancel = onDismiss)

                    Phase.ERROR -> ErrorBody(
                        message = errorText,
                        onRetry = { tick++ },
                        onCancel = onDismiss,
                    )

                    Phase.READY -> PreviewBody(
                        task = task,
                        onTaskChange = { task = it },
                        onRegenerate = { tick++ },
                        warning = warning,
                        applyError = applyError,
                        oldText = oldText,
                        newText = newText,
                        onApply = { applyChange() },
                        onCancel = onDismiss,
                    )

                    Phase.APPLIED -> AppliedBody(
                        filePath = filePath,
                        canUndo = snapshotId != null,
                        applyError = applyError,
                        onShowDiff = { showDiff = true },
                        onUndo = { undoApply() },
                        onDone = onDismiss,
                    )
                }
            }
        }
    }

    // 应用后的行级差异：与「本轮修改」同一套 AiDiff 快照渲染
    if (showDiff) {
        snapshotId?.let { id ->
            AiDiffDialog(
                projectRoot = projectRoot,
                snapshotId = id,
                onDismiss = { showDiff = false },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 提示词与纯函数（单测覆盖）
// ---------------------------------------------------------------------------

/** 重构请求的输出上限（整文件输出，给足但不无限）。 */
internal const val REFACOR_MAX_TOKENS = 4096

/** 源码长度上限（超长文件整文件重构容易被截断，直接拦下）。 */
internal const val MAX_SOURCE_CHARS = 300_000

/** 选区模式下附带的上下文行数（前 80 后 40，帮模型理解语境）。 */
private const val CONTEXT_BEFORE_LINES = 80
private const val CONTEXT_AFTER_LINES = 40

internal fun systemPrompt(): String = """
    你是「移动码匠」IDE 的代码重构引擎，输出**可直接替换原代码**的重构结果。
    严格要求：
    1. 只输出代码本身：不要解释改动、不要 Markdown 代码围栏以外的任何文字。
    2. 保持行为不变（除非用户的重构要求另有说明），不要顺手改动无关代码。
    3. 保持原有缩进风格与文件编码；输出必须与被替换片段的上下文无缝衔接。
    4. 输出必须完整：不允许省略号、`// ...` 之类占位，不允许截断。
    5. 语言要求：任何说明性文字用简体中文，但输出内容只能是代码。
""".trimIndent()

/**
 * 用户消息：任务 + 文件信息 + 源码（整文件）或选区 + 上下文。
 *
 * @param start 选区起始下标；负数 = 整文件模式
 */
internal fun userPrompt(
    task: String,
    language: String,
    path: String,
    source: String,
    start: Int,
    end: Int,
): String {
    val requirement = if (start < 0) {
        "输出**完整文件**的重构结果（保留全部内容，只做必要的重构改动）。"
    } else {
        "只输出**选中片段**的替换内容（不要输出选区以外的代码）。"
    }
    val purpose = task.trim().ifEmpty { "重构代码，保持行为不变，提升可读性" }
    return buildString {
        append("任务：").append(purpose).append('\n')
        append("文件：").append(path).append("（").append(language.ifBlank { "纯文本" }).append("）\n")
        append("要求：").append(requirement).append('\n')
        if (start < 0) {
            append("\n【完整文件】\n")
            append(source)
        } else {
            val lines = source.replace("\r\n", "\n").split('\n')
            val startLine = source.substring(0, start).count { it == '\n' }
            val endLine = startLine + source.substring(start, end).count { it == '\n' }
            append("\n【选中的代码】\n")
            append(source.substring(start, end))
            val before = if (startLine > 0) {
                "\n【上文】\n" + lines
                    .slice(0 until (startLine).coerceAtMost(lines.size))
                    .takeLast(CONTEXT_BEFORE_LINES)
                    .joinToString("\n")
            } else {
                ""
            }
            val after = if (endLine + 1 < lines.size) {
                "\n【下文】\n" + lines
                    .slice((endLine + 1).coerceAtMost(lines.size) until lines.size)
                    .take(CONTEXT_AFTER_LINES)
                    .joinToString("\n")
            } else {
                ""
            }
            if (before.isNotEmpty()) append(before)
            if (after.isNotEmpty()) append(after)
        }
    }
}

/**
 * 从模型输出提取代码：有围栏取围栏正文（与 [AiCompletion.stripFence] 同规则），
 * 无围栏则整段当作代码；去掉首尾空行。
 */
internal fun extract(raw: String): String {
    val text = raw.replace("\r\n", "\n").replace('\r', '\n')
    val lines = text.split('\n')
    val open = lines.indexOfFirst { it.trimStart().startsWith("```") }
    val body = if (open >= 0) {
        val inner = lines.drop(open + 1)
        val close = inner.indexOfFirst { it.trimStart().startsWith("```") }
        if (close >= 0) inner.take(close) else inner
    } else {
        lines
    }
    return body.joinToString("\n").trim('\n')
}

/** 用 [replacement] 替换 `source[start, end)`。 */
internal fun splice(source: String, start: Int, end: Int, replacement: String): String {
    val s = start.coerceIn(0, source.length)
    val e = end.coerceIn(s, source.length)
    return source.substring(0, s) + replacement + source.substring(e)
}

/**
 * 落盘前的完整性提醒：返回内容明显短于原文时，提示用户先核对 diff 再应用
 * （常见于模型把整文件改“没”了或被 max_tokens 截断）。
 */
internal fun warningOf(old: String, new: String, sliced: Boolean): String {
    if (sliced) return ""
    val oldLines = old.count { it == '\n' } + 1
    val newLines = new.count { it == '\n' } + 1
    return if (oldLines >= 20 && newLines * 2 < oldLines) {
        "返回内容明显少于原文（$oldLines 行 → $newLines 行），应用前请确认没有被截断"
    } else {
        ""
    }
}

private enum class Phase { LOADING, READY, ERROR, APPLIED }

// ---------------------------------------------------------------------------
// 界面
// ---------------------------------------------------------------------------

@Composable
private fun RefactorHeader(filePath: String, applied: Boolean, onClose: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.AutoAwesome,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 12.dp, end = 10.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (applied) "AI 重构 · 已应用" else "AI 重构",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = filePath,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontFamily = FontFamily.Monospace,
            )
        }
        IconButton(onClick = onClose, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Default.Close, contentDescription = "关闭")
        }
    }
}

@Composable
private fun LoadingBody(onCancel: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Text(
            text = "正在生成重构方案…（首次请求可能需要几秒）",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp),
        )
        TextButton(onClick = onCancel, modifier = Modifier.padding(top = 8.dp)) {
            Text("取消")
        }
    }
}

@Composable
private fun ErrorBody(message: String, onRetry: () -> Unit, onCancel: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        Row(modifier = Modifier.padding(top = 12.dp)) {
            TextButton(onClick = onCancel) { Text("取消") }
            TextButton(onClick = onRetry) { Text("重试") }
        }
    }
}

@Composable
private fun PreviewBody(
    task: String,
    onTaskChange: (String) -> Unit,
    onRegenerate: () -> Unit,
    warning: String,
    applyError: String,
    oldText: String,
    newText: String,
    onApply: () -> Unit,
    onCancel: () -> Unit,
) {
    val rows = remember(oldText, newText) {
        diffLines(
            oldText.replace("\r\n", "\n").split('\n'),
            newText.replace("\r\n", "\n").split('\n'),
        ).first
    }
    val palette = LocalAppPalette.current
    val added = remember(rows) { rows.count { it.type == AiDiffRowType.ADD } }
    val removed = remember(rows) { rows.count { it.type == AiDiffRowType.DEL } }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = task,
                onValueChange = onTaskChange,
                label = { Text("重构要求（可选）") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRegenerate, modifier = Modifier.padding(start = 4.dp)) {
                Text("重新生成")
            }
        }

        if (warning.isNotBlank() || applyError.isNotBlank()) {
            Text(
                text = warning.ifBlank { applyError },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }

        Text(
            text = "改动预览：+$added 行 / -$removed 行（确认后再写入）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )

        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(rows.size) { index -> DiffRowRow(row = rows[index], palette = palette) }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onCancel) { Text("取消") }
            Button(onClick = onApply) { Text("应用改动") }
        }
    }
}

@Composable
private fun AppliedBody(
    filePath: String,
    canUndo: Boolean,
    applyError: String,
    onShowDiff: () -> Unit,
    onUndo: () -> Unit,
    onDone: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "已写入 $filePath",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "写入前已创建改前快照，可随时撤销；也可先查看行级 Diff。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp, bottom = 4.dp),
        )
        if (applyError.isNotBlank()) {
            Text(
                text = applyError,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Row(modifier = Modifier.padding(top = 8.dp)) {
            TextButton(onClick = onShowDiff) { Text("查看 Diff") }
            TextButton(onClick = onUndo, enabled = canUndo) { Text("撤销") }
            Button(onClick = onDone, modifier = Modifier.padding(start = 8.dp)) { Text("完成") }
        }
    }
}
