package com.mobilecoder.ide.feature.editor

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mobilecoder.ide.core.common.ui.EmptyState
import com.mobilecoder.ide.core.storage.CodeSnippet
import com.mobilecoder.ide.core.storage.SnippetStore

// ---------------------------------------------------------------------------
// 触发词候选面板（编辑区底部浮层）
// ---------------------------------------------------------------------------

/**
 * 输入触发词时弹出的候选列表（Issue 7）。
 *
 * 只展示 [state.items]；点按即插入（带跳位），面板随即由控制器收起。
 * 高度上限 240dp + 内部滚动，保证小屏不遮住整篇正文。
 */
@Composable
fun SnippetCandidatePanel(
    state: EditorController.SnippetPanelState,
    onInsert: (CodeSnippet) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!state.open || state.items.isEmpty()) return
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "片段「${state.word}」",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 32.dp)) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "关闭片段面板",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider()
            LazyColumn(modifier = Modifier.heightIn(max = 240.dp)) {
                items(state.items) { snippet ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onInsert(snippet) }
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Code,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 10.dp),
                        ) {
                            Text(
                                text = snippet.prefix,
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = snippet.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            text = SnippetStore.LANGUAGE_LABELS[snippet.language] ?: snippet.language,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (snippet.builtin) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                            // 行尾 tag 永不换行：名字再长也只省略，不把触发词 / 描述挤下去
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 跳位指示条（插入后依次落 $1 $2 … $0）
// ---------------------------------------------------------------------------

/**
 * 插入片段后的跳位条：显示「2/3」并提供「下一个 / 完成」。
 *
 * 移动端没有 Tab 键，跳位必须显式可点；继续打字时偏移由
 * [SnippetEngine.adjustStops] 跟随平移，指示条内容同步刷新。
 */
@Composable
fun SnippetStopBar(
    session: EditorController.SnippetSession?,
    onNext: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (session == null || session.total <= 1) return
    Surface(
        modifier = modifier.padding(horizontal = 8.dp, vertical = 6.dp),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer,
        shadowElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "片段跳位 ${session.index + 1}/${session.total}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            TextButton(onClick = onNext) {
                Text("下一个", style = MaterialTheme.typography.labelLarge)
            }
            TextButton(onClick = onDone) {
                Text("完成", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 片段管理面板（列表 + 增删改 + 导入导出）
// ---------------------------------------------------------------------------

/**
 * 片段管理浮层：按语言筛选、搜索触发词 / 说明，长按外的更多菜单里增删改，
 * 顶栏「导入 / 导出」走 SAF（`CreateDocument` / `OpenDocument`，JSON）。
 *
 * 插入动作只在有打开文件时可用（点条目即插入到光标处并关面板）。
 */
@Composable
fun SnippetManagerOverlay(
    onDismiss: () -> Unit,
    onInsert: (CodeSnippet) -> Unit,
    modifier: Modifier = Modifier,
) {
    val snippets by EditorController.snippets.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var languageFilter by remember { mutableStateOf(SnippetStore.LANG_ANY.take(0)) } // "" = 全部
    var editing by remember { mutableStateOf<CodeSnippet?>(null) }
    var createOpen by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<CodeSnippet?>(null) }
    var pendingDelete by remember { mutableStateOf<CodeSnippet?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> if (uri != null) EditorController.exportSnippets(uri) }
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) EditorController.importSnippets(uri) }

    val matched = remember(snippets, query, languageFilter) {
        snippets.filter { snippet ->
            (languageFilter.isEmpty() || snippet.language == languageFilter) &&
                (query.isBlank() ||
                    snippet.prefix.contains(query.trim(), ignoreCase = true) ||
                    snippet.description.contains(query.trim(), ignoreCase = true))
        }
    }

    Column(modifier = modifier.background(MaterialTheme.colorScheme.background)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text("搜索触发词或说明") },
                textStyle = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { createOpen = true }) {
                Icon(Icons.Default.Add, contentDescription = "新建片段")
            }
            IconButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "text/*")) }) {
                Icon(
                    imageVector = Icons.Default.FileDownload,
                    contentDescription = "导入片段",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { exportLauncher.launch("mobilecoder-snippets.json") }) {
                Icon(
                    imageVector = Icons.Default.FileUpload,
                    contentDescription = "导出片段",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "关闭",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // 语言筛选（「全部」在最前，随后是库内语言）
        LazyColumn(modifier = Modifier.weight(1f)) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // 8 个 chip 在手机宽度上放不下，横向滚动而不是被裁掉
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FilterChip(
                        selected = languageFilter.isEmpty(),
                        onClick = { languageFilter = "" },
                        label = { Text("全部") },
                    )
                    SnippetStore.LANGUAGES.forEach { lang ->
                        FilterChip(
                            selected = languageFilter == lang,
                            onClick = {
                                languageFilter = if (languageFilter == lang) "" else lang
                            },
                            label = { Text(SnippetStore.LANGUAGE_LABELS[lang] ?: lang) },
                        )
                    }
                }
            }
            if (matched.isEmpty()) {
                item {
                    EmptyState(
                        title = "没有匹配的片段",
                        subtitle = "点右上角 + 新建，或从 JSON 导入",
                    )
                }
            }
            items(matched, key = { it.id }) { snippet ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onInsert(snippet) }
                        .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = snippet.prefix,
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "  " + (SnippetStore.LANGUAGE_LABELS[snippet.language] ?: snippet.language) +
                                    if (snippet.builtin) " · 内置" else "",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            text = snippet.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Box {
                        IconButton(onClick = { menuFor = snippet }) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "片段操作",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        DropdownMenu(
                            expanded = menuFor?.id == snippet.id,
                            onDismissRequest = { menuFor = null },
                        ) {
                            DropdownMenuItem(
                                text = { Text("编辑") },
                                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                                onClick = {
                                    menuFor = null
                                    editing = snippet
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("删除") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                },
                                enabled = !snippet.builtin,
                                onClick = {
                                    menuFor = null
                                    pendingDelete = snippet
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    if (createOpen || editing != null) {
        SnippetEditDialog(
            initial = editing,
            onConfirm = { prefix, description, language, body ->
                createOpen = false
                val target = editing
                editing = null
                EditorController.saveSnippet(
                    id = target?.takeIf { !it.builtin }?.id,
                    prefix = prefix,
                    description = description,
                    language = language,
                    body = body,
                )
            },
            onDismiss = {
                createOpen = false
                editing = null
            },
        )
    }

    pendingDelete?.let { snippet ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除片段？") },
            text = { Text("「${snippet.prefix}」删除后不可恢复（内置片段不可删除）。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        EditorController.deleteSnippet(snippet.id)
                    },
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

// ---------------------------------------------------------------------------
// 片段编辑弹窗
// ---------------------------------------------------------------------------

/**
 * 新建 / 编辑自定义片段：触发词（字母数字下划线）、说明、语言、正文。
 * 正文支持 `$1`…`$9` 跳位、`$0` 最终光标、`\$` 字面美元符。
 */
@Composable
private fun SnippetEditDialog(
    initial: CodeSnippet?,
    onConfirm: (prefix: String, description: String, language: String, body: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var prefix by remember { mutableStateOf(initial?.prefix ?: "") }
    var description by remember { mutableStateOf(initial?.description ?: "") }
    var language by remember { mutableStateOf(initial?.language ?: SnippetStore.LANG_ANY) }
    var body by remember { mutableStateOf(initial?.body ?: "") }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 520.dp),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = if (initial == null) "新建片段" else "编辑片段",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                OutlinedTextField(
                    value = prefix,
                    onValueChange = { prefix = it },
                    label = { Text("触发词（如 fori）") },
                    singleLine = true,
                    isError = prefix.isNotBlank() && !prefix.all { it.isLetterOrDigit() || it == '_' },
                    supportingText = {
                        if (prefix.isNotBlank() && !prefix.all { it.isLetterOrDigit() || it == '_' }) {
                            Text("只能是字母 / 数字 / 下划线")
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("说明") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
                Row(
                    // 语言 chip 横向滚动，窄屏上不会把「通用」等靠后的选项裁掉
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    SnippetStore.LANGUAGES.forEach { lang ->
                        FilterChip(
                            selected = language == lang,
                            onClick = { language = lang },
                            label = { Text(SnippetStore.LANGUAGE_LABELS[lang] ?: lang) },
                        )
                    }
                }
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    label = { Text("正文（$1…$9 跳位，$0 最终光标，\\$ 转义）") },
                    minLines = 5,
                    maxLines = 10,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    TextButton(
                        enabled = prefix.isNotBlank() && body.isNotBlank() &&
                            prefix.all { it.isLetterOrDigit() || it == '_' },
                        onClick = { onConfirm(prefix, description, language, body) },
                    ) { Text("保存") }
                }
            }
        }
    }
}
