package com.mobilecoder.ide.feature.git

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import com.mobilecoder.ide.core.common.ui.EmptyState
import com.mobilecoder.ide.core.common.ui.StatChip
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 冲突页（PRD 2.5：三方对照 + 采用某一侧 + 手动编辑 + 完成/中止合并）。
 *
 * 三方内容来自 [GitController.conflictSide]（native 层 git_index_conflict_get），
 * side: 1=祖先 2=我方 3=对方；手动编辑读取工作区文件后经
 * [GitController.resolveText] 写回并标记为已解决。
 */
@Composable
fun ConflictTab(
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val conflicts by GitController.conflicts.collectAsStateWithLifecycle()
    val merging by GitController.merging.collectAsStateWithLifecycle()

    var expandedPath by rememberSaveable { mutableStateOf("") }
    var editingPath by rememberSaveable { mutableStateOf("") }

    // 默认展开第一个冲突；解决后自动收敛到下一个
    LaunchedEffect(conflicts) {
        if (conflicts.none { it.path == expandedPath }) {
            expandedPath = conflicts.firstOrNull()?.path ?: ""
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // ---------------- 合并状态条 ----------------
        val blocked = conflicts.isNotEmpty()
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = if (blocked) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            },
            contentColor = if (blocked) {
                MaterialTheme.colorScheme.onErrorContainer
            } else {
                MaterialTheme.colorScheme.onSecondaryContainer
            },
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = when {
                        blocked -> "有 ${conflicts.size} 个文件存在冲突"
                        merging -> "合并进行中，等待确认"
                        else -> "当前没有冲突"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "逐个文件对照「祖先 / 我方 / 对方」内容，点「采用」直接选定，" +
                        "或点「手动编辑」拼出最终结果。",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (merging) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(
                            onClick = {
                                scope.launch { runCatching { GitController.mergeAbort() } }
                            },
                        ) { Text("中止合并") }
                        Button(
                            onClick = {
                                scope.launch { runCatching { GitController.finishMerge() } }
                            },
                            enabled = !blocked,
                        ) { Text("完成合并") }
                    }
                }
            }
        }

        // ---------------- 冲突列表 ----------------
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            if (conflicts.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        title = "没有待处理的冲突",
                        subtitle = "合并 / 拉取产生冲突时会自动出现在这里",
                    )
                }
            }

            items(conflicts, key = { it.path }) { conflict ->
                ConflictItem(
                    conflict = conflict,
                    expanded = expandedPath == conflict.path,
                    onToggle = {
                        expandedPath = if (expandedPath == conflict.path) "" else conflict.path
                    },
                    onApplySide = { side ->
                        scope.launch {
                            runCatching { GitController.resolveSide(conflict.path, side) }
                        }
                    },
                    onEdit = { editingPath = conflict.path },
                )
            }
        }
    }

    if (editingPath.isNotBlank()) {
        EditConflictDialog(
            path = editingPath,
            onDismiss = { editingPath = "" },
            onSave = { newText ->
                val target = editingPath
                editingPath = ""
                scope.launch { runCatching { GitController.resolveText(target, newText) } }
            },
        )
    }
}

// ---------------------------------------------------------------------------
// 单个冲突条目 + 三方内容
// ---------------------------------------------------------------------------

@Composable
private fun ConflictItem(
    conflict: GitConflict,
    expanded: Boolean,
    onToggle: () -> Unit,
    onApplySide: (Int) -> Unit,
    onEdit: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = MaterialTheme.shapes.small,
            ) {
                Text(
                    text = "冲突",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                )
            }
            Text(
                text = conflict.path,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (expanded) "收起" else "展开",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        if (expanded) {
            ConflictSides(
                path = conflict.path,
                ancestorOid = conflict.ancestorOid,
                oursOid = conflict.oursOid,
                theirsOid = conflict.theirsOid,
                onApplySide = onApplySide,
                onEdit = onEdit,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/** 三方内容（我方 / 对方 / 祖先）+ 采用按钮。 */
@Composable
private fun ConflictSides(
    path: String,
    ancestorOid: String,
    oursOid: String,
    theirsOid: String,
    onApplySide: (Int) -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sides by produceState(initialValue = emptyList<ConflictSide>(), key1 = path) {
        value = listOf(2, 3, 1).mapNotNull { side ->
            runCatching { GitController.conflictSide(path, side) }.getOrNull()
        }
    }
    val oidOf = mapOf(
        1 to ancestorOid.take(8),
        2 to oursOid.take(8),
        3 to theirsOid.take(8),
    )
    val binary = sides.any { it.binary }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (sides.isEmpty()) {
                Text(
                    text = "正在读取三方内容…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (binary) {
                StatChip(label = "类型", value = "二进制", emphasize = true)
            }
            Text(
                text = "祖先 ${oidOf[1]} · 我方 ${oidOf[2]} · 对方 ${oidOf[3]}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(onClick = onEdit, enabled = !binary && sides.isNotEmpty()) {
                Text("手动编辑")
            }
        }

        if (binary) {
            Text(
                text = "该文件包含二进制内容，无法逐行编辑；请直接采用其中一侧。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        sides.forEach { side ->
            SideCard(
                side = side,
                oid = oidOf[side.side].orEmpty(),
                onApply = { onApplySide(side.side) },
            )
        }
    }
}

@Composable
private fun SideCard(
    side: ConflictSide,
    oid: String,
    onApply: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = side.label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (oid.isNotBlank()) {
                    Text(
                        text = oid,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = if (side.binary) "${side.bytes} B" else "${side.text.length} 字",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                TextButton(onClick = onApply) { Text("采用") }
            }

            when {
                side.binary -> Text(
                    text = "二进制内容不可预览",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                side.text.isBlank() -> Text(
                    text = "（该侧为空文件）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                else -> Text(
                    text = side.text,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 190.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 手动编辑弹窗
// ---------------------------------------------------------------------------

/** 工作区冲突文件的读取结果。 */
private data class EditFile(val text: String, val binary: Boolean, val missing: Boolean)

@Composable
private fun EditConflictDialog(
    path: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    val repo by GitController.repo.collectAsStateWithLifecycle()
    var text by rememberSaveable(path) { mutableStateOf("") }
    var loaded by rememberSaveable(path) { mutableStateOf(false) }

    val file by produceState(initialValue = null as EditFile?, key1 = path) {
        value = withContext(Dispatchers.IO) {
            val base = repo.workdir.ifBlank { repo.path }
            try {
                val bytes = File(base, path).readBytes()
                val probe = if (bytes.size > 4096) bytes.copyOf(4096) else bytes
                val binary = probe.any { it == 0.toByte() }
                EditFile(
                    text = if (binary) "" else String(bytes, Charsets.UTF_8),
                    binary = binary,
                    missing = false,
                )
            } catch (t: Throwable) {
                EditFile(text = "", binary = false, missing = true)
            }
        }
    }

    LaunchedEffect(file) {
        val f = file ?: return@LaunchedEffect
        if (!loaded && !f.missing && !f.binary) {
            text = f.text
            loaded = true
        }
    }

    val info = file
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("手动解决冲突") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = path,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                when {
                    info == null -> Text(
                        text = "正在读取文件内容…",
                        style = MaterialTheme.typography.bodySmall,
                    )

                    info.missing -> Text(
                        text = "无法读取该文件，请改为直接采用其中一侧。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )

                    info.binary -> Text(
                        text = "二进制文件不可编辑，请采用其中一侧。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )

                    else -> {
                        OutlinedTextField(
                            value = text,
                            onValueChange = { text = it },
                            label = { Text("最终内容（保存后标记为已解决）") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 8,
                            maxLines = 16,
                        )
                        Text(
                            text = "提示：若内容里还有 <<<<<<< / ======= / >>>>>>> 冲突标记，" +
                                "请手工删除后再保存。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(text) },
                enabled = info != null && !info.missing && !info.binary && text.isNotBlank(),
            ) { Text("保存并标记已解决") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
