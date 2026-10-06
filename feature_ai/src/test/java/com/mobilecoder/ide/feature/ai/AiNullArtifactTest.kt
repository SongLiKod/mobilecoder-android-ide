package com.mobilecoder.ide.feature.ai

import org.junit.Assert.assertEquals
import org.junit.Test

/** 旧落盘数据 "null"/"nullnull" 脏内容清洗（SSE content JSON null 事故的兜底）。 */
class AiNullArtifactTest {

    @Test
    fun nullArtifactCleaned() {
        assertEquals("", "null".cleanJsonNullArtifact())
        assertEquals("", "nullnull".cleanJsonNullArtifact())
        assertEquals("", "nullnullnull".cleanJsonNullArtifact())
        assertEquals("", "  nullnull  ".cleanJsonNullArtifact())
    }

    @Test
    fun realContentKept() {
        assertEquals("你好", "你好".cleanJsonNullArtifact())
        assertEquals("nullable 是一个概念", "nullable 是一个概念".cleanJsonNullArtifact())
        assertEquals("null hypothesis", "null hypothesis".cleanJsonNullArtifact())
        assertEquals("", "".cleanJsonNullArtifact())
    }
}
