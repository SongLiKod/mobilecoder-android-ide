package com.mobilecoder.ide.feature.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 候选序列：当前接口/模型优先，禁用与缺配置的接口不参与（容灾轮换的决策基础）。 */
class AiCandidatesTest {

    private fun provider(
        id: String,
        baseUrl: String = "https://$id.example.com/v1",
        enabled: Boolean = true,
        activeModelId: String = "",
        models: List<AiModel>,
    ) = AiProvider(
        id = id,
        name = id.uppercase(),
        baseUrl = baseUrl,
        enabled = enabled,
        models = models,
        activeModelId = activeModelId,
    )

    @Test
    fun `active endpoint leads and active model first`() {
        val config = AiProvidersConfig(
            providers = listOf(
                provider("p1", activeModelId = "m2", models = listOf(AiModel("m1"), AiModel("m2"))),
                provider("p2", activeModelId = "n1", models = listOf(AiModel("n1"))),
            ),
            activeProviderId = "p1",
        )
        val candidates = config.candidates()
        assertEquals("p1", candidates[0].providerId)
        assertEquals("m2", candidates[0].model)
        assertEquals(listOf("m2", "m1", "n1"), candidates.map { it.model })
        assertEquals("p2", candidates[2].providerId)
    }

    @Test
    fun `disabled provider and model are excluded`() {
        val config = AiProvidersConfig(
            providers = listOf(
                provider("p1", enabled = false, activeModelId = "m1", models = listOf(AiModel("m1"))),
                provider(
                    "p2",
                    activeModelId = "n1",
                    models = listOf(AiModel("n1"), AiModel("n2", enabled = false)),
                ),
            ),
            activeProviderId = "p1",
        )
        val candidates = config.candidates()
        assertEquals(listOf("n1"), candidates.map { it.model })
        assertEquals("p2", candidates[0].providerId)
    }

    @Test
    fun `provider without baseUrl or ready model is skipped`() {
        val noUrl = provider("p1", baseUrl = "  ", activeModelId = "m1", models = listOf(AiModel("m1")))
        val noModels = provider("p2", activeModelId = "", models = emptyList())
        val ok = provider("p3", activeModelId = "x", models = listOf(AiModel("x")))
        val config = AiProvidersConfig(providers = listOf(noUrl, noModels, ok), activeProviderId = "p1")
        assertEquals(listOf("p3"), config.candidates().map { it.providerId })
    }

    @Test
    fun `blank active id falls back to first ready provider`() {
        val config = AiProvidersConfig(
            providers = listOf(
                provider("p1", baseUrl = "  ", models = listOf(AiModel("m1"))),
                provider("p2", activeModelId = "n1", models = listOf(AiModel("n1"))),
            ),
            activeProviderId = "",
        )
        assertEquals("p2", config.activeEndpoint()?.providerId)
        assertEquals("p2", config.candidates()[0].providerId)
    }

    @Test
    fun `no ready endpoint yields null and empty candidates`() {
        val config = AiProvidersConfig()
        assertNull(config.activeEndpoint())
        assertTrue(config.candidates().isEmpty())
    }

    @Test
    fun `endpoint url joins chat completions and label`() {
        val endpoint = AiEndpoint(
            provider = provider("p1", baseUrl = "https://api.x.com/v1/", activeModelId = "m", models = listOf(AiModel("m"))),
            modelId = "m",
        )
        assertEquals("https://api.x.com/v1/chat/completions", endpoint.endpoint)
        assertEquals("P1 / m", endpoint.label)
        assertFalse(endpoint.endpoint.contains("//v1//"))
    }
}
