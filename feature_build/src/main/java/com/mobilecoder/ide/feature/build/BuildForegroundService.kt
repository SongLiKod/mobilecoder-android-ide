package com.mobilecoder.ide.feature.build

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService

/**
 * 构建前台服务（PRD 4.2「后台任务保活，锁屏不中断编译」+ TECH 6「WAKE_LOCK 后台编译保活」）。
 *
 * 生命周期与一次构建一致：
 *  1. 构建启动 → [startBuild]：拉起前台服务，展示「构建进行中…」低优先级通知，持有 PARTIAL_WAKE_LOCK；
 *  2. 构建中 → [update]：更新阶段文案；
 *  3. 构建结束 → [finishBuild]：转成完成通知（点击回到 App），释放 WAKE_LOCK 并 stopForeground/stopSelf。
 *
 * 服务在 feature_build 的 AndroidManifest.xml 中声明（foregroundServiceType=dataSync），
 * 由 `BuildRunner` 调用，进程内幂等（重复 start 只更新通知）。
 */
class BuildForegroundService : LifecycleService() {

    private var wakeLock: android.os.PowerManager.WakeLock? = null

    private var foreground = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val text = intent?.getStringExtra(EXTRA_TEXT).orEmpty().ifBlank { "构建进行中…" }
        when (intent?.action) {
            ACTION_FINISH -> {
                val ok = intent.getBooleanExtra(EXTRA_SUCCESS, false)
                val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "构建完成" }
                // 先满足 startForegroundService 的 startForeground 契约，再转完成通知
                promote(title, text, ongoing = true)
                finish(ok, title, text)
            }
            ACTION_UPDATE -> {
                promote("MobileCoder 构建", text, ongoing = true)
            }
            else -> {
                acquireWakeLock()
                promote("MobileCoder 构建", text, ongoing = true)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    // ------------------------------------------------------------------

    /** 进入前台并展示进行中通知（每个 startForegroundService 都必须调用一次）。 */
    private fun promote(title: String, text: String, ongoing: Boolean) {
        runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(title, text, ongoing),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
            foreground = true
        }
    }

    /** 构建结束：转为完成通知（可点击回到 App），释放资源后退出。 */
    private fun finish(success: Boolean, title: String, text: String) {
        val notification = buildNotification(title, text, ongoing = false)
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        foreground = false
        runCatching {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, notification)
        }
        releaseWakeLock()
        stopSelf()
    }

    private fun buildNotification(title: String, text: String, ongoing: Boolean): Notification {
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
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setAutoCancel(!ongoing)
            .build()
    }

    private fun createChannel() {
        runCatching {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "构建任务",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "编译打包任务的进行中与完成通知（PRD 2.7）"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MobileCoder:build").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            wakeLock?.let { if (it.isHeld) it.release() }
        }
        wakeLock = null
    }

    companion object {
        const val CHANNEL_ID = "mobilecoder_build"
        const val NOTIFICATION_ID = 4207

        private const val ACTION_UPDATE = "com.mobilecoder.ide.feature.build.action.UPDATE"
        private const val ACTION_FINISH = "com.mobilecoder.ide.feature.build.action.FINISH"
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_SUCCESS = "success"

        /** 服务是否已因构建而启动（避免 finish 时无端拉起服务）。 */
        @Volatile
        private var running = false

        /** 构建启动：拉起前台服务（幂等，已运行则只刷新通知）。 */
        fun startBuild(context: Context, text: String) {
            running = true
            dispatch(context, null, text)
        }

        /** 更新阶段文案（服务未启动时静默忽略，避免后台启动限制）。 */
        fun update(context: Context, text: String) {
            if (!running) return
            dispatch(context, ACTION_UPDATE, text)
        }

        /** 构建结束：完成通知 + 释放 WAKE_LOCK + stopForeground/stopSelf。 */
        fun finishBuild(context: Context, success: Boolean, title: String, text: String) {
            if (!running) return
            running = false
            val intent = Intent(context, BuildForegroundService::class.java).apply {
                action = ACTION_FINISH
                putExtra(EXTRA_SUCCESS, success)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_TEXT, text)
            }
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        private fun dispatch(context: Context, action: String?, text: String) {
            val intent = Intent(context, BuildForegroundService::class.java).apply {
                if (action != null) this.action = action
                putExtra(EXTRA_TEXT, text)
            }
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }
    }
}
