package com.mobilecoder.ide.feature.git

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mobilecoder.ide.core.common.ui.AppAlertDialog
import com.mobilecoder.ide.core.common.ui.EmptyState
import com.mobilecoder.ide.core.common.ui.StatChip
import kotlinx.coroutines.launch

/**
 * 远程页（PRD 2.5：远程仓库管理 + 拉取 / 推送 / 获取 + 进度与日志展示）。
 *
 * 网络操作的凭据注入与清理在 [GitController] 内完成（HTTPS 账号口令 / SSH 内存私钥）。
 */
@Composable
fun RemotesTab(
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val remotes by GitController.remotes.collectAsStateWithLifecycle()
    val head by GitController.head.collectAsStateWithLifecycle()
    val busy by GitController.busy.collectAsStateWithLifecycle()
    val progress by GitController.progress.collectAsStateWithLifecycle()
    val progressLog by GitController.progressLog.collectAsStateWithLifecycle()

    var selected by rememberSaveable { mutableStateOf("") }
    var showAdd by rememberSaveable { mutableStateOf(false) }
    var addName by rememberSaveable { mutableStateOf("") }
    var addUrl by rememberSaveable { mutableStateOf("") }
    var editTarget by rememberSaveable { mutableStateOf("") }
    var editUrl by rememberSaveable { mutableStateOf("") }
    var deleteTarget by rememberSaveable { mutableStateOf("") }

    // 选中的远程必须仍然存在
    LaunchedEffect(remotes) {
        if (remotes.none { it.name == selected }) {
            selected = remotes.firstOrNull()?.name ?: ""
        }
    }

    val branch = head?.branch.orEmpty()

    Column(modifier = modifier.fillMaxSize()) {
        // ---------------- 远程选择 + 网络操作 ----------------
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (remotes.isEmpty()) {
                Text(
                    text = "尚未配置远程仓库，先「添加远程」再进行拉取 / 推送。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    remotes.forEach { remote ->
                        RemoteChip(
                            name = remote.name,
                            selected = remote.name == selected,
                            onClick = { selected = remote.name },
                        )
                    }
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                OutlinedButton(
                    onClick = {
                        scope.launch { runCatching { GitController.fetch(selected) } }
                    },
                    enabled = selected.isNotBlank() && !busy,
                ) { Text("获取更新") }

                Button(
                    onClick = {
                        scope.launch { runCatching { GitController.pull(selected) } }
                    },
                    enabled = selected.isNotBlank() && !busy,
                ) { Text("拉取并合并") }

                OutlinedButton(
                    onClick = {
                        scope.launch { runCatching { GitController.push(selected, branch) } }
                    },
                    enabled = selected.isNotBlank() && branch.isNotBlank() && !busy,
                ) { Text("推送") }

                Text(
                    text = if (branch.isBlank()) "（当前分支未知）" else "分支：$branch",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (head != null && branch.isNotBlank()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatChip(label = "领先上游", value = "${head?.ahead ?: 0}")
                    StatChip(label = "落后上游", value = "${head?.behind ?: 0}")
                }
            }
        }

        // ---------------- 网络进度日志 ----------------
        if (progressLog.isNotEmpty()) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (busy) {
                        val fraction = progress?.fraction
                        if (fraction != null) {
                            LinearProgressIndicator(
                                progress = { fraction },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                    Text(
                        text = progressLog.takeLast(40).joinToString("\n"),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 140.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }
            }
        }

        // ---------------- 远程列表 ----------------
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp),
        ) {
            item(key = "add") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(onClick = { showAdd = true }) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 4.dp),
                        )
                        Text("添加远程")
                    }
                    Text(
                        text = "共 ${remotes.size} 个远程",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (remotes.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        title = "暂无远程仓库",
                        subtitle = "添加 origin 等远程地址后即可拉取与推送",
                    )
                }
            }

            items(remotes, key = { it.name }) { remote ->
                RemoteRow(
                    remote = remote,
                    selected = remote.name == selected,
                    onSelect = { selected = remote.name },
                    onEditUrl = {
                        editTarget = remote.name
                        editUrl = remote.url
                    },
                    onDelete = { deleteTarget = remote.name },
                )
            }
        }
    }

    // ---------------- 添加远程 ----------------
    if (showAdd) {
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("添加远程仓库") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = addName,
                        onValueChange = { addName = it },
                        label = { Text("名称") },
                        placeholder = { Text("origin") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = addUrl,
                        onValueChange = { addUrl = it },
                        label = { Text("地址") },
                        placeholder = { Text("https://github.com/user/repo.git") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = addName.trim()
                        val url = addUrl.trim()
                        showAdd = false
                        addName = ""
                        addUrl = ""
                        scope.launch { runCatching { GitController.addRemote(name, url) } }
                    },
                    enabled = addName.isNotBlank() && addUrl.isNotBlank(),
                ) { Text("添加") }
            },
            dismissButton = {
                TextButton(onClick = { showAdd = false }) { Text("取消") }
            },
        )
    }

    // ---------------- 修改地址 ----------------
    if (editTarget.isNotBlank()) {
        AlertDialog(
            onDismissRequest = { editTarget = "" },
            title = { Text("修改远程地址") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "远程名称：$editTarget",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = editUrl,
                        onValueChange = { editUrl = it },
                        label = { Text("地址") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = editTarget
                        val url = editUrl
                        editTarget = ""
                        scope.launch { runCatching { GitController.setRemoteUrl(name, url) } }
                    },
                    enabled = editUrl.isNotBlank(),
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { editTarget = "" }) { Text("取消") }
            },
        )
    }

    // ---------------- 删除确认 ----------------
    if (deleteTarget.isNotBlank()) {
        AppAlertDialog(
            title = "删除远程仓库",
            message = "确认删除远程「$deleteTarget」？只会移除本地配置，不影响远端数据。",
            confirmLabel = "删除",
            destructive = true,
            onConfirm = {
                val target = deleteTarget
                deleteTarget = ""
                scope.launch { runCatching { GitController.removeRemote(target) } }
            },
            onDismiss = { deleteTarget = "" },
        )
    }
}

/** 远程选择胶囊。 */
@Composable
private fun RemoteChip(
    name: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/** 单个远程仓库行。 */
@Composable
private fun RemoteRow(
    remote: GitRemote,
    selected: Boolean,
    onSelect: () -> Unit,
    onEditUrl: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Surface(
            color = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            shape = MaterialTheme.shapes.small,
        ) {
            Text(
                text = remote.name,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }

        if (selected) {
            Text(
                text = "当前",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        Text(
            text = remote.url,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        IconButton(onClick = onEditUrl) {
            Icon(
                imageVector = Icons.Default.Edit,
                contentDescription = "修改地址",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = "删除远程",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
