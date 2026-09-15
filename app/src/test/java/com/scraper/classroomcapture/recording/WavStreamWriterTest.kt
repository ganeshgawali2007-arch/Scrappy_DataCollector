package com.scraper.classroomcapture.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

// WAV writer correctness (todos P4.4/P4.8): header layout, size patching,
// and 10-minute size math without writing 19 MB in the test.
class WavStreamWriterTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `one second of audio finalizes to valid wav`() {
        val file = File(tmp.root, "one.wav")
        val writer = WavStreamWriter(file)
        val frames = ShortArray(1600) { 1000 }
        repeat(10) { writer.writePcm(frames, frames.size) }
        assertEquals(16000L, writer.framesWritten)
        assertEquals(1000L, writer.durationMs())
        writer.finalizeAndClose()

        assertEquals(44L + 16000 * 2, file.length())
        val header = file.readBytes().copyOfRange(0, 44)
        assertEquals("RIFF", String(header.copyOfRange(0, 4)))
        assertEquals("WAVE", String(header.copyOfRange(8, 12)))
        assertEquals("fmt ", String(header.copyOfRange(12, 16)))
        assertEquals("data", String(header.copyOfRange(36, 40)))
        val sizes = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(36 + 32000, sizes.getInt(4))
        assertEquals(32000, sizes.getInt(40))
        assertEquals(16000, sizes.getInt(24))
        assertEquals(16, sizes.getShort(34).toInt())
    }

    @Test
    fun `ten minute size math is exact`() {
        val tenMinutesFrames = 10L * 60 * AudioConfig.SAMPLE_RATE_HZ
        assertEquals(9_600_000L, tenMinutesFrames)
        val header = WavStreamWriter.buildHeader(tenMinutesFrames)
        val sizes = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val dataBytes = 9_600_000L * 2
        assertEquals((36 + dataBytes).toInt(), sizes.getInt(4))
        assertEquals(dataBytes.toInt(), sizes.getInt(40))
        // ~19.2 MB: comfortably under the 100 MB refuse threshold.
        assertTrue(dataBytes < 100L * 1024 * 1024)
    }

    @Test
    fun `finalize is idempotent and post finalize writes fail`() {
        val file = File(tmp.root, "x.wav")
        val writer = WavStreamWriter(file)
        writer.finalizeAndClose()
        writer.finalizeAndClose()
        var thrown = false
        try {
            writer.writePcm(ShortArray(8), 8)
        } catch (e: IllegalStateException) {
            thrown = true
        }
        assertTrue(thrown)
    }

    @Test
    fun `oversize sample is refused not wrapped`() {
        var thrown = false
        try {
            WavStreamWriter.buildHeader((Int.MAX_VALUE - 36L) / 2 + 1)
        } catch (e: IllegalArgumentException) {
            thrown = true
        }
        assertTrue(thrown)
    }
}
