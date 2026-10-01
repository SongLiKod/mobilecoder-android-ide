package com.mobilecoder.ide.core.storage

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 操作历史存储（原 CLI 面板废弃后，「历史」页完全依赖这份数据）。
 *
 * 覆盖：记录顺序、收藏 / 删除 / 清空、容量裁剪（收藏不被挤掉），
 * 以及落盘往返（含制表符、换行、中文等需要转义的命令文本）。
 */
class OperationHistoryStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun file(): File = File(tmp.root, "history/operations.tsv")

    private fun store(max: Int = 500): OperationHistoryStore = OperationHistoryStore(file(), max)

    @Test
    fun `新记录排在最前，空白命令被忽略`() {
        val store = store()
        store.record("apt build", OperationSource.CLI, project = "demo", exitCode = 0, durationMs = 1_200)
        store.record("npm install", OperationSource.TERMINAL)
        assertNull("空白命令不应入历史", store.record("   ", OperationSource.TERMINAL))

        val list = store.records.value
        assertEquals(2, list.size)
        assertEquals("npm install", list[0].command)
        assertEquals("apt build", list[1].command)
        assertEquals("demo", list[1].project)
        assertEquals(0, list[1].exitCode)
        assertEquals(1_200L, list[1].durationMs)
        assertEquals(OperationSource.CLI, list[1].source)
    }

    @Test
    fun `收藏、删除、清空都只影响目标记录`() {
        val store = store()
        val keep = store.record("apt lint", OperationSource.CLI)!!
        val drop = store.record("ls -la", OperationSource.TERMINAL)!!

        store.setFavorite(keep.id, true)
        assertTrue("收藏应生效", store.records.value.first { it.id == keep.id }.favorite)

        store.remove(drop.id)
        assertEquals(1, store.records.value.size)

        // 清空默认保留收藏
        store.clear(keepFavorites = true)
        assertEquals(1, store.records.value.size)
        assertEquals("apt lint", store.records.value.first().command)

        // 显式连收藏一起清
        store.clear(keepFavorites = false)
        assertTrue(store.records.value.isEmpty())
    }

    @Test
    fun `超出容量只裁剪最旧的非收藏记录`() {
        val store = store(max = 3)
        val oldest = store.record("cmd-a", OperationSource.TERMINAL)!!
        store.record("cmd-b", OperationSource.TERMINAL)
        store.record("cmd-c", OperationSource.TERMINAL)
        store.setFavorite(oldest.id, true)
        store.record("cmd-d", OperationSource.TERMINAL)

        val commands = store.records.value.map { it.command }
        assertEquals(3, commands.size)
        assertEquals("cmd-d", commands.first())
        assertTrue("收藏的记录不能被容量挤掉", commands.contains("cmd-a"))
    }

    @Test
    fun `落盘后重新加载可完整还原`() = runBlocking {
        val tricky = "git commit -m \"修复\t缩进\n换行\" —— 中文 & \"引号\""
        val saved = store()
        saved.record(tricky, OperationSource.GIT, project = "我的项目", exitCode = -1, durationMs = 42)
        val lint = saved.record("apt lint", OperationSource.CLI, exitCode = 1, durationMs = 80)!!
        saved.setFavorite(lint.id, true)
        saved.flush()

        val reloaded = OperationHistoryStore(file())
        assertEquals(2, reloaded.records.value.size)

        val first = reloaded.records.value[0]
        assertEquals("apt lint", first.command)
        assertTrue("收藏标记要能跨重启还原", first.favorite)
        assertEquals(1, first.exitCode)
        assertEquals(80L, first.durationMs)
        assertEquals(OperationSource.CLI, first.source)

        val second = reloaded.records.value[1]
        assertEquals(tricky, second.command)
        assertEquals("我的项目", second.project)
        assertEquals(-1, second.exitCode)
        assertEquals(42L, second.durationMs)
        assertEquals(OperationSource.GIT, second.source)
    }

    @Test
    fun `清空后落盘，重新加载为空`() = runBlocking {
        val store = store()
        store.record("apt build", OperationSource.CLI)
        store.flush()
        store.clear(keepFavorites = false)
        store.flush()

        assertTrue(store.records.value.isEmpty())
        assertTrue(OperationHistoryStore(file()).records.value.isEmpty())
    }
}
