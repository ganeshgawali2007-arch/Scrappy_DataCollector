package com.scraper.classroomcapture.llm

import com.scraper.classroomcapture.data.serialization.AsrBlock
import com.scraper.classroomcapture.data.serialization.AudioBlock
import com.scraper.classroomcapture.data.serialization.GradeValue
import com.scraper.classroomcapture.data.serialization.PedagogicalRecordV2
import com.scraper.classroomcapture.data.serialization.ProvenanceBlock
import com.scraper.classroomcapture.processing.EngineErrorCodes
import com.scraper.classroomcapture.processing.EngineException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

// P10: GGUF registry install/verify, prompt versioning, strict JSON
// validation, evidence grounding, insufficient-evidence handling, and
// MODEL_MISSING/CORRUPT mapping (capture/export never blocked).
class LlmEngineTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var modelsDir: File

    @Before fun setUp() {
        modelsDir = File(tmp.root, "models").apply { mkdirs() }
    }

    private fun record(
        source: String,
        sampleId: String = "a1",
    ): PedagogicalRecordV2 =
        PedagogicalRecordV2(
            sampleId = sampleId,
            sessionId = "s1",
            sequenceNumber = 1,
            source = source,
            sourceLanguage = "mr",
            grade = GradeValue.Numeric(3),
            subject = "Mathematics",
            audio = AudioBlock("audio/a1.wav", "abc", durationMs = 1000),
            asr = AsrBlock(status = "available", text = source),
            provenance = ProvenanceBlock(createdAt = "2026-09-15T00:00:00Z", processingState = "TRANSCRIBED", appVersion = "test"),
        )

    private fun writeModelFile(
        name: String = "model.gguf",
        bytes: ByteArray = "gguf-bytes".toByteArray(),
    ): File {
        val f = File(tmp.root, name)
        f.writeBytes(bytes)
        return f
    }

    private fun shaOf(f: File): String {
        val d = MessageDigest.getInstance("SHA-256")
        d.update(f.readBytes())
        return d.digest().joinToString("") { "%02x".format(it) }
    }

    @Test fun `valid output with grounded evidence passes`() =
        runBlocking {
            val src = writeModelFile()
            val reg = GgufRegistry(modelsDir)
            reg.installFromFile(src, "schema-gen", "1.0", shaOf(src), "MIT")
            val validJson =
                """{"activity":"explanation","pedagogical_form":"explanation",""" +
                    """"concept_stage":"practice","evidence_spans":[{"field":"activity","quote":"hello class"}]}"""
            val bridge = FakeLlamaBridge(jsonFor = { validJson })
            val engine = LocalLlmEngine(reg, bridge, "schema-gen")
            val res = engine.annotate(record("hello class"))
            assertEquals("explanation", res.activity)
            assertTrue(res.generatedBy.startsWith("AI_SUGGESTION:"))
            assertEquals("1.0", res.modelVersion)
            assertEquals(PromptTemplate.VERSION, res.promptVersion)
            assertTrue(bridge.lastPrompt!!.contains(PromptTemplate.VERSION))
        }

    @Test fun `insufficient transcript never hallucinates`() =
        runBlocking {
            val src = writeModelFile("m2.gguf")
            val reg = GgufRegistry(modelsDir)
            reg.installFromFile(src, "schema-gen", "1.0", shaOf(src), "MIT")
            val engine = LocalLlmEngine(reg, FakeLlamaBridge(), "schema-gen")
            try {
                engine.annotate(record(""))
                fail("expected insufficient evidence")
            } catch (e: EngineException) {
                assertEquals(EngineErrorCodes.INPUT_INVALID, e.code)
                assertTrue(e.message!!.contains("insufficient_audio_evidence"))
            }
        }

    @Test fun `malformed json is rejected without persistence`() =
        runBlocking {
            val src = writeModelFile("m3.gguf")
            val reg = GgufRegistry(modelsDir)
            reg.installFromFile(src, "schema-gen", "1.0", shaOf(src), "MIT")
            val engine = LocalLlmEngine(reg, FakeLlamaBridge(jsonFor = { "not json {" }), "schema-gen")
            try {
                engine.annotate(record("hello class"))
                fail("expected rejection")
            } catch (e: EngineException) {
                assertEquals(EngineErrorCodes.OUTPUT_REJECTED, e.code)
            }
        }

    @Test fun `ungrounded evidence quote is rejected`() =
        runBlocking {
            val src = writeModelFile("m4.gguf")
            val reg = GgufRegistry(modelsDir)
            reg.installFromFile(src, "schema-gen", "1.0", shaOf(src), "MIT")
            val engine =
                LocalLlmEngine(
                    reg,
                    FakeLlamaBridge(jsonFor = {
                        """{"activity":"explanation","evidence_spans":[{"field":"activity","quote":"invented phrase never spoken"}]}"""
                    }),
                    "schema-gen",
                )
            try {
                engine.annotate(record("hello class"))
                fail("expected rejection")
            } catch (e: EngineException) {
                assertEquals(EngineErrorCodes.OUTPUT_REJECTED, e.code)
            }
        }

    @Test fun `missing model maps to MODEL_MISSING`() =
        runBlocking {
            val engine = LocalLlmEngine(GgufRegistry(modelsDir), FakeLlamaBridge(), "schema-gen")
            try {
                engine.annotate(record("hello class"))
                fail("expected missing")
            } catch (e: EngineException) {
                assertEquals(EngineErrorCodes.MODEL_MISSING, e.code)
            }
        }

    @Test fun `unavailable bridge maps to MODEL_MISSING`() =
        runBlocking {
            val src = writeModelFile("m5.gguf")
            val reg = GgufRegistry(modelsDir)
            reg.installFromFile(src, "schema-gen", "1.0", shaOf(src), "MIT")
            val engine = LocalLlmEngine(reg, FakeLlamaBridge(available = false), "schema-gen")
            try {
                engine.annotate(record("hello class"))
                fail("expected missing")
            } catch (e: EngineException) {
                assertEquals(EngineErrorCodes.MODEL_MISSING, e.code)
            }
        }

    @Test fun `registry rejects bad sha`() {
        val src = writeModelFile("m6.gguf")
        val reg = GgufRegistry(modelsDir)
        try {
            reg.installFromFile(src, "schema-gen", "1.0", "00".repeat(32), "MIT")
            fail("expected install failure")
        } catch (e: Exception) {
            assertTrue(e.message!!.contains("SHA-256"))
        }
    }

    @Test fun `prompt template is versioned and bounded`() {
        val p = PromptTemplate.build(record("hello"))
        assertTrue(p.contains(PromptTemplate.VERSION))
        assertTrue(p.contains("hello"))
    }
}
