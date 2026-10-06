package com.mobilecoder.ide.feature.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 失效冷却：AUTH 整接口、QUOTA/NOT_FOUND 单模型、RATE_LIMIT 短冷却、瞬时错误不标记。 */
class AiFailureTrackerTest {

    private val failMs = 10 * 60_000L
    private val rateMs = 60_000L
    private val t0 = 1_000_000L
    private val tracker = AiFailureTracker(failMs, rateMs)

    private fun endpoint(
        providerId: String = "p1",
        model: String = "m1",
    ) = AiEndpoint(
        providerId = providerId,
        providerName = providerId,
        baseUrl = "https://x/v1",
        model = model,
        apiKey = "",
    )

    @Test
    fun `AUTH marks whole provider`() {
        tracker.mark(AiErrorKind.AUTH, endpoint(model = "m1"), t0)
        assertTrue(tracker.isFailed(endpoint(model = "m1"), t0 + 1))
        assertTrue(tracker.isFailed(endpoint(model = "m2"), t0 + 1))
        assertFalse(tracker.isFailed(endpoint(providerId = "p2", model = "m1"), t0 + 1))
    }

    @Test
    fun `QUOTA marks only that model`() {
        tracker.mark(AiErrorKind.QUOTA, endpoint(model = "m1"), t0)
        assertTrue(tracker.isFailed(endpoint(model = "m1"), t0 + 1))
        assertFalse(tracker.isFailed(endpoint(model = "m2"), t0 + 1))
    }

    @Test
    fun `NOT_FOUND marks only that model`() {
        tracker.mark(AiErrorKind.NOT_FOUND, endpoint(model = "m1"), t0)
        assertTrue(tracker.isFailed(endpoint(model = "m1"), t0 + 1))
        assertFalse(tracker.isFailed(endpoint(model = "m2"), t0 + 1))
    }

    @Test
    fun `RATE_LIMIT uses shorter cooldown`() {
        tracker.mark(AiErrorKind.RATE_LIMIT, endpoint(model = "m1"), t0)
        assertTrue(tracker.isFailed(endpoint(model = "m1"), t0 + rateMs - 1))
        assertFalse(tracker.isFailed(endpoint(model = "m1"), t0 + rateMs + 1))
    }

    @Test
    fun `QUOTA cooldown expires after full window`() {
        tracker.mark(AiErrorKind.QUOTA, endpoint(model = "m1"), t0)
        assertTrue(tracker.isFailed(endpoint(model = "m1"), t0 + failMs - 1))
        assertFalse(tracker.isFailed(endpoint(model = "m1"), t0 + failMs + 1))
    }

    @Test
    fun `transient errors never mark`() {
        listOf(
            AiErrorKind.TIMEOUT,
            AiErrorKind.NETWORK,
            AiErrorKind.SERVER,
            AiErrorKind.PARSE,
            AiErrorKind.UNKNOWN,
            AiErrorKind.CONTEXT,
        ).forEach { kind ->
            tracker.mark(kind, endpoint(model = "m1"), t0)
        }
        assertFalse(tracker.isFailed(endpoint(model = "m1"), t0 + 1))
    }

    @Test
    fun `clear resets everything`() {
        tracker.mark(AiErrorKind.AUTH, endpoint(model = "m1"), t0)
        assertTrue(tracker.isFailed(endpoint(model = "m1"), t0 + 1))
        tracker.clear()
        assertFalse(tracker.isFailed(endpoint(model = "m1"), t0 + 1))
    }
}
