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

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _find = MutableStateFlow(FindState())
    val find: StateFlow<FindState> = _find.asStateFlow()

    private val _search = MutableStateFlow(ProjectSearchState())
    val search: StateFlow<ProjectSearchState> = _search.asStateFlow()

    private var initialized = false
    private var settingsLoaded = false
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
            _settings.value = EditorSettings(
                fontSize = runCatching { prefs.editorFontSize() }.getOrDefault(14),
                lineNumbers = runCatching { prefs.editorLineNumbers() }.getOrDefault(true),
                wordWrap = runCatching { prefs.editorWordWrap() }.getOrDefault(false),
                autoSave = runCatching { prefs.editorAutoSave() }.getOrDefault(true),
            )
        }
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

    /** 绑定当前项目（切换项目时关闭旧项目 Tab 并重建文件树）。 */
    fun setProject(path: String) {
        if (path.isBlank() || _projectRoot.value == path) return
        _projectRoot.value = path
        _collapsedDirs.value = emptySet()
        _tabs.value = _tabs.value.filter { it.path.startsWith(path) }
        if (_tabs.value.none { it.path == _activePath.value }) {
            _activePath.value = _tabs.value.lastOrNull()?.path
        }
        refreshTree()
    }

    fun refreshTree() {
        val root = _projectRoot.value ?: return
        scope.launch {
            val nodes = withContext(Dispatchers.IO) {
                runCatching {
                    FileRepository.tree(File(root), maxDepth = 8, showHidden = false)
                }.getOrDefault(emptyList())
            }
            if (_projectRoot.value == root) _tree.value = nodes
        }
    }

    fun toggleDirectory(path: String) {
        _collapsedDirs.value = if (path in _collapsedDirs.value) {
            _collapsedDirs.value - path
        } else {
            _collapsedDirs.value + path
        }
    }

    // ------------------------------------------------------------------
    // 打开 / 关闭 / 保存
    // ------------------------------------------------------------------

    /** 打开文件（[targetLine] > 0 时打开后跳转到该行）。 */
    fun openFile(file: File, targetLine: Int = 0) {
        val path = file.absolutePath
        val existing = _tabs.value.firstOrNull { it.path == path }
        if (existing != null) {
            _activePath.value = path
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
            scheduleAnalysis(path, content, tab.language)
        }
    }

    fun activate(path: String) {
        if (_tabs.value.any { it.path == path }) _activePath.value = path
    }

    fun closeTab(path: String) {
        val index = _tabs.value.indexOfFirst { it.path == path }
        if (index < 0) return
        analysisJobs.remove(path)?.cancel()
        val rest = _tabs.value.filterNot { it.path == path }
        _tabs.value = rest
        if (_activePath.value == path) {
            _activePath.value = if (rest.isEmpty()) {
                null
            } else {
                rest[(index - 1).coerceIn(0, rest.size - 1)].path
            }
        }
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
        _tabs.value = _tabs.value.map {
            if (it.path == path) it.copy(value = value) else it
        }
        scheduleAnalysis(path, value.text, current.language)
        scheduleAutoSave(path)
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
