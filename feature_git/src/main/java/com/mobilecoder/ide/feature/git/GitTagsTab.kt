package com.mobilecoder.ide.feature.git

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
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
import com.mobilecoder.ide.core.common.ui.AppAlertDialog
import com.mobilecoder.ide.core.common.ui.EmptyState
import kotlinx.coroutines.launch

/**
 * 标签页（PRD 2.5：标签创建 / 删除，附注标签展示说明信息）。
 */
@Composable
fun TagsTab(
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val tags by GitController.tags.collectAsStateWithLifecycle()

    var showCreate by rememberSaveable { mutableStateOf(false) }
    var newName by rememberSaveable { mutableStateOf("") }
    var newMessage by rememberSaveable { mutableStateOf("") }
    var deleteTarget by rememberSaveable { mutableStateOf("") }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = { showCreate = true }) { Text("新建标签") }
            Text(
                text = "共 ${tags.size} 个标签 · 附注标签会在创建时生成说明",
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
            if (tags.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        title = "暂无标签",
                        subtitle = "在重要版本上创建标签，方便后续定位发布节点",
                    )
                }
            }

            items(tags, key = { it.name }) { tag ->
                TagRow(
                    tag = tag,
                    onDelete = { deleteTarget = tag.name },
                )
            }
        }
    }

    // ---------------- 新建标签 ----------------
    if (showCreate) {
        AlertDialog(
            onDismissRequest = { showCreate = false },
            modifier = Modifier.imePadding(),
            title = { Text("新建标签") },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("标签名") },
                        placeholder = { Text("v1.0.0") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = newMessage,
                        onValueChange = { newMessage = it },
                        label = { Text("说明（可选）") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 4,
                        supportingText = {
                            Text("填写后创建附注标签；留空则创建轻量标签")
                        },
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = newName.trim()
                        val message = newMessage.trim()
                        showCreate = false
                        newName = ""
                        newMessage = ""
                        scope.launch {
                            runCatching { GitController.createTag(name, message) }
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

    // ---------------- 删除确认 ----------------
    if (deleteTarget.isNotBlank()) {
        AppAlertDialog(
            title = "删除标签",
            message = "确认删除标签「$deleteTarget」？只会移除标签引用，相关提交不受影响。",
            confirmLabel = "删除",
            destructive = true,
            onConfirm = {
                val target = deleteTarget
                deleteTarget = ""
                scope.launch { runCatching { GitController.deleteTag(target) } }
            },
            onDismiss = { deleteTarget = "" },
        )
    }
}

/** 单个标签行。 */
@Composable
private fun TagRow(
    tag: GitTag,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = MaterialTheme.shapes.small,
            ) {
                Text(
                    text = tag.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }

            Surface(
                color = if (tag.annotated) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                shape = MaterialTheme.shapes.small,
            ) {
                Text(
                    text = if (tag.annotated) "附注" else "轻量",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (tag.annotated) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                )
            }

            Text(
                text = tag.oid.take(10),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )

            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "删除标签",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (tag.message.isNotBlank()) {
            Text(
                text = tag.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 4.dp, end = 16.dp, bottom = 6.dp),
            )
        }
    }
}
