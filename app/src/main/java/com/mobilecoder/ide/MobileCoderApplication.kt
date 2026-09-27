package com.mobilecoder.ide

import android.app.Application
import com.mobilecoder.ide.core.common.theme.AppThemeMode
import com.mobilecoder.ide.core.common.theme.ThemeManager
import com.mobilecoder.ide.core.nativebridge.NativeRuntime
import com.mobilecoder.ide.core.storage.AppStorage
import com.mobilecoder.ide.feature.ai.AiController
import com.mobilecoder.ide.feature.build.BuildRunner
import com.mobilecoder.ide.feature.cli.CliController
import com.mobilecoder.ide.feature.git.GitController
import com.mobilecoder.ide.feature.ssh.SshController
import com.mobilecoder.ide.feature.terminal.TerminalManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 应用主入口（TECH.md 7：app 负责主入口、主题管理、全局导航）。
 *
 * 启动顺序（全部非阻塞，保证冷启动 ≤2s / TECH.md 8）：
 *  1. 存储层（DataStore、项目索引、密钥库）
 *  2. 主题管理器（注入持久化并恢复上次主题）
 *  3. 进程环境变量（HOME/PATH/ANDROID_HOME，供终端与 CLI 子进程继承）
 *  4. 各 feature 的进程级服务（终端会话、CLI 队列、Git 会话、构建队列）
 *  5. 恢复上次打开的项目
 */
class MobileCoderApplication : Application() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 全局主题管理器：三模式（浅色 / 深色 / 跟随系统）。 */
    val themeManager: ThemeManager by lazy {
        ThemeManager(
            persistence = AppStorage.themePersistence,
            initialMode = AppThemeMode.SYSTEM,
        )
    }

    override fun onCreate() {
        super.onCreate()

        // 1) 数据层
        AppStorage.init(this)
        AppStorage.paths.ensure()

        // 2) 主题：注入 DataStore 持久化并异步恢复
        scope.launch { runCatching { themeManager.restore() } }

        // 3) 进程环境（终端 PTY / CLI 子进程继承）
        NativeRuntime.ensureProcessEnvironment(this)

        // 4) feature 进程级服务
        runCatching { CliController.init(this) }
        runCatching { TerminalManager.init(this) }
        runCatching { GitController.init(this) }
        runCatching { SshController.init(this) }
        runCatching { BuildRunner.init(this) }
        runCatching { AiController.init(this) }

        // 5) 恢复上次项目
        scope.launch { runCatching { AppState.restore() } }
    }
}
