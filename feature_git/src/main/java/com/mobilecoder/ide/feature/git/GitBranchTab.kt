package com.mobilecoder.ide.feature.git

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
    var mergeTarget by rememberSaveable { mutableStateOf("") }
    var deleteTarget by rememberSaveable { mutableStateOf("") }

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
                text = "共 ${branches.size} 个本地分支",
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
                    onCheckout = {
                        scope.launch {
                            runCatching { GitController.checkoutBranch(branch.name) }
                        }
                    },
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
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("分支名") },
                    placeholder = { Text("feature/xxx") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = newName.trim()
                        showCreate = false
                        newName = ""
                        scope.launch { runCatching { GitController.createBranch(name) } }
                    },
                    enabled = newName.isNotBlank(),
                ) { Text("创建") }
            },
            dismissButton = {
                TextButton(onClick = { showCreate = false }) { Text("取消") }
            },
        )
    }

    // ---------------- 合并确认 ----------------
    if (mergeTarget.isNotBlank()) {
        AppAlertDialog(
            title = "合并分支",
            message = "把「$mergeTarget」合并到当前分支「${head?.branch ?: "HEAD"}」？\n" +
                "若产生冲突会自动跳转到「冲突」页处理。",
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
                    text = head?.branch?.ifBlank { "（HEAD 尚未指向分支）" } ?: "（无 HEAD）",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (head?.detached == true) {
                    Text(
                        text = "分离 HEAD",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                if (head != null) {
                    StatChip(label = "领先上游", value = "${head.ahead}", emphasize = head.ahead > 0)
                    StatChip(label = "落后上游", value = "${head.behind}", emphasize = head.behind > 0)
                    if (head.unborn) {
                        StatChip(label = "状态", value = "尚无提交", emphasize = true)
                    }
                }
                if (merging) {
                    StatChip(label = "状态", value = "合并中", emphasize = true)
                }
            }

            Text(
                text = "当前分支不可删除；点右侧按钮可切换 / 合并分支",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

/** 单个分支行。 */
@Composable
private fun BranchRow(
    branch: GitBranch,
    onCheckout: () -> Unit,
    onMerge: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !branch.isHead, onClick = onMerge)
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
            } else {
                Text(
                    text = "无上游分支",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
            }

            if (!branch.isHead) {
                TextButton(onClick = onCheckout) { Text("切换") }
                TextButton(onClick = onMerge) { Text("合并") }
                IconButton(onClick = onDelete, enabled = !branch.isHead) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "删除分支",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
