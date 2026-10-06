package com.mobilecoder.ide.feature.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 错误分类：状态码 + 文本关键词 → [AiErrorKind]（容灾轮换的直接决策依据）。 */
class AiErrorClassifyTest {

    @Test
    fun `401 and 403 map to AUTH`() {
        assertEquals(AiErrorKind.AUTH, AiClient.httpError(401, "whatever").kind)
        assertEquals(AiErrorKind.AUTH, AiClient.httpError(403, "").kind)
        assertFalse(AiClient.httpError(401, "").retryable)
    }

    @Test
    fun `402 maps to QUOTA and 429 with quota wording maps to QUOTA`() {
        assertEquals(AiErrorKind.QUOTA, AiClient.httpError(402, "").kind)
        val quota429 = AiClient.httpError(429, "insufficient_quota: balance expired")
        assertEquals(AiErrorKind.QUOTA, quota429.kind)
    }

    @Test
    fun `plain 429 maps to RATE_LIMIT and is retryable`() {
        val e = AiClient.httpError(429, "too many requests, slow down")
        assertEquals(AiErrorKind.RATE_LIMIT, e.kind)
        assertTrue(e.retryable)
    }

    @Test
    fun `404 and 5xx mapping`() {
        assertEquals(AiErrorKind.NOT_FOUND, AiClient.httpError(404, "no such model").kind)
        val server = AiClient.httpError(503, "upstream exploded")
        assertEquals(AiErrorKind.SERVER, server.kind)
        assertTrue(server.retryable)
    }

    @Test
    fun `classifyText detects auth quota rate context`() {
        assertEquals(AiErrorKind.AUTH, AiClient.classifyText("invalid api key provided"))
        assertEquals(AiErrorKind.QUOTA, AiClient.classifyText("insufficient_quota"))
        assertEquals(AiErrorKind.RATE_LIMIT, AiClient.classifyText("rate limit exceeded"))
        assertEquals(AiErrorKind.NOT_FOUND, AiClient.classifyText("model gpt-9 does not exist"))
        assertEquals(AiErrorKind.CONTEXT, AiClient.classifyText("context length exceeded"))
        assertEquals(AiErrorKind.UNKNOWN, AiClient.classifyText("whatever happened"))
    }

    @Test
    fun `human message carries kind hint and detail`() {
        val e = AiClient.httpError(401, "sk-xxx is not valid")
        assertTrue(e.message.contains("HTTP 401"))
        assertTrue(e.message.contains("Key"))
        assertTrue(e.message.contains("sk-xxx is not valid"))
    }

    @Test
    fun `rate limit cooldown kinds are the only retryable short list`() {
        assertTrue(AiClient.httpError(429, "").retryable)
        assertTrue(AiClient.httpError(500, "").retryable)
        assertFalse(AiClient.httpError(404, "").retryable)
        assertFalse(AiClient.httpError(402, "").retryable)
    }
}
