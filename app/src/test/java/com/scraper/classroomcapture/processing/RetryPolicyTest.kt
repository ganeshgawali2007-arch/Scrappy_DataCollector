package com.scraper.classroomcapture.processing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Backoff math + failure classification (todos P6.4).
class RetryPolicyTest {
    @Test
    fun `backoff doubles from 30s and caps at 30min`() {
        assertEquals(0, RetryPolicy.delayMs(0))
        assertEquals(30_000, RetryPolicy.delayMs(1))
        assertEquals(60_000, RetryPolicy.delayMs(2))
        assertEquals(120_000, RetryPolicy.delayMs(3))
        assertEquals(30 * 60_000L, RetryPolicy.delayMs(100))
    }

    @Test
    fun `transient failures retry`() {
        assertTrue(RetryPolicy.isRetryable(EngineErrorCodes.INFERENCE_FAILED))
        assertTrue(RetryPolicy.isRetryable(EngineErrorCodes.OUT_OF_MEMORY))
    }

    @Test
    fun `deterministic failures never retry`() {
        listOf(
            EngineErrorCodes.MODEL_MISSING,
            EngineErrorCodes.MODEL_LOAD_FAILED,
            EngineErrorCodes.MODEL_CORRUPT,
            EngineErrorCodes.INPUT_INVALID,
            EngineErrorCodes.OUTPUT_REJECTED,
        ).forEach { assertFalse(it, RetryPolicy.isRetryable(it)) }
    }

    @Test
    fun `unknown codes fail safe`() {
        assertFalse(RetryPolicy.isRetryable("SOMETHING_NEW"))
    }
}
