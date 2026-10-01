package com.mobilecoder.ide.update

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import java.io.File

/**
 * APK 安装通道：把下载好的更新包塞进 PackageInstaller 会话并提交。
 *
 * 提交后由系统接管：确认框覆盖在本应用之上（不跳转任何其他界面）；若本应用
 * 尚无「安装未知应用」授权，系统会先给出授权引导（一次性，平台强制，无法绕过）。
 * 会话结果经 [UpdateReceiver] 回传给 [UpdateController]。
 */
object ApkInstaller {

    /** 提交全量安装会话（写入 → commit）。失败抛给调用方展示。 */
    fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL,
        )
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("update", 0, apk.length()).use { out ->
                apk.inputStream().use { input -> input.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(context, UpdateReceiver::class.java)
            // FLAG_MUTABLE（API 31+）：系统要往回填安装状态；旧系统不认该位，安全忽略
            val mutability =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_MUTABLE
                } else {
                    0
                }
            val pending = PendingIntent.getBroadcast(
                context,
                sessionId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or mutability,
            )
            session.commit(pending.intentSender)
        }
    }
}

/**
 * 安装会话状态回传。系统状态栏通知之外的唯一回调（commit 的 intentSender）：
 *  - [PackageInstaller.STATUS_PENDING_USER_ACTION]：系统把确认框 Intent 交给我们拉起
 *  - [PackageInstaller.STATUS_SUCCESS]：安装完成（自更新会随后杀进程，回传尽力而为）
 *  - 其余失败码：带上系统状态串展示，便于定位（如签名不匹配）
 */
class UpdateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE,
        )
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                UpdateController.onAwaitingConfirm()
                if (confirm == null) {
                    UpdateController.onInstallResult(
                        UpdateController.InstallResult.FAILED,
                        "系统未返回安装确认页",
                    )
                    return
                }
                try {
                    context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: ActivityNotFoundException) {
                    UpdateController.onInstallResult(
                        UpdateController.InstallResult.FAILED,
                        "无法打开系统安装确认页",
                    )
                }
            }

            PackageInstaller.STATUS_SUCCESS -> UpdateController.onInstallResult(
                UpdateController.InstallResult.SUCCESS,
                "更新安装完成，重新打开应用即可生效",
            )

            PackageInstaller.STATUS_FAILURE_ABORTED -> UpdateController.onInstallResult(
                UpdateController.InstallResult.ABORTED,
                "已取消安装，可重新下载",
            )

            else -> {
                val detail = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                UpdateController.onInstallResult(
                    UpdateController.InstallResult.FAILED,
                    "安装失败（code=$status）" + (detail?.let { "：$it" } ?: ""),
                )
            }
        }
    }
}
