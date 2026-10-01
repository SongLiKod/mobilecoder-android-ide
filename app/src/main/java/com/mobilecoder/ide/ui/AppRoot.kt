package com.mobilecoder.ide.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.mobilecoder.ide.AppState
import com.mobilecoder.ide.R
import com.mobilecoder.ide.core.common.theme.AppThemeMode
import com.mobilecoder.ide.core.common.theme.ThemeManager
import com.mobilecoder.ide.core.common.ui.AppAlertDialog
import com.mobilecoder.ide.core.common.ui.EmptyState
import com.mobilecoder.ide.core.common.ui.SectionHeader
import com.mobilecoder.ide.core.common.ui.rememberImeVisible
import com.mobilecoder.ide.core.storage.AppStorage
import com.mobilecoder.ide.core.storage.ProjectMeta
import com.mobilecoder.ide.core.storage.ProjectTemplate
import com.mobilecoder.ide.feature.ai.AiScreen
import com.mobilecoder.ide.feature.build.BuildScreen
import com.mobilecoder.ide.feature.cli.HistoryScreen
import com.mobilecoder.ide.feature.editor.EditorScreen
import com.mobilecoder.ide.feature.git.GitController
import com.mobilecoder.ide.feature.git.GitProgress
import com.mobilecoder.ide.feature.git.GitScreen
import com.mobilecoder.ide.feature.ssh.SshScreen
import com.mobilecoder.ide.feature.terminal.TerminalScreen
import java.io.File
import kotlinx.coroutines.launch

/** 全局导航目的地（app 负责全局导航：TECH.md 7）。 */
enum class AppDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    HOME("home", "项目", Icons.Default.Folder),
    EDITOR("editor", "编辑", Icons.Default.Edit),
    TERMINAL("terminal", "终端", Icons.Default.Terminal),
    HISTORY("history", "历史", Icons.Default.History),
    GIT("git", "Git", Icons.Default.AccountTree),
    SSH("ssh", "SSH", Icons.Default.Lock),
    AI("ai", "AI", Icons.Default.SmartToy),
    BUILD("build", "构建", Icons.Default.Build),
    ;

    /**
     * 是否需要「当前项目」上下文。
     * 「历史」页汇总全部项目/全局操作，没有项目也能看，故与 HOME、SSH 同列。
     */
    val needsProject: Boolean get() = this != HOME && this != SSH && this != HISTORY

    companion object {
        fun of(route: String?): AppDestination? =
            entries.firstOrNull { it.route == route }
    }
}

/**
 * 全局根布局：Scaffold（**紧凑单行顶栏** + 底部导航）+ NavHost 挂载全部 feature 页面。
 * 顶栏刻意压到 40dp 单行（应用名 + 当前项目同行），把高度让给各页内容区。
 * 同时承载 PRD 2.1「全局主题系统」三模式切换（首页）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(
    themeManager: ThemeManager,
    modifier: Modifier = Modifier,
) {
    val navController = rememberNavController()
    val project by AppState.project.collectAsStateWithLifecycle()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    // 软键盘可见时把底部导航整条撤掉（否则「导航 + 输入区」占去近半屏），收起键盘后恢复
    val imeVisible by rememberImeVisible()

    Scaffold(
        // imePadding：键盘弹出时整体上移，避免输入框被遮挡；
        // 同时 bottomBar 在键盘弹出期间隐藏，内容区拿到整块空间。
        modifier = modifier.fillMaxSize().imePadding(),
        topBar = {
            // 紧凑单行顶栏 = 状态栏内边距 + 40dp 标题行。
            // 原 M3 TopAppBar 是「状态栏 + 64dp 固定行 + 两行标题」，合计约 90dp，
            // 每个页面都被吃掉近 1/8 的可视高度；这里把应用名与项目名并到一行，
            // 每页内容区直接多出约 24dp。
            Surface(color = MaterialTheme.colorScheme.surface) {
                val subtitle = project?.name?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.no_project_open)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .height(40.dp)
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 8.dp),
                    )
                }
            }
        },
        bottomBar = {
            // 键盘弹出 → 隐藏底部导航（把屏幕让给内容和键盘）；键盘收起 → 恢复显示
            if (!imeVisible) {
                NavigationBar {
                    AppDestination.entries.forEach { destination ->
                        NavigationBarItem(
                            selected = currentRoute == destination.route,
                            onClick = { navController.navigateTo(destination.route) },
                            icon = { Icon(destination.icon, contentDescription = destination.label) },
                            label = { Text(destination.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = AppDestination.HOME.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(AppDestination.HOME.route) {
                HomeScreen(
                    themeManager = themeManager,
                    onOpenProject = { navController.navigateTo(AppDestination.EDITOR.route) },
                    onOpenSsh = { navController.navigateTo(AppDestination.SSH.route) },
                )
            }
            composable(AppDestination.EDITOR.route) {
                ProjectGuard { path -> EditorScreen(projectPath = path) }
            }
            composable(AppDestination.TERMINAL.route) {
                ProjectGuard { path -> TerminalScreen(projectPath = path) }
            }
            composable(AppDestination.HISTORY.route) {
                HistoryScreen()
            }
            composable(AppDestination.GIT.route) {
                ProjectGuard { path -> GitScreen(projectPath = path) }
            }
            composable(AppDestination.SSH.route) {
                SshScreen()
            }
            composable(AppDestination.AI.route) {
                ProjectGuard { path -> AiScreen(projectPath = path) }
            }
            composable(AppDestination.BUILD.route) {
                ProjectGuard { path -> BuildScreen(projectPath = path) }
            }
        }
    }
}

/** 需要项目上下文的页面守卫：无项目时渲染引导（用户可回「项目」页打开）。 */
@Composable
private fun ProjectGuard(
    content: @Composable (projectPath: String) -> Unit,
) {
    val project by AppState.project.collectAsStateWithLifecycle()
    val root = project?.let { AppStorage.projects.projectDir(it.relativePath).path }

    if (root.isNullOrBlank()) {
        EmptyState(
            title = "尚未打开项目",
            subtitle = "请先在「项目」页新建或打开一个项目",
            modifier = Modifier.fillMaxSize(),
        )
        return
    }
    content(root)
}

private fun NavHostController.navigateTo(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

// ---------------------------------------------------------------------------
// 首页：项目管理 + 全局主题（PRD 2.1）
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(
    themeManager: ThemeManager,
    onOpenProject: () -> Unit,
    onOpenSsh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var projects by remember { mutableStateOf<List<ProjectMeta>>(emptyList()) }
    var showCreate by rememberSaveable { mutableStateOf(false) }
    var showClone by rememberSaveable { mutableStateOf(false) }
    var cloneNotice by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<ProjectMeta?>(null) }
    val gitBusy by GitController.busy.collectAsStateWithLifecycle()
    val gitProgress by GitController.progress.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        projects = runCatching { AppStorage.projects.list() }.getOrDefault(emptyList())
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionHeader(title = "我的项目", modifier = Modifier.weight(1f))
            IconButton(
                onClick = {
                    cloneNotice = null
                    showClone = true
                },
            ) {
                Icon(Icons.Default.Download, contentDescription = "克隆仓库")
            }
            IconButton(onClick = { showCreate = true }) {
                Icon(Icons.Default.Add, contentDescription = "新建项目")
            }
        }

        if (projects.isEmpty()) {
            EmptyState(
                title = "还没有项目",
                subtitle = "点击右上角 + 创建第一个安卓项目",
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            ) {
                items(projects, key = { it.relativePath }) { meta ->
                    ProjectCard(
                        meta = meta,
                        onOpen = {
                            scope.launch {
                                AppStorage.projects.markOpened(meta.relativePath)
                                AppState.open(meta)
                                onOpenProject()
                            }
                        },
                        onDelete = { pendingDelete = meta },
                    )
                }
            }
        }

        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionHeader(title = stringResource(R.string.theme_section_title))
            ThemeModeRow(themeManager = themeManager)
            Text(
                text = stringResource(R.string.theme_scope_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenSsh)
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = "GitHub SSH 密钥",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        text = "生成 / 加密存储 / 一键测试连通性",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (showCreate) {
        CreateProjectDialog(
            onDismiss = { showCreate = false },
            onCreate = { name, template ->
                showCreate = false
                scope.launch {
                    val meta = AppStorage.projects.create(name, template)
                    AppStorage.projects.markOpened(meta.relativePath)
                    AppState.open(meta)
                    projects = AppStorage.projects.list()
                    onOpenProject()
                }
            },
        )
    }

    if (showClone) {
        CloneRepoDialog(
            busy = gitBusy,
            progress = gitProgress,
            notice = cloneNotice,
            projectsRoot = AppStorage.projects.projectsRoot,
            onDismiss = {
                if (!gitBusy) {
                    showClone = false
                    cloneNotice = null
                }
            },
            onClone = { url, branch, target ->
                cloneNotice = null
                scope.launch {
                    val dir = File(target)
                    val hasFiles = dir.isDirectory && dir.listFiles()?.isNotEmpty() == true

                    // ① 目标已存在（上次克隆已完成但当时没登记成功）→ 直接登记并打开
                    if (hasFiles) {
                        if (File(dir, ".git").exists()) {
                            val existing = AppStorage.projects.adopt(dir)
                            if (existing != null) {
                                showClone = false
                                AppStorage.projects.markOpened(existing.relativePath)
                                AppState.open(existing)
                                projects = AppStorage.projects.list()
                                onOpenProject()
                                return@launch
                            }
                            cloneNotice = "目录里已是 Git 仓库，但不在项目目录内，无法加入列表：$target"
                        } else {
                            cloneNotice = "目标目录已存在且不是 Git 仓库：$target\n（请换一个目标目录）"
                        }
                        return@launch
                    }

                    // ② 先登记再克隆：克隆过程中即使异常退出，项目也已经在列表里
                    runCatching {
                        dir.parentFile?.mkdirs()
                        dir.mkdirs()
                    }
                    val registered = runCatching { AppStorage.projects.adopt(dir) }.getOrNull()
                    val ok = runCatching { GitController.cloneOnly(url, target, branch) }
                        .getOrDefault(false)
                    if (!ok) {
                        val text = GitController.message.value?.text
                        GitController.clearMessage()
                        val hint = if (registered != null) {
                            "\n（目录已登记到项目列表，可删除后重试）"
                        } else {
                            ""
                        }
                        cloneNotice = (text ?: "克隆失败：请检查仓库地址与网络") + hint
                        // 空壳目录不留垃圾：登记过且没拉下任何文件 → 撤销登记
                        if (registered != null && dir.listFiles()?.isEmpty() != false) {
                            runCatching { AppStorage.projects.delete(registered.relativePath) }
                        }
                        return@launch
                    }

                    val meta = runCatching { AppStorage.projects.adopt(dir) }.getOrNull()
                        ?: registered
                    if (meta == null) {
                        // 克隆成功但目录不在项目目录内：无法登记为项目
                        cloneNotice = "克隆完成：$target\n（不在项目目录内，未加入项目列表）"
                        return@launch
                    }
                    showClone = false
                    AppStorage.projects.markOpened(meta.relativePath)
                    AppState.open(meta)
                    projects = AppStorage.projects.list()
                    onOpenProject()
                }
            },
        )
    }

    pendingDelete?.let { meta ->
        AppAlertDialog(
            title = "删除项目",
            message = "确认删除「${meta.name}」？项目源码将从磁盘移除，不可恢复。",
            confirmLabel = "删除",
            destructive = true,
            onConfirm = {
                pendingDelete = null
                scope.launch {
                    AppStorage.projects.delete(meta.relativePath)
                    if (AppState.project.value?.relativePath == meta.relativePath) {
                        AppState.clear()
                    }
                    projects = AppStorage.projects.list()
                }
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

@Composable
private fun ProjectCard(
    meta: ProjectMeta,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = meta.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${meta.template.label} · ${meta.relativePath}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 从仓库地址推导默认目录名（与 Git 页克隆弹窗一致）。 */
private fun repoNameOf(url: String): String {
    val cleaned = url.trim().removeSuffix("/").removeSuffix(".git")
    val name = cleaned.substringAfterLast('/').substringAfterLast(':')
    return name.filter { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }
        .ifBlank { "repository" }
}

/**
 * 克隆仓库为新项目（PRD 2.2「项目目录」：克隆下来的目录本身就是项目，无需先新建）。
 *
 * 成功后由调用方登记进项目索引并打开；失败/提示文案显示在弹窗内（克隆进度走
 * [GitController.progress]，与 Git 页共用同一引擎）。
 */
@Composable
private fun CloneRepoDialog(
    busy: Boolean,
    progress: GitProgress?,
    notice: String?,
    projectsRoot: File,
    onDismiss: () -> Unit,
    onClone: (url: String, branch: String, target: String) -> Unit,
) {
    var url by rememberSaveable { mutableStateOf("") }
    var branch by rememberSaveable { mutableStateOf("") }
    var target by rememberSaveable { mutableStateOf("") }

    val defaultTarget = remember(url, projectsRoot) {
        File(projectsRoot, repoNameOf(url)).path
    }
    val fraction = progress?.fraction

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.imePadding(),
        title = { Text("克隆仓库为新项目") },
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
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = branch,
                    onValueChange = { branch = it },
                    label = { Text("分支（可选，默认远程默认分支）") },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = target,
                    onValueChange = { target = it },
                    label = { Text("目标目录（可选）") },
                    supportingText = { Text("留空则克隆到 $defaultTarget") },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (busy) {
                    if (fraction != null) {
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    Text(
                        text = "正在克隆…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                notice?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
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
                enabled = url.isNotBlank() && !busy,
            ) { Text("克隆") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CreateProjectDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String, template: ProjectTemplate) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var templateIndex by rememberSaveable { mutableStateOf(ProjectTemplate.ANDROID_APP.ordinal) }
    val template = ProjectTemplate.entries.getOrElse(templateIndex) { ProjectTemplate.ANDROID_APP }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.imePadding(),
        title = { Text("新建项目") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("项目名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("模板", style = MaterialTheme.typography.labelMedium)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        ProjectTemplate.entries.forEachIndexed { index, item ->
                            FilterChip(
                                selected = templateIndex == index,
                                onClick = { templateIndex = index },
                                label = { Text(item.label) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(name.trim().ifBlank { "未命名项目" }, template) },
                enabled = name.isNotBlank(),
            ) { Text("创建") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

// ---------------------------------------------------------------------------
// 主题三模式切换（PRD 2.1）
// ---------------------------------------------------------------------------

/** PRD 2.1 三模式切换：浅色 / 深色 / 跟随系统。 */
@Composable
fun ThemeModeRow(
    themeManager: ThemeManager,
    modifier: Modifier = Modifier,
) {
    val current by themeManager.mode.collectAsStateWithLifecycle()

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ThemeModeOption.entries.forEach { option ->
            FilterChip(
                selected = current == option.mode,
                onClick = { themeManager.setMode(option.mode) },
                label = { Text(stringResource(option.labelRes)) },
            )
        }
    }
}

/** 三模式与文案资源的对应关系。 */
private enum class ThemeModeOption(val mode: AppThemeMode, val labelRes: Int) {
    LIGHT(AppThemeMode.LIGHT, R.string.theme_light),
    DARK(AppThemeMode.DARK, R.string.theme_dark),
    SYSTEM(AppThemeMode.SYSTEM, R.string.theme_system),
}
