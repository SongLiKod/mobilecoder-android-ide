package com.mobilecoder.ide.core.storage

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.mobilecoder.ide.core.common.theme.AppThemeMode
import com.mobilecoder.ide.core.common.theme.ThemePersistence
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 主题持久化（DataStore 实现），由 AppStorage 提供给 ThemeManager。
 * KEY 与三模式枚举名一一对应（TECH.md 3.1/3.2）。
 */
class ThemePersistenceImpl(
    private val dataStore: DataStore<Preferences>,
) : ThemePersistence {

    override fun load(): Flow<String> =
        dataStore.data.map { it[KEY] ?: AppThemeMode.SYSTEM.name }

    override suspend fun save(mode: AppThemeMode) {
        dataStore.edit { it[KEY] = mode.name }
    }

    companion object {
        val KEY: Preferences.Key<String> = stringPreferencesKey("theme_mode")
    }
}
