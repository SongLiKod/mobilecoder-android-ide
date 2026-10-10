package com.mobilecoder.ide.feature.editor

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.FileCopy
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mobilecoder.ide.core.common.apk.ApkInstaller
import com.mobilecoder.ide.core.common.ui.AppAlertDialog
import com.mobilecoder.ide.core.common.ui.EmptyState
import com.mobilecoder.ide.core.common.ui.SectionHeader
import com.mobilecoder.ide.core.storage.FileNode
import com.mobilecoder.ide.core.storage.FileRepository
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    // 当前分支上的祖先目录链（收起的目录也入栈，否则其子树无从判隐藏），
    // 按 (depth, path) 记录，离开子树时弹出 depth >= 当前节点 的项。
    val ancestors = ArrayList<Pair<Int, String>>()
    for (node in tree) {
        while (ancestors.isNotEmpty() && ancestors.last().first >= node.depth) {
            ancestors.removeAt(ancestors.size - 1)
        }
        if (ancestors.any { it.second in collapsed }) continue
        val expanded = node.isDirectory && node.file.path !in collapsed
        rows.add(TreeRow(node, node.depth, expanded))
        if (node.isDirectory) ancestors.add(node.depth to node.file.path)
    }
    return rows
}

/**
 * 拖拽落点解析：命中的行是目录则直接作为目标，是文件则取其所在目录。
 *
 * 只按**当前行序列** [rows] 匹配（与拖拽高亮同源）：[bounds] 是坐标簿，
 * 行离开组合即被清掉（TreeRowItem 的 DisposableEffect），避免陈旧 rect 让
 * 拖拽按历史坐标把文件移动到看不见的目录。
 *
 * internal 仅为单测可见（DropTargetResolveTest 锁「陈旧坐标簿不参与解析」）。
 */
internal fun resolveDropTarget(pos: Offset, bounds: Map<String, Rect>, rows: List<TreeRow>): String? {
    val row = rows.firstOrNull { r -> bounds[r.path]?.contains(pos) == true } ?: return null
    return if (row.isDirectory) row.path else row.node.file.parentFile?.path
}

/**
 * 拖拽状态：[origin] 行左上角（root 坐标，用于把行内指针位置换算成 root 坐标），
 * [pos] 当前指针位置，[startPos] 拖臂时刻的指针位置——两者相等即「原地长按」。
 */
private data class DragState(
    val path: String,
    val origin: Offset,
    val pos: Offset,
    val startPos: Offset,
)

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
 * 点击打开 / 收起展开，长按弹出菜单（新建文件 / 新建目录 / 重命名 / 移动 / 复制路径 / 导出 / 删除，
 * `.apk` 文件另显示「安装」），长按拖拽移动文件到目标目录。
 * 顶部提供「全部折叠 / 全部展开」切换（新项目默认全部折叠）；
 * 打开 / 切换文件时按 [EditorController.locatePath] 展开祖先并滚动定位、高亮当前文件。
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
    val activePath by EditorController.activePath.collectAsStateWithLifecycle()
    val locateTick by EditorController.locateTick.collectAsStateWithLifecycle()
    val settings by EditorController.settings.collectAsStateWithLifecycle()
    val rows = remember(tree, collapsed) { buildTreeRows(tree, collapsed) }
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    // 顶部「全部折叠 / 全部展开」按钮的目标态：树里还有展开着的目录即显示折叠按钮
    val anyExpanded = remember(tree, collapsed) {
        tree.any { it.isDirectory && it.file.path !in collapsed }
    }

    // 打开 / 切换文件 → 滚动定位到目标行。定位触发的补载是异步的（链式读盘），
    // 所以每 50ms 重查一次树，直到目标行出现（约 2s 上限，找不到则静默放弃）。
    LaunchedEffect(locateTick) {
        val target = EditorController.locatePath.value ?: return@LaunchedEffect
        repeat(40) {
            val current = buildTreeRows(
                EditorController.tree.value,
                EditorController.collapsedDirs.value,
            )
            val idx = current.indexOfFirst { it.path == target }
            if (idx >= 0) {
                listState.scrollToItem(idx)
                return@LaunchedEffect
            }
            delay(50)
        }
    }

    var menuPath by remember { mutableStateOf<String?>(null) }
    var createMenuOpen by remember { mutableStateOf(false) }
    var nameDialog by remember { mutableStateOf<NameDialog?>(null) }
    var pendingDelete by remember { mutableStateOf<FileNode?>(null) }
    var pendingMove by remember { mutableStateOf<FileNode?>(null) }
    // 待导出条目：先记下（选择器期间行可能被折叠 / 刷新掉），拿到目标目录后再执行
    var pendingExport by remember { mutableStateOf<FileNode?>(null) }
    var hiddenNamesDialogOpen by remember { mutableStateOf(false) }
    var drag by remember { mutableStateOf<DragState?>(null) }
    val bounds = remember { mutableStateMapOf<String, Rect>() }
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { treeUri ->
        val node = pendingExport
        pendingExport = null
        if (treeUri != null && node != null) {
            EditorController.exportTo(node.file.path, treeUri)
        }
    }

    val dropTargetPath = drag?.let { state -> resolveDropTarget(state.pos, bounds, rows) }

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
                    // 新建（与长按菜单共用同一套创建流程；重命名 / 移动 / 导出 / 删除仍为长按专属）
                    Box {
                        IconButton(
                            onClick = { createMenuOpen = true },
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "新建")
                        }
                        DropdownMenu(
                            expanded = createMenuOpen,
                            onDismissRequest = { createMenuOpen = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("新建文件") },
                                leadingIcon = { Icon(Icons.Default.FileCopy, contentDescription = null) },
                                onClick = {
                                    createMenuOpen = false
                                    rootPath?.let {
                                        nameDialog = NameDialog.Create(NameDialog.Create.Kind.FILE, it)
                                    }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("新建目录") },
                                leadingIcon = {
                                    Icon(Icons.Default.CreateNewFolder, contentDescription = null)
                                },
                                onClick = {
                                    createMenuOpen = false
                                    rootPath?.let {
                                        nameDialog =
                                            NameDialog.Create(NameDialog.Create.Kind.DIRECTORY, it)
                                    }
                                },
                            )
                        }
                    }
                    // 全部折叠 / 全部展开（互斥切换；新项目默认折叠态）
                    IconButton(
                        onClick = {
                            if (anyExpanded) EditorController.collapseAll()
                            else EditorController.expandAll()
                        },
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            imageVector = if (anyExpanded) {
                                Icons.Default.UnfoldLess
                            } else {
                                Icons.Default.UnfoldMore
                            },
                            contentDescription = if (anyExpanded) "全部折叠" else "全部展开",
                        )
                    }
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
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
                        items(rows, key = { it.path }) { row ->
                            TreeRowItem(
                                row = row,
                                bounds = bounds,
                                drag = drag,
                                dropTargetPath = dropTargetPath,
                                highlighted = row.path == activePath,
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
                                    val arm = origin + local
                                    drag = DragState(row.path, origin, arm, arm)
                                },
                                onDrag = { local ->
                                    val origin = drag?.origin ?: return@TreeRowItem
                                    val pos = origin + local
                                    // 零位移 move（手势注入器会原样回放同坐标）不算拖动：
                                    // 不清菜单、不改落点，原地长按弹菜单不受干扰
                                    if (pos != drag?.pos) {
                                        menuPath = null
                                        drag = drag?.copy(pos = pos)
                                    }
                                },
                                onDragEnd = {
                                    val state = drag
                                    drag = null
                                    // 拖臂后零位移（原地长按弹菜单）不构成拖放：直接忽略，
                                    // 连落点解析都不做，杜绝按陈旧坐标误移动文件
                                    if (state != null && state.pos != state.startPos) {
                                        val target = resolveDropTarget(state.pos, bounds, rows)
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

                                        TreeAction.COPY_PATH -> {
                                            val path = node.file.absolutePath
                                            clipboard.setText(AnnotatedString(path))
                                            EditorController.showMessage("已复制路径到剪贴板")
                                        }

                                        TreeAction.EXPORT -> {
                                            pendingExport = node
                                            exportLauncher.launch(null)
                                        }

                                        TreeAction.INSTALL ->
                                            EditorController.showMessage(
                                                ApkInstaller.install(context, node.file)
                                                    ?: "已发起安装：${node.name}",
                                            )

                                        TreeAction.DELETE -> pendingDelete = node
                                        TreeAction.MOVE -> pendingMove = node
                                    }
                                },
                            )
                        }
                    }
                }

                // 显示隐藏文件开关：点开后 .gitignore / .github / .env 等点开头文件、目录可见；
                // 「自定义…」维护「不显示」名单（默认 build / node_modules / captures）
                HorizontalDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "显示隐藏文件",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = { hiddenNamesDialogOpen = true },
                        modifier = Modifier.height(32.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) {
                        Text(
                            text = "自定义…",
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                        )
                    }
                    Switch(
                        checked = settings.showHiddenFiles,
                        onCheckedChange = { EditorController.toggleShowHiddenFiles() },
                    )
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
            rootPath = rootPath,
            showHidden = settings.showHiddenFiles,
            onPick = { dir ->
                pendingMove = null
                EditorController.move(node.file.path, dir.file.path)
            },
            onDismiss = { pendingMove = null },
        )
    }

    if (hiddenNamesDialogOpen) {
        HiddenNamesDialog(
            initial = settings.hiddenNames,
            onConfirm = { names ->
                hiddenNamesDialogOpen = false
                EditorController.setHiddenNames(names)
            },
            onDismiss = { hiddenNamesDialogOpen = false },
        )
    }
}

private enum class TreeAction {
    NEW_FILE,
    NEW_DIRECTORY,
    RENAME,
    COPY_PATH,
    EXPORT,
    INSTALL,
    DELETE,
    MOVE,
}

/**
 * 「不显示」名单编辑弹窗：每行（或逗号）一个名称，按名匹配任意层级的文件 / 目录。
 * 保存后经 [EditorController.setHiddenNames] 注入过滤口径并重建树。
 */
@Composable
private fun HiddenNamesDialog(
    initial: List<String>,
    onConfirm: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial.joinToString("\n")) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("不显示的文件 / 目录") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "每行一个名称（也可用逗号分隔），匹配任意层级的同名文件或目录；" +
                        "留空则全部显示。默认隐藏构建产物 build / node_modules / captures。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 140.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(parseHiddenNames(text)) }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 名单解析：按行 / 逗号 / 分号切分，去掉路径与空白并去重。 */
private fun parseHiddenNames(raw: String): List<String> = raw
    .split('\n', '\r', ',', '，', ';', '；', '、')
    .map { it.trim().substringAfterLast('/').substringAfterLast('\\') }
    .filter { it.isNotEmpty() }
    .distinct()

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TreeRowItem(
    row: TreeRow,
    bounds: MutableMap<String, Rect>,
    drag: DragState?,
    dropTargetPath: String?,
    highlighted: Boolean,
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

    // 行离开组合（折叠 / 刷新 / 滚出视口）时同步清掉坐标簿里的旧 rect，
    // 否则拖拽落点会按历史坐标解析到看不见的目录（曾把 ui 目录误移动到 app/ 下）。
    DisposableEffect(row.path) {
        onDispose { bounds.remove(row.path) }
    }

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
                    // 当前打开的文件：淡主色底，便于在树里一眼定位
                    highlighted -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.75f)
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
            // 「安装」只对 .apk 出现（与构建产物行共用 ApkInstaller：系统安装器 + 未知应用授权）
            if (!row.isDirectory && ApkInstaller.isApk(row.node.file)) {
                DropdownMenuItem(
                    text = { Text("安装") },
                    leadingIcon = {
                        Icon(Icons.Default.SystemUpdate, contentDescription = null)
                    },
                    onClick = { onMenuItem(TreeAction.INSTALL) },
                )
            }
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
                text = { Text("复制路径") },
                leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                onClick = { onMenuItem(TreeAction.COPY_PATH) },
            )
            DropdownMenuItem(
                text = { Text("导出到…") },
                leadingIcon = { Icon(Icons.Default.FileDownload, contentDescription = null) },
                onClick = { onMenuItem(TreeAction.EXPORT) },
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

/** 移动目标选择弹层的可见行：一个目录 + 层级 / 展开态 / 是否还有子目录可展开。 */
data class MoveDirRow(
    val node: FileNode,
    val depth: Int,
    val expanded: Boolean,
    val hasChildren: Boolean,
) {
    val path: String get() = node.file.path
}

/**
 * 由「目录路径 → 直系子目录」缓存生成弹层可见行：
 * 根恒展开（首层直接可见），其余目录 [expanded] 里登记的才逐层展开；
 * 尚未读盘缓存子级的目录按「可能有子级」出箭头，读完是空列表箭头自动消失。
 */
fun buildMoveDirRows(
    root: FileNode,
    expanded: Set<String>,
    childDirs: Map<String, List<FileNode>>,
): List<MoveDirRow> {
    val rows = ArrayList<MoveDirRow>()
    fun walk(dir: FileNode, depth: Int) {
        val kids = childDirs[dir.file.path]
        val isOpen = depth == 0 || dir.file.path in expanded
        rows.add(MoveDirRow(dir, depth, isOpen, kids?.isNotEmpty() ?: true))
        if (isOpen) kids?.forEach { walk(it, depth + 1) }
    }
    walk(root, 0)
    return rows
}

/**
 * 移动目标目录选择：按项目目录层级显示，默认折叠（只列首层），点箭头一层层展开。
 * 子级展开时按需读盘，不受主树 eager 深度限制——再深的目录也能逐层选到。
 * 被移动节点本身不进候选列表（其子树因此无从展开进入）；当前所在目录置灰不可选；
 * 点目录名选中后需按「移动到此处」确认，防误触直接移动。
 */
@Composable
private fun MoveDialog(
    node: FileNode,
    rootPath: String?,
    showHidden: Boolean,
    onPick: (FileNode) -> Unit,
    onDismiss: () -> Unit,
) {
    val root = remember(rootPath) {
        rootPath?.let { rp ->
            File(rp).takeIf { it.isDirectory }?.let { FileNode(it.name, it, true, 0L, 0) }
        }
    }
    val scope = rememberCoroutineScope()
    val expanded = remember { mutableStateListOf<String>() }
    val childDirs = remember { mutableStateMapOf<String, List<FileNode>>() }
    var selected by remember { mutableStateOf<FileNode?>(null) }
    val currentParent = node.file.parentFile?.path

    suspend fun loadChildren(dir: FileNode): List<FileNode> = withContext(Dispatchers.IO) {
        runCatching {
            FileRepository.listTreeChildren(dir.file, showHidden, childDepth = dir.depth + 1)
                .filter { it.isDirectory && it.file.path != node.file.path }
                .map { it.copy(depth = dir.depth + 1) }
        }.getOrDefault(emptyList())
    }

    LaunchedEffect(root, showHidden) {
        val r = root ?: return@LaunchedEffect
        if (r.file.path !in childDirs) childDirs[r.file.path] = loadChildren(r)
    }

    val rows = root?.let { buildMoveDirRows(it, expanded.toSet(), childDirs) } ?: emptyList()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("移动「${node.name}」到") },
        text = {
            if (rows.isEmpty()) {
                Text("没有可选目录")
            } else {
                LazyColumn(modifier = Modifier.height(280.dp)) {
                    items(rows, key = { it.path }) { row ->
                        MoveDirRowItem(
                            row = row,
                            selected = row.path == selected?.file?.path,
                            disabled = row.path == currentParent,
                            onToggle = {
                                val path = row.path
                                if (path in expanded) {
                                    expanded.remove(path)
                                } else {
                                    expanded.add(path)
                                    if (path !in childDirs) {
                                        scope.launch { childDirs[path] = loadChildren(row.node) }
                                    }
                                }
                            },
                            onSelect = { selected = row.node },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { selected?.let(onPick) },
                enabled = selected != null,
            ) { Text("移动到此处") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 弹层里的目录行：箭头区展开 / 折叠，行其余部分点选目标；当前所在目录置灰并标注。 */
@Composable
private fun MoveDirRowItem(
    row: MoveDirRow,
    selected: Boolean,
    disabled: Boolean,
    onToggle: () -> Unit,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                } else {
                    Color.Transparent
                },
            )
            .clickable(enabled = !disabled, onClick = onSelect)
            .padding(start = (6 + row.depth * 14).dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (row.hasChildren) {
            Box(
                modifier = Modifier
                    .width(28.dp)
                    .fillMaxHeight()
                    .clickable(
                        onClickLabel = if (row.expanded) "折叠" else "展开",
                    ) { onToggle() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (row.expanded) {
                        Icons.Default.KeyboardArrowDown
                    } else {
                        Icons.Default.ChevronRight
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            }
        } else {
            Spacer(modifier = Modifier.width(28.dp))
        }
        Icon(
            imageVector = Icons.Default.Folder,
            contentDescription = null,
            tint = if (disabled) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.primary
            },
            modifier = Modifier.padding(start = 2.dp, end = 6.dp),
        )
        Text(
            text = row.node.name,
            style = MaterialTheme.typography.bodyMedium,
            color = if (disabled) {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (disabled) {
            Text(
                text = "当前位置",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                maxLines = 1,
            )
        }
    }
}
