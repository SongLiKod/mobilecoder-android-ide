package com.mobilecoder.ide

import com.mobilecoder.ide.core.storage.AppStorage
import com.mobilecoder.ide.core.storage.ProjectMeta
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 全局应用状态：当前打开的项目。
 *
 * 所有 feature 页面（编辑器 / 终端 / CLI / Git / 构建）都从这里读取工作目录，
 * 保证「一个项目上下文贯穿全部功能」。
 */
object AppState {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _project = MutableStateFlow<ProjectMeta?>(null)
    val project: StateFlow<ProjectMeta?> = _project.asStateFlow()

    /** 当前项目工作目录绝对路径；未打开项目时返回空串。 */
    val projectPath: String
        get() = _project.value?.let { AppStorage.projects.projectDir(it.relativePath).path } ?: ""

    val projectName: String
        get() = _project.value?.name ?: ""

    /** 打开项目（更新最近打开时间并作为全局工作目录）。 */
    fun open(meta: ProjectMeta) {
        _project.value = meta
        scope.launch { runCatching { AppStorage.projects.markOpened(meta.relativePath) } }
        scope.launch { runCatching { AppStorage.preferences.setLastProjectPath(meta.relativePath) } }
    }

    /** 从持久化恢复上次打开的项目（冷启动）。 */
    suspend fun restore() {
        val relative = AppStorage.preferences.lastProjectPath()
        if (relative.isBlank()) return
        val meta = AppStorage.projects.find(relative) ?: return
        _project.value = meta
    }

    fun clear() {
        _project.value = null
    }
}
