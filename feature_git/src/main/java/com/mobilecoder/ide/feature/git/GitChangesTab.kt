package com.mobilecoder.ide.feature.git

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mobilecoder.ide.core.common.theme.LocalAppPalette
import com.mobilecoder.ide.core.common.ui.AppAlertDialog
import com.mobilecoder.ide.core.common.ui.EmptyState
import com.mobilecoder.ide.core.common.ui.SectionHeader
import com.mobilecoder.ide.core.common.ui.StatChip
import kotlinx.coroutines.launch

/** 待二次确认的「还原」请求（还原是破坏性操作，确认后才执行）。 */
private data class RestoreRequest(
    val group: GitChangeGroup,
    val entry: GitStatusEntry,
)

/**
 * 变更页（PRD 2.5：分组状态 + 暂存/取消暂存 + 提交 + Diff 查看器）。
 *
 * 分组：已暂存 / 未暂存 / 未跟踪 / 冲突 / 已忽略；
 * 行点击展开 Diff 查看器；顶部为提交区与工作区统计。
 * 「还原」需二次确认：未暂存组只丢弃工作区改动，已暂存组连同暂存一并回到 HEAD；
 * **新增文件（未跟踪 `??` / 已暂存 `A `）的「还原」= 删除该文件**（`GitController.removeAdded`）。
 */
@Composable
fun ChangesTab(
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val status by GitController.status.collectAsStateWithLifecycle()
    val stats by GitController.diffStats.collectAsStateWithLifecycle()
    val identity by GitController.identity.collectAsStateWithLifecycle()
    val busy by GitController.busy.collectAsStateWithLifecycle()

    var commitMessage by rememberSaveable { mutableStateOf("") }
    var expandedKey by rememberSaveable { mutableStateOf("") }
    var pendingRestore by remember { mutableStateOf<RestoreRequest?>(null) }

    val staged = remember(status) { status.filter { it.inGroup(GitChangeGroup.STAGED) } }
    val unstaged = remember(status) { status.filter { it.inGroup(GitChangeGroup.UNSTAGED) } }
    val untracked = remember(status) { status.filter { it.inGroup(GitChangeGroup.UNTRACKED) } }
    val conflicted = remember(status) { status.filter { it.inGroup(GitChangeGroup.CONFLICTED) } }
    val ignored = remember(status) { status.filter { it.inGroup(GitChangeGroup.IGNORED) } }
    val empty = staged.isEmpty() && unstaged.isEmpty() && untracked.isEmpty() &&
        conflicted.isEmpty() && ignored.isEmpty()

    Column(modifier = modifier.fillMaxSize()) {
        // ---------------- 提交区 ----------------
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = commitMessage,
                onValueChange = { commitMessage = it },
                label = { Text("提交信息（必填）") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 4,
                enabled = !busy,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "${identity.name} <${identity.email}>",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Button(
                    onClick = {
                        scope.launch {
                            val rc = GitController.commit(commitMessage)
                            if (rc == 0) commitMessage = ""
                        }
                    },
                    enabled = commitMessage.isNotBlank() && !busy,
                ) {
                    Text("提交")
                }
            }
        }

        // ---------------- 工作区统计 ----------------
        val statsValue = stats
        if (statsValue != null && !statsValue.isEmpty) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                StatChip(label = "新增文件", value = "${statsValue.added}")
                StatChip(label = "删除文件", value = "${statsValue.deleted}")
                StatChip(label = "修改文件", value = "${statsValue.modified}")
                StatChip(label = "增加行", value = "+${statsValue.linesAdd}", emphasize = true)
                StatChip(label = "删除行", value = "-${statsValue.linesDel}", emphasize = true)
            }
        }

        // ---------------- 变更列表 ----------------
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            if (empty) {
                item(key = "empty") {
                    EmptyState(
                        title = "工作区没有变更",
                        subtitle = "所有文件都已提交，或当前仓库还没有内容",
                    )
                }
            }

            changeGroup(
                group = GitChangeGroup.STAGED,
                entries = staged,
                actionLabel = "全部取消暂存",
                onAction = { scope.launch { runCatching { GitController.unstageAll() } } },
                expandedKey = expandedKey,
                onToggle = { key ->
                    expandedKey = if (expandedKey == key) "" else key
                },
                onPrimaryAction = { entry ->
                    scope.launch { runCatching { GitController.unstage(entry.path) } }
                },
                onRestore = { entry -> pendingRestore = RestoreRequest(GitChangeGroup.STAGED, entry) },
            )

            changeGroup(
                group = GitChangeGroup.UNSTAGED,
                entries = unstaged,
                actionLabel = "全部暂存",
                onAction = { scope.launch { runCatching { GitController.stageAll() } } },
                expandedKey = expandedKey,
                onToggle = { key ->
                    expandedKey = if (expandedKey == key) "" else key
                },
                onPrimaryAction = { entry ->
                    scope.launch { runCatching { GitController.stage(entry.path) } }
                },
                onRestore = { entry -> pendingRestore = RestoreRequest(GitChangeGroup.UNSTAGED, entry) },
            )

            changeGroup(
                group = GitChangeGroup.UNTRACKED,
                entries = untracked,
                actionLabel = "全部暂存",
                onAction = { scope.launch { runCatching { GitController.stageAll() } } },
                expandedKey = expandedKey,
                onToggle = { key ->
                    expandedKey = if (expandedKey == key) "" else key
                },
                onPrimaryAction = { entry ->
                    scope.launch { runCatching { GitController.stage(entry.path) } }
                },
                // 新增文件没有历史可回滚 → 「还原」= 删除该文件（二次确认后执行）
                onRestore = { entry -> pendingRestore = RestoreRequest(GitChangeGroup.UNTRACKED, entry) },
            )

            changeGroup(
                group = GitChangeGroup.CONFLICTED,
                entries = conflicted,
                actionLabel = null,
                onAction = {},
                expandedKey = expandedKey,
                onToggle = { key ->
                    expandedKey = if (expandedKey == key) "" else key
                },
                onPrimaryAction = null,
            )

            changeGroup(
                group = GitChangeGroup.IGNORED,
                entries = ignored,
                actionLabel = null,
                onAction = {},
                expandedKey = expandedKey,
                onToggle = { key ->
                    expandedKey = if (expandedKey == key) "" else key
                },
                onPrimaryAction = null,
            )
        }
    }

    // ---------------- 还原确认 ----------------
    pendingRestore?.let { req ->
        val added = req.entry.isNew
        AppAlertDialog(
            title = if (added) {
                "删除新增「${req.entry.path}」"
            } else {
                "还原「${req.entry.path}」"
            },
            message = when {
                // 新增文件：还原的语义就是删文件（未跟踪直接删；已暂存的新增连同暂存记录一起清）
                added && req.group == GitChangeGroup.STAGED ->
                    "该文件已暂存为新增但尚未提交。将取消暂存并删除该文件，删除后无法恢复。"

                added ->
                    "该文件是新增（未跟踪）文件。将从磁盘删除该文件，删除后无法恢复。"

                req.group == GitChangeGroup.STAGED ->
                    "将丢弃该文件的全部改动（含已暂存部分），恢复到最近一次提交的内容。此操作不可撤销。"

                else ->
                    "将丢弃该文件在工作区的改动，恢复为暂存区中的内容；已暂存的部分不受影响。"
            },
            confirmLabel = if (added) "删除" else "还原",
            destructive = true,
            onConfirm = {
                pendingRestore = null
                scope.launch {
                    runCatching {
                        when {
                            req.entry.isNew -> GitController.removeAdded(req.entry.path)
                            req.group == GitChangeGroup.STAGED ->
                                GitController.restoreToHead(req.entry.path)

                            else -> GitController.restoreWorktree(req.entry.path)
                        }
                    }
                }
            },
            onDismiss = { pendingRestore = null },
        )
    }
}

// ---------------------------------------------------------------------------
// 分组渲染
// ---------------------------------------------------------------------------

private fun LazyListScope.changeGroup(
    group: GitChangeGroup,
    entries: List<GitStatusEntry>,
    actionLabel: String?,
    onAction: () -> Unit,
    expandedKey: String,
    onToggle: (String) -> Unit,
    onPrimaryAction: ((GitStatusEntry) -> Unit)?,
    onRestore: ((GitStatusEntry) -> Unit)? = null,
) {
    if (entries.isEmpty()) return

    val action: (@Composable () -> Unit)? = if (actionLabel == null) {
        null
    } else {
        {
            TextButton(onClick = onAction, enabled = entries.isNotEmpty()) {
                Text(actionLabel)
            }
        }
    }

    item(key = "${group.name}#head") {
        SectionHeader(
            title = "${group.label}（${entries.size}）",
            modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp),
            action = action,
        )
    }

    items(entries, key = { "${group.name}#${it.path}" }) { entry ->
        StatusRow(
            entry = entry,
            group = group,
            expanded = expandedKey == "${group.name}#${entry.path}",
            onToggle = { onToggle("${group.name}#${entry.path}") },
            onPrimaryAction = onPrimaryAction,
            onRestore = onRestore,
        )
    }
}

// ---------------------------------------------------------------------------
// 单行状态 + 展开的 Diff
// ---------------------------------------------------------------------------

@Composable
private fun StatusRow(
    entry: GitStatusEntry,
    group: GitChangeGroup,
    expanded: Boolean,
    onToggle: () -> Unit,
    onPrimaryAction: ((GitStatusEntry) -> Unit)?,
    onRestore: ((GitStatusEntry) -> Unit)? = null,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusBadge(
                code = entry.code,
                kind = if (group == GitChangeGroup.STAGED) entry.index else entry.worktree,
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = entry.path,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (onPrimaryAction != null) {
                val staged = group == GitChangeGroup.STAGED
                IconButton(onClick = { onPrimaryAction(entry) }) {
                    Icon(
                        imageVector = if (staged) Icons.Default.Close else Icons.Default.Check,
                        contentDescription = if (staged) "取消暂存" else "暂存",
                        tint = if (staged) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    )
                }
            }
            if (onRestore != null) {
                // 新增文件没有历史可回滚 → 「还原」= 删文件，用删除图标 + 错误色明示破坏性
                val delete = entry.isNew
                IconButton(onClick = { onRestore(entry) }) {
                    Icon(
                        imageVector = if (delete) Icons.Default.Delete else Icons.Default.Restore,
                        contentDescription = if (delete) "还原（删除新增文件）" else "还原",
                        tint = if (delete) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }

        if (expanded) {
            DiffViewer(
                path = entry.path,
                staged = group == GitChangeGroup.STAGED,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
    }
}

/** 状态徽标（配色取自 AppPalette 的 diff 字段）。 */
@Composable
private fun StatusBadge(
    code: String,
    kind: GitStatusKind,
) {
    val palette = LocalAppPalette.current
    val container = when (kind) {
        GitStatusKind.NEW, GitStatusKind.UNTRACKED -> palette.diffAddedBackground.toComposeColor()
        GitStatusKind.DELETED -> palette.diffRemovedBackground.toComposeColor()
        GitStatusKind.CONFLICTED -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val content = when (kind) {
        GitStatusKind.NEW, GitStatusKind.UNTRACKED -> palette.diffAddedForeground.toComposeColor()
        GitStatusKind.DELETED -> palette.diffRemovedForeground.toComposeColor()
        GitStatusKind.CONFLICTED -> MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        color = container,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = code,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            color = content,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
        )
    }
}
