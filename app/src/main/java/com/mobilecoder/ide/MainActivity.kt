package com.mobilecoder.ide

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mobilecoder.ide.core.common.theme.MobileCoderTheme
import com.mobilecoder.ide.ui.AppRoot

/**
 * 主入口 Activity（Manifest：.MainActivity，LAUNCHER）。
 *
 * 通过 configChanges 声明（orientation|screenSize|...|uiMode|fontScale|locale），
 * 深浅色与横竖屏切换不会重建 Activity，主题由 Compose 局部重组完成切换。
 */
class MainActivity : ComponentActivity() {

    /** 构建完成通知需要运行时授权（Android 13+ / POST_NOTIFICATIONS）。 */
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                // 拒绝授权不阻塞功能：构建状态仍会在 App 内实时展示
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestNotificationPermissionIfNeeded()

        val themeManager = (application as MobileCoderApplication).themeManager

        setContent {
            val themeMode by themeManager.mode.collectAsStateWithLifecycle()

            MobileCoderTheme(mode = themeMode) {
                AppRoot(themeManager = themeManager)
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
