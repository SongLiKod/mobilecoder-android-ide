package com.mobilecoder.ide.feature.editor

import android.content.Context
import android.net.Uri
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.mobilecoder.ide.core.storage.AppPreferences
import com.mobilecoder.ide.core.storage.AppStorage
import com.mobilecoder.ide.core.storage.CodeSnippet
import com.mobilecoder.ide.core.storage.FileNode
import com.mobilecoder.ide.core.storage.FileRepository
import com.mobilecoder.ide.core.storage.SafImport
import com.mobilecoder.ide.core.storage.SnippetStore
import com.mobilecoder.ide.feature.ai.AiCompletion
import com.mobilecoder.ide.feature.preview.PreviewController
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 编辑器进程级单例状态（PRD 2.2）。
 *
 * 持有打开的文件 Tab（含脏标记）、活动文件、每文件文本与光标位置、编辑器设置、
 * 文件树、检索状态与实时检查结果；跨底部导航切换时状态天然保留。
 */
object EditorController {

    /** 一个已打开的文件（Tab）。 */
    data class EditorTab(
        val path: String,
        val name: String,
        val relativePath: String,
        val language: Language,
        val value: TextFieldValue,
        val savedText: String,
        val editable: Boolean,
        val collapsedFolds: Set<Int> = emptySet(),
        val analysis: CodeAnalysisResult = CodeAnalysisResult(emptyList(), emptyList()),
        val scrollY: Int = 0,
    ) {
        val dirty: Boolean get() = editable && value.text != savedText
        val totalLines: Int get() = value.text.count { it == '\n' } + 1
    }

    /** 编辑器设置（持久化到 AppPreferences）。 */
    data class EditorSettings(
        val fontSize: Int = 14,
        val lineNumbers: Boolean = true,
        val wordWrap: Boolean = false,
        val autoSave: Boolean = true,
        val showHiddenFiles: Boolean = true,
        /** 文件树「不显示」的文件 / 目录名（按名匹配任意层级），默认构建产物三件套。 */
        val hiddenNames: List<String> = FileRepository.DEFAULT_HIDDEN_NAMES.toList(),
        /** 「不支持在编辑器里打开」的扩展名（小写、不带点），打开前拦截以防卡死。 */
        val unopenableExts: List<String> = FileRepository.DEFAULT_UNOPENABLE_EXTS.toList(),
        /** 单个文件可打开的大小上限（MB），超过即提示不支持预览/编辑，默认 10。 */
        val maxOpenMb: Int = 10,
        /** 侧边栏「实时预览」窗格总开关（Issue 13），设置页可关，默认开。 */
        val previewPane: Boolean = true,
        /** 「AI 行内补全」总开关（光标处出幽灵文本），默认开。 */
        val aiComplete: Boolean = true,
    )

    /**
     * 文件内查找状态。
     *
     * @param replaceOpen 替换行是否展开（默认收起，只查找不打扰）
     * @param replace     替换内容（字面量，不支持正则 / 变量展开）
     */
    data class FindState(
        val open: Boolean = false,
        val query: String = "",
        val index: Int = 0,
        val replaceOpen: Boolean = false,
        val replace: String = "",
    )

    /** 全局项目检索状态。 */
    data class ProjectSearchState(
        val open: Boolean = false,
        val query: String = "",
        val running: Boolean = false,
        val hits: List<FileRepository.SearchHit> = emptyList(),
    )

    /** 快速打开文件面板状态（files = 相对项目根的路径，字典序）。 */
    data class OpenPickerState(
        val open: Boolean = false,
        val loading: Boolean = false,
        val files: List<String> = emptyList(),
    )

    /**
     * 片段候选面板（Issue 7）：敲触发词时在编辑区底部弹出。
     *
     * [word] 为光标前的触发词干、[start] 为它在正文里的起始下标（插入时整段替换），
     * [items] 为空即收起——UI 只订阅本状态，不必各自算匹配。
     */
    data class SnippetPanelState(
        val open: Boolean = false,
        val word: String = "",
        val start: Int = 0,
        val items: List<CodeSnippet> = emptyList(),
    )

    /**
     * 插入后的跳位会话：[stops] 为各跳位在正文里的下标，[index] 为当前跳位。
     * 用户继续输入时由 [SnippetEngine.adjustStops] 平移；越过末位即结束。
     */
    data class SnippetSession(
        val path: String,
        val stops: List<Int>,
        val index: Int,
    ) {
        val current: Int get() = stops.getOrElse(index) { stops.lastOrNull() ?: 0 }
        val total: Int get() = stops.size
    }

    /** 撤销 / 重做可用状态（对应活动文件，供工具栏按钮点亮）。 */
    data class HistoryAvailability(val canUndo: Boolean, val canRedo: Boolean)

    /**
     * 行内 AI 补全建议（类 Copilot 的「幽灵文本」）。
     *
     * @param path       归属文件（Tab 切走即失效，渲染前比对）
     * @param text       待插入的补全文本
     * @param start      插入点（发起请求时光标下标）
     * @param anchorText 发起请求时的正文；正文一变即视为过期（不展示、不接受）
     */
    data class InlineSuggestion(
        val path: String,
        val text: String,
        val start: Int,
        val anchorText: String,
    )

    /**
     * 「AI 重构」请求（文件树长按菜单 / 编辑器 ⋮ 菜单触发）。
     *
     * 编辑器侧只负责发请求，弹窗与 AI 调用由 AiRefactorDialog 承接（见 EditorScreen）。
     *
     * @param path 目标文件绝对路径
     * @param selectionStart 选区起始下标；负数 = 整文件重构
     * @param selectionEnd   选区结束下标（不含）
     */
    data class RefactorRequest(
        val path: String,
        val selectionStart: Int = -1,
        val selectionEnd: Int = -1,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _tabs = MutableStateFlow<List<EditorTab>>(emptyList())
    val tabs: StateFlow<List<EditorTab>> = _tabs.asStateFlow()

    private val _activePath = MutableStateFlow<String?>(null)
    val activePath: StateFlow<String?> = _activePath.asStateFlow()

    private val _settings = MutableStateFlow(EditorSettings())
    val settings: StateFlow<EditorSettings> = _settings.asStateFlow()

    private val _projectRoot = MutableStateFlow<String?>(null)
    val projectRoot: StateFlow<String?> = _projectRoot.asStateFlow()

    private val _tree = MutableStateFlow<List<FileNode>>(emptyList())
    val tree: StateFlow<List<FileNode>> = _tree.asStateFlow()

    private val _collapsedDirs = MutableStateFlow<Set<String>>(emptySet())
    val collapsedDirs: StateFlow<Set<String>> = _collapsedDirs.asStateFlow()

    /** 定位目标：最近一次打开 / 切换到的文件（文件树据此展开 + 滚动 + 高亮）。 */
    private val _locatePath = MutableStateFlow<String?>(null)
    val locatePath: StateFlow<String?> = _locatePath.asStateFlow()

    /** 定位脉冲：每次打开 / 切换文件 +1，文件树侧靠它（重新）触发滚动。 */
    private val _locateTick = MutableStateFlow(0)
    val locateTick: StateFlow<Int> = _locateTick.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _find = MutableStateFlow(FindState())
    val find: StateFlow<FindState> = _find.asStateFlow()

    private val _search = MutableStateFlow(ProjectSearchState())
    val search: StateFlow<ProjectSearchState> = _search.asStateFlow()

    private val _openPicker = MutableStateFlow(OpenPickerState())
    val openPicker: StateFlow<OpenPickerState> = _openPicker.asStateFlow()

    private val _snippetPanel = MutableStateFlow(SnippetPanelState())
    val snippetPanel: StateFlow<SnippetPanelState> = _snippetPanel.asStateFlow()

    private val _snippetSession = MutableStateFlow<SnippetSession?>(null)
    val snippetSession: StateFlow<SnippetSession?> = _snippetSession.asStateFlow()

    /** 全部片段（内置 + 自定义，已按覆盖规则合并），UI 直接订阅。 */
    val snippets: StateFlow<List<CodeSnippet>> = SnippetStore.snippets

    private val _history = MutableStateFlow(HistoryAvailability(false, false))
    val history: StateFlow<HistoryAvailability> = _history.asStateFlow()

    private val _focusTick = MutableStateFlow(0)
    val focusTick: StateFlow<Int> = _focusTick.asStateFlow()

    /** 行内 AI 补全建议（null = 无建议；UI 只订阅，不用自己算过期）。 */
    private val _inlineSuggestion = MutableStateFlow<InlineSuggestion?>(null)
    val inlineSuggestion: StateFlow<InlineSuggestion?> = _inlineSuggestion.asStateFlow()

    /** 「AI 重构」待处理请求（null = 无；EditorScreen 订阅后弹出重构对话框）。 */
    private val _refactorRequest = MutableStateFlow<RefactorRequest?>(null)
    val refactorRequest: StateFlow<RefactorRequest?> = _refactorRequest.asStateFlow()

    /** 每个文件独立的撤销 / 重做历史。 */
    private val histories = HashMap<String, EditHistory<TextFieldValue>>()

    private var initialized = false
    private var settingsLoaded = false

    /** 应用上下文（[ensureInit] 注入），供导出等无界面 IO 使用。 */
    private var appContext: Context? = null

    /**
     * 首次加载树后是否需要「默认全部折叠」（仅在 setProject 置位，refreshTree 消费）。
     */
    private var collapseByDefault = false
    private val analysisJobs = HashMap<String, Job>()
    private var saveJob: Job? = null
    private var findJob: Job? = null
    private var searchJob: Job? = null
    private var messageJob: Job? = null

    /** 行内补全的防抖请求（每次键入都会取消上一次再重排）。 */
    private var completionJob: Job? = null

    /**
     * 接受补全后的一次性抑制：接受本身也是一次正文变化，若不抑制会立刻
     * 又发一轮补全请求（用户什么都没敲却不断冒出新建议）。
     */
    private var completionSuppress = false

    // ---- 编译级诊断（构建页解析出的错误 → 编辑器标注）----
    // 结构级 lint 与外部诊断分表存放、合并写回 analysis.issues，避免互相覆盖；
    // 两张表都在 [diagLock] 下读写（scheduleAnalysis 与 setDiagnostics 可能并发）。
    private val diagLock = Any()
    private val structuralIssues = HashMap<String, List<EditorIssue>>()
    private val diagnostics = HashMap<String, MutableList<EditorIssue>>()

    // ------------------------------------------------------------------
    // 初始化
    // ------------------------------------------------------------------

    /** 幂等初始化（AppStorage.init 幂等），并加载编辑器设置与片段库。 */
    fun ensureInit(context: Context) {
        if (!initialized) {
            initialized = true
            appContext = context.applicationContext
            runCatching { AppStorage.init(context.applicationContext) }
            runCatching { PreviewController.ensureInit(context.applicationContext) }
            // 片段库异步加载：面板打开前就绪即可，不阻塞首帧
            scope.launch { runCatching { SnippetStore.ensureLoaded() } }
        }
        if (!settingsLoaded) {
            settingsLoaded = true
            loadSettings()
        }
    }

    private fun loadSettings() {
        scope.launch {
            val prefs = preferencesOrNull() ?: return@launch
            val loaded = EditorSettings(
                fontSize = runCatching { prefs.editorFontSize() }.getOrDefault(14),
                lineNumbers = runCatching { prefs.editorLineNumbers() }.getOrDefault(true),
                wordWrap = runCatching { prefs.editorWordWrap() }.getOrDefault(false),
                autoSave = runCatching { prefs.editorAutoSave() }.getOrDefault(true),
                showHiddenFiles = runCatching { prefs.editorShowHiddenFiles() }.getOrDefault(true),
                hiddenNames = runCatching { prefs.editorHiddenNames() }
                    .getOrDefault(FileRepository.DEFAULT_HIDDEN_NAMES.toList()),
                unopenableExts = normalizeExts(
                    runCatching { prefs.editorUnopenableExts() }
                        .getOrDefault(FileRepository.DEFAULT_UNOPENABLE_EXTS.toList()),
                ),
                maxOpenMb = runCatching { prefs.editorMaxOpenMb() }.getOrDefault(10)
                    .coerceIn(1, 1024),
                previewPane = runCatching { prefs.editorPreviewPane() }.getOrDefault(true),
                aiComplete = runCatching { prefs.editorAiComplete() }.getOrDefault(true),
            )
            val showHiddenChanged = loaded.showHiddenFiles != _settings.value.showHiddenFiles
            val hiddenNamesChanged = loaded.hiddenNames != _settings.value.hiddenNames
            _settings.value = loaded
            applyHiddenNames(loaded.hiddenNames)
            applyUnopenableExts(loaded.unopenableExts)
            applyMaxOpenMb(loaded.maxOpenMb)
            // 持久化的「显示隐藏文件 / 不显示名单」偏好晚于首次建树加载完成时，按它重建一次
            if (showHiddenChanged || hiddenNamesChanged) refreshTree()
        }
    }

    /** 把「不显示」名单注入 [FileRepository]（树 / 检索 / 快速打开 / AI 工具同源生效）。 */
    private fun applyHiddenNames(names: List<String>) {
        FileRepository.customHiddenNames = names.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    /** 扩展名名单归一化：去空白、去前导点、转小写、去重。 */
    private fun normalizeExts(exts: List<String>): List<String> = exts
        .map { it.trim().removePrefix(".").lowercase() }
        .filter { it.isNotEmpty() }
        .distinct()

    /** 把「不支持打开」的扩展名注入 [FileRepository]（openFile 读盘前拦截）。 */
    private fun applyUnopenableExts(exts: List<String>) {
        FileRepository.customUnopenableExts = exts.toSet()
    }

    /** 把单文件打开大小上限（MB → 字节）注入 [FileRepository]（openFile 读盘前拦截）。 */
    private fun applyMaxOpenMb(mb: Int) {
        FileRepository.customMaxOpenBytes = mb.coerceIn(1, 1024) * 1024L * 1024L
    }

    private fun preferencesOrNull(): AppPreferences? =
        runCatching { AppStorage.preferences }.getOrNull()

    private fun persist(action: suspend (AppPreferences) -> Unit) {
        scope.launch {
            val prefs = preferencesOrNull() ?: return@launch
            runCatching { action(prefs) }
        }
    }

    // ------------------------------------------------------------------
    // 项目 / 文件树
    // ------------------------------------------------------------------

    /**
     * 初始（eager）建树深度：第 8 层目录的子级不读盘，首次展开时由
     * [loadChildrenOf] 按需补载。这是「目录树显示不完整」的根因修复点——
     * `app/src/main/java/com/.../ui` 的文件在第 9 层，曾被 `maxDepth = 8` 整段截断。
     */
    private const val MAX_EAGER_DEPTH = 8

    /** 「全部展开」整树重建的深度上限：真实源码路径远小于此值，同时给 IO 一个上界。 */
    private const val MAX_EXPAND_DEPTH = 32

    /** 跨文件替换时单个文件的读盘上限（4MB；编辑器里已打开的文件不受此限）。 */
    private const val MAX_REPLACE_BYTES = 4L * 1024 * 1024

    /** 绑定当前项目（切换项目时关闭旧项目 Tab 并重建文件树）。 */
    fun setProject(path: String) {
        if (path.isBlank() || _projectRoot.value == path) return
        _projectRoot.value = path
        _collapsedDirs.value = emptySet()
        // 上一个项目的构建诊断不带到新项目（BuildRunner 侧也要等下次构建才刷新）
        synchronized(diagLock) {
            diagnostics.clear()
            structuralIssues.clear()
        }
        collapseByDefault = true // 新项目树默认全部折叠（refreshTree 消费）
        _tabs.value = _tabs.value.filter { it.path.startsWith(path) }
        histories.keys.retainAll(_tabs.value.mapTo(HashSet()) { it.path })
        if (_tabs.value.none { it.path == _activePath.value }) {
            _activePath.value = _tabs.value.lastOrNull()?.path
        }
        refreshHistory()
        refreshTree()
        // 预览窗格跟随项目：换项目要停掉上一个项目的 dev server 并重新识别预览形态
        runCatching { PreviewController.setProject(path) }
    }

    fun refreshTree() {
        val root = _projectRoot.value ?: return
        val showHidden = _settings.value.showHiddenFiles
        scope.launch {
            val nodes = withContext(Dispatchers.IO) {
                runCatching {
                    FileRepository.tree(File(root), maxDepth = MAX_EAGER_DEPTH, showHidden = showHidden)
                }.getOrDefault(emptyList())
            }
            if (_projectRoot.value != root) return@launch
            _tree.value = nodes
            val dirs = directoryPathsOf(nodes)
            if (collapseByDefault) {
                collapseByDefault = false
                _collapsedDirs.value = dirs
            } else {
                // 普通刷新：保留用户展开状态，仅清掉已不存在目录的残留折叠项
                _collapsedDirs.value = _collapsedDirs.value.intersect(dirs)
            }
        }
    }

    fun toggleDirectory(path: String) {
        val collapsed = _collapsedDirs.value
        if (path in collapsed) {
            _collapsedDirs.value = collapsed - path
            // 展开方向：深层子级可能还没读盘，按需补载（否则展开后是空目录）
            scope.launch { loadChildrenOf(path) }
        } else {
            _collapsedDirs.value = collapsed + path
        }
    }

    /** 全部折叠：树内所有目录收起，只留顶层清单。 */
    fun collapseAll() {
        _collapsedDirs.value = directoryPathsOf(_tree.value)
    }

    /**
     * 全部展开：清空折叠态，并把未物化的深层子级一次性补齐。
     *
     * 只清折叠态是不够的——eager 树到 [MAX_EAGER_DEPTH] 层为止，更深层目录
     * 「展开却为空」（与单目录展开同理），故收尾用一次更深的整树重建（IO 异步）。
     */
    fun expandAll() {
        _collapsedDirs.value = emptySet()
        val root = _projectRoot.value ?: return
        val showHidden = _settings.value.showHiddenFiles
        scope.launch {
            val deep = withContext(Dispatchers.IO) {
                runCatching {
                    FileRepository.tree(File(root), maxDepth = MAX_EXPAND_DEPTH, showHidden = showHidden)
                }.getOrDefault(emptyList())
            }
            if (_projectRoot.value == root) _tree.value = deep
        }
    }

    /**
     * 补载 [dirPath] 的子级并按 DFS 不变式插入树中（幂等）。
     * 目录节点不在树内（刷新未完成 / 树外路径）或子级已物化时为空操作。
     */
    private suspend fun loadChildrenOf(dirPath: String) {
        val tree = _tree.value
        val idx = tree.indexOfFirst { it.file.path == dirPath }
        if (idx < 0 || hasChildrenLoaded(tree, idx)) return
        val dir = tree[idx]
        val children = withContext(Dispatchers.IO) {
            FileRepository.listTreeChildren(
                dir.file,
                showHidden = _settings.value.showHiddenFiles,
                childDepth = dir.depth + 1,
            ).map { it.copy(depth = dir.depth + 1) }
        }
        _tree.value = withChildrenInserted(_tree.value, dirPath, children)
    }

    /**
     * 打开 / 切换文件 → 目录定位（在目录树里找到该文件）：
     *  1. 祖先分支全部展开（折叠集合里摘掉祖先链）；
     *  2. 链式补载路径各级子级（第 9 层以下文件不在 eager 树里，不补载就无从定位）；
     *  3. [locatePath] + [locateTick] 通知文件树滚动到目标行。
     *
     * 树外文件（root 为空或路径不在项目下）不定位。
     */
    private fun locateInTree(path: String) {
        val root = _projectRoot.value?.trimEnd('/')
        if (root.isNullOrEmpty()) return
        if (!path.startsWith("$root/")) return
        val ancestors = ancestorDirPaths(root, path)
        if (ancestors.isNotEmpty()) {
            _collapsedDirs.value = _collapsedDirs.value - ancestors.toSet()
            scope.launch {
                // 链式：先有父目录节点才能读它的子级，故按浅到深逐级补载
                for (ancestor in ancestors) loadChildrenOf(ancestor)
            }
        }
        _locatePath.value = path
        _locateTick.value++
    }

    // ------------------------------------------------------------------
    // 打开 / 关闭 / 保存
    // ------------------------------------------------------------------

    /** 打开文件（[targetLine] > 1 时打开后跳到该行，[targetColumn] 定位列，1 起始）。 */
    fun openFile(file: File, targetLine: Int = 0, targetColumn: Int = 1) {
        val path = file.absolutePath
        val existing = _tabs.value.firstOrNull { it.path == path }
        if (existing != null) {
            locateInTree(path)
            _activePath.value = path
            refreshHistory()
            if (targetLine > 1) jumpTo(path, targetLine, targetColumn)
            return
        }
        scope.launch {
            // 读盘前拦截：不支持打开的类型 / 过大 / 二进制（如 .apk）会把 UI 卡死，
            // 只提示不支持在线预览或编辑，不加载内容。
            val reason = withContext(Dispatchers.IO) { FileRepository.unopenableReason(file) }
            if (reason != null) {
                showMessage("$reason：${file.name}")
                return@launch
            }
            locateInTree(path)
            val content = withContext(Dispatchers.IO) { FileRepository.readText(file) }
            if (content == null) {
                showMessage("文件读取失败，不支持在线预览或编辑：${file.name}")
                return@launch
            }
            val root = _projectRoot.value
            val relative = if (root != null && path.startsWith(root)) {
                path.removePrefix(root).removePrefix("/")
            } else {
                file.name
            }
            val base = if (targetLine > 1) offsetOfLine(content, targetLine) else 0
            val offset = (base + (targetColumn - 1).coerceAtLeast(0))
                .coerceIn(0, content.length)
            val tab = EditorTab(
                path = path,
                name = file.name,
                relativePath = relative,
                language = Language.of(file.name),
                value = TextFieldValue(content, TextRange(offset.coerceIn(0, content.length))),
                savedText = content,
                editable = true,
            )
            _tabs.value = _tabs.value + tab
            _activePath.value = path
            refreshHistory()
            scheduleAnalysis(path, content, tab.language)
        }
    }

    fun activate(path: String) {
        if (_tabs.value.any { it.path == path }) {
            if (_snippetPanel.value.open) dismissSnippetPanel()
            rejectInlineSuggestion()
            _activePath.value = path
            locateInTree(path)
            refreshHistory()
        }
    }

    fun closeTab(path: String) {
        val index = _tabs.value.indexOfFirst { it.path == path }
        if (index < 0) return
        analysisJobs.remove(path)?.cancel()
        histories.remove(path)
        synchronized(diagLock) { structuralIssues.remove(keyOf(path)) }
        if (_snippetSession.value?.path == path) endSnippetSession()
        if (_snippetPanel.value.open && _activePath.value == path) dismissSnippetPanel()
        if (_inlineSuggestion.value?.path == path) rejectInlineSuggestion()
        val rest = _tabs.value.filterNot { it.path == path }
        _tabs.value = rest
        if (_activePath.value == path) {
            _activePath.value = if (rest.isEmpty()) {
                null
            } else {
                rest[(index - 1).coerceIn(0, rest.size - 1)].path
            }
        }
        refreshHistory()
    }

    fun save(path: String) {
        scope.launch { saveNow(path, silent = false) }
    }

    fun saveActive() {
        val path = _activePath.value ?: return
        save(path)
    }

    private suspend fun saveNow(path: String, silent: Boolean) {
        val tab = _tabs.value.firstOrNull { it.path == path } ?: return
        if (!tab.editable) return
        val text = tab.value.text
        val ok = withContext(Dispatchers.IO) {
            runCatching { FileRepository.writeText(File(path), text) }.getOrDefault(false)
        }
        if (ok) {
            _tabs.value = _tabs.value.map {
                if (it.path == path) it.copy(savedText = text) else it
            }
            // 保存成功 → 通知预览窗格刷新（静态 HTML 保存即刷新，见 Issue 13）
            runCatching { PreviewController.notifySaved(path) }
            if (!silent) showMessage("已保存 ${tab.name}")
        } else if (!silent) {
            showMessage("保存失败，请检查存储权限")
        }
    }

    // ------------------------------------------------------------------
    // 快速打开（⋮ 菜单「打开文件」，不必经文件树逐层点开）
    // ------------------------------------------------------------------

    /** 开 / 关快速打开面板；打开时在 IO 线程刷新项目文件清单。 */
    fun setOpenPicker(open: Boolean) {
        if (!open) {
            _openPicker.value = OpenPickerState()
            return
        }
        val root = _projectRoot.value?.trimEnd('/', '\\')
        if (root.isNullOrBlank()) {
            _openPicker.value = OpenPickerState(open = true)
            showMessage("请先在「项目」页打开一个项目")
            return
        }
        _openPicker.value = OpenPickerState(open = true, loading = true)
        scope.launch {
            val files = withContext(Dispatchers.IO) {
                runCatching { FileRepository.listOpenableFiles(File(root)) }
                    .getOrDefault(emptyList())
                    .map { it.path.removePrefix(root).trimStart('/', '\\') }
            }
            // 面板可能已被关掉（或重新打开过），只在仍打开时回填
            if (_openPicker.value.open) {
                _openPicker.value = OpenPickerState(open = true, loading = false, files = files)
            }
        }
    }

    /** 选中快速打开面板里的文件：关面板并打开。 */
    fun openPickerFile(relativePath: String) {
        val root = _projectRoot.value?.trimEnd('/', '\\') ?: return
        setOpenPicker(false)
        openFile(File(root, relativePath))
    }

    // ------------------------------------------------------------------
    // 编辑
    // ------------------------------------------------------------------

    /** 编辑器内容变化（文本变化会触发实时检查与自动保存防抖）。 */
    fun onEditorValue(path: String, value: TextFieldValue) {
        val current = _tabs.value.firstOrNull { it.path == path } ?: return
        if (value.text == current.value.text) {
            if (value.selection != current.value.selection || value.composition != current.value.composition) {
                // 光标挪动（点选 / 拖选）：幽灵文本的插入点已对不上，直接撤掉
                _inlineSuggestion.value = null
                _tabs.value = _tabs.value.map {
                    if (it.path == path) it.copy(value = value) else it
                }
            }
            return
        }
        recordHistory(path, current.value, value.text.length - current.value.text.length)
        _tabs.value = _tabs.value.map {
            if (it.path == path) it.copy(value = value) else it
        }
        adjustSnippetSession(path, current.value.text, value.text)
        refreshSnippetPanel(path, value)
        scheduleAnalysis(path, value.text, current.language)
        scheduleAutoSave(path)
        scheduleCompletion(path, value)
    }

    // ------------------------------------------------------------------
    // AI 行内补全（光标处幽灵文本：Tab 接受 / Esc 拒绝）
    // ------------------------------------------------------------------

    /** 键入停顿多久才发补全请求（太短会把每个字母都变成一次网络请求）。 */
    private const val COMPLETION_DEBOUNCE_MS = 350L

    /**
     * 防抖发起一次补全：停顿 [COMPLETION_DEBOUNCE_MS] 后取光标前后文发**小请求**
     * （`max_tokens` 受控，见 AiClient.complete），回来时正文 / 光标已变则丢弃——
     * 与 [scheduleAnalysis] 同一套过期判定。
     *
     * 任何失败都由 AiCompletion.suggest 内部静默降级为「无建议」，不打断输入。
     */
    private fun scheduleCompletion(path: String, value: TextFieldValue) {
        completionJob?.cancel()
        _inlineSuggestion.value = null
        if (completionSuppress) {
            completionSuppress = false
            return
        }
        if (!_settings.value.aiComplete) return
        if (path != _activePath.value) return
        val tab = _tabs.value.firstOrNull { it.path == path }
        if (tab == null || !tab.editable) return
        // 与片段候选面板 / 跳位会话互斥：面板开着时不打扰
        if (_snippetPanel.value.open) return
        if (_snippetSession.value?.path == path) return
        // 输入法组字中（拼音未上屏）与选中了内容时都不补全
        if (value.composition != null) return
        val cursor = value.selection.start
        if (cursor <= 0 || value.selection.start != value.selection.max) return
        val text = value.text
        if (text.isBlank()) return

        completionJob = scope.launch {
            delay(COMPLETION_DEBOUNCE_MS)
            val suggestion = AiCompletion.suggest(
                prefix = text.take(cursor),
                suffix = text.substring(cursor),
                language = tab.language.label,
            ) ?: return@launch
            val live = _tabs.value.firstOrNull { it.path == path } ?: return@launch
            if (live.value.text != text || live.value.selection.start != cursor) return@launch
            if (_activePath.value != path || !_settings.value.aiComplete) return@launch
            if (_snippetPanel.value.open) return@launch
            _inlineSuggestion.value = InlineSuggestion(path, suggestion, cursor, text)
        }
    }

    /**
     * 接受补全：幽灵文本插入光标处、光标移到插入末尾。
     *
     * 插入前二次校验正文与光标（建议可能在展示后过期），过期则只清状态不插入。
     * 插入是一步撤销（[recordHistory] 按整段 delta 记一次）。
     */
    fun acceptInlineSuggestion() {
        val suggestion = _inlineSuggestion.value ?: return
        completionJob?.cancel()
        _inlineSuggestion.value = null
        val tab = _tabs.value.firstOrNull { it.path == suggestion.path } ?: return
        if (!tab.editable) return
        if (tab.value.text != suggestion.anchorText || tab.value.selection.start != suggestion.start) {
            return
        }
        val text = tab.value.text
        val at = suggestion.start.coerceIn(0, text.length)
        val newText = text.substring(0, at) + suggestion.text + text.substring(at)
        val caret = (at + suggestion.text.length).coerceIn(0, newText.length)
        // 接受本身是一次正文变化：抑制它触发的下一轮补全请求
        completionSuppress = true
        onEditorValue(
            suggestion.path,
            TextFieldValue(text = newText, selection = TextRange(caret), composition = null),
        )
        requestEditorFocus()
    }

    /** 拒绝补全（Esc / 点「忽略」），在途请求一并取消。 */
    fun rejectInlineSuggestion() {
        completionJob?.cancel()
        completionSuppress = false
        _inlineSuggestion.value = null
    }

    // ------------------------------------------------------------------
    // AI 重构（文件树长按菜单 / 编辑器 ⋮ 菜单入口）
    // ------------------------------------------------------------------

    /**
     * 发起一次「AI 重构」：只登记请求，弹窗（发 AI → 看 diff → 确认 → 落盘）
     * 由 EditorScreen 订阅后挂载 AiRefactorDialog。
     *
     * @param path 目标文件绝对路径
     * @param selectionStart 选区起始下标；负数 = 整文件重构
     */
    fun requestRefactor(path: String, selectionStart: Int = -1, selectionEnd: Int = -1) {
        val file = java.io.File(path)
        if (!file.isFile) {
            showMessage("该文件不可重构：$path")
            return
        }
        // 快照与落盘都按「相对项目根」寻址，项目外的文件一律拒绝，避免写到别处
        val root = _projectRoot.value?.trimEnd('/', '\\')
        if (root == null || !path.startsWith("$root/")) {
            showMessage("仅支持项目内文件：${file.name}")
            return
        }
        rejectInlineSuggestion()
        _refactorRequest.value = RefactorRequest(path, selectionStart, selectionEnd)
    }

    /** 关闭「AI 重构」弹窗。 */
    fun clearRefactor() {
        _refactorRequest.value = null
    }

    /**
     * 用给定内容整体替换某文件的编辑态（AI 重构「应用 / 撤销」后回灌）。
     *
     * 磁盘已由重构流程写入，这里同步 [EditorTab.savedText] 让 Tab 立刻变干净；
     * 旧行号下的折叠下标不再可靠，一并清空。
     */
    fun replaceFileContent(path: String, newText: String) {
        val tab = _tabs.value.firstOrNull { it.path == path } ?: return
        if (tab.value.text == newText) return
        recordHistory(path, tab.value, newText.length - tab.value.text.length)
        val caret = tab.value.selection.start.coerceIn(0, newText.length)
        _tabs.value = _tabs.value.map {
            if (it.path == path) {
                it.copy(
                    value = TextFieldValue(text = newText, selection = TextRange(caret), composition = null),
                    savedText = newText,
                    collapsedFolds = emptySet(),
                )
            } else {
                it
            }
        }
        _inlineSuggestion.value = null
        _snippetSession.value = null
        _snippetPanel.value = SnippetPanelState()
        scheduleAnalysis(path, newText, tab.language)
        refreshHistory()
    }

    // ------------------------------------------------------------------
    // 代码片段（Issue 7）
    // ------------------------------------------------------------------

    /**
     * 按光标前的触发词刷新候选面板：有命中才弹，词干吃空（退格到标点 /
     * 空格）即收起。非活动文件不参与（面板只服务正在编辑的那一份）。
     */
    private fun refreshSnippetPanel(path: String, value: TextFieldValue) {
        if (path != _activePath.value) {
            if (_snippetPanel.value.open) _snippetPanel.value = SnippetPanelState()
            return
        }
        val tab = _tabs.value.firstOrNull { it.path == path }
        val trigger = if (tab?.editable == true) {
            SnippetEngine.triggerAt(value.text, value.selection.start)
        } else {
            null
        }
        if (trigger == null) {
            _snippetPanel.value = SnippetPanelState()
            return
        }
        val languageKey = tab?.let { SnippetEngine.languageKeyOf(it.language) }
        val items = SnippetEngine.candidates(
            snippets = snippets.value,
            word = trigger.word,
            languageKey = languageKey,
        )
        _snippetPanel.value = if (items.isEmpty()) {
            SnippetPanelState()
        } else {
            SnippetPanelState(open = true, word = trigger.word, start = trigger.start, items = items)
        }
    }

    /** 手动关闭候选面板（点空白 / 选完即关）。 */
    fun dismissSnippetPanel() {
        _snippetPanel.value = SnippetPanelState()
    }

    /**
     * 插入片段：用展开后的正文整体替换触发词干，光标落到第一个跳位
     * （无跳位则落 `$0` / 末尾），并开启跳位会话供「下一个」继续跳。
     *
     * 插入是**单步撤销**：整段正文替换一次 recordHistory，不会拆成逐字符。
     */
    fun insertSnippet(path: String, snippet: CodeSnippet) {
        val tab = _tabs.value.firstOrNull { it.path == path } ?: return
        if (!tab.editable) return
        val text = tab.value.text
        val cursor = tab.value.selection.start.coerceIn(0, text.length)
        val trigger = SnippetEngine.triggerAt(text, cursor)
        val start = trigger?.start ?: cursor
        val indent = indentOfLine(text, start)
        val expansion = SnippetEngine.expand(snippet.body, indent)
        val newText = text.substring(0, start) + expansion.text + text.substring(cursor)
        val firstStop = expansion.stops.firstOrNull() ?: expansion.cursor
        val caret = (start + firstStop).coerceIn(0, newText.length)
        onEditorValue(
            path,
            TextFieldValue(text = newText, selection = TextRange(caret), composition = null),
        )
        _snippetPanel.value = SnippetPanelState()
        _snippetSession.value = if (expansion.stops.size > 1) {
            SnippetSession(
                path = path,
                stops = expansion.stops.map { (start + it).coerceIn(0, newText.length) },
                index = 0,
            )
        } else {
            null
        }
        requestEditorFocus()
    }

    /** 跳到下一个跳位；越过末位自动结束会话。 */
    fun nextSnippetStop() {
        val session = _snippetSession.value ?: return
        val next = session.index + 1
        if (next >= session.total) {
            endSnippetSession()
            return
        }
        val tab = _tabs.value.firstOrNull { it.path == session.path }
        if (tab == null) {
            endSnippetSession()
            return
        }
        val target = session.stops[next].coerceIn(0, tab.value.text.length)
        _tabs.value = _tabs.value.map {
            if (it.path == session.path) {
                it.copy(value = tab.value.copy(selection = TextRange(target), composition = null))
            } else {
                it
            }
        }
        _snippetSession.value = session.copy(index = next)
        requestEditorFocus()
    }

    /** 手动结束跳位会话（「完成」按钮 / 关文件）。 */
    fun endSnippetSession() {
        _snippetSession.value = null
    }

    /**
     * 用户继续编辑时平移会话跳位（插入后到下一次跳转之间的输入 / 删除）。
     * 文件不匹配或会话不存在时是空操作。
     */
    private fun adjustSnippetSession(path: String, prev: String, cur: String) {
        val session = _snippetSession.value ?: return
        if (session.path != path) return
        val stops = SnippetEngine.adjustStops(prev, cur, session.stops)
        _snippetSession.value = session.copy(stops = stops)
    }

    /** 自定义片段增删改（持久化由 SnippetStore 负责；错误信息直接以轻提示返回）。 */
    fun saveSnippet(
        id: String? = null,
        prefix: String,
        description: String,
        language: String,
        body: String,
    ) {
        scope.launch {
            val error = SnippetStore.upsert(id, prefix, description, language, body)
            if (error == null) {
                showMessage(if (id == null) "已添加片段" else "已保存片段")
            } else {
                showMessage(error)
            }
        }
    }

    fun deleteSnippet(id: String) {
        scope.launch {
            val ok = SnippetStore.remove(id)
            showMessage(if (ok) "已删除片段" else "内置片段不可删除")
        }
    }

    /** 导出全部自定义片段到 [uri]（SAF 建档选择器返回）。 */
    fun exportSnippets(uri: Uri) {
        val context = appContext
        if (context == null) {
            showMessage("导出失败：存储未初始化")
            return
        }
        scope.launch {
            val json = runCatching { SnippetStore.exportJson() }.getOrDefault("")
            if (json.isEmpty()) {
                showMessage("导出失败：没有可导出的自定义片段")
                return@launch
            }
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                        out.write(json.toByteArray(Charsets.UTF_8))
                    } != null
                }.getOrDefault(false)
            }
            showMessage(if (ok) "已导出自定义片段" else "导出失败：目标不可写")
        }
    }

    /** 从 [uri] 导入片段（与现有合并，同「语言 + 触发词」覆盖）。 */
    fun importSnippets(uri: Uri) {
        val context = appContext
        if (context == null) {
            showMessage("导入失败：存储未初始化")
            return
        }
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                }.getOrNull()
            }
            if (text.isNullOrBlank()) {
                showMessage("导入失败：文件不可读")
                return@launch
            }
            val result = runCatching { SnippetStore.importJson(text, replace = false) }
                .getOrElse { showMessage("导入失败：不是有效的片段 JSON"); return@launch }
            showMessage("已导入 ${result.added} 条片段" + if (result.updated > 0) "（覆盖 ${result.updated} 条）" else "")
        }
    }

    /** 当前光标所在行的缩进空白（片段续行对齐用）。 */
    private fun indentOfLine(text: String, offset: Int): String {
        val at = offset.coerceIn(0, text.length)
        val lineStart = if (at == 0) {
            0
        } else {
            text.lastIndexOf('\n', at - 1).let { if (it < 0) 0 else it + 1 }
        }
        var end = lineStart
        while (end < text.length && (text[end] == ' ' || text[end] == '\t')) end++
        return text.substring(lineStart, end)
    }

    // ------------------------------------------------------------------
    // 撤销 / 重做
    // ------------------------------------------------------------------

    private fun recordHistory(path: String, before: TextFieldValue, delta: Int) {
        histories.getOrPut(path) { EditHistory() }.record(before, delta)
        refreshHistory()
    }

    /** 撤销上一步（连续键入合并为一步，粘贴 / 多行删除各自成步）。 */
    fun undo(path: String) = stepHistory(path, redo = false)

    /** 重做一步。 */
    fun redo(path: String) = stepHistory(path, redo = true)

    private fun stepHistory(path: String, redo: Boolean) {
        val history = histories[path] ?: return
        val tab = _tabs.value.firstOrNull { it.path == path } ?: return
        // 撤销 / 重做会整体改写正文，幽灵文本的锚点随之失效
        rejectInlineSuggestion()
        val next = if (redo) history.redo(tab.value) else history.undo(tab.value)
        if (next == null) {
            refreshHistory()
            return
        }
        val language = tab.language
        _tabs.value = _tabs.value.map {
            if (it.path == path) it.copy(value = next) else it
        }
        scheduleAnalysis(path, next.text, language)
        scheduleAutoSave(path)
        refreshHistory()
        requestEditorFocus()
    }

    private fun refreshHistory() {
        val history = histories[_activePath.value]
        _history.value = HistoryAvailability(
            canUndo = history?.canUndo == true,
            canRedo = history?.canRedo == true,
        )
    }

    /** 请求把焦点还给编辑器正文（跳行 / 大纲跳转后恢复光标可见与可输入）。 */
    fun requestEditorFocus() {
        _focusTick.value += 1
    }

    private fun scheduleAnalysis(path: String, text: String, language: Language) {
        analysisJobs.remove(path)?.cancel()
        if (text.isEmpty()) {
            val merged = CodeAnalysisResult(mergedIssues(path, emptyList()), emptyList())
            synchronized(diagLock) { structuralIssues[keyOf(path)] = emptyList() }
            _tabs.value = _tabs.value.map {
                if (it.path == path) {
                    it.copy(analysis = merged)
                } else {
                    it
                }
            }
            return
        }
        analysisJobs[path] = scope.launch {
            delay(400)
            val result = withContext(Dispatchers.Default) { analyzeCode(text, language) }
            val tab = _tabs.value.firstOrNull { it.path == path } ?: return@launch
            if (tab.value.text != text) return@launch
            // 结构级 lint 单独记账，合并交给 mergedIssues（诊断注入时还要再合并一次）
            synchronized(diagLock) { structuralIssues[keyOf(path)] = result.issues }
            val merged = result.copy(issues = mergedIssues(path, result.issues))
            _tabs.value = _tabs.value.map {
                if (it.path == path) it.copy(analysis = merged) else it
            }
        }
    }

    private fun scheduleAutoSave(path: String) {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(800)
            if (_settings.value.autoSave) saveNow(path, silent = true)
        }
    }

    /** 折叠 / 展开指定起始行。 */
    fun toggleFold(path: String, startLine: Int) {
        _tabs.value = _tabs.value.map {
            if (it.path != path) return@map it
            val collapsed = if (startLine in it.collapsedFolds) {
                it.collapsedFolds - startLine
            } else {
                it.collapsedFolds + startLine
            }
            it.copy(collapsedFolds = collapsed)
        }
    }

    fun setAllFolds(path: String, collapse: Boolean) {
        _tabs.value = _tabs.value.map {
            if (it.path != path) return@map it
            it.copy(
                collapsedFolds = if (collapse) {
                    it.analysis.folds.mapTo(HashSet()) { region -> region.startLine }
                } else {
                    emptySet()
                },
            )
        }
    }

    /** 记录某个文件的滚动位置（切 Tab / 离开页面时调用）。 */
    fun setScroll(path: String, scrollY: Int) {
        if (scrollY < 0) return
        _tabs.value = _tabs.value.map {
            if (it.path == path && it.scrollY != scrollY) it.copy(scrollY = scrollY) else it
        }
    }

    fun scrollOf(path: String): Int = _tabs.value.firstOrNull { it.path == path }?.scrollY ?: 0

    /** 跳转到指定行列（问题面板 / 检索结果 / 查找命中共用）。 */    fun jumpTo(path: String, line: Int, column: Int = 1) {
        val tab = _tabs.value.firstOrNull { it.path == path } ?: return
        rejectInlineSuggestion()
        val base = offsetOfLine(tab.value.text, line)
        val offset = (base + column - 1).coerceIn(0, tab.value.text.length)
        _tabs.value = _tabs.value.map {
            if (it.path == path) {
                it.copy(value = tab.value.copy(selection = TextRange(offset), composition = null))
            } else {
                it
            }
        }
    }

    // ------------------------------------------------------------------
    // 设置
    // ------------------------------------------------------------------

    fun setFontSize(value: Int) {
        val size = value.coerceIn(10, 28)
        if (size == _settings.value.fontSize) return
        _settings.value = _settings.value.copy(fontSize = size)
        persist { it.setEditorFontSize(size) }
    }

    fun toggleLineNumbers() {
        val value = !_settings.value.lineNumbers
        _settings.value = _settings.value.copy(lineNumbers = value)
        persist { it.setEditorLineNumbers(value) }
    }

    fun toggleWordWrap() {
        val value = !_settings.value.wordWrap
        _settings.value = _settings.value.copy(wordWrap = value)
        persist { it.setEditorWordWrap(value) }
    }

    fun toggleAutoSave() {
        val value = !_settings.value.autoSave
        _settings.value = _settings.value.copy(autoSave = value)
        persist { it.setEditorAutoSave(value) }
    }

    /** 「AI 行内补全」总开关：关掉即停发补全请求并清掉已展示的建议。 */
    fun toggleAiComplete() {
        val value = !_settings.value.aiComplete
        _settings.value = _settings.value.copy(aiComplete = value)
        persist { it.setEditorAiComplete(value) }
        if (!value) rejectInlineSuggestion()
    }

    /**
     * 侧边栏「实时预览」窗格总开关（Issue 13：设置页可开关）。
     * 关闭时立即收起已展开的窗格（顺带停掉 dev server）。
     */
    fun setPreviewPane(enabled: Boolean) {
        if (enabled == _settings.value.previewPane) return
        _settings.value = _settings.value.copy(previewPane = enabled)
        persist { it.setEditorPreviewPane(enabled) }
        if (!enabled) runCatching { PreviewController.setOpen(false) }
    }

    fun togglePreviewPane() = setPreviewPane(!_settings.value.previewPane)

    /** 文件树「显示隐藏文件」开关：切换后按新口径重建树（保留用户展开状态）。 */
    fun toggleShowHiddenFiles() {
        val value = !_settings.value.showHiddenFiles
        _settings.value = _settings.value.copy(showHiddenFiles = value)
        persist { it.setEditorShowHiddenFiles(value) }
        refreshTree()
    }

    /** 自定义「不显示」的文件 / 目录名：注入过滤口径后重建树（保留用户展开状态）。 */
    fun setHiddenNames(names: List<String>) {
        val cleaned = names.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (cleaned == _settings.value.hiddenNames) return
        _settings.value = _settings.value.copy(hiddenNames = cleaned)
        applyHiddenNames(cleaned)
        persist { it.setEditorHiddenNames(cleaned) }
        refreshTree()
    }

    /**
     * 自定义「不支持在编辑器里打开」的扩展名：注入拦截口径后立即生效。
     * 清空 = 全部尝试打开（大小 / 二进制嗅探两道兜底仍拦截，防卡死）。
     */
    fun setUnopenableExts(exts: List<String>) {
        val cleaned = normalizeExts(exts)
        if (cleaned == _settings.value.unopenableExts) return
        _settings.value = _settings.value.copy(unopenableExts = cleaned)
        applyUnopenableExts(cleaned)
        persist { it.setEditorUnopenableExts(cleaned) }
    }

    /** 自定义单文件打开大小上限（MB）：立即生效，下次打开文件即按新上限拦截。 */
    fun setMaxOpenMb(value: Int) {
        val mb = value.coerceIn(1, 1024)
        if (mb == _settings.value.maxOpenMb) return
        _settings.value = _settings.value.copy(maxOpenMb = mb)
        applyMaxOpenMb(mb)
        persist { it.setEditorMaxOpenMb(mb) }
    }

    // ------------------------------------------------------------------
    // 文件操作（长按菜单 / 拖拽移动）
    // ------------------------------------------------------------------

    fun createFile(dirPath: String, name: String) = fileOp(
        label = "新建文件",
        block = { FileRepository.createFile(File(dirPath), name.trim()) != null },
    )

    fun createDirectory(dirPath: String, name: String) = fileOp(
        label = "新建目录",
        block = { FileRepository.createDirectory(File(dirPath), name.trim()) != null },
    )

    fun rename(path: String, newName: String) {
        val old = File(path)
        fileOp(
            label = "重命名",
            block = { FileRepository.rename(old, newName.trim()) != null },
            after = {
                // 重命名后同步已打开 Tab 的路径
                val trimmed = newName.trim()
                if (trimmed.isEmpty() || trimmed == old.name) return@fileOp
                val newPath = File(old.parentFile, trimmed).absolutePath
                _tabs.value = _tabs.value.map { tab ->
                    if (tab.path != path) tab else {
                        val root = _projectRoot.value
                        tab.copy(
                            path = newPath,
                            name = trimmed,
                            relativePath = root?.let { newPath.removePrefix(it).removePrefix("/") }
                                ?: trimmed,
                        )
                    }
                }
                if (_activePath.value == path) _activePath.value = newPath
            },
        )
    }

    fun delete(path: String) = fileOp(
        label = "删除",
        block = { FileRepository.delete(File(path)) },
        after = { closeTab(path) },
    )

    fun move(path: String, targetDirPath: String) = fileOp(
        label = "移动",
        block = { FileRepository.move(File(path), File(targetDirPath)) != null },
        after = {
            // 移动后同步已打开 Tab 的路径
            val target = File(targetDirPath)
            _tabs.value = _tabs.value.map { tab ->
                if (tab.path != path) tab else {
                    val newPath = File(target, tab.name).absolutePath
                    val root = _projectRoot.value
                    tab.copy(
                        path = newPath,
                        relativePath = root?.let { newPath.removePrefix(it).removePrefix("/") }
                            ?: tab.name,
                    )
                }
            }
            val movedPath = File(target, File(path).name).absolutePath
            if (_activePath.value == path) _activePath.value = movedPath
        },
    )

    /**
     * 导出 [path]（文件，或目录连同其内容递归）到 [treeUri] 指向的系统目录。
     *
     * 目标目录由文件树 / 项目卡片长按菜单「导出到…」经 SAF 选择；导出前先落盘未保存的编辑，
     * 免得导出的是旧版本。目标位于源内部（导出到自身子目录）会拒绝，否则无限递归。
     * [onResult] 在完成时回调（首页没有 MessageBar，靠它本地提示）。
     */
    fun exportTo(path: String, treeUri: Uri, onResult: (Boolean) -> Unit = {}) {
        val context = appContext
        if (context == null) {
            showMessage("导出失败：存储未初始化")
            onResult(false)
            return
        }
        scope.launch {
            val src = File(path)
            val name = src.name
            if (!withContext(Dispatchers.IO) { src.exists() }) {
                showMessage("导出失败：源不存在")
                onResult(false)
                return@launch
            }
            // 导出落盘版本：编辑器里未保存的改动先写盘
            if (_tabs.value.any { it.path == path && it.dirty }) saveNow(path, silent = true)
            val inside = withContext(Dispatchers.IO) {
                runCatching { FileExporter.isTargetInside(treeUri, src) }.getOrDefault(false)
            }
            if (inside) {
                showMessage("导出失败：不能导出到自身目录内")
                onResult(false)
                return@launch
            }
            showMessage("正在导出「$name」…")
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    FileExporter.takePersistablePermission(context, treeUri)
                    FileExporter.export(context, src, treeUri)
                }.getOrDefault(false)
            }
            showMessage(if (ok) "已导出「$name」" else "导出失败：目标目录不可写")
            onResult(ok)
        }
    }

    /**
     * 把 SAF 选择的文件（[uris]，可多个）复制进目录 [dirPath]，同名文件覆盖。
     * 与「导出到…」互为反向：导出是项目 → 系统目录，导入是系统目录 → 项目。
     */
    fun importFiles(dirPath: String, uris: List<Uri>) {
        val context = appContext
        if (context == null) {
            showMessage("导入失败：存储未初始化")
            return
        }
        if (uris.isEmpty()) return
        scope.launch {
            val done = withContext(Dispatchers.IO) {
                runCatching {
                    uris.count { uri -> SafImport.copyDocumentTo(context, uri, File(dirPath)) != null }
                }.getOrDefault(0)
            }
            if (done > 0) {
                refreshTree()
                showMessage(if (done == uris.size) "已导入 $done 个文件" else "已导入 $done/${uris.size} 个文件")
            } else {
                showMessage("导入失败：源不可读或目标不可写")
            }
        }
    }

    /**
     * 把 SAF 选择的目录（[treeUri] 树）连同内容复制进目录 [dirPath]，
     * 作为其子目录（重名自动加序号）；复制完成后刷新文件树。
     */
    fun importFolder(dirPath: String, treeUri: Uri) {
        val context = appContext
        if (context == null) {
            showMessage("导入失败：存储未初始化")
            return
        }
        scope.launch {
            val name = withContext(Dispatchers.IO) {
                runCatching { SafImport.copyTreeTo(context, treeUri, File(dirPath)) }.getOrNull()
            }
            if (name != null) {
                refreshTree()
                showMessage("已导入目录「$name」")
            } else {
                showMessage("导入失败：目录不可读或目标不可写")
            }
        }
    }

    private fun fileOp(label: String, block: () -> Boolean, after: () -> Unit = {}) {
        if (_projectRoot.value == null) return
        scope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { block() }.getOrDefault(false) }
            if (ok) {
                after()
                refreshTree()
                showMessage("${label}成功")
            } else {
                showMessage("${label}失败：名称可能已存在")
            }
        }
    }

    // ------------------------------------------------------------------
    // 查找 / 检索
    // ------------------------------------------------------------------

    fun setFindOpen(open: Boolean) {
        _find.value = if (open) _find.value.copy(open = true) else FindState()
    }

    fun setFindQuery(query: String) {
        _find.value = _find.value.copy(query = query, index = 0)
        findJob?.cancel()
        findJob = scope.launch {
            delay(150)
        }
    }

    fun stepFind(delta: Int, total: Int) {
        if (total <= 0) return
        val current = _find.value.index.coerceIn(0, total - 1)
        val next = ((current + delta) % total + total) % total
        _find.value = _find.value.copy(index = next)
    }

    fun setFindIndex(index: Int) {
        _find.value = _find.value.copy(index = index.coerceAtLeast(0))
    }

    /** 展开 / 收起查找条下方的替换行（FindBar 左侧箭头）。 */
    fun setReplaceOpen(open: Boolean) {
        _find.value = _find.value.copy(replaceOpen = open)
    }

    fun setReplaceQuery(replacement: String) {
        _find.value = _find.value.copy(replace = replacement)
    }

    /**
     * 替换**当前命中**并落到下一处（逐个替换）。
     *
     * 与普通编辑同走 [onEditorValue]：进撤销历史、触发实时检查与自动保存。
     */
    fun replaceCurrent() {
        val path = _activePath.value ?: return
        val tab = _tabs.value.firstOrNull { it.path == path } ?: return
        if (!tab.editable) return
        val find = _find.value
        if (find.query.isEmpty()) {
            showMessage("请输入查找内容")
            return
        }
        val matches = findAll(tab.value.text, find.query)
        if (matches.isEmpty()) {
            showMessage("无匹配")
            return
        }
        val at = find.index.coerceIn(0, matches.size - 1)
        val start = matches[at]
        val next = replaceRange(tab.value.text, start, find.query.length, find.replace)
        val caret = (start + find.replace.length).coerceIn(0, next.length)
        onEditorValue(
            path,
            TextFieldValue(text = next, selection = TextRange(caret), composition = null),
        )
        // 命中列表随替换变化：把下标收在合法范围内（UI 的 total 会同步重算）
        val after = findAll(next, find.query).size
        _find.value = _find.value.copy(index = at.coerceAtMost((after - 1).coerceAtLeast(0)))
    }

    /** 当前文件**全部替换**，并提示替换处数。 */
    fun replaceAllInActive() {
        val path = _activePath.value ?: return
        val tab = _tabs.value.firstOrNull { it.path == path } ?: return
        if (!tab.editable) return
        val find = _find.value
        if (find.query.isEmpty()) {
            showMessage("请输入查找内容")
            return
        }
        val text = tab.value.text
        val matches = findAll(text, find.query)
        if (matches.isEmpty()) {
            showMessage("无匹配")
            return
        }
        val outcome = replaceAllIn(text, find.query, find.replace)
        val caret = remapOffset(
            matches = matches,
            queryLength = find.query.length,
            replacement = find.replace,
            offset = tab.value.selection.start,
        ).coerceAtMost(outcome.text.length)
        onEditorValue(
            path,
            TextFieldValue(text = outcome.text, selection = TextRange(caret), composition = null),
        )
        _find.value = _find.value.copy(index = 0)
        showMessage("已替换 ${outcome.count} 处")
    }

    /**
     * **跨文件替换**（全项目检索面板的「全部替换」）。
     *
     * 遍历复用 [FileRepository.walkFiles]（与检索同口径：跳过隐藏项 / 构建产物、
     * 深度 ≤ 10）；**编辑器里已打开的文件改缓冲区**（撤销历史 + 自动保存），
     * 其余直接落盘 —— 双写会把未保存的编辑覆盖掉。大小写不敏感，与查找口径一致。
     */
    fun replaceAllInProject(query: String, replacement: String) {
        val root = _projectRoot.value
        if (root == null) {
            showMessage("未打开项目")
            return
        }
        if (query.isEmpty()) {
            showMessage("请输入查找内容")
            return
        }
        scope.launch {
            val openTabs = _tabs.value.associateBy { keyOf(it.path) }
            val pending = ArrayList<Pair<String, String>>()
            var files = 0
            var count = 0
            withContext(Dispatchers.IO) {
                FileRepository.walkFiles(File(root)) { file ->
                    val open = openTabs[keyOf(file.path)]
                    if (open != null) {
                        val outcome = replaceAllIn(open.value.text, query, replacement)
                        if (outcome.count > 0) {
                            files++
                            count += outcome.count
                            pending.add(open.path to outcome.text)
                        }
                        return@walkFiles true
                    }
                    if (!FileRepository.isTextFile(file) || file.length() > MAX_REPLACE_BYTES) {
                        return@walkFiles true
                    }
                    val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
                        ?: return@walkFiles true
                    val outcome = replaceAllIn(text, query, replacement)
                    if (outcome.count > 0 && FileRepository.writeText(file, outcome.text)) {
                        files++
                        count += outcome.count
                    }
                    true
                }
            }
            pending.forEach { (path, next) -> applyBufferReplace(path, next) }
            if (files == 0) {
                showMessage("没有找到「$query」")
            } else {
                showMessage("已在 $files 个文件中替换 $count 处")
                setSearchQuery(query) // 内容已变，刷新检索命中
            }
        }
    }

    /** 跨文件替换时把改写后的正文套进打开中的缓冲区（单步撤销）。 */
    private fun applyBufferReplace(path: String, next: String) {
        val tab = _tabs.value.firstOrNull { it.path == path } ?: return
        if (next == tab.value.text) return
        recordHistory(path, tab.value, next.length - tab.value.text.length)
        val caret = tab.value.selection.start.coerceIn(0, next.length)
        _tabs.value = _tabs.value.map {
            if (it.path == path) it.copy(value = TextFieldValue(next, TextRange(caret))) else it
        }
        scheduleAnalysis(path, next, tab.language)
        scheduleAutoSave(path)
    }

    fun setSearchOpen(open: Boolean) {
        _search.value = if (open) {
            _search.value.copy(open = true)
        } else {
            ProjectSearchState()
        }
    }

    fun setSearchQuery(query: String) {
        _search.value = _search.value.copy(query = query)
        searchJob?.cancel()
        val root = _projectRoot.value ?: return
        searchJob = scope.launch {
            delay(300)
            val q = _search.value.query
            if (q.isBlank()) {
                _search.value = _search.value.copy(hits = emptyList(), running = false)
                return@launch
            }
            _search.value = _search.value.copy(running = true)
            val hits = withContext(Dispatchers.IO) {
                runCatching { FileRepository.search(File(root), q, 200) }.getOrDefault(emptyList())
            }
            if (_search.value.query == q) {
                _search.value = _search.value.copy(hits = hits, running = false)
            }
        }
    }

    /** 打开检索命中的文件并跳到行。 */
    fun openHit(hit: FileRepository.SearchHit) {
        setSearchOpen(false)
        openFile(hit.file, targetLine = hit.line)
    }

    // ------------------------------------------------------------------
    // 编译级诊断（构建页解析出的错误 → 编辑器标注）
    // ------------------------------------------------------------------

    /**
     * 注入编译级诊断（AppRoot 收集 `BuildRunner.errors` 后调用；空列表即清空）。
     *
     * [items] 按文件归并与结构级 lint 合并后写回 [EditorTab.analysis]，于是
     * **问题面板、行号槽、状态栏计数、正文下划线四处一次到位**——
     * 与构建页「点击错误 → requestJump」构成「构建 → 定位 → 修改」闭环。
     */
    fun setDiagnostics(items: List<EditorDiagnostic>) {
        scope.launch {
            synchronized(diagLock) {
                diagnostics.clear()
                for (item in items) {
                    if (item.file.isBlank()) continue
                    val list = diagnostics.getOrPut(keyOf(item.file)) { ArrayList() }
                    list.add(EditorIssue(item.line, item.column, item.message, item.isError))
                }
            }
            remergeIssues()
        }
    }

    /** 外部诊断到达后，把每个打开的 Tab 的 analysis.issues 重新合并一遍。 */
    private fun remergeIssues() {
        _tabs.value = _tabs.value.map { tab ->
            val structural = synchronized(diagLock) { structuralIssues[keyOf(tab.path)] }
                ?: tab.analysis.issues
            val merged = tab.analysis.copy(issues = mergedIssues(tab.path, structural))
            if (merged == tab.analysis) tab else tab.copy(analysis = merged)
        }
    }

    /** 结构级 lint + 编译级诊断合并（按行列排序），统一供问题面板 / 行号槽 / 下划线消费。 */
    private fun mergedIssues(path: String, structural: List<EditorIssue>): List<EditorIssue> {
        val extra = synchronized(diagLock) { diagnostics[keyOf(path)] }
        if (extra.isNullOrEmpty()) return structural
        return (structural + extra).sortedWith(compareBy({ it.line }, { it.column }))
    }

    /**
     * 路径归一（诊断表的键）：Android 上 `/data/data` → `/data/user/0` 是符号链接，
     * 构建错误给的是 canonical 路径而 Tab 存的是 absolutePath，不归一就对不上。
     */
    private fun keyOf(path: String): String =
        runCatching { File(path).canonicalPath }.getOrDefault(path)

    // ------------------------------------------------------------------
    // 格式化
    // ------------------------------------------------------------------

    /**
     * 格式化当前文件：proot 内跑 ktfmt（Kotlin / Java）或 prettier（JS / TS / HTML …），
     * 工具由软件市场安装，见 [CodeFormatter]。
     *
     * 走**落盘格式化**（ktfmt 不支持 stdin）：先把未保存的编辑保存掉 → 跑格式化器 →
     * 读回新内容套进缓冲区。整个格式化记为**一步撤销**。
     */
    fun formatActiveFile() {
        val context = appContext ?: return
        val path = _activePath.value ?: return
        val tab = _tabs.value.firstOrNull { it.path == path } ?: return
        if (!tab.editable) {
            showMessage("当前文件不可编辑")
            return
        }
        val root = _projectRoot.value
        if (root == null) {
            showMessage("未打开项目")
            return
        }
        scope.launch {
            if (tab.dirty) saveNow(path, silent = true)
            val result = withContext(Dispatchers.IO) {
                CodeFormatter.format(context, File(root), path)
            }
            if (!result.ok) {
                showMessage(result.message)
                return@launch
            }
            val text = result.text
            if (text == null) {
                showMessage("格式化完成，但读回文件失败：${tab.name}")
                return@launch
            }
            if (!result.changed) {
                showMessage("已是标准格式，无需改动")
                return@launch
            }
            applyFormatted(path, text)
            showMessage("已格式化（${result.tool}）")
        }
    }

    /** 格式化结果写回缓冲区：单步撤销 + 重新分析 + savedText 对齐磁盘。 */
    private fun applyFormatted(path: String, text: String) {
        val tab = _tabs.value.firstOrNull { it.path == path } ?: return
        if (text == tab.value.text) return
        recordHistory(path, tab.value, text.length - tab.value.text.length)
        val caret = tab.value.selection.start.coerceIn(0, text.length)
        _tabs.value = _tabs.value.map {
            if (it.path == path) {
                it.copy(value = TextFieldValue(text, TextRange(caret)), savedText = text)
            } else {
                it
            }
        }
        scheduleAnalysis(path, text, tab.language)
        refreshHistory()
    }

    // ------------------------------------------------------------------
    // 提示
    // ------------------------------------------------------------------

    fun showMessage(text: String) {
        messageJob?.cancel()
        messageJob = scope.launch {
            _message.value = text
            delay(2600)
            _message.value = null
        }
    }

    fun clearMessage() {
        messageJob?.cancel()
        _message.value = null
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /** 返回 1 起始行的起始下标。 */
    fun offsetOfLine(text: String, line: Int): Int {
        if (line <= 1) return 0
        var index = 0
        var current = 1
        while (current < line) {
            val next = text.indexOf('\n', index)
            if (next < 0) return text.length
            index = next + 1
            current++
        }
        return index
    }

    /** 返回光标处的 1 起始行号。 */
    fun lineOfOffset(text: String, offset: Int): Int {
        val limit = offset.coerceIn(0, text.length)
        var line = 1
        for (i in 0 until limit) {
            if (text[i] == '\n') line++
        }
        return line
    }
}

/**
 * 触控编辑辅助：自动缩进。
 *
 * - 回车：复制上一行缩进，上一行以 `{ ( [` 结尾时再进一层
 * - 输入 `} ) ]` 且行首只有空白时反缩进一层
 */
internal fun applySmartEdit(old: TextFieldValue, new: TextFieldValue): TextFieldValue {
    if (new.text == old.text) return new
    val indentUnit = "    "
    val cursor = new.selection.start
    if (cursor <= 0 || cursor <= old.selection.start || cursor > new.text.length) return new

    // 回车换行
    if (new.text[cursor - 1] == '\n') {
        val lineStart = new.text.lastIndexOf('\n', cursor - 2).let { if (it < 0) 0 else it + 1 }
        val currentLine = new.text.substring(lineStart, cursor - 1)
        val indent = currentLine.takeWhile { it == ' ' || it == '\t' }
        val trimmedLine = currentLine.trimEnd()
        val extra = if (trimmedLine.isNotEmpty() && trimmedLine.last() in "{([") indentUnit else ""
        if (indent.isEmpty() && extra.isEmpty()) return new
        val inserted = "\n$indent$extra"
        val text = new.text.substring(0, cursor - 1) + inserted + new.text.substring(cursor)
        return new.copy(
            text = text,
            selection = TextRange(cursor - 1 + inserted.length),
            composition = null,
        )
    }

    // 行首输入闭合符号 → 反缩进
    val typed = new.text[cursor - 1]
    if (typed == ')' || typed == '}' || typed == ']') {
        val lineStart = new.text.lastIndexOf('\n', cursor - 1).let { if (it < 0) 0 else it + 1 }
        val before = new.text.substring(lineStart, cursor - 1)
        if (before.isNotBlank() && before.all { it == ' ' || it == '\t' }) {
            var remove = 0
            while (remove < before.length && remove < indentUnit.length) {
                if (before[before.length - 1 - remove] != ' ') break
                remove++
            }
            if (remove > 0) {
                val text = new.text.removeRange(cursor - 1 - remove, cursor - 1)
                return new.copy(
                    text = text,
                    selection = TextRange(cursor - remove),
                    composition = null,
                )
            }
        }
    }
    return new
}
