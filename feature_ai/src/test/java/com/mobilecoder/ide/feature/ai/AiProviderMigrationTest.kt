package com.mobilecoder.ide.feature.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 旧单接口配置 → 多接口结构的迁移组装。 */
class AiProviderMigrationTest {

    @Test
    fun `deepseek legacy url becomes named ready provider`() {
        val config = AiProviderStore.legacyConfig(
            baseUrl = "https://api.deepseek.com/v1",
            model = "deepseek-chat",
            key = "sk-123",
        )
        assertEquals(1, config.providers.size)
        val p = config.providers.single()
        assertEquals("DeepSeek", p.name)
        assertEquals("https://api.deepseek.com/v1", p.baseUrl)
        assertEquals("sk-123", p.apiKey)
        assertEquals("deepseek-chat", p.activeModelId)
        assertEquals(listOf("deepseek-chat"), p.models.map { it.id })
        assertTrue(p.looksReady)
        assertEquals(p.id, config.activeProviderId)
        assertEquals("deepseek-chat", config.activeEndpoint()?.model)
    }

    @Test
    fun `blank legacy model yields not-ready provider`() {
        val config = AiProviderStore.legacyConfig("https://api.openai.com/v1", "", "sk-x")
        val p = config.providers.single()
        assertTrue(p.models.isEmpty())
        assertFalse(p.looksReady)
        assertEquals(null, config.activeEndpoint())
    }

    @Test
    fun `legacy name heuristics`() {
        assertEquals("OpenAI", AiProviderStore.legacyNameOf("https://api.openai.com/v1"))
        assertEquals("Kimi", AiProviderStore.legacyNameOf("https://api.moonshot.cn/v1"))
        assertEquals("通义千问", AiProviderStore.legacyNameOf("https://dashscope.aliyuncs.com/compatible-mode/v1"))
        assertEquals("Ollama", AiProviderStore.legacyNameOf("http://127.0.0.1:11434/v1"))
        assertEquals("Ollama", AiProviderStore.legacyNameOf("http://localhost:11434/v1"))
        assertEquals("默认接口", AiProviderStore.legacyNameOf("https://proxy.example.com/api"))
    }

    @Test
    fun `presets are well formed`() {
        val presets = AiProviderStore.presets()
        assertTrue(presets.size >= 4)
        presets.forEach { preset ->
            assertTrue(preset.name.isNotBlank())
            assertTrue(preset.baseUrl.startsWith("http"))
            assertTrue(preset.models.isNotEmpty())
        }
    }
}
