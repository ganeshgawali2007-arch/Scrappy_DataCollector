package com.scraper.classroomcapture.asr

import com.scraper.classroomcapture.processing.EngineErrorCodes
import com.scraper.classroomcapture.processing.EngineException
import com.scraper.classroomcapture.recording.AudioConfig
import com.scraper.classroomcapture.recording.WavStreamWriter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

// Engine policy (P7.3/P7.5/P7.7): language gate, WAV gate, model gate,
// native-error mapping, diagnostics. Uses FakeWhisperBridge so the emulator
// gate needs no .so or weights.
class WhisperAsrEngineTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun registryWithModel(id: String = "tiny"): FileModelRegistry {
        val registry = FileModelRegistry(File(tmp.root, "models-$id"))
        val source = File(tmp.root, "$id.bin").apply { writeBytes("weights-$id".toByteArray()) }
        val sha = FileModelRegistry.sha256Of(source)
        registry.installFromFile(source, id, "v1", sha, "MIT")
        return registry
    }

    private fun wav(frames: Int = AudioConfig.SAMPLE_RATE_HZ): File {
        val f = File(tmp.root, "a-${System.nanoTime()}.wav")
        val w = WavStreamWriter(f)
        val chunk = ShortArray(1600)
        var remaining = frames
        while (remaining > 0) {
            val n = minOf(remaining, chunk.size)
            w.writePcm(chunk, n)
            remaining -= n
        }
        w.finalizeAndClose()
        return f
    }

    @Test
    fun `hi en mr transcribe with model identity and diagnostics`() =
        runBlocking {
            val registry = registryWithModel("tiny")
            val bridge = FakeWhisperBridge(textFor = { lang -> "namaste $lang" })
            val engine = WhisperAsrEngine(registry, bridge, "tiny")
            listOf("hi", "en", "mr").forEach { lang ->
                val result = engine.transcribe(wav(), lang)
                assertEquals("namaste $lang", result.text)
                assertEquals("tiny", result.modelId)
                assertEquals("v1", result.modelVersion)
                assertEquals(lang, result.language)
                assertTrue(result.segments.isNotEmpty())
            }
            val diag = engine.lastDiagnostics
            assertTrue(diag != null)
            // 1 s audio, 50 ms fake inference → RTF 0.05.
            assertEquals(1000L, diag!!.audioDurationMs)
            assertEquals(50L, diag.inferenceMs)
            assertTrue(diag.realTimeFactor < 1.0)
        }

    @Test
    fun `unsupported language rejected permanently`() =
        runBlocking {
            val registry = registryWithModel("tiny")
            val engine = WhisperAsrEngine(registry, FakeWhisperBridge(), "tiny")
            try {
                engine.transcribe(wav(), "fr")
                fail("expected INPUT_INVALID")
            } catch (e: EngineException) {
                assertEquals(EngineErrorCodes.INPUT_INVALID, e.code)
            }
        }

    @Test
    fun `missing model maps to MODEL_MISSING`() =
        runBlocking {
            val registry = FileModelRegistry(File(tmp.root, "empty-models"))
            val engine = WhisperAsrEngine(registry, FakeWhisperBridge(), "tiny")
            try {
                engine.transcribe(wav(), "hi")
                fail("expected MODEL_MISSING")
            } catch (e: EngineException) {
                assertEquals(EngineErrorCodes.MODEL_MISSING, e.code)
            }
        }

    @Test
    fun `unavailable bridge maps to MODEL_MISSING`() =
        runBlocking {
            val registry = registryWithModel("tiny")
            val engine = WhisperAsrEngine(registry, FakeWhisperBridge(available = false), "tiny")
            try {
                engine.transcribe(wav(), "hi")
                fail("expected MODEL_MISSING")
            } catch (e: EngineException) {
                assertEquals(EngineErrorCodes.MODEL_MISSING, e.code)
            }
        }

    @Test
    fun `native crash maps to retryable INFERENCE_FAILED`() =
        runBlocking {
            val registry = registryWithModel("tiny")
            val crashing =
                object : WhisperBridge {
                    override fun isAvailable() = true

                    override fun loadModel(modelPath: String) = Unit

                    override fun unload() = Unit

                    override fun transcribe(
                        wavPath: String,
                        language: String,
                    ): NativeTranscription = throw RuntimeException("segfault")
                }
            val engine = WhisperAsrEngine(registry, crashing, "tiny")
            try {
                engine.transcribe(wav(), "en")
                fail("expected INFERENCE_FAILED")
            } catch (e: EngineException) {
                assertEquals(EngineErrorCodes.INFERENCE_FAILED, e.code)
                assertTrue(e.retryable)
            }
        }

    @Test
    fun `bad wav never reaches native bridge`() =
        runBlocking {
            val registry = registryWithModel("tiny")
            val bridge = FakeWhisperBridge()
            val engine = WhisperAsrEngine(registry, bridge, "tiny")
            val bad = File(tmp.root, "bad.wav").apply { writeBytes(ByteArray(50)) }
            try {
                engine.transcribe(bad, "hi")
                fail("expected INPUT_INVALID")
            } catch (e: EngineException) {
                assertEquals(EngineErrorCodes.INPUT_INVALID, e.code)
            }
            assertEquals(0, bridge.transcribes)
        }
}
