package com.scraper.classroomcapture.data.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Threshold boundary tests (todos P3.5). Pure functions — no filesystem.
class StoragePreflightTest {
    @Test
    fun `plenty of space is ok`() {
        assertTrue(StoragePreflight.check(2L * 1024 * 1024 * 1024, 0) is PreflightResult.Ok)
    }

    @Test
    fun `below warn threshold reports low`() {
        val result = StoragePreflight.check(StoragePreflight.WARN_FREE_BYTES - 1, 0)
        assertTrue(result is PreflightResult.Low)
    }

    @Test
    fun `below refuse threshold refuses`() {
        val result = StoragePreflight.check(StoragePreflight.REFUSE_FREE_BYTES - 1, 0)
        assertTrue(result is PreflightResult.Refused)
    }

    @Test
    fun `needed bytes project against thresholds`() {
        // 150 MB free, 60 MB recording planned -> 90 MB projected -> refuse.
        val refused = StoragePreflight.check(150L * 1024 * 1024, 60L * 1024 * 1024)
        assertTrue(refused is PreflightResult.Refused)
        // Same free space, 1 MB sidecar -> still above warn -> ok.
        val ok = StoragePreflight.check(600L * 1024 * 1024, 1024L * 1024)
        assertTrue(ok is PreflightResult.Ok)
    }

    @Test
    fun `refusal carries free bytes for the resolution message`() {
        val result = StoragePreflight.check(10L, 0) as PreflightResult.Refused
        assertEquals(10L, result.freeBytes)
    }
}
