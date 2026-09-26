package com.mobilecoder.ide.core.common.theme

/**
 * 全局主题模式（三模式）—— 对应 TECH.md 3.1 主题枚举定义。
 *
 * - [LIGHT]：强制浅色，忽略系统
 * - [DARK]：强制深色，忽略系统
 * - [SYSTEM]：监听系统 UiModeManager 动态跟随
 */
enum class AppThemeMode {
    LIGHT,
    DARK,
    SYSTEM;

    companion object {
        /** 从持久化字符串还原主题模式，未知值回退 SYSTEM。 */
        fun fromName(name: String?): AppThemeMode =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: SYSTEM
    }
}
