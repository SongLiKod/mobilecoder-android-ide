package com.mobilecoder.ide.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
import com.mobilecoder.ide.core.common.ui.isImeVisible
import com.mobilecoder.ide.core.storage.AppStorage
import com.mobilecoder.ide.core.storage.ProjectMeta
import com.mobilecoder.ide.core.storage.ProjectTemplate
import com.mobilecoder.ide.feature.ai.AiScreen
import com.mobilecoder.ide.feature.build.BuildScreen
import com.mobilecoder.ide.feature.build.EnvironmentScreen
import com.mobilecoder.ide.feature.history.HistoryScreen
import com.mobilecoder.ide.feature.editor.EditorController
import com.mobilecoder.ide.feature.editor.EditorScreen
import com.mobilecoder.ide.feature.git.GitController
import com.mobilecoder.ide.feature.git.GitHead
import com.mobilecoder.ide.feature.git.GitProgress
import com.mobilecoder.ide.feature.git.GitScreen
import com.mobilecoder.ide.feature.ssh.SshScreen
import com.mobilecoder.ide.feature.terminal.TerminalScreen
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 全局导航目的地（app 负责全局导航：TECH.md 7）。 */
enum class AppDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,

    /** 是否占底部导航位（重构后底栏固定 5 个 tab，其余为二级页：统一返回条）。 */
    val inBottomBar: Boolean = true,

    /** 悬浮圆点扇形菜单里的短标签（菜单项紧凑，长名如「构建与运行」→「构建」）。 */
    val menuLabel: String = label,
) {
    // ---- 底栏 5 tab ----
    HOME("home", "项目", Icons.Default.Folder),
    EDITOR("editor", "编辑", Icons.Default.Edit),
    TERMINAL("terminal", "终端", Icons.Default.Terminal),
    GIT("git", "Git", Icons.Default.AccountTree),
    MORE("more", "更多", Icons.Default.MoreHoriz),

    // ---- 二级页（顶部统一返回条，标题即 label；系统返回键回上一级）----
    BUILD("build", "构建与运行", Icons.Default.Build, inBottomBar = false, menuLabel = "构建"),
    AI("ai", "AI 助手", Icons.Default.SmartToy, inBottomBar = false, menuLabel = "AI"),
    SSH("ssh", "SSH 密钥", Icons.Default.Lock, inBottomBar = false, menuLabel = "SSH"),
    HISTORY("history", "历史记录", Icons.Default.History, inBottomBar = false, menuLabel = "记录"),
    ENV("env", "环境中心", Icons.Default.Widgets, inBottomBar = false, menuLabel = "环境"),
    MARKET("market", "软件市场", Icons.Default.ShoppingCart, inBottomBar = false, menuLabel = "市场"),
    SETTINGS("settings", "设置", Icons.Default.Settings, inBottomBar = false),
    ABOUT("about", "关于", Icons.Default.Info, inBottomBar = false),
    ;

    /** 是否需要「当前项目」上下文（无项目时由 ProjectGuard 引导去「项目」页）。 */
    val needsProject: Boolean
        get() = this == EDITOR || this == TERMINAL || this == GIT ||
            this == BUILD || this == AI

    companion object {
        fun of(route: String?): AppDestination? =
            entries.firstOrNull { it.route == route }
    }
}

/**
 * 全局根布局：Scaffold（顶栏 + 底部导航 5 tab）+ NavHost 挂载全部 feature 页面。
 * 二级页（构建 / AI / SSH 密钥 / 记录 / 环境中心 / 设置 / 关于）统一走 [SubPage]
 * 返回条；主题三模式切换（PRD 2.1）在「设置」页。
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

    // ---- 全局分支徽标（顶栏唯一来源，②）：打开项目即绑定仓库拿到 head；
    //      非 Git 仓库 head 为 null → 徽标自然隐藏；Git 页 / 终端里的 git 操作
    //      刷新 head 后顶栏自动跟随更新。 ----
    val gitHead by GitController.head.collectAsStateWithLifecycle()
    val projectDirPath = project?.let { AppStorage.projects.projectDir(it.relativePath).path }
    LaunchedEffect(projectDirPath) {
        val path = projectDirPath
        if (!path.isNullOrBlank()) {
            runCatching { GitController.bind(path) }
        }
    }

    // 底栏高亮：停留在二级页时高亮其来源 tab（更多 → 构建、编辑 → ▶ 构建）
    var lastTabRoute by rememberSaveable { mutableStateOf(AppDestination.HOME.route) }

    // ---- ① 滚动自动收起顶/底栏（Chrome 式）：手指下滑露出、上滑收起 ----
    var barsHidden by remember { mutableStateOf(false) }

    // ---- 悬浮导航圆点（设置页「悬浮导航」可开关，默认开）：状态的唯一来源，
    //      设置页切换时立即生效，同时持久化到 AppPreferences。 ----
    var floatingDotEnabled by remember { mutableStateOf(true) }
    // 展开菜单的标签开关 + 自定义菜单 route 集合（空 = 全部）+ 各项所在环，设置页与圆点共用
    var floatingDotLabels by remember { mutableStateOf(true) }
    var floatingDotMenus by remember { mutableStateOf(emptySet<String>()) }
    var floatingDotRings by remember { mutableStateOf(emptyMap<String, Int>()) }
    val dotScope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        floatingDotEnabled = runCatching {
            AppStorage.preferences.floatingDotEnabled()
        }.getOrDefault(true)
        floatingDotLabels = runCatching {
            AppStorage.preferences.floatingDotLabels()
        }.getOrDefault(true)
        floatingDotMenus = runCatching { AppStorage.preferences.floatingDotMenus() }.getOrNull()
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            ?: emptySet()
        floatingDotRings = runCatching { AppStorage.preferences.floatingDotRings() }.getOrNull()
            ?.split(',')
            ?.mapNotNull { part ->
                val bits = part.split('=')
                val route = bits.getOrNull(0)?.trim()
                val ring = bits.getOrNull(1)?.toIntOrNull()
                if (!route.isNullOrEmpty() && ring != null) route to ring else null
            }
            ?.toMap()
            ?: emptyMap()
    }
    val scrollAccum = remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    // 阈值 48dp：阅读/输入时手指的小幅抖动不再误触收起
    val hideThreshold = with(density) { 48.dp.toPx() }
    val revealThreshold = with(density) { 8.dp.toPx() }
    val barsScrollConnection = remember(hideThreshold, revealThreshold) {
        object : NestedScrollConnection {
            // 只观察不消费：NavHost 上挂的 nestedScroll 会收到所有子孙滚动
            // （LazyColumn / verticalScroll）上抛的方向增量。
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val delta = available.y
                if (delta == 0f) return Offset.Zero
                // 方向变了先清零，避免来回小幅抖动互相抵消
                if ((delta > 0f) != (scrollAccum.floatValue > 0f)) scrollAccum.floatValue = 0f
                scrollAccum.floatValue += delta
                // delta > 0：内容被往下拽（手指下滑）→ 露出栏；< 0 → 收起
                if (scrollAccum.floatValue <= -hideThreshold) {
                    barsHidden = true
                    scrollAccum.floatValue = 0f
                } else if (scrollAccum.floatValue >= revealThreshold) {
                    barsHidden = false
                    scrollAccum.floatValue = 0f
                }
                return Offset.Zero
            }
        }
    }
    // 换页面重新露出顶/底栏，并记录最近一次的底栏 tab（二级页高亮用）
    LaunchedEffect(currentRoute) {
        Log.i("NavDbg", "currentRoute -> $currentRoute")
        barsHidden = false
        scrollAccum.floatValue = 0f
        val route = currentRoute
        if (route != null && AppDestination.of(route)?.inBottomBar == true) {
            lastTabRoute = route
        }
    }

    // ---- ③ 沉浸式全屏：隐藏系统状态栏 + 导航栏 ----
    var fullscreen by remember { mutableStateOf(false) }
    val view = LocalView.current
    val activity = LocalContext.current.findActivity()
    LaunchedEffect(fullscreen, activity) {
        val window = activity?.window ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(window, view)
        if (fullscreen) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    // 全屏时按返回键 = 退出全屏（而不是退出应用）
    BackHandler(enabled = fullscreen) { fullscreen = false }

    // 悬浮圆点渲染在 Scaffold 之外、其上层：Scaffold 的 top/bottomBar 画在 content
    // 之后，圆点原来放 content Box 里会被顶/底栏盖住（展开菜单在最上层显示）
    Box(modifier = modifier.fillMaxSize()) {
        Scaffold(
            // imePadding：键盘弹出时整体上移，避免输入框被遮挡（键盘可见时底部导航
            // 已不渲染，见下方 bottomBar）
            modifier = Modifier.fillMaxSize().imePadding(),
        topBar = {
            // 收起条件：① 向下滚动收起 ② 全屏（② 键盘弹出时顶栏保留，输入时仍能看到项目与分支）
            AnimatedVisibility(
                visible = !barsHidden && !fullscreen,
                enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
            ) {
                AppTopBar(
                    projectName = project?.name,
                    gitHead = if (project != null) gitHead else null,
                    onToggleFullscreen = { fullscreen = !fullscreen },
                )
            }
        },
        bottomBar = {
            // 底部导航跟随 ①②③ 收起；键盘弹出时把空间全部让给键盘与内容
            AnimatedVisibility(
                visible = !barsHidden && !isImeVisible() && !fullscreen,
                enter = expandVertically(expandFrom = Alignment.Bottom) + fadeIn(),
                exit = shrinkVertically(shrinkTowards = Alignment.Bottom) + fadeOut(),
            ) {
                // iOS 风格悬浮胶囊条：只放小图标（比 NavigationBar + 文字标签更省空间），
                // 选中项套高亮胶囊，整体带阴影浮在内容之上。系统手势条 inset 在这里
                // 自己补（Material NavigationBar 内部处理，换掉后需手动 windowInsetsPadding）。
                val isSecondary = AppDestination.of(currentRoute)?.inBottomBar == false
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .shadow(8.dp, RoundedCornerShape(26.dp))
                            .clip(RoundedCornerShape(26.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppDestination.entries
                            .filter { it.inBottomBar }
                            .forEach { destination ->
                                val selected = currentRoute == destination.route ||
                                    (isSecondary && destination.route == lastTabRoute)
                                val pillColor by animateColorAsState(
                                    targetValue = if (selected) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        Color.Transparent
                                    },
                                    label = "tabPill",
                                )
                                val iconTint by animateColorAsState(
                                    targetValue = if (selected) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    label = "tabIcon",
                                )
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .padding(vertical = 6.dp)
                                        .clip(RoundedCornerShape(percent = 50))
                                        .background(pillColor)
                                        .selectable(
                                            selected = selected,
                                            role = Role.Tab,
                                            onClick = {
                                                navController.navigateTo(destination.route)
                                            },
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = destination.icon,
                                        contentDescription = destination.label,
                                        modifier = Modifier.size(22.dp),
                                        tint = iconTint,
                                    )
                                }
                            }
                    }
                }
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            NavHost(
                navController = navController,
                startDestination = AppDestination.HOME.route,
                modifier = Modifier
                    .fillMaxSize()
                    // ① 全局滚动监听：挂在 NavHost 上，所有 feature 页的滚动都会冒泡到这里
                    .nestedScroll(barsScrollConnection),
            ) {
                composable(AppDestination.HOME.route) {
                    HomeScreen(
                        onOpenProject = { navController.navigateTo(AppDestination.EDITOR.route) },
                    )
                }
                composable(AppDestination.EDITOR.route) {
                    ProjectGuard(onGoHome = { navController.navigateTo(AppDestination.HOME.route) }) { path ->
                        EditorScreen(
                            projectPath = path,
                            onOpenBuild = { navController.navigateToSub(AppDestination.BUILD.route) },
                        )
                    }
                }
                composable(AppDestination.TERMINAL.route) {
                    ProjectGuard(onGoHome = { navController.navigateTo(AppDestination.HOME.route) }) { path ->
                        TerminalScreen(projectPath = path)
                    }
                }
                composable(AppDestination.GIT.route) {
                    ProjectGuard(onGoHome = { navController.navigateTo(AppDestination.HOME.route) }) { path ->
                        GitScreen(projectPath = path)
                    }
                }
                composable(AppDestination.MORE.route) {
                    MoreScreen(
                        onOpen = { destination -> navController.navigateToSub(destination.route) },
                    )
                }
                composable(AppDestination.BUILD.route) {
                    SubPage(AppDestination.BUILD, onBack = { navController.popBackStack() }) {
                        ProjectGuard(onGoHome = { navController.navigateTo(AppDestination.HOME.route) }) { path ->
                            BuildScreen(
                                projectPath = path,
                                onOpenEnvironment = { startOnMarket ->
                                    navController.navigateToSub(
                                        if (startOnMarket) AppDestination.MARKET.route
                                        else AppDestination.ENV.route,
                                    )
                                },
                            )
                        }
                    }
                }
                composable(AppDestination.AI.route) {
                    SubPage(AppDestination.AI, onBack = { navController.popBackStack() }) {
                        ProjectGuard(onGoHome = { navController.navigateTo(AppDestination.HOME.route) }) { path ->
                            AiScreen(
                                projectPath = path,
                                currentFilePath = EditorController.activePath
                                    .collectAsStateWithLifecycle().value,
                                onOpenFile = { rel, _ ->
                                    EditorController.openFile(File(path, rel))
                                    navController.navigateTo(AppDestination.EDITOR.route)
                                },
                            )
                        }
                    }
                }
                composable(AppDestination.SSH.route) {
                    SubPage(AppDestination.SSH, onBack = { navController.popBackStack() }) {
                        SshScreen()
                    }
                }
                composable(AppDestination.HISTORY.route) {
                    SubPage(AppDestination.HISTORY, onBack = { navController.popBackStack() }) {
                        HistoryScreen()
                    }
                }
                composable(AppDestination.SETTINGS.route) {
                    SubPage(AppDestination.SETTINGS, onBack = { navController.popBackStack() }) {
                        SettingsScreen(
                            themeManager = themeManager,
                            floatingDotEnabled = floatingDotEnabled,
                            onFloatingDotEnabledChange = { next ->
                                floatingDotEnabled = next
                                dotScope.launch {
                                    runCatching {
                                        AppStorage.preferences.setFloatingDotEnabled(next)
                                    }
                                }
                            },
                            floatingDotLabels = floatingDotLabels,
                            onFloatingDotLabelsChange = { next ->
                                floatingDotLabels = next
                                dotScope.launch {
                                    runCatching {
                                        AppStorage.preferences.setFloatingDotLabels(next)
                                    }
                                }
                            },
                            floatingDotMenus = floatingDotMenus,
                            onFloatingDotMenusChange = { next ->
                                floatingDotMenus = next
                                dotScope.launch {
                                    runCatching {
                                        AppStorage.preferences.setFloatingDotMenus(
                                            next.joinToString(","),
                                        )
                                    }
                                }
                            },
                            floatingDotRings = floatingDotRings,
                            onFloatingDotRingsChange = { next ->
                                floatingDotRings = next
                                dotScope.launch {
                                    runCatching {
                                        AppStorage.preferences.setFloatingDotRings(
                                            next.entries.joinToString(",") { "${it.key}=${it.value}" },
                                        )
                                    }
                                }
                            },
                        )
                    }
                }
                composable(AppDestination.ABOUT.route) {
                    SubPage(AppDestination.ABOUT, onBack = { navController.popBackStack() }) {
                        AboutScreen()
                    }
                }
                composable(AppDestination.ENV.route) {
                    SubPage(AppDestination.ENV, onBack = { navController.popBackStack() }) {
                        EnvironmentScreen(
                            startOnMarket = false,
                            projectPath = projectDirPath,
                        )
                    }
                }
                composable(AppDestination.MARKET.route) {
                    SubPage(AppDestination.MARKET, onBack = { navController.popBackStack() }) {
                        EnvironmentScreen(
                            startOnMarket = true,
                            projectPath = projectDirPath,
                        )
                    }
                }
            }

            if (fullscreen) {
                // 全屏时顶栏已收起，给一个悬浮退出按钮（按返回键同样能退出）
                ExitFullscreenButton(
                    onClick = { fullscreen = false },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp),
                )
            }
        }
        }

        // 悬浮导航圆点：位于 Scaffold 之上（最上层），展开菜单不被顶/底栏或内容遮挡
        FloatingNavDot(
            enabled = floatingDotEnabled,
            currentRoute = currentRoute,
            showLabels = floatingDotLabels,
            visibleMenus = floatingDotMenus,
            ringOverrides = floatingDotRings,
            onNavigate = { destination ->
                Log.i("NavDbg", "menu click -> ${destination.route}")
                if (destination.inBottomBar) {
                    navController.navigateTo(destination.route)
                } else {
                    navController.navigateToSub(destination.route)
                }
            },
        )
    }
}

/**
 * 紧凑顶栏：应用名 + 当前项目并到同一行（原为两行标题、64dp 高的 TopAppBar），
 * 内容区最小 44dp；状态栏内边距由 windowInsetsPadding 吃掉，Scaffold 通过
 * consumedWindowInsets 不会再叠加一次。右侧是全屏开关（③）。
 * 分支徽标（②）全局唯一：项目名右侧，非仓库（gitHead 为 null）自然隐藏。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppTopBar(
    projectName: String?,
    gitHead: GitHead?,
    onToggleFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(TopAppBarDefaults.windowInsets)
                .heightIn(min = 44.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            Text(
                text = " · " + (projectName ?: stringResource(R.string.no_project_open)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            val badge = gitHead
            if (badge != null && badge.branch.isNotBlank()) {
                GlobalBranchBadge(
                    branch = badge.branch,
                    ahead = badge.ahead,
                    behind = badge.behind,
                    detached = badge.detached,
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            IconButton(onClick = onToggleFullscreen) {
                Icon(
                    imageVector = Icons.Default.Fullscreen,
                    contentDescription = "全屏",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 顶栏分支徽标：分支图标 + 名称 + 领先/落后计数（原编辑器 / 终端页各一份，收归全局唯一）。 */
@Composable
private fun GlobalBranchBadge(
    branch: String,
    ahead: Int,
    behind: Int,
    detached: Boolean,
    modifier: Modifier = Modifier,
) {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Icon(
                imageVector = Icons.Default.AccountTree,
                contentDescription = "当前分支",
                tint = tint,
                modifier = Modifier.size(12.dp),
            )
            Text(
                text = if (detached) "HEAD ($branch)" else branch,
                style = MaterialTheme.typography.labelSmall,
                color = tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 96.dp),
            )
            if (ahead > 0 || behind > 0) {
                Text(
                    text = buildString {
                        if (ahead > 0) append("↑$ahead")
                        if (ahead > 0 && behind > 0) append(" ")
                        if (behind > 0) append("↓$behind")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
            }
        }
    }
}

/** 全屏模式下的悬浮退出按钮（顶栏已收起时的唯一可见出口，返回键是另一条）。 */
@Composable
private fun ExitFullscreenButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.size(40.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.66f),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        IconButton(onClick = onClick) {
            Icon(
                imageVector = Icons.Default.FullscreenExit,
                contentDescription = "退出全屏",
            )
        }
    }
}

/** 从 Context 链里拿到宿主 Activity（用于操作 Window / 系统栏）。 */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** 需要项目上下文的页面守卫：无项目时渲染引导 + 一键回「项目」页。 */
@Composable
private fun ProjectGuard(
    onGoHome: () -> Unit,
    content: @Composable (projectPath: String) -> Unit,
) {
    val project by AppState.project.collectAsStateWithLifecycle()
    val root = project?.let { AppStorage.projects.projectDir(it.relativePath).path }

    if (root.isNullOrBlank()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "尚未打开项目",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = "请先打开或新建一个项目",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onGoHome) {
                Text("去「项目」页")
            }
        }
        return
    }
    content(root)
}

private fun NavHostController.navigateTo(route: String) {
    Log.i("NavDbg", "navigateTo $route from=${currentDestination?.route}")
    navigate(route) {
        popUpTo(graph.findStartDestination().id)
        launchSingleTop = true
    }
    Log.i("NavDbg", "navigateTo $route done -> ${currentDestination?.route}")
}

/** 二级页导航：入栈（不弹掉来源页），系统返回键回上一级（更多 → 构建 → 环境中心）。 */
private fun NavHostController.navigateToSub(route: String) {
    navigate(route) {
        launchSingleTop = true
    }
}

/**
 * 二级页容器：统一返回条（返回箭头 + 标题，内容区最小 44dp）。
 * 所有二级路由（构建 / AI / SSH 密钥 / 记录 / 环境中心 / 设置 / 关于）共用，
 * 页面内部不再自绘路由级返回行与页面级大标题。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubPage(
    destination: AppDestination,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(TopAppBarDefaults.windowInsets)
                .heightIn(min = 44.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                )
            }
            Text(
                text = destination.label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 16.dp),
            )
        }
        HorizontalDivider()
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            content()
        }
    }
}

// ---------------------------------------------------------------------------
// 首页：只管项目（打开 / 新建 / 克隆 / 删除）；主题 / SSH / 关于移入「设置」「更多」
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(
    onOpenProject: () -> Unit,
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
    val context = LocalContext.current
    // 待导出 / 待重命名项目 + 结果提示（首页没有编辑器的 MessageBar，这里本地提示）
    var pendingExport by remember { mutableStateOf<ProjectMeta?>(null) }
    var pendingRename by remember { mutableStateOf<ProjectMeta?>(null) }
    var homeNotice by remember { mutableStateOf<String?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { treeUri ->
        val meta = pendingExport
        pendingExport = null
        if (treeUri != null && meta != null) {
            val path = meta.absolutePathOf(AppStorage.projects.projectsRoot).path
            EditorController.exportTo(path, treeUri) { ok ->
                homeNotice = if (ok) "已导出「${meta.name}」" else "导出失败：目标目录不可写"
            }
        }
    }

    LaunchedEffect(Unit) { runCatching { EditorController.ensureInit(context) } }
    LaunchedEffect(homeNotice) {
        if (homeNotice != null) {
            delay(3000)
            homeNotice = null
        }
    }

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
                Icon(Icons.Default.CloudDownload, contentDescription = "克隆仓库")
            }
            IconButton(onClick = { showCreate = true }) {
                Icon(Icons.Default.Add, contentDescription = "新建项目")
            }
        }

        // 导出 / 重命名结果提示（首页没有编辑器的 MessageBar，这里本地提示）
        homeNotice?.let { text ->
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }

        if (projects.isEmpty()) {
            EmptyState(
                title = "还没有项目",
                subtitle = "创建新项目，或克隆一个 Git 仓库",
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = {
                        cloneNotice = null
                        showClone = true
                    },
                ) { Text("克隆仓库") }
                Button(onClick = { showCreate = true }) { Text("新建项目") }
            }
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
                        onExport = {
                            pendingExport = meta
                            exportLauncher.launch(null)
                        },
                        onRename = { pendingRename = meta },
                    )
                }
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

    pendingRename?.let { meta ->
        RenameProjectDialog(
            meta = meta,
            onConfirm = { value ->
                pendingRename = null
                scope.launch {
                    val renamed = runCatching {
                        AppStorage.projects.rename(meta.relativePath, value)
                    }.getOrNull()
                    homeNotice = if (renamed != null) {
                        "已重命名为「${renamed.name}」"
                    } else {
                        "重命名失败：名称可能已存在"
                    }
                    if (renamed != null &&
                        AppState.project.value?.relativePath == meta.relativePath
                    ) {
                        // 重命名的正是当前打开的项目：同步全局状态，编辑器按新路径重建树
                        AppState.open(renamed)
                    }
                    projects = AppStorage.projects.list()
                }
            },
            onDismiss = { pendingRename = null },
        )
    }
}

/** 项目卡片：整卡打开，长按弹出菜单（打开 / 重命名 / 导出 / 删除）——删除不再占常驻图标位。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProjectCard(
    meta: ProjectMeta,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
    onRename: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onOpen,
                    onLongClick = { menuOpen = true },
                ),
        ) {
            Row(
                modifier = Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 14.dp),
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
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
            ) {
                DropdownMenuItem(
                    text = { Text("打开") },
                    leadingIcon = { Icon(Icons.Default.Folder, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onOpen()
                    },
                )
                DropdownMenuItem(
                    text = { Text("重命名") },
                    leadingIcon = {
                        Icon(Icons.Default.DriveFileRenameOutline, contentDescription = null)
                    },
                    onClick = {
                        menuOpen = false
                        onRename()
                    },
                )
                DropdownMenuItem(
                    text = { Text("导出到…") },
                    leadingIcon = { Icon(Icons.Default.FileDownload, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onExport()
                    },
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
                    onClick = {
                        menuOpen = false
                        onDelete()
                    },
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

/** 重命名项目输入弹窗（项目卡片长按菜单「重命名」）。 */
@Composable
private fun RenameProjectDialog(
    meta: ProjectMeta,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(meta.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名项目") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("项目名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim()) },
                enabled = name.isNotBlank(),
            ) { Text("确定") }
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
