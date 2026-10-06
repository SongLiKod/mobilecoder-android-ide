package com.mobilecoder.ide.feature.editor

import android.content.Context
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.mobilecoder.ide.core.storage.AppPreferences
import com.mobilecoder.ide.core.storage.AppStorage
import com.mobilecoder.ide.core.storage.FileNode
import com.mobilecoder.ide.core.storage.FileRepository
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
    )

    /** 文件内查找状态。 */
    data class FindState(
        val open: Boolean = false,
        val query: String = "",
        val index: Int = 0,
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

    /** 撤销 / 重做可用状态（对应活动文件，供工具栏按钮点亮）。 */
    data class HistoryAvailability(val canUndo: Boolean, val canRedo: Boolean)

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

    private val _history = MutableStateFlow(HistoryAvailability(false, false))
    val history: StateFlow<HistoryAvailability> = _history.asStateFlow()

    private val _focusTick = MutableStateFlow(0)
    val focusTick: StateFlow<Int> = _focusTick.asStateFlow()

    /** 每个文件独立的撤销 / 重做历史。 */
    private val histories = HashMap<String, EditHistory<TextFieldValue>>()

    private var initialized = false
    private var settingsLoaded = false

    /**
     * 首次加载树后是否需要「默认全部折叠」（仅在 setProject 置位，refreshTree 消费）。
     */
    private var collapseByDefault = false
    private val analysisJobs = HashMap<String, Job>()
    private var saveJob: Job? = null
    private var findJob: Job? = null
    private var searchJob: Job? = null
    private var messageJob: Job? = null

    // ------------------------------------------------------------------
    // 初始化
    // ------------------------------------------------------------------

    /** 幂等初始化（AppStorage.init 幂等），并加载编辑器设置。 */
    fun ensureInit(context: Context) {
        if (!initialized) {
            initialized = true
            runCatching { AppStorage.init(context.applicationContext) }
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
            )
            val showHiddenChanged = loaded.showHiddenFiles != _settings.value.showHiddenFiles
            val hiddenNamesChanged = loaded.hiddenNames != _settings.value.hiddenNames
            _settings.value = loaded
            applyHiddenNames(loaded.hiddenNames)
            // 持久化的「显示隐藏文件 / 不显示名单」偏好晚于首次建树加载完成时，按它重建一次
            if (showHiddenChanged || hiddenNamesChanged) refreshTree()
        }
    }

    /** 把「不显示」名单注入 [FileRepository]（树 / 检索 / 快速打开 / AI 工具同源生效）。 */
    private fun applyHiddenNames(names: List<String>) {
        FileRepository.customHiddenNames = names.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
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

    /** 绑定当前项目（切换项目时关闭旧项目 Tab 并重建文件树）。 */
    fun setProject(path: String) {
        if (path.isBlank() || _projectRoot.value == path) return
        _projectRoot.value = path
        _collapsedDirs.value = emptySet()
        collapseByDefault = true // 新项目树默认全部折叠（refreshTree 消费）
        _tabs.value = _tabs.value.filter { it.path.startsWith(path) }
        histories.keys.retainAll(_tabs.value.mapTo(HashSet()) { it.path })
        if (_tabs.value.none { it.path == _activePath.value }) {
            _activePath.value = _tabs.value.lastOrNull()?.path
        }
        refreshHistory()
        refreshTree()
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

    /** 打开文件（[targetLine] > 0 时打开后跳转到该行）。 */
    fun openFile(file: File, targetLine: Int = 0) {
        val path = file.absolutePath
        locateInTree(path)
        val existing = _tabs.value.firstOrNull { it.path == path }
        if (existing != null) {
            _activePath.value = path
            refreshHistory()
            if (targetLine > 1) jumpTo(path, targetLine, 1)
            return
        }
        scope.launch {
            val text = withContext(Dispatchers.IO) { FileRepository.readText(file) }
            val editable = text != null
            val content = text ?: ""
            val root = _projectRoot.value
            val relative = if (root != null && path.startsWith(root)) {
                path.removePrefix(root).removePrefix("/")
            } else {
                file.name
            }
            val offset = if (targetLine > 1) offsetOfLine(content, targetLine) else 0
            val tab = EditorTab(
                path = path,
                name = file.name,
                relativePath = relative,
                language = Language.of(file.name),
                value = TextFieldValue(content, TextRange(offset.coerceIn(0, content.length))),
                savedText = content,
                editable = editable,
            )
            _tabs.value = _tabs.value + tab
            _activePath.value = path
            refreshHistory()
            scheduleAnalysis(path, content, tab.language)
        }
    }

    fun activate(path: String) {
        if (_tabs.value.any { it.path == path }) {
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
        scheduleAnalysis(path, value.text, current.language)
        scheduleAutoSave(path)
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
            _tabs.value = _tabs.value.map {
                if (it.path == path) {
                    it.copy(analysis = CodeAnalysisResult(emptyList(), emptyList()))
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
            _tabs.value = _tabs.value.map {
                if (it.path == path) it.copy(analysis = result) else it
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
