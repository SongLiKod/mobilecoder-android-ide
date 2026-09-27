package com.mobilecoder.ide.feature.ssh

import com.mobilecoder.ide.core.nativebridge.NativeRuntime
import com.mobilecoder.ide.core.nativebridge.SshNative
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 连接测试结果（字段与 ssh_jni.c 返回帧一一对应）。 */
internal data class SshTestResult(
    /** 0 连接+认证成功 / 1 连接成功但认证未完成 / -1 连接失败 */
    val code: Int,
    /** 后端原始 message（失败时原样展示，不做美化） */
    val message: String,
    /** 主机密钥指纹 SHA256:... */
    val fingerprint: String,
    /** 主机密钥类型（ssh-rsa / ssh-ed25519 ...） */
    val hostKeyType: String,
    /** 本次测试是否携带了私钥 */
    val usedKey: Boolean,
    /** 是否选择了 Ed25519 密钥（后端 mbedTLS 构建不支持其认证） */
    val usedEd25519: Boolean = false,
) {
    val success: Boolean get() = code == 0
    val reachable: Boolean get() = code >= 0
}

/**
 * 一键测试 SSH 连通性（PRD 2.6）。
 *
 * 全程 Dispatchers.IO：JNI 内是阻塞式 TCP + 握手 + 公钥认证。
 * 私钥仅作为「内存凭据」传入 native 层，不落盘、不写日志。
 */
internal object SshConnectionTester {

    /** 帧分隔符 U+0001（ssh_jni.c MC_FIELD_SEP）。 */
    private const val SEP = '\u0001'

    suspend fun test(
        host: String,
        port: Int,
        username: String,
        privateKeyPem: String?,
        passphrase: String,
        timeoutMs: Int = 10_000,
        usedEd25519: Boolean = false,
    ): SshTestResult = withContext(Dispatchers.IO) {
        try {
            if (host.isBlank()) {
                return@withContext failure("主机地址为空", privateKeyPem, usedEd25519)
            }
            if (!NativeRuntime.ensureSshReady()) {
                return@withContext failure(
                    "本地 SSH 引擎初始化失败（libssh2 未能加载）",
                    privateKeyPem,
                    usedEd25519,
                )
            }
            val frame = SshNative.testConnection(
                host.trim(),
                port,
                username.trim().ifBlank { "git" },
                privateKeyPem,
                passphrase,
                timeoutMs,
            )
            parse(frame ?: "", privateKeyPem, usedEd25519)
        } catch (t: Throwable) {
            failure("连接测试异常：${t.message ?: t.javaClass.simpleName}", privateKeyPem, usedEd25519)
        }
    }

    private fun parse(frame: String, key: String?, usedEd25519: Boolean): SshTestResult {
        if (frame.isEmpty()) return failure("native 层未返回结果", key, usedEd25519)
        val parts = frame.split(SEP)
        val code = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: -1
        return SshTestResult(
            code = code,
            message = parts.getOrNull(1) ?: "",
            fingerprint = parts.getOrNull(2) ?: "",
            hostKeyType = parts.getOrNull(3) ?: "",
            usedKey = !key.isNullOrBlank(),
            usedEd25519 = usedEd25519,
        )
    }

    private fun failure(message: String, key: String?, usedEd25519: Boolean) = SshTestResult(
        code = -1,
        message = message,
        fingerprint = "",
        hostKeyType = "",
        usedKey = !key.isNullOrBlank(),
        usedEd25519 = usedEd25519,
    )
}
