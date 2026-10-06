package com.mobilecoder.ide.feature.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 内置接口预设（方案 P0）：结构完整性 + 分组/免费标记。 */
class AiPresetTest {

    @Test
    fun presetsAreWellFormed() {
        val presets = AiProviderStore.presets()
        assertTrue("应至少内置 8 个预设", presets.size >= 8)
        assertEquals("名称不可重复", presets.size, presets.map { it.name }.distinct().size)
        assertEquals("Base URL 不可重复", presets.size, presets.map { it.baseUrl }.distinct().size)
        presets.forEach { p ->
            assertTrue("${p.name} 应为 http(s) 地址", p.baseUrl.startsWith("http"))
            assertTrue("${p.name} 应预置至少一个模型", p.models.isNotEmpty())
            assertTrue("${p.name} 模型名不可空白", p.models.all { it.isNotBlank() })
        }
    }

    @Test
    fun domesticAndOverseasGroups() {
        val presets = AiProviderStore.presets()
        val domestic = presets.filter { it.group == PresetGroup.DOMESTIC }
        val overseas = presets.filter { it.group == PresetGroup.OVERSEAS }
        assertEquals("分组须覆盖全部预设", presets.size, domestic.size + overseas.size)
        assertTrue(domestic.any { it.name == "智谱 AI" })
        assertTrue(overseas.any { it.name == "Groq" })
        assertTrue(overseas.any { it.name == "OpenRouter" })
    }

    @Test
    fun freeTags() {
        val presets = AiProviderStore.presets()
        val zhipu = presets.first { it.name == "智谱 AI" }
        assertTrue(zhipu.freeTag)
        assertEquals("glm-4-flash", zhipu.models.first())
        val siliconflow = presets.first { it.name == "硅基流动" }
        assertTrue(siliconflow.freeTag)
        presets.filter { it.group == PresetGroup.OVERSEAS }.forEach {
            assertTrue("${it.name} 海外预设应带免费标记", it.freeTag)
        }
    }
}
