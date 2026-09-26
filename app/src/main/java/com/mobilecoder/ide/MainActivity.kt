package com.mobilecoder.ide

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val themeManager = (application as MobileCoderApplication).themeManager

        setContent {
            val themeMode by themeManager.mode.collectAsStateWithLifecycle()

            MobileCoderTheme(mode = themeMode) {
                AppRoot(themeManager = themeManager)
            }
        }
    }
}
