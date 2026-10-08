package com.mobilecoder.ide.feature.editor

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导出的自嵌套防线：目标目录位于源内部时必须拒绝。
 *
 * 否则把目录导出到自己的子目录，每层都会先建目标再列出源（含刚建的目标），
 * 无限递归复制自己。
 */
class FileExporterTest {

    @Test
    fun `目标等于源 - 拒绝`() {
        assertTrue(isPathInside("/r/app", "/r/app"))
    }

    @Test
    fun `目标在源内部 - 拒绝`() {
        assertTrue(isPathInside("/r/app/build/out", "/r/app"))
        assertTrue(isPathInside("/r/app/a/b/c", "/r/app"))
    }

    @Test
    fun `仅前缀相同不算子路径 - 放行`() {
        // /r/appx 不是 /r/app 的子路径（按分隔符级别比对，不能只看 startsWith）
        assertFalse(isPathInside("/r/appx", "/r/app"))
        assertFalse(isPathInside("/r/apple", "/r/app"))
    }

    @Test
    fun `目标在源外部 - 放行`() {
        assertFalse(isPathInside("/r/app", "/r/app/build"))
        assertFalse(isPathInside("/storage/emulated/0/Download", "/r/app"))
    }

    @Test
    fun `相对段被解析后才比对 - 放行`() {
        // /r/app/build/../.. 规范化后是 /r，不在 /r/app 内
        assertFalse(isPathInside("/r/app/build/../..", "/r/app"))
        assertTrue(isPathInside("/r/app/x/../y", "/r/app"))
    }

    @Test
    fun `源路径尾部分隔符不影响判定`() {
        assertTrue(isPathInside("/r/app/build", "/r/app/"))
        assertFalse(isPathInside("/r/appx", "/r/app/"))
    }

    @Test
    fun `根目录下的任何目标都算内部`() {
        assertTrue(isPathInside(File("/r").path, "/"))
    }
}
