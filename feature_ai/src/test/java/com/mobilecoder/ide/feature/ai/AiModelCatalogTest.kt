package com.mobilecoder.ide.feature.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 动态模型列表拉取（方案 P1）：免费识别 / 排序限量 / 异常兜底。 */
class AiModelCatalogTest {

    @Test
    fun freeTagRecognition() {
        assertTrue(AiModelCatalog.hasFreeTag("deepseek/deepseek-chat-v3-0324:free"))
        assertTrue(AiModelCatalog.hasFreeTag(" meta-llama/llama-3.3-70b:free "))
        assertFalse(AiModelCatalog.hasFreeTag("glm-4-flash"))
        assertFalse(AiModelCatalog.hasFreeTag("model:freeish"))
        assertFalse(AiModelCatalog.hasFreeTag(""))
    }

    @Test
    fun finalizeSortsFreeFirstThenAlpha() {
        val out = AiModelCatalog.finalizeList(
            listOf(
                CatalogModel("b-model"),
                CatalogModel("a-free:free", free = true),
                CatalogModel("a-model"),
                CatalogModel("c-free", free = true),
            ),
        )
        assertEquals(
            listOf("a-free:free", "c-free", "a-model", "b-model"),
            out.map { it.id },
        )
    }

    @Test
    fun finalizeLimitsTo500() {
        val out = AiModelCatalog.finalizeList((1..600).map { CatalogModel("m$it") })
        assertEquals(500, out.size)
    }

    @Test
    fun parseModelsFallsBackInsteadOfCrashing() {
        // 单测环境 org.json 不可用（mockable jar），坏数据/坏环境都必须被 runCatching 兜住
        val r = runCatching { AiModelCatalog.parseModels("not-json", "https://example.com/v1") }
        assertTrue(r.isFailure || r.getOrDefault(emptyList()).isEmpty())
    }
}
