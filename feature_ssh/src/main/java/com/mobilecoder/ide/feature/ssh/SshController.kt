package com.mobilecoder.ide.feature.ssh

import android.content.Context
import com.mobilecoder.ide.core.storage.AppStorage
import com.mobilecoder.ide.core.storage.SshKeyMeta
import com.mobilecoder.ide.core.storage.SshKeyStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * SSH 模块进程级入口（app 的 Application.onCreate 调用 [init]）。
 *
 * 约束：
 *  - 幂等：重复调用无副作用
 *  - 非阻塞：只投递后台任务，绝不阻塞冷启动（TECH.md 8 ≤2s）
 *  - 绝不崩溃：内部 try/catch 吞掉所有 Throwable，即使存储层不可用也只降级为空列表
 */
object SshController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _keys = MutableStateFlow<List<SshKeyMeta>>(emptyList())

    /** 密钥索引（不含私钥），由存储层镜像而来，UI 直接订阅。 */
    val keys: StateFlow<List<SshKeyMeta>> = _keys.asStateFlow()

    @Volatile
    private var started = false

    /** 幂等初始化：确保存储层就绪并开始同步密钥索引。 */
    fun init(context: Context) {
        try {
            if (started) return
            synchronized(this) {
                if (started) return
                AppStorage.init(context.applicationContext)
                val store = AppStorage.sshKeys
                started = true
                scope.launch { runCatching { store.keys.collect { _keys.value = it } } }
                scope.launch { runCatching { store.refresh() } }
            }
        } catch (_: Throwable) {
            // 任何异常都不允许上抛（Application.onCreate 崩溃保护）
        }
    }

    /** 存储层是否就绪。 */
    val ready: Boolean
        get() = started

    /** 取密钥库（未初始化时返回 null，调用方需提示「存储层未就绪」）。 */
    internal fun store(): SshKeyStore? {
        if (!started) return null
        return runCatching { AppStorage.sshKeys }.getOrNull()
    }

    /** 手动刷新（打开页面 / 完成写操作后调用）。 */
    internal suspend fun refresh() {
        val store = store() ?: return
        runCatching { store.refresh() }
        runCatching { _keys.value = store.keys.value }
    }
}
