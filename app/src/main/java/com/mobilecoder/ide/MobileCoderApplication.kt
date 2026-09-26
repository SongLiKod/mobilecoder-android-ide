package com.mobilecoder.ide

import android.app.Application
import com.mobilecoder.ide.core.common.theme.AppThemeMode
import com.mobilecoder.ide.core.common.theme.ThemeManager

/**
 * 应用主入口（TECH.md 7. 工程模块划分：app 承担主入口、主题管理、全局导航）。
 *
 * 持有全局 [ThemeManager] 单实例，全站 UI 订阅同一 StateFlow，
 * 保证主题切换无闪烁、无 Activity 重建（TECH.md 8 硬性技术指标）。
 */
class MobileCoderApplication : Application() {

    /** 全局主题管理器：三模式（浅色 / 深色 / 跟随系统）。 */
    val themeManager: ThemeManager by lazy {
        ThemeManager(persistence = null, initialMode = AppThemeMode.SYSTEM)
    }

    override fun onCreate() {
        super.onCreate()
        // 主题持久化由 core_storage 提供 ThemePersistence 后在此注入并调用 restore()；
        // 尚未注入时 ThemeManager 以 SYSTEM 启动，不阻塞冷启动路径（TECH.md 8：≤ 2s）。
    }
}
