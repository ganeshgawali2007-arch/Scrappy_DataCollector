package com.scraper.classroomcapture.asr

import com.scraper.classroomcapture.processing.EngineException
import com.scraper.classroomcapture.recording.AudioConfig
import com.scraper.classroomcapture.recording.WavStreamWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile

// WAV gate before native inference (P7.5). Uses real files written by the
// P4 writer so the contract matches capture output exactly.
class WavValidatorTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `valid 1s capture passes with duration`() {
        val wav = writeWav(frames = AudioConfig.SAMPLE_RATE_HZ)
        val valid = WavValidator.validate(wav)
        assertEquals(1000L, valid.durationMs)
    }

    @Test
    fun `hi en mr fixtures all validate`() {
        // Developer fixtures (P7.6): clearly labeled synthetic tones, never
        // presented as field accuracy. Each language gets a distinct file so
        // the pipeline test can assert per-language routing.
        listOf("hi", "en", "mr").forEach { lang ->
            val wav = writeWav(frames = AudioConfig.SAMPLE_RATE_HZ / 2, name = "fixture_$lang.wav")
            val valid = WavValidator.validate(wav)
            assertEquals(500L, valid.durationMs)
        }
    }

    @Test
    fun `non-wav rejected`() {
        val f = tmp.newFile("bad.wav")
        f.writeBytes(ByteArray(100))
        expectInvalid(f)
    }

    @Test
    fun `empty audio rejected`() {
        val f = tmp.newFile("empty.wav")
        // Header claiming zero frames.
        f.writeBytes(WavStreamWriter.buildHeader(0) + ByteArray(0))
        expectInvalid(f)
    }

    @Test
    fun `too-short audio rejected`() {
        val wav = writeWav(frames = 100) // ~6 ms
        expectInvalid(wav)
    }

    @Test
    fun `wrong sample rate rejected`() {
        val wav = writeWav(frames = AudioConfig.SAMPLE_RATE_HZ)
        // Patch sample rate to 8 kHz.
        RandomAccessFile(wav, "rw").use { raf ->
            raf.seek(24)
            raf.writeInt(Integer.reverseBytes(8000))
        }
        expectInvalid(wav)
    }

    @Test
    fun `truncated file rejected`() {
        val wav = writeWav(frames = AudioConfig.SAMPLE_RATE_HZ)
        // Claim more data than the file holds.
        RandomAccessFile(wav, "rw").use { raf ->
            raf.setLength(100)
        }
        expectInvalid(wav)
    }

    private fun writeWav(
        frames: Int,
        name: String = "audio.wav",
    ): File {
        val f = File(tmp.root, name)
        val writer = WavStreamWriter(f)
        val chunk = ShortArray(1600) { (it % 100).toShort() }
        var remaining = frames
        while (remaining > 0) {
            val n = minOf(remaining, chunk.size)
            writer.writePcm(chunk, n)
            remaining -= n
        }
        writer.finalizeAndClose()
        assertTrue(f.isFile)
        return f
    }

    private fun expectInvalid(f: File) {
        try {
            WavValidator.validate(f)
            fail("expected INPUT_INVALID for ${f.name}")
        } catch (e: EngineException) {
            assertEquals("INPUT_INVALID", e.code)
        }
    }
}
