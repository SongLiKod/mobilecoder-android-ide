@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.mobilecoder.ide.feature.git

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mobilecoder.ide.core.common.ui.EmptyState
import java.io.File
import kotlinx.coroutines.launch

/**
 * Git 页面（PRD 2.5 全功能可视化 Git）。
 *
 * 布局：仓库信息行 → 分段 Tab → 对应内容（weight(1) 内部自滚动）。
 * 不自带 bottomBar（外层 app 已有全局底部导航）。
 */
@Composable
fun GitScreen(
    projectPath: String,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val repo by GitController.repo.collectAsStateWithLifecycle()
    val message by GitController.message.collectAsStateWithLifecycle()
    val busy by GitController.busy.collectAsStateWithLifecycle()
    val loading by GitController.loading.collectAsStateWithLifecycle()
    val conflicts by GitController.conflicts.collectAsStateWithLifecycle()
    val progress by GitController.progress.collectAsStateWithLifecycle()

    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showClone by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(projectPath) {
        runCatching { GitController.bind(projectPath) }
    }

    Column(modifier = modifier.fillMaxSize()) {
        RepoHeader(
            repo = repo,
            loading = loading,
            onRefresh = { scope.launch { runCatching { GitController.refresh() } } },
        )

        message?.let { banner ->
            MessageBanner(message = banner, onClose = { GitController.clearMessage() })
        }

        if (busy) {
            NetworkProgress(progress = progress)
        }

        if (repo.opened) {
            val titles = listOf("变更", "日志", "分支", "冲突", "标签", "远程", "设置")
            ScrollableTabRow(
                selectedTabIndex = tab,
                edgePadding = 8.dp,
                containerColor = MaterialTheme.colorScheme.surface,
            ) {
                titles.forEachIndexed { index, title ->
                    val label = if (index == TAB_CONFLICTS && conflicts.isNotEmpty()) {
                        "冲突 (${conflicts.size})"
                    } else {
                        title
                    }
                    Tab(
                        selected = tab == index,
                        onClick = { tab = index },
                        text = { Text(label, maxLines = 1) },
                    )
                }
            }

            when (tab) {
                TAB_CHANGES -> ChangesTab(
                    modifier = Modifier.weight(1f),
                )

                TAB_LOG -> LogTab(
                    modifier = Modifier.weight(1f),
                )

                TAB_BRANCHES -> BranchTab(
                    modifier = Modifier.weight(1f),
                    onConflicts = { tab = TAB_CONFLICTS },
                )

                TAB_CONFLICTS -> ConflictTab(
                    modifier = Modifier.weight(1f),
                )

                TAB_TAGS -> TagsTab(
                    modifier = Modifier.weight(1f),
                )

                TAB_REMOTES -> RemotesTab(
                    modifier = Modifier.weight(1f),
                )

                else -> SettingsTab(
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            NoRepoContent(
                modifier = Modifier.weight(1f),
                repo = repo,
                loading = loading,
                onInit = {
                    scope.launch {
                        runCatching { GitController.initRepository(repo.path) }
                    }
                },
                onClone = { showClone = true },
            )
        }
    }

    if (showClone) {
        CloneDialog(
            projectPath = projectPath,
            onDismiss = { showClone = false },
            onClone = { url, branch, target ->
                showClone = false
                scope.launch {
                    runCatching { GitController.clone(url, target, branch) }
                }
            },
        )
    }
}

/** Tab 索引。 */
private const val TAB_CHANGES = 0
private const val TAB_LOG = 1
private const val TAB_BRANCHES = 2
private const val TAB_CONFLICTS = 3
private const val TAB_TAGS = 4
private const val TAB_REMOTES = 5

// ---------------------------------------------------------------------------
// 顶部仓库信息行
// ---------------------------------------------------------------------------

@Composable
private fun RepoHeader(
    repo: GitRepoUiState,
    loading: Boolean,
    onRefresh: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Git",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                when {
                    repo.opened -> Text(
                        text = "已打开",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )

                    repo.isRepo -> Text(
                        text = "仓库不可用",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )

                    else -> Text(
                        text = "非 Git 仓库",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = File(repo.path.ifBlank { "-" }).name,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier
                    .padding(end = 8.dp)
                    .size(18.dp),
                strokeWidth = 2.dp,
            )
        }
        IconButton(onClick = onRefresh) {
            Icon(Icons.Default.Refresh, contentDescription = "刷新")
        }
    }
}

// ---------------------------------------------------------------------------
// 提示横幅
// ---------------------------------------------------------------------------

@Composable
private fun MessageBanner(
    message: GitMessage,
    onClose: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (message.isError) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        },
        contentColor = if (message.isError) {
            MaterialTheme.colorScheme.onErrorContainer
        } else {
            MaterialTheme.colorScheme.onSecondaryContainer
        },
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 6.dp),
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Close, contentDescription = "关闭提示")
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 网络进度
// ---------------------------------------------------------------------------

@Composable
private fun NetworkProgress(progress: GitProgress?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val fraction = progress?.fraction
        if (fraction != null) {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        if (progress != null && (progress.message.isNotBlank() || progress.stage.isNotBlank())) {
            Text(
                text = "${stageLabel(progress.stage)} ${progress.message}".trim(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 非仓库空状态 + 克隆弹窗
// ---------------------------------------------------------------------------

@Composable
private fun NoRepoContent(
    repo: GitRepoUiState,
    loading: Boolean,
    onInit: () -> Unit,
    onClone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(modifier = Modifier.height(16.dp))
        if (loading) {
            CircularProgressIndicator()
        } else {
            EmptyState(
                title = "当前目录不是 Git 仓库",
                subtitle = repo.path,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onInit) { Text("初始化仓库") }
                OutlinedButton(onClick = onClone) { Text("克隆仓库") }
            }
            Text(
                text = "初始化：在当前目录创建 .git，开始本地版本管理\n" +
                    "克隆：输入远程地址（HTTPS / SSH）拉取已有仓库",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** 从仓库地址推导默认目录名。 */
private fun repoNameOf(url: String, projectPath: String): String {
    val cleaned = url.trim().removeSuffix("/").removeSuffix(".git")
    val name = cleaned.substringAfterLast('/').substringAfterLast(':')
    val safe = name.filter { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }
    return if (safe.isBlank()) File(projectPath).name else safe
}

@Composable
private fun CloneDialog(
    projectPath: String,
    onDismiss: () -> Unit,
    onClone: (url: String, branch: String, target: String) -> Unit,
) {
    var url by rememberSaveable { mutableStateOf("") }
    var branch by rememberSaveable { mutableStateOf("") }
    var target by rememberSaveable { mutableStateOf("") }

    val defaultTarget = remember(url, projectPath) {
        File(projectPath, repoNameOf(url, projectPath)).path
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("克隆仓库") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("仓库地址") },
                    placeholder = { Text("https://github.com/user/repo.git") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = branch,
                    onValueChange = { branch = it },
                    label = { Text("分支（可选，默认远程默认分支）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = target,
                    onValueChange = { target = it },
                    label = { Text("目标目录（可选）") },
                    supportingText = { Text("留空则克隆到 $defaultTarget") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onClone(
                        url.trim(),
                        branch.trim(),
                        target.trim().ifBlank { defaultTarget },
                    )
                },
                enabled = url.isNotBlank(),
            ) { Text("克隆") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
