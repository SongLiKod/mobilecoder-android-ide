package com.mobilecoder.ide.core.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.mobilecoder.ide.core.common.theme.ThemePersistence
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private val Context.mobileCoderDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "mobilecoder",
)

/**
 * 数据层入口（TECH.md 7 工程模块划分：core_storage 负责数据持久化）。
 *
 * App 启动时调用一次 [init]，此后全工程通过 `AppStorage.xxx` 访问存储能力。
 * 采用进程级单例：DataStore / 密钥库 / 项目索引天然共享。
 */
object AppStorage {

    @Volatile
    private var initialized = false

    private lateinit var appContext: Context

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            appContext = context.applicationContext
            paths = StoragePaths.from(appContext.filesDir, appContext.cacheDir)
            paths.ensure()
            dataStore = appContext.mobileCoderDataStore
            crypto = CryptoBox(dataStore)
            themePersistence = ThemePersistenceImpl(dataStore)
            preferences = AppPreferences(dataStore, crypto)
            projects = ProjectRepository(paths, dataStore)
            sshKeys = SshKeyStore(paths, dataStore, crypto)
            initialized = true
        }
        // 预热：Keystore 探测 + 密钥索引恢复（不阻塞冷启动，TECH.md 8 ≤2s）
        scope.launch {
            runCatching { crypto.ensureKey() }
            runCatching { sshKeys.refresh() }
        }
    }

    private fun requireInit(): Unit = check(initialized) {
        "AppStorage 未初始化：请在 Application.onCreate() 中先调用 AppStorage.init(context)"
    }

    /** 应用上下文（native 初始化、日志等处使用）。 */
    val context: Context
        get() {
            requireInit()
            return appContext
        }

    lateinit var paths: StoragePaths
        private set

    lateinit var dataStore: DataStore<Preferences>
        private set

    lateinit var crypto: CryptoBox
        private set

    lateinit var themePersistence: ThemePersistence
        private set

    lateinit var preferences: AppPreferences
        private set

    lateinit var projects: ProjectRepository
        private set

    lateinit var sshKeys: SshKeyStore
        private set

    /** 存储结构根（`files/`）。 */
    val filesRoot: File
        get() {
            requireInit()
            return paths.files
        }
}
