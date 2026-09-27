package com.mobilecoder.ide.feature.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.FileCopy
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mobilecoder.ide.core.common.ui.AppAlertDialog
import com.mobilecoder.ide.core.common.ui.EmptyState
import com.mobilecoder.ide.core.common.ui.SectionHeader
import com.mobilecoder.ide.core.storage.FileNode
import java.io.File

/** 文件树可见行（含缩进层级与展开状态）。 */
data class TreeRow(
    val node: FileNode,
    val depth: Int,
    val expanded: Boolean,
) {
    val path: String get() = node.file.path
    val isDirectory: Boolean get() = node.isDirectory
}

/**
 * 由 [com.mobilecoder.ide.core.storage.FileRepository.tree] 的扁平结果生成可见行：
 * 被收起目录的子树整段隐藏（构建产物目录在 tree() 内已过滤）。
 */
fun buildTreeRows(tree: List<FileNode>, collapsed: Set<String>): List<TreeRow> {
    if (tree.isEmpty()) return emptyList()
    val rows = ArrayList<TreeRow>(tree.size)
    val ancestors = ArrayList<String>()
    for (node in tree) {
        while (ancestors.size >= node.depth) ancestors.removeAt(ancestors.size - 1)
        if (ancestors.any { it in collapsed }) continue
        val expanded = node.isDirectory && node.file.path !in collapsed
        rows.add(TreeRow(node, node.depth, expanded))
        if (expanded) ancestors.add(node.file.path)
    }
    return rows
}

/** 拖拽落点解析：命中的行是目录则直接作为目标，是文件则取其所在目录。 */
private fun resolveDropTarget(pos: Offset, bounds: Map<String, Rect>): String? {
    for ((path, rect) in bounds) {
        if (rect.contains(pos)) {
            val file = File(path)
            return if (file.isDirectory) path else file.parentFile?.path
        }
    }
    return null
}

private data class DragState(val path: String, val origin: Offset, val pos: Offset)

private sealed interface NameDialog {
    val title: String
    val initial: String

    data class Create(val kind: Kind, val dirPath: String) : NameDialog {
        enum class Kind { FILE, DIRECTORY }
        override val title: String get() = if (kind == Kind.FILE) "新建文件" else "新建目录"
        override val initial: String get() = ""
    }

    data class Rename(val path: String, val current: String) : NameDialog {
        override val title: String get() = "重命名"
        override val initial: String get() = current
    }
}

/**
 * 项目文件树抽屉（PRD 2.2「项目树目录」）：
 * 点击打开 / 收起展开，长按弹出菜单（新建文件 / 新建目录 / 重命名 / 删除 / 移动），
 * 长按拖拽移动文件到目标目录。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileTreeDrawer(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tree by EditorController.tree.collectAsStateWithLifecycle()
    val collapsed by EditorController.collapsedDirs.collectAsStateWithLifecycle()
    val rootPath by EditorController.projectRoot.collectAsStateWithLifecycle()
    val rows = remember(tree, collapsed) { buildTreeRows(tree, collapsed) }

    var menuPath by remember { mutableStateOf<String?>(null) }
    var nameDialog by remember { mutableStateOf<NameDialog?>(null) }
    var pendingDelete by remember { mutableStateOf<FileNode?>(null) }
    var pendingMove by remember { mutableStateOf<FileNode?>(null) }
    var drag by remember { mutableStateOf<DragState?>(null) }
    val bounds = remember { mutableStateMapOf<String, Rect>() }

    val dropTargetPath = drag?.let { state ->
        rows.firstOrNull { row -> bounds[row.path]?.contains(state.pos) == true }
            ?.let { if (it.isDirectory) it.path else it.node.file.parentFile?.path }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.34f))
                .clickable(onClick = onDismiss),
        )

        Surface(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .width(290.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 10.dp,
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SectionHeader(
                        title = rootPath?.let { File(it).name } ?: "文件",
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { EditorController.refreshTree() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "关闭")
                    }
                }

                if (rows.isEmpty()) {
                    EmptyState(
                        title = "项目暂无文件",
                        subtitle = "可长按空白处新建文件",
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(rows, key = { it.path }) { row ->
                            TreeRowItem(
                                row = row,
                                bounds = bounds,
                                drag = drag,
                                dropTargetPath = dropTargetPath,
                                onTap = {
                                    if (row.isDirectory) {
                                        EditorController.toggleDirectory(row.path)
                                    } else {
                                        EditorController.openFile(row.node.file)
                                        onDismiss()
                                    }
                                },
                                onMenu = { menuPath = row.path },
                                onDragStart = { local ->
                                    val origin = bounds[row.path]?.topLeft ?: Offset.Zero
                                    drag = DragState(row.path, origin, origin + local)
                                },
                                onDrag = { local ->
                                    menuPath = null
                                    val origin = drag?.origin ?: return@TreeRowItem
                                    drag = drag?.copy(pos = origin + local)
                                },
                                onDragEnd = {
                                    val state = drag
                                    drag = null
                                    if (state != null) {
                                        val target = resolveDropTarget(state.pos, bounds)
                                        if (target != null &&
                                            target != state.path &&
                                            target != File(state.path).parentFile?.path
                                        ) {
                                            EditorController.move(state.path, target)
                                        }
                                    }
                                },
                                menuExpanded = menuPath == row.path,
                                onMenuDismiss = { menuPath = null },
                                onMenuItem = { action ->
                                    menuPath = null
                                    val node = row.node
                                    when (action) {
                                        TreeAction.NEW_FILE -> nameDialog =
                                            NameDialog.Create(
                                                NameDialog.Create.Kind.FILE,
                                                if (node.isDirectory) node.file.path
                                                else node.file.parentFile.path,
                                            )

                                        TreeAction.NEW_DIRECTORY -> nameDialog =
                                            NameDialog.Create(
                                                NameDialog.Create.Kind.DIRECTORY,
                                                if (node.isDirectory) node.file.path
                                                else node.file.parentFile.path,
                                            )

                                        TreeAction.RENAME -> nameDialog =
                                            NameDialog.Rename(node.file.path, node.name)

                                        TreeAction.DELETE -> pendingDelete = node
                                        TreeAction.MOVE -> pendingMove = node
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    nameDialog?.let { dialog ->
        NameInputDialog(
            title = dialog.title,
            initial = dialog.initial,
            onConfirm = { value ->
                nameDialog = null
                when (dialog) {
                    is NameDialog.Create ->
                        if (dialog.kind == NameDialog.Create.Kind.FILE) {
                            EditorController.createFile(dialog.dirPath, value)
                        } else {
                            EditorController.createDirectory(dialog.dirPath, value)
                        }

                    is NameDialog.Rename -> EditorController.rename(dialog.path, value)
                }
            },
            onDismiss = { nameDialog = null },
        )
    }

    pendingDelete?.let { node ->
        AppAlertDialog(
            title = "删除",
            message = "确认删除「${node.name}」？${if (node.isDirectory) "目录及其中所有文件" else "文件"}将被移除，不可恢复。",
            confirmLabel = "删除",
            destructive = true,
            onConfirm = {
                pendingDelete = null
                EditorController.delete(node.file.path)
            },
            onDismiss = { pendingDelete = null },
        )
    }

    pendingMove?.let { node ->
        MoveDialog(
            node = node,
            rows = rows,
            rootPath = rootPath,
            onPick = { dir ->
                pendingMove = null
                EditorController.move(node.file.path, dir.file.path)
            },
            onDismiss = { pendingMove = null },
        )
    }
}

private enum class TreeAction { NEW_FILE, NEW_DIRECTORY, RENAME, DELETE, MOVE }

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TreeRowItem(
    row: TreeRow,
    bounds: MutableMap<String, Rect>,
    drag: DragState?,
    dropTargetPath: String?,
    onTap: () -> Unit,
    onMenu: () -> Unit,
    onDragStart: (Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    menuExpanded: Boolean,
    onMenuDismiss: () -> Unit,
    onMenuItem: (TreeAction) -> Unit,
) {
    val isDragging = drag?.path == row.path
    val isTarget = !isDragging && dropTargetPath == row.path && row.isDirectory

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .onGloballyPositioned { coords -> bounds[row.path] = coords.boundsInRoot() }
            .pointerInput(row.path) {
                detectDragGesturesAfterLongPress(
                    onDragStart = onDragStart,
                    onDrag = { change, _ ->
                        onDrag(change.position)
                        change.consume()
                    },
                    onDragEnd = { onDragEnd() },
                    onDragCancel = { onDragEnd() },
                )
            }
            .combinedClickable(onClick = onTap, onLongClick = onMenu)
            .background(
                when {
                    isTarget -> MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                    isDragging -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                    else -> Color.Transparent
                },
            )
            .padding(start = (6 + row.depth * 14).dp, end = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (row.isDirectory) {
                Icon(
                    imageVector = if (row.expanded) {
                        Icons.Default.KeyboardArrowDown
                    } else {
                        Icons.Default.ChevronRight
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(18.dp),
                )
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 2.dp),
                )
            } else {
                Spacer(modifier = Modifier.width(18.dp))
                Icon(
                    imageVector = Icons.Default.FileCopy,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            Text(
                text = row.node.name,
                style = MaterialTheme.typography.bodyMedium,
                color = if (row.isDirectory) {
                    MaterialTheme.colorScheme.onBackground
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 4.dp),
            )
        }

        DropdownMenu(expanded = menuExpanded, onDismissRequest = onMenuDismiss) {
            DropdownMenuItem(
                text = { Text("新建文件") },
                leadingIcon = { Icon(Icons.Default.FileCopy, contentDescription = null) },
                onClick = { onMenuItem(TreeAction.NEW_FILE) },
            )
            DropdownMenuItem(
                text = { Text("新建目录") },
                leadingIcon = { Icon(Icons.Default.CreateNewFolder, contentDescription = null) },
                onClick = { onMenuItem(TreeAction.NEW_DIRECTORY) },
            )
            DropdownMenuItem(
                text = { Text("重命名") },
                leadingIcon = {
                    Icon(Icons.Default.DriveFileRenameOutline, contentDescription = null)
                },
                onClick = { onMenuItem(TreeAction.RENAME) },
            )
            DropdownMenuItem(
                text = { Text("移动到…") },
                leadingIcon = { Icon(Icons.Default.DriveFileMove, contentDescription = null) },
                onClick = { onMenuItem(TreeAction.MOVE) },
            )
            DropdownMenuItem(
                text = { Text("删除") },
                leadingIcon = {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                },
                onClick = { onMenuItem(TreeAction.DELETE) },
            )
        }
    }
}

/** 输入弹窗（新建文件 / 新建目录 / 重命名）。 */
@Composable
private fun NameInputDialog(
    title: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.imePadding(),
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text("名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(value.trim()) },
                enabled = value.isNotBlank(),
            ) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 移动目标目录选择。 */
@Composable
private fun MoveDialog(
    node: FileNode,
    rows: List<TreeRow>,
    rootPath: String?,
    onPick: (FileNode) -> Unit,
    onDismiss: () -> Unit,
) {
    val candidates = remember(rows, rootPath) {
        buildList {
            rootPath?.let { root ->
                add(FileNode(File(root).name, File(root), true, 0L, 0))
            }
            rows.filter { it.isDirectory && it.path != node.file.path }.forEach {
                add(it.node)
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("移动「${node.name}」到") },
        text = {
            if (candidates.isEmpty()) {
                Text("没有可选目录")
            } else {
                LazyColumn(modifier = Modifier.height(280.dp)) {
                    items(candidates, key = { it.file.path }) { dir ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(dir) }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Default.Folder,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = dir.name,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
