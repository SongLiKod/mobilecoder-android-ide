package com.mobilecoder.ide.feature.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [GitStatusEntry] 的分组与 [GitStatusEntry.isNew] 判定。
 *
 * `isNew` 是变更页「还原 = 删除新增文件」的唯一开关：
 * 只有从未进过 HEAD 的文件（未跟踪 `??` / 已暂存新增 `A `）才允许被那样删掉，
 * 已入库的修改、删除文件一律不能命中，否则会误删有历史的内容。
 */
class GitStatusEntryTest {

    /** statusList 帧分隔符（git_jni.c 的 MC_FIELD_SEP = 0x01）。 */
    private val fs = Char(1).toString()

    private fun entry(index: Int, worktree: Int, ignored: Boolean = false) =
        GitStatusEntry(path = "app/Main.kt", indexRaw = index, worktreeRaw = worktree, ignored = ignored)

    @Test
    fun `未跟踪的新文件判定为新增`() {
        val e = entry(index = 0, worktree = 0x80) // GIT_STATUS_WT_NEW
        assertTrue(e.isNew)
        assertEquals("??", e.code)
        assertTrue(e.inGroup(GitChangeGroup.UNTRACKED))
        assertFalse(e.inGroup(GitChangeGroup.STAGED))
    }

    @Test
    fun `已暂存的新增判定为新增`() {
        val e = entry(index = 0x01, worktree = 0) // GIT_STATUS_INDEX_NEW
        assertTrue(e.isNew)
        assertEquals("A ", e.code)
        assertTrue(e.inGroup(GitChangeGroup.STAGED))
        assertFalse(e.inGroup(GitChangeGroup.UNTRACKED))
    }

    @Test
    fun `已暂存新增后工作区又改过仍是新增`() {
        val e = entry(index = 0x01, worktree = 0x100) // INDEX_NEW + WT_MODIFIED
        assertTrue(e.isNew)
        assertEquals("Am", e.code)
        assertTrue(e.inGroup(GitChangeGroup.STAGED))
    }

    @Test
    fun `已跟踪文件的修改不能判为新增`() {
        val e = entry(index = 0, worktree = 0x100) // WT_MODIFIED
        assertFalse(e.isNew)
        assertTrue(e.inGroup(GitChangeGroup.UNSTAGED))
    }

    @Test
    fun `已暂存的修改不能判为新增`() {
        val e = entry(index = 0x02, worktree = 0) // INDEX_MODIFIED
        assertFalse(e.isNew)
        assertTrue(e.inGroup(GitChangeGroup.STAGED))
    }

    @Test
    fun `已提交文件的删除状态不能判为新增`() {
        val e = entry(index = 0, worktree = 0x200) // WT_DELETED
        assertFalse(e.isNew)
        assertTrue(e.inGroup(GitChangeGroup.UNSTAGED))
    }

    @Test
    fun `忽略文件不算新增`() {
        val e = entry(index = 0, worktree = 0, ignored = true)
        assertFalse(e.isNew)
        assertTrue(e.inGroup(GitChangeGroup.IGNORED))
    }

    @Test
    fun `解析状态帧后 isNew 与分组保持一致`() {
        val raw = "build/out.apk${fs}1${fs}0${fs}0\n" +
            "src/App.kt${fs}0${fs}128${fs}0\n"
        val list = parseStatus(raw)
        assertEquals(2, list.size)

        val stagedNew = list[0]
        assertTrue(stagedNew.isNew)
        assertEquals("A ", stagedNew.code)
        assertTrue(stagedNew.inGroup(GitChangeGroup.STAGED))

        val untracked = list[1]
        assertTrue(untracked.isNew)
        assertEquals("??", untracked.code)
        assertTrue(untracked.inGroup(GitChangeGroup.UNTRACKED))
    }
}
