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
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.ui.text.font.FontWeight
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
 * 分组：已暂存 / 未暂存 / 未跟踪 / 已忽略；存在冲突时顶部展示可点击的
 * 冲突提醒横幅（点击进入冲突处理子页）；行点击以全屏对话框展开 Diff 查看器；
 * 顶部为提交区与工作区统计。
 * 「还原」需二次确认：未暂存组只丢弃工作区改动，已暂存组连同暂存一并回到 HEAD；
 * 未跟踪组提供「删除」（物理删除，同样二次确认——新增文件不在版本库中，无法还原）。
 */
@Composable
fun ChangesTab(
    modifier: Modifier = Modifier,
    onOpenConflicts: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val status by GitController.status.collectAsStateWithLifecycle()
    val stats by GitController.diffStats.collectAsStateWithLifecycle()
    val identity by GitController.identity.collectAsStateWithLifecycle()
    val busy by GitController.busy.collectAsStateWithLifecycle()

    var commitMessage by rememberSaveable { mutableStateOf("") }
    var expandedKey by rememberSaveable { mutableStateOf("") }
    var pendingRestore by remember { mutableStateOf<RestoreRequest?>(null) }
    var pendingDelete by remember { mutableStateOf<GitStatusEntry?>(null) }

    val staged = remember(status) { status.filter { it.inGroup(GitChangeGroup.STAGED) } }
    val unstaged = remember(status) { status.filter { it.inGroup(GitChangeGroup.UNSTAGED) } }
    val untracked = remember(status) { status.filter { it.inGroup(GitChangeGroup.UNTRACKED) } }
    val conflicted = remember(status) { status.filter { it.inGroup(GitChangeGroup.CONFLICTED) } }
    val ignored = remember(status) { status.filter { it.inGroup(GitChangeGroup.IGNORED) } }
    val empty = staged.isEmpty() && unstaged.isEmpty() && untracked.isEmpty() &&
        conflicted.isEmpty() && ignored.isEmpty()

    Column(modifier = modifier.fillMaxSize()) {
        // ---------------- 冲突提醒（点击进入冲突处理子页） ----------------
        if (conflicted.isNotEmpty()) {
            ConflictBanner(
                count = conflicted.size,
                onClick = onOpenConflicts,
            )
        }

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
                onDelete = { entry -> pendingDelete = entry },
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
        AppAlertDialog(
            title = "还原「${req.entry.path}」",
            message = if (req.group == GitChangeGroup.STAGED) {
                "将丢弃该文件的全部改动（含已暂存部分），恢复到最近一次提交的内容。此操作不可撤销。"
            } else {
                "将丢弃该文件在工作区的改动，恢复为暂存区中的内容；已暂存的部分不受影响。"
            },
            confirmLabel = "还原",
            destructive = true,
            onConfirm = {
                pendingRestore = null
                val path = req.entry.path
                val toHead = req.group == GitChangeGroup.STAGED
                scope.launch {
                    runCatching {
                        if (toHead) GitController.restoreToHead(path)
                        else GitController.restoreWorktree(path)
                    }
                }
            },
            onDismiss = { pendingRestore = null },
        )
    }

    // ---------------- 删除确认（未跟踪文件：物理删除，不可恢复） ----------------
    pendingDelete?.let { entry ->
        AppAlertDialog(
            title = "删除「${entry.path}」",
            message = if (entry.path.endsWith("/")) {
                "将从工作区永久删除该目录及其全部内容。新增目录未纳入版本库，删除后无法恢复。"
            } else {
                "将从工作区永久删除该文件。新增文件未纳入版本库，删除后无法恢复。"
            },
            confirmLabel = "删除",
            destructive = true,
            onConfirm = {
                pendingDelete = null
                scope.launch { runCatching { GitController.deleteUntracked(entry.path) } }
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

// ---------------------------------------------------------------------------
// 冲突提醒横幅（点击进入冲突处理子页）
// ---------------------------------------------------------------------------

@Composable
private fun ConflictBanner(
    count: Int,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                modifier = Modifier.padding(end = 10.dp),
            )
            Text(
                text = "有 $count 处合并冲突需要处理",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.Default.KeyboardArrowRight,
                contentDescription = null,
            )
        }
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
    onDelete: ((GitStatusEntry) -> Unit)? = null,
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
            onDelete = onDelete,
        )
    }
}

// ---------------------------------------------------------------------------
// 单行状态 + 全屏 Diff 对话框
// ---------------------------------------------------------------------------

@Composable
private fun StatusRow(
    entry: GitStatusEntry,
    group: GitChangeGroup,
    expanded: Boolean,
    onToggle: () -> Unit,
    onPrimaryAction: ((GitStatusEntry) -> Unit)?,
    onRestore: ((GitStatusEntry) -> Unit)? = null,
    onDelete: ((GitStatusEntry) -> Unit)? = null,
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
                IconButton(onClick = { onRestore(entry) }) {
                    Icon(
                        imageVector = Icons.Default.Restore,
                        contentDescription = "还原",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (onDelete != null) {
                IconButton(onClick = { onDelete(entry) }) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "删除",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (expanded) {
            DiffViewerDialog(
                path = entry.path,
                staged = group == GitChangeGroup.STAGED,
                onDismiss = { onToggle() },
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
