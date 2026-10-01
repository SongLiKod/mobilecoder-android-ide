package com.mobilecoder.ide.core.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [HistoryStore] 纯逻辑：去重 / 收藏保留 / 限量、展示顺序（收藏置顶）、来源过滤。
 */
class HistoryStoreTest {

    private fun record(
        id: String,
        text: String,
        source: String = HistoryStore.SOURCE_TERMINAL,
        time: Long,
        favorite: Boolean = false,
    ) = HistoryRecord(id = id, text = text, source = source, time = time, favorite = favorite)

    @Test
    fun `addRecord 同文本去重置顶且保留收藏标记`() {
        val old = record("1", "git status", time = 100, favorite = true)
        val other = record("2", "ls", time = 200)
        val fresh = record("3", "git status", time = 300)

        val next = HistoryStore.addRecord(listOf(old, other), fresh, limit = 500)

        assertEquals(2, next.size) // 旧的同文本记录被刷新，不重复
        assertEquals("3", next.first().id) // 新记录在最前
        assertTrue("重复执行不应丢掉收藏", next.first().favorite)
        assertEquals(300, next.first().time)
    }

    @Test
    fun `addRecord 超限丢弃最旧的普通项但收藏优先保留`() {
        val favorite = record("fav", "npm run build", time = 1, favorite = true)
        val plain = (2L..10L).map { record("p$it", "cmd$it", time = it) }

        val next = HistoryStore.addRecord(
            plain + favorite,
            record("new", "fresh", time = 11),
            limit = 5,
        )

        assertEquals(5, next.size)
        assertTrue("收藏项即使最旧也不能被挤掉", next.any { it.id == "fav" })
        assertTrue("新记录必须存在", next.any { it.id == "new" })
        // 整体仍按时间倒序
        assertEquals(next.sortedByDescending { it.time }, next)
    }

    @Test
    fun `displayOrder 收藏在前且各自按时间倒序`() {
        val list = listOf(
            record("1", "a", time = 100),
            record("2", "b", time = 400, favorite = true),
            record("3", "c", time = 300),
            record("4", "d", time = 200, favorite = true),
        )

        val ordered = HistoryStore.displayOrder(list)

        assertEquals(listOf("2", "4", "3", "1"), ordered.map { it.id })
    }

    @Test
    fun `textsOf 只取指定来源且按时间倒序`() {
        val list = listOf(
            record("1", "git status", time = 100),
            record("2", "构建 assembleDebug", source = HistoryStore.SOURCE_BUILD, time = 500),
            record("3", "ls -la", time = 300),
        )

        assertEquals(
            listOf("ls -la", "git status"),
            HistoryStore.textsOf(list, HistoryStore.SOURCE_TERMINAL),
        )
        assertEquals(
            listOf("构建 assembleDebug"),
            HistoryStore.textsOf(list, HistoryStore.SOURCE_BUILD),
        )
    }
}
