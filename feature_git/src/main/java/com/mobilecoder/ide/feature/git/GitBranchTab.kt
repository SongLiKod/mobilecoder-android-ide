package com.mobilecoder.ide.feature.git

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mobilecoder.ide.core.common.ui.AppAlertDialog
import com.mobilecoder.ide.core.common.ui.EmptyState
import com.mobilecoder.ide.core.common.ui.StatChip
import kotlinx.coroutines.launch

/**
 * 分支页（PRD 2.5：分支增删切换 + 合并 + 冲突入口 + 中止合并）。
 */
@Composable
fun BranchTab(
    modifier: Modifier = Modifier,
    onConflicts: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val branches by GitController.branches.collectAsStateWithLifecycle()
    val head by GitController.head.collectAsStateWithLifecycle()
    val merging by GitController.merging.collectAsStateWithLifecycle()

    var showCreate by rememberSaveable { mutableStateOf(false) }
    var newName by rememberSaveable { mutableStateOf("") }
    /** 新建分支起始点："" = 从 HEAD 创建，否则为所选本地分支名。 */
    var newSource by rememberSaveable { mutableStateOf("") }
    var mergeTarget by rememberSaveable { mutableStateOf("") }
    var deleteTarget by rememberSaveable { mutableStateOf("") }
    var checkoutTarget by rememberSaveable { mutableStateOf("") }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = { showCreate = true }) { Text("新建分支") }
            if (merging) {
                OutlinedButton(
                    onClick = {
                        scope.launch { runCatching { GitController.mergeAbort() } }
                    },
                ) { Text("中止合并") }
            }
            Text(
                text = run {
                    val localCount = branches.count { !it.isRemote }
                    val remoteCount = branches.size - localCount
                    if (remoteCount == 0) "共 $localCount 个本地分支"
                    else "共 $localCount 个本地分支 · $remoteCount 个远程分支"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            item(key = "head") {
                HeadCard(head = head, merging = merging)
            }

            if (branches.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        title = "暂无本地分支",
                        subtitle = "完成首次提交后会自动创建",
                    )
                }
            }

            items(branches, key = { it.name }) { branch ->
                BranchRow(
                    branch = branch,
                    onCheckout = { checkoutTarget = branch.name },
                    onMerge = { mergeTarget = branch.name },
                    onDelete = { deleteTarget = branch.name },
                )
            }
        }
    }

    // ---------------- 新建分支 ----------------
    if (showCreate) {
        AlertDialog(
            onDismissRequest = { showCreate = false },
            modifier = Modifier.imePadding(),
            title = { Text("新建分支") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("分支名") },
                        placeholder = { Text("feature/xxx") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // 起始点：默认从 HEAD 创建；可改从任一本地分支创建（原生层接受任意
                    // revspec，这里只暴露本地分支，覆盖「从哪个分支创建」的主场景）。
                    Text(
                        text = "从哪个分支创建",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = newSource.isEmpty(),
                            onClick = { newSource = "" },
                            label = { Text("当前 HEAD") },
                        )
                        branches.filter { !it.isRemote }.forEach { branch ->
                            FilterChip(
                                selected = newSource == branch.name,
                                onClick = { newSource = branch.name },
                                label = { Text(branchSourceLabel(branch)) },
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = newName.trim()
                        val start = newSource.trim()
                        showCreate = false
                        newName = ""
                        newSource = ""
                        scope.launch {
                            runCatching { GitController.createBranch(name, start.ifBlank { null }) }
                        }
                    },
                    enabled = newName.isNotBlank(),
                ) { Text("创建") }
            },
            dismissButton = {
                TextButton(onClick = { showCreate = false }) { Text("取消") }
            },
        )
    }

    // ---------------- 切换分支确认（行点击 / 「切换」按钮共用一条路径） ----------------
    if (checkoutTarget.isNotBlank()) {
        val tracking = GitController.remoteTrackingName(checkoutTarget)
        AppAlertDialog(
            title = "切换分支",
            message = if (tracking != null) {
                val (remote, localName) = tracking
                "检出远程分支「$remote/$localName」：本地分支「$localName」不存在时" +
                    "会自动创建并关联上游；未提交改动会跟随工作区。"
            } else {
                "切换到分支「$checkoutTarget」？未提交改动会跟随工作区。"
            },
            confirmLabel = "切换",
            onConfirm = {
                val target = checkoutTarget
                checkoutTarget = ""
                scope.launch { runCatching { GitController.checkoutBranch(target) } }
            },
            onDismiss = { checkoutTarget = "" },
        )
    }

    // ---------------- 合并确认 ----------------
    if (mergeTarget.isNotBlank()) {
        AppAlertDialog(
            title = "合并分支",
            message = "把「$mergeTarget」合并到当前分支「${head?.branch ?: "HEAD"}」？\n" +
                "若产生冲突会自动打开「冲突处理」页。",
            confirmLabel = "合并",
            onConfirm = {
                val target = mergeTarget
                mergeTarget = ""
                scope.launch {
                    val rc = runCatching { GitController.merge(target) }.getOrDefault(-1)
                    if (rc == 1) onConflicts()
                }
            },
            onDismiss = { mergeTarget = "" },
        )
    }

    // ---------------- 删除确认 ----------------
    if (deleteTarget.isNotBlank()) {
        AppAlertDialog(
            title = "删除分支",
            message = "确认删除分支「$deleteTarget」？分支引用会被移除（已合并的提交仍保留）。",
            confirmLabel = "删除",
            destructive = true,
            onConfirm = {
                val target = deleteTarget
                deleteTarget = ""
                scope.launch { runCatching { GitController.deleteBranch(target) } }
            },
            onDismiss = { deleteTarget = "" },
        )
    }
}

/** 当前 HEAD 信息卡片。 */
@Composable
private fun HeadCard(head: GitHead?, merging: Boolean) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = headTitle(head),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    // 游离态用错误色标出：此时没有「当前分支」，所有分支行都会显示「切换」。
                    color = if (head?.detached == true) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                if (head != null) {
                    // 游离 HEAD 不在任何分支上，C 层也不会计算领先 / 落后（固定 0），不显示。
                    if (!head.detached) {
                        StatChip(label = "领先上游", value = "${head.ahead}", emphasize = head.ahead > 0)
                        StatChip(label = "落后上游", value = "${head.behind}", emphasize = head.behind > 0)
                    }
                    if (head.unborn) {
                        StatChip(label = "状态", value = "尚无提交", emphasize = true)
                    }
                }
                if (merging) {
                    StatChip(label = "状态", value = "合并中", emphasize = true)
                }
            }

            Text(
                text = if (head?.detached == true) {
                    "当前处于分离 HEAD（不在任何分支上），点下方任一分支可回到该分支"
                } else {
                    "当前分支不可删除；点分支行可切换分支，点右侧 ⋮ 可合并 / 删除分支"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

/** HeadCard 标题：附着 = 分支名；游离 = 分离 HEAD + 短提交号；未出生 / 无仓库给提示文案。 */
internal fun headTitle(head: GitHead?): String = when {
    head == null -> "（无 HEAD）"
    head.detached -> "分离 HEAD · " + head.branch.ifBlank { "未知提交" }
    head.branch.isBlank() -> "（HEAD 尚未指向分支）"
    else -> head.branch
}

/** 新建分支起始点选项文案：当前分支追加「·当前」标记。 */
internal fun branchSourceLabel(branch: GitBranch): String =
    if (branch.isHead) "${branch.name} · 当前" else branch.name

/** 单个分支行：行点击 = 切换分支（带确认），右侧 ⋮ 菜单 = 合并 / 删除。 */
@Composable
private fun BranchRow(
    branch: GitBranch,
    onCheckout: () -> Unit,
    onMerge: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !branch.isHead, onClick = onCheckout)
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Surface(
                color = if (branch.isHead) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                shape = MaterialTheme.shapes.small,
            ) {
                Text(
                    text = branch.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (branch.isHead) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (branch.isHead) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }

            if (branch.isHead) {
                Text(
                    text = "当前",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Spacer2()

            if (branch.upstream.isNotBlank()) {
                Text(
                    text = "↑${branch.ahead} ↓${branch.behind} · ${branch.upstream}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            } else if (!branch.isRemote) {
                // 远程跟踪分支本身就是上游，不提示「无上游」（对齐 git branch -a 的行为）
                Text(
                    text = "无上游分支",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }

            if (!branch.isHead) {
                TextButton(onClick = onCheckout) { Text("切换") }
                Box {
                    IconButton(
                        onClick = { menuOpen = true },
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "更多操作",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("合并到当前分支") },
                            onClick = {
                                menuOpen = false
                                onMerge()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("删除分支") },
                            onClick = {
                                menuOpen = false
                                onDelete()
                            },
                        )
                    }
                }
            }
        }
    }
}

/** 行内小占位。 */
@Composable
private fun Spacer2() {
    androidx.compose.foundation.layout.Spacer(
        modifier = Modifier.width(4.dp),
    )
}
