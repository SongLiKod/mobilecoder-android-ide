package com.mobilecoder.ide

import android.app.Application
import com.mobilecoder.ide.core.common.cli.AptCli
import com.mobilecoder.ide.core.common.theme.AppThemeMode
import com.mobilecoder.ide.core.common.theme.ThemeManager
import com.mobilecoder.ide.core.nativebridge.NativeRuntime
import com.mobilecoder.ide.core.storage.AppStorage
import com.mobilecoder.ide.core.storage.OperationSource
import com.mobilecoder.ide.feature.ai.AiController
import com.mobilecoder.ide.feature.build.BuildRunner
import com.mobilecoder.ide.feature.cli.CliBootstrap
import com.mobilecoder.ide.feature.git.GitController
import com.mobilecoder.ide.feature.ssh.SshController
import com.mobilecoder.ide.feature.terminal.TerminalManager
import java.io.File
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
 *  4. 各 feature 的进程级服务（终端会话、apt 命令队列 + 操作历史钩子、Git 会话、构建队列）
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

        // 0) 崩溃留痕：Java 异常写入文件，便于事后定位（原生 SIGSEGV 仍看 tombstone）
        runCatching {
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                runCatching {
                    val dir = File(filesDir, "crash")
                    if (!dir.exists()) dir.mkdirs()
                    File(dir, "last_crash.txt").writeText(
                        "${java.util.Date()}\n" +
                            "thread=${thread.name}\n" +
                            "$throwable\n" +
                            throwable.stackTraceToString(),
                    )
                }
                previous?.uncaughtException(thread, throwable)
            }
        }

        // 1) 数据层
        AppStorage.init(this)
        AppStorage.paths.ensure()

        // 2) 主题：注入 DataStore 持久化并异步恢复
        scope.launch { runCatching { themeManager.restore() } }

        // 3) 进程环境（终端 PTY / CLI 子进程继承）
        NativeRuntime.ensureProcessEnvironment(this)

        // 3.5) 自愈可执行权限：终端里直接敲 `node`/`npm` 不经过安装流程，
        //      只要 files/bin 或 sdk/*/bin 丢了执行位，就只表现为
        //      `sh: …/files/bin/node: Permission denied`，启动时统一补一次（幂等、毫秒级）
        runCatching { com.mobilecoder.ide.feature.build.BuildEnvironment.repairExecutable(this) }

        // 4) feature 进程级服务
        runCatching { CliBootstrap.install() }
        // apt / git 等进程内命令执行完成 → 写入操作历史（底部导航「历史」页的数据源）。
        // core_common 不能依赖 core_storage，所以用钩子把结果回传给存储层。
        AptCli.onExecuted = { line, cwd, exitCode, durationMs ->
            runCatching {
                AppStorage.history.record(
                    command = line,
                    source = OperationSource.CLI,
                    project = cwd.name,
                    exitCode = exitCode,
                    durationMs = durationMs,
                )
            }
        }
        runCatching { TerminalManager.init(this) }
        runCatching { GitController.init(this) }
        runCatching { SshController.init(this) }
        runCatching { BuildRunner.init(this) }
        runCatching { AiController.init(this) }

        // 5) 恢复上次项目
        scope.launch { runCatching { AppState.restore() } }
    }
}
