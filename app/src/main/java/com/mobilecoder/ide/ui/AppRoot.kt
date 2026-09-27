package com.mobilecoder.ide.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import com.mobilecoder.ide.core.storage.AppStorage
import com.mobilecoder.ide.core.storage.ProjectMeta
import com.mobilecoder.ide.core.storage.ProjectTemplate
import com.mobilecoder.ide.feature.build.BuildScreen
import com.mobilecoder.ide.feature.cli.CliScreen
import com.mobilecoder.ide.feature.editor.EditorScreen
import com.mobilecoder.ide.feature.git.GitScreen
import com.mobilecoder.ide.feature.ssh.SshScreen
import com.mobilecoder.ide.feature.terminal.TerminalScreen
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
    CLI("cli", "CLI", Icons.AutoMirrored.Filled.List),
    GIT("git", "Git", Icons.Default.AccountTree),
    SSH("ssh", "SSH", Icons.Default.Lock),
    BUILD("build", "构建", Icons.Default.Build),
    ;

    /** 是否需要「当前项目」上下文。 */
    val needsProject: Boolean get() = this != HOME && this != SSH

    companion object {
        fun of(route: String?): AppDestination? =
            entries.firstOrNull { it.route == route }
    }
}

/**
 * 全局根布局：Scaffold（顶栏 + 底部导航）+ NavHost 挂载全部 feature 页面。
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

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = project?.name ?: stringResource(R.string.no_project_open),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
            )
        },
        bottomBar = {
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
            composable(AppDestination.CLI.route) {
                ProjectGuard { path -> CliScreen(projectPath = path) }
            }
            composable(AppDestination.GIT.route) {
                ProjectGuard { path -> GitScreen(projectPath = path) }
            }
            composable(AppDestination.SSH.route) {
                SshScreen()
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
    var pendingDelete by remember { mutableStateOf<ProjectMeta?>(null) }

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
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
