package com.mobilecoder.ide.feature.editor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.mobilecoder.ide.core.storage.FileNode
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 拖拽落点解析回归：只认当前行 + 当前坐标簿。
 *
 * 事故复盘（真机）：树反复折叠/展开后 [com.mobilecoder.ide.feature.editor.FileTreePanel]
 * 的坐标簿留下已离开视口行的旧 rect，原地长按弹菜单时落点按旧坐标解析成别的目录，
 * 导致 ui 目录被误移动。修复 = 行离开组合即清坐标（DisposableEffect）+ 解析只按 rows。
 */
class DropTargetResolveTest {

    private fun row(path: String, depth: Int, isDir: Boolean): TreeRow {
        val node = FileNode(File(path).name, File(path), isDir, 0L, depth)
        return TreeRow(node, depth, isDir)
    }

    @Test
    fun `命中当前行 - 目录取自身、文件取父目录、无命中为 null`() {
        val app = row(File("/r/app").path, 1, isDir = true)
        val file = row(File("/r/app/A.kt").path, 2, isDir = false)
        val rows = listOf(app, file)
        val bounds = mapOf(
            app.path to Rect(0f, 0f, 100f, 40f),
            file.path to Rect(0f, 40f, 100f, 80f),
        )

        assertEquals(app.path, resolveDropTarget(Offset(50f, 20f), bounds, rows))
        assertEquals(File("/r/app").path, resolveDropTarget(Offset(50f, 60f), bounds, rows))
        assertNull(resolveDropTarget(Offset(500f, 500f), bounds, rows))
    }

    @Test
    fun `坐标簿里的陈旧行不得命中 - 即使 rect 覆盖按压点`() {
        // app 已滚出视口（DisposableEffect 清掉了它的 rect 条目）
        val staleApp = row(File("/r/app").path, 1, isDir = true)
        val ui = row(File("/r/ide/ui").path, 8, isDir = true)
        val rows = listOf(ui)
        val bounds = mapOf(ui.path to Rect(0f, 0f, 100f, 40f))

        assertEquals(ui.path, resolveDropTarget(Offset(50f, 20f), bounds, rows))

        // 坐标簿只剩陈旧条目、当前行无任何命中 → 必须返回 null（不移动）
        val onlyStale = mapOf(staleApp.path to Rect(0f, 0f, 100f, 40f))
        assertNull(resolveDropTarget(Offset(50f, 20f), onlyStale, rows))
    }
}
