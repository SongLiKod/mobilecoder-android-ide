package com.mobilecoder.ide.feature.terminal

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.mobilecoder.ide.core.nativebridge.NativeRuntime
import com.mobilecoder.ide.core.nativebridge.TerminalNative
import com.mobilecoder.ide.core.storage.AppStorage
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 终端进程级单例（PRD 2.3）。
 *
 * App 启动即由 `Application.onCreate()` 调用 [init]：**幂等、非阻塞、全程 try/catch 绝不崩溃**。
 * 持有全部会话（每个会话独立 PTY / 屏幕缓冲 / 滚动日志），因此切走底部导航再回来
 * 终端仍在运行 —— 对应 PRD「支持后台长时间任务（编译、同步、打包不中断）」。
 */
object TerminalManager {

    /** native 层并发会话上限（MC_MAX_TERMINALS = 8）。 */
    const val MAX_SESSIONS = 8

    /** 布局测量前的默认尺寸。 */
    const val DEFAULT_COLS = 80
    const val DEFAULT_ROWS = 24

    private val lock = Any()
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _sessions = MutableStateFlow<List<TerminalSession>>(emptyList())

    /** 全部会话（进程级，跨导航保持）。 */
    val sessions: StateFlow<List<TerminalSession>> = _sessions.asStateFlow()

    private val _activeId = MutableStateFlow(0)

    /** 当前活动会话 id（0 = 无）。 */
    val activeId: StateFlow<Int> = _activeId.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)

    /** 错误提示（中文说明 + 重试），UI 用弹窗消费。 */
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)

    /** 普通提示（如日志导出路径）。 */
    val notice: StateFlow<String?> = _notice.asStateFlow()

    @Volatile
    private var initialized = false

    @Volatile
    private var nativeOk = true

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var retryAction: (() -> Unit)? = null

    // ------------------------------------------------------------------
    // 初始化（幂等 / 非阻塞 / 不抛异常）
    // ------------------------------------------------------------------

    fun init(context: Context) {
        if (initialized) return
        synchronized(lock) {
            if (initialized) return
            try {
                val app = context.applicationContext
                appContext = app
                // 1) 存储层（AppStorage 自身幂等）
                runCatching { AppStorage.init(app) }
                // 2) 进程环境变量：HOME/PATH/ANDROID_HOME/GRADLE_USER_HOME 等，PTY 子进程继承
                runCatching { NativeRuntime.ensureProcessEnvironment(app) }
                // 3) 目录：tmp（编译缓存）与 logs（终端日志导出）
                runCatching { File(app.cacheDir, "tmp").mkdirs() }
                runCatching { File(app.filesDir, "logs").mkdirs() }
                // 4) shell 启动配置：.mkshrc 与 .profile（内容相同，双保险）
                runCatching { writeShellConfig(app) }
                // 5) 探测原生库是否可加载（失败只降级提示，绝不崩溃）
                try {
                    TerminalNative.setEnv("MOBILECODER_TERMINAL", "1")
                    nativeOk = true
                    runCatching {
                        TerminalNative.setEnv("ENV", File(app.filesDir, ".mkshrc").absolutePath)
                    }
                } catch (t: Throwable) {
                    nativeOk = false
                }
            } catch (t: Throwable) {
                // 绝不崩溃
            }
            initialized = true
        }
    }

    /**
     * mksh（Android `/system/bin/sh`）交互式启动加载 `$HOME/.mkshrc`；
     * 同时写一份 `.profile`（登录 shell）并注入 `ENV` 变量三重保险。
     */
    private fun writeShellConfig(context: Context) {
        val files = context.filesDir
        val content = shellConfigContent(files)
        File(files, ".mkshrc").writeText(content)
        File(files, ".profile").writeText(content)
    }

    private fun shellConfigContent(files: File): String {
        val bin = files.absolutePath + "/bin"
        return buildString {
            appendLine("# MobileCoder 内置终端启动配置（由 TerminalManager.init 写入）")
            appendLine("# mksh 交互式启动时加载 \$HOME/.mkshrc；.profile 为登录 shell 双保险")
            appendLine("HISTSIZE=1000")
            appendLine("export HISTSIZE")
            appendLine("case \":\$PATH:\" in")
            appendLine(" *\":$bin:\"*) ;;")
            appendLine(" *) PATH=\"\$PATH:$bin\" ;;")
            appendLine("esac")
            appendLine("export PATH")
            appendLine("# 简洁提示符：显示当前目录名（mksh 支持参数替换，不支持 \\w）")
            appendLine("PS1='\${PWD##*/} \$ '")
            appendLine("alias ll='ls -l'")
            appendLine("alias la='ls -a'")
            appendLine("alias grep='grep --color=auto'")
        }
    }

    // ------------------------------------------------------------------
    // 会话管理
    // ------------------------------------------------------------------

    fun select(id: Int) {
        if (_sessions.value.any { it.id == id }) _activeId.value = id
    }

    fun activeSession(): TerminalSession? = _sessions.value.firstOrNull { it.id == _activeId.value }

    /**
     * 新建会话（cwd = 当前项目目录，PRD「终端自动绑定当前项目路径」）。
     *
     * @return 成功返回会话；失败时写入 [error]（中文说明 + 重试）并返回 null。
     */
    fun create(cols: Int, rows: Int, cwd: String?): TerminalSession? {
        val c = cols.coerceIn(10, 400)
        val r = rows.coerceIn(4, 200)
        val dir = cwd?.trim()?.takeIf { it.isNotBlank() }
            ?: appContext?.filesDir?.absolutePath
            ?: "/"

        if (_sessions.value.size >= MAX_SESSIONS) {
            fail("最多同时开启 $MAX_SESSIONS 个终端会话，请先关闭一个再新建。") { create(c, r, cwd) }
            return null
        }
        if (!nativeOk) {
            fail("终端引擎不可用：原生库 libmobilecoder 加载失败，无法创建 PTY 会话。请重启应用后重试。") {
                create(c, r, cwd)
            }
            return null
        }

        val session = TerminalSession(-1, dir, c, r, handler, scope)
        val id = try {
            TerminalNative.create(dir, c, r, session)
        } catch (t: Throwable) {
            nativeOk = false
            fail("创建终端会话失败：${t.message ?: t::class.java.simpleName}。请点击重试。") {
                create(c, r, cwd)
            }
            return null
        }
        if (id < 0) {
            fail("创建终端会话失败（PTY 初始化返回 -1）。请关闭部分会话后点击重试。") { create(c, r, cwd) }
            return null
        }

        session.id = id
        _sessions.value = _sessions.value + session
        _activeId.value = id
        return session
    }

    /** 关闭单个会话（销毁底层 PTY 并移出列表）。 */
    fun close(id: Int) {
        val session = _sessions.value.firstOrNull { it.id == id } ?: return
        session.destroy()
        val rest = _sessions.value.filterNot { it.id == id }
        _sessions.value = rest
        if (_activeId.value == id) _activeId.value = rest.lastOrNull()?.id ?: 0
    }

    /** 关闭全部会话。 */
    fun closeAll() {
        val list = _sessions.value
        _sessions.value = emptyList()
        _activeId.value = 0
        list.forEach { it.destroy() }
    }

    /** 重启已退出的会话（同一 cwd / 尺寸，标签位置由 UI 决定）。 */
    fun restart(id: Int) {
        val old = _sessions.value.firstOrNull { it.id == id } ?: return
        _sessions.value = _sessions.value.filterNot { it.id == id }
        old.destroy()
        val fresh = create(old.emulator.cols, old.emulator.rows, old.cwd)
        if (fresh == null && _activeId.value == id) _activeId.value = 0
    }

    // ------------------------------------------------------------------
    // 日志导出（PRD 2.3「日志导出」）
    // ------------------------------------------------------------------

    /** 把会话 scrollback 写入 `files/logs/terminal-yyyyMMdd-HHmmss.log`，返回文件（失败 null）。 */
    fun exportLog(session: TerminalSession): File? {
        return try {
            val files = try {
                AppStorage.paths.files
            } catch (t: Throwable) {
                appContext?.filesDir ?: return null
            }
            val logs = File(files, "logs")
            if (!logs.exists()) logs.mkdirs()
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val file = File(logs, "terminal-$stamp.log")
            val header = buildString {
                appendLine("# MobileCoder 终端日志导出")
                appendLine("# 会话：${session.title.value}")
                appendLine("# 目录：${session.cwd.ifBlank { "/" }}")
                appendLine("# 时间：$stamp")
                appendLine("#" + "-".repeat(46))
            }
            file.writeText(header + session.exportText() + "\n")
            file
        } catch (t: Throwable) {
            null
        }
    }

    // ------------------------------------------------------------------
    // 错误与提示
    // ------------------------------------------------------------------

    private fun fail(message: String, retry: () -> Unit) {
        retryAction = retry
        _error.value = message
    }

    /** 弹窗「重试」。 */
    fun retry() {
        val action = retryAction
        _error.value = null
        retryAction = null
        action?.invoke()
    }

    /** 关闭错误弹窗。 */
    fun clearError() {
        _error.value = null
        retryAction = null
    }

    fun clearNotice() {
        _notice.value = null
    }

    /** 展示一条普通提示（UI 以单按钮弹窗消费）。 */
    fun showNotice(message: String) {
        _notice.value = message
    }
}
