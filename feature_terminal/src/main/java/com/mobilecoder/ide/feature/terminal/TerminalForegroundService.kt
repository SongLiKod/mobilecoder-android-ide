package com.mobilecoder.ide.feature.terminal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService

/**
 * 终端保活前台服务：**只要还有终端会话就常驻**（低优先级通知），切后台后
 * 进程不被冻结、不被 LMK 回收——否则 Android 12+ 会把 cached 进程整个
 * SIGSTOP，会话里的任务与监听端口随之停摆（用户报障：「终端启动任务，
 * 切到后台就不能访问了」）。
 *
 * 生命周期与会话数量同步（`TerminalManager.syncKeepAlive()`）：
 *  1. 新建会话 → [keepAlive]：拉起前台服务，展示「N 个会话运行中」；
 *  2. 关闭部分会话 → [keepAlive]：只刷新计数（`setOnlyAlertOnce` 静默更新）；
 *  3. 关闭全部 → [stop]：撤通知并 stopSelf（会话是内存态，进程若真被杀，
 *     会话同样不复存在，故用 START_NOT_STICKY，不复活无会话的通知）。
 *
 * **为什么不持 WAKE_LOCK**：终端会话生命周期近似「永远」（shell 常驻），
 * 常驻持锁会整夜耗电；切后台不中断由前台服务本身保障（非 cached 进程，
 * 调度与网络正常）。锁屏深睡下的网络策略如后续有需求再单独处理。
 *
 * 与 `BuildForegroundService`（feature_build，构建期专用）互不影响：
 * 通知渠道与通知 ID 各自独立，两者可同时存在。服务在本模块
 * AndroidManifest.xml 中声明（dataSync 类型，复用 app 已有的
 * FOREGROUND_SERVICE_DATA_SYNC 权限）；拉起时机必然是用户新建会话的
 * 前台时刻，不触碰后台启动前台服务的限制。
 *
 * 通知正文计数为纯函数 [keepAliveText]（JVM 可测）。
 */
class TerminalForegroundService : LifecycleService() {

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> {
                // 先满足 startForegroundService 的 startForeground 契约，再撤通知
                promote(intent.getStringExtra(EXTRA_TEXT).orEmpty().ifBlank { keepAliveText(1) })
                stop()
            }
            else -> promote(intent?.getStringExtra(EXTRA_TEXT).orEmpty().ifBlank { keepAliveText(1) })
        }
        return START_NOT_STICKY
    }

    // ------------------------------------------------------------------

    /** 进入前台并展示保活通知（每个 startForegroundService 都必须调用一次）。 */
    private fun promote(text: String) {
        runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(text),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        }
    }

    /** 全部会话已关闭：撤下通知并退出服务。 */
    private fun stop() {
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        runCatching { stopSelf() }
    }

    private fun buildNotification(text: String): Notification {
        val contentIntent = runCatching {
            packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
                PendingIntent.getActivity(
                    this,
                    0,
                    launch,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            }
        }.getOrNull()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("MobileCoder 终端")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(BIG_TEXT + text))
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun createChannel() {
        runCatching {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "终端会话",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "终端会话后台保活通知（关闭全部会话后消失）"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "mobilecoder_terminal"
        const val NOTIFICATION_ID = 4208

        private const val ACTION_STOP = "com.mobilecoder.ide.feature.terminal.action.STOP"
        private const val EXTRA_TEXT = "text"

        private const val BIG_TEXT =
            "终端会话在后台保持运行：任务与开发服务器不会因切出应用而中断。\n" +
                "关闭全部会话后，本通知自动消失。\n"

        /** 服务是否已因会话而启动（避免未启动就 stop 时无端拉起服务）。 */
        @Volatile
        private var running = false

        /** 会话创建/关闭后调用：拉起或刷新保活通知（幂等，重复调用只更新文案）。 */
        fun keepAlive(context: Context, count: Int) {
            running = true
            dispatch(context, keepAliveText(count), action = null)
        }

        /** 全部会话关闭：撤通知并停止服务（未启动时静默，绝不无端拉起）。 */
        fun stop(context: Context) {
            if (!running) return
            running = false
            dispatch(context, keepAliveText(1), action = ACTION_STOP)
        }

        private fun dispatch(context: Context, text: String, action: String?) {
            val intent = Intent(context, TerminalForegroundService::class.java).apply {
                if (action != null) this.action = action
                putExtra(EXTRA_TEXT, text)
            }
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }
    }
}

/**
 * 保活通知正文（纯函数，JVM 可测）。
 *
 * @param count 当前存活会话数（防御性下限 1：0 时由调用方走 [TerminalForegroundService.stop]）
 */
internal fun keepAliveText(count: Int): String = "${count.coerceAtLeast(1)} 个会话运行中"
