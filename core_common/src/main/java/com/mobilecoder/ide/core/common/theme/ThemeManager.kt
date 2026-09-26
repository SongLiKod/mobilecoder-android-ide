package com.mobilecoder.ide.core.common.theme

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 主题持久化数据源（接口定义在 common 层，DataStore 实现位于 core_storage，
 * 依赖倒置以便 common 层保持纯 Kotlin 可单测）。
 */
interface ThemePersistence {
    fun load(): Flow<String>
    suspend fun save(mode: AppThemeMode)
}

/**
 * 主题模式管理器：持有当前 [AppThemeMode]，对外暴露 StateFlow 供全站 UI 订阅。
 * 主题切换全局无闪烁、无重启（硬性技术指标 TECH.md 8）。
 */
class ThemeManager(
    private val persistence: ThemePersistence? = null,
    initialMode: AppThemeMode = AppThemeMode.SYSTEM,
) {

    private val _mode = MutableStateFlow(initialMode)

    /** 当前主题模式（三模式之一） */
    val mode: StateFlow<AppThemeMode> = _mode.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 切换主题并异步持久化 */
    fun setMode(mode: AppThemeMode) {
        if (_mode.value == mode) return
        _mode.value = mode
        val target = persistence ?: return
        scope.launch { target.save(mode) }
    }

    /** 从持久化存储恢复主题模式（启动时调用一次） */
    suspend fun restore() {
        val target = persistence ?: return
        target.load().collect { saved ->
            _mode.value = AppThemeMode.fromName(saved)
        }
    }

    companion object {
        /**
         * 主题切换逻辑（TECH.md 3.2）：
         * LIGHT 强制浅色、DARK 强制深色、SYSTEM 跟随系统。
         */
        fun resolveIsDark(mode: AppThemeMode, systemIsDark: Boolean): Boolean = when (mode) {
            AppThemeMode.LIGHT -> false
            AppThemeMode.DARK -> true
            AppThemeMode.SYSTEM -> systemIsDark
        }
    }
}
