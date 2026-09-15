package com.scraper.classroomcapture.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

// Meter math on synthetic PCM (todos P4.8).
class MeterStatsTest {
    @Test
    fun `silence gives null levels and full silence ratio`() {
        val meter = MeterStats()
        meter.addChunk(ShortArray(1600), 1600)
        assertNull(meter.overallRmsDb())
        assertNull(meter.peakDb())
        assertEquals(1.0, meter.silenceRatio()!!, 1e-9)
        assertFalse(meter.sustainedClipping())
    }

    @Test
    fun `constant tone gives expected decibels`() {
        val meter = MeterStats()
        meter.addChunk(ShortArray(1600) { 10000 }, 1600)
        // 20*log10(10000/32768) ≈ -10.31 dB
        assertEquals(-10.31, meter.overallRmsDb()!!, 0.01)
        assertEquals(-10.31, meter.peakDb()!!, 0.01)
        assertEquals(0.0, meter.silenceRatio()!!, 1e-9)
    }

    @Test
    fun `full scale samples count as clipped`() {
        val meter = MeterStats()
        meter.addChunk(ShortArray(1600) { 32767 }, 1600)
        assertTrue(meter.clippedSamples > 0)
        assertEquals(0.0, meter.peakDb()!!, 0.01)
    }

    @Test
    fun `sustained clipping needs a full persistent window`() {
        val meter = MeterStats()
        repeat(10) { meter.addChunk(ShortArray(1600) { 32767 }, 1600) }
        assertFalse(meter.sustainedClipping())
        repeat(40) { meter.addChunk(ShortArray(1600) { 32767 }, 1600) }
        assertTrue(meter.sustainedClipping())
    }

    @Test
    fun `sparse clipping does not warn`() {
        val meter = MeterStats()
        repeat(50) { i ->
            if (i % 5 == 0) {
                meter.addChunk(ShortArray(1600) { 32767 }, 1600)
            } else {
                meter.addChunk(ShortArray(1600) { 100 }, 1600)
            }
        }
        // 20% clipped chunks: under the 30% threshold.
        assertFalse(meter.sustainedClipping())
        assertTrue(meter.clippedSamples > 0)
    }

    @Test
    fun `empty stats are all null`() {
        val meter = MeterStats()
        assertNull(meter.overallRmsDb())
        assertNull(meter.peakDb())
        assertNull(meter.silenceRatio())
        assertEquals(0L, meter.totalFrames)
    }

    @Test
    fun `partial chunk lengths accumulate exactly`() {
        val meter = MeterStats()
        meter.addChunk(ShortArray(1600) { 500 }, 400)
        meter.addChunk(ShortArray(1600) { 500 }, 1200)
        assertEquals(1600L, meter.totalFrames)
        assertEquals(2L, meter.totalChunks)
        assertTrue(abs(meter.overallRmsDb()!! - -36.33) < 0.01)
    }
}
