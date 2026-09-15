package com.scraper.classroomcapture.processing

import com.scraper.classroomcapture.data.serialization.AnnotationResultV1
import com.scraper.classroomcapture.data.serialization.AsrResultV1
import com.scraper.classroomcapture.data.serialization.PedagogicalRecordV2
import com.scraper.classroomcapture.data.serialization.ScrappyJson
import com.scraper.classroomcapture.data.store.FakeArtifactRepository
import com.scraper.classroomcapture.data.store.FakeSampleRepository
import com.scraper.classroomcapture.domain.model.Artifact
import com.scraper.classroomcapture.domain.model.ArtifactKind
import com.scraper.classroomcapture.domain.model.ClassroomSample
import com.scraper.classroomcapture.domain.model.JobKind
import com.scraper.classroomcapture.domain.model.JobState
import com.scraper.classroomcapture.domain.model.ProcessingJob
import com.scraper.classroomcapture.domain.model.QualityMetrics
import com.scraper.classroomcapture.domain.model.SampleState
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

// Dispatcher behavior with fake engines (todos P6): success, idempotent
// skip, checksum gate, retry/backoff, permanent failure, lease reclaim,
// LLM exportable fallback, and recording priority.
class DispatcherTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var samples: FakeSampleRepository
    private lateinit var artifacts: FakeArtifactRepository
    private lateinit var jobs: FakeJobRepository
    private lateinit var events: FakeEventRepository
    private lateinit var store: com.scraper.classroomcapture.data.store.FileArtifactStore
    private var now = 1_000_000L
    private var recording = false

    private fun dispatcher(): ProcessingDispatcher =
        ProcessingDispatcher(
            samples = samples,
            artifacts = artifacts,
            jobs = jobs,
            events = events,
            store = store,
            recordingActive = { recording },
            clock = { now },
            appVersion = "test",
        )

    @Before
    fun setUp() {
        samples = FakeSampleRepository()
        artifacts = FakeArtifactRepository()
        jobs = FakeJobRepository()
        events = FakeEventRepository()
        store = com.scraper.classroomcapture.data.store.FileArtifactStore(File(tmp.root, "scrappy"))
    }

    @Test
    fun `asr success transcribes persists and advances`() =
        runBlocking {
            samples.samples["a1"] = sample("a1", SampleState.QUEUED_ASR)
            writeAudio("s1", "a1")
            addAudioArtifact("a1")
            jobs.enqueue(job("j1", "a1", JobKind.ASR))
            val engine = FakeAsrEngine("hello class")
            val d = dispatcher()
            d.registerAsrEngine(engine)

            d.pumpOnce()

            assertEquals(SampleState.TRANSCRIBED, samples.samples["a1"]?.state)
            assertEquals(JobState.SUCCEEDED, jobs.jobs["j1"]?.state)
            assertEquals("test-asr", jobs.jobs["j1"]?.modelId)
            assertEquals(1, engine.calls)
            val sidecar = artifacts.artifacts.firstOrNull { it.kind == ArtifactKind.ASR_JSON }
            requireNotNull(sidecar)
            assertTrue(store.exists(sidecar.relativePath))
            assertTrue(events.events.any { it.type == "ASR_SUCCEEDED" })
        }

    @Test
    fun `finished work is skipped never duplicated`() =
        runBlocking {
            samples.samples["a1"] = sample("a1", SampleState.TRANSCRIBED)
            jobs.enqueue(job("j1", "a1", JobKind.ASR))
            val engine = FakeAsrEngine("x")
            val d = dispatcher()
            d.registerAsrEngine(engine)

            d.pumpOnce()

            assertEquals(0, engine.calls)
            assertEquals(JobState.SUCCEEDED, jobs.jobs["j1"]?.state)
            assertTrue(artifacts.artifacts.isEmpty())
        }

    @Test
    fun `checksum mismatch fails permanently`() =
        runBlocking {
            samples.samples["a1"] = sample("a1", SampleState.QUEUED_ASR)
            writeAudio("s1", "a1")
            // Artifact row claims a different hash (audio changed under us).
            artifacts.artifacts += audioArtifact("a1", "deadbeef")
            jobs.enqueue(job("j1", "a1", JobKind.ASR))
            val engine = FakeAsrEngine("x")
            val d = dispatcher()
            d.registerAsrEngine(engine)

            d.pumpOnce()

            assertEquals(0, engine.calls)
            assertEquals(SampleState.ERROR, samples.samples["a1"]?.state)
            assertEquals(JobState.FAILED, jobs.jobs["j1"]?.state)
        }

    @Test
    fun `retryable failure backs off then succeeds`() =
        runBlocking {
            samples.samples["a1"] = sample("a1", SampleState.QUEUED_ASR)
            writeAudio("s1", "a1")
            addAudioArtifact("a1")
            jobs.enqueue(job("j1", "a1", JobKind.ASR))
            val engine = FlakyAsrEngine(failures = 1)
            val d = dispatcher()
            d.registerAsrEngine(engine)

            d.pumpOnce()
            // First attempt failed retryably: requeued with a 30 s delay.
            assertEquals(JobState.QUEUED, jobs.jobs["j1"]?.state)
            assertEquals(SampleState.QUEUED_ASR, samples.samples["a1"]?.state)
            assertEquals(now + 30_000, jobs.jobs["j1"]?.nextAttemptAt)
            assertTrue(events.events.any { it.type == "JOB_RETRY" })

            // Too early: not due.
            now += 10_000
            d.pumpOnce()
            assertEquals(1, engine.calls)

            // Due: second attempt succeeds (attemptCount 2).
            now += 25_000
            d.pumpOnce()
            assertEquals(2, engine.calls)
            assertEquals(SampleState.TRANSCRIBED, samples.samples["a1"]?.state)
            assertEquals(2, jobs.jobs["j1"]?.attemptCount)
        }

    @Test
    fun `missing model fails permanently without retry`() =
        runBlocking {
            samples.samples["a1"] = sample("a1", SampleState.QUEUED_ASR)
            writeAudio("s1", "a1")
            addAudioArtifact("a1")
            jobs.enqueue(job("j1", "a1", JobKind.ASR))
            val d = dispatcher()
            d.registerAsrEngine(FailingAsrEngine(EngineErrorCodes.MODEL_MISSING, false))

            d.pumpOnce()

            assertEquals(JobState.FAILED, jobs.jobs["j1"]?.state)
            assertEquals(SampleState.ERROR, samples.samples["a1"]?.state)
            assertEquals(EngineErrorCodes.MODEL_MISSING, jobs.jobs["j1"]?.lastErrorCode)
            assertTrue(events.events.none { it.type == "JOB_RETRY" })
        }

    @Test
    fun `expired lease is reclaimed and completed once`() =
        runBlocking {
            samples.samples["a1"] = sample("a1", SampleState.TRANSCRIBING)
            writeAudio("s1", "a1")
            addAudioArtifact("a1")
            // Simulates a kill mid-transcription: RUNNING with a lapsed lease.
            jobs.jobs["j1"] =
                job("j1", "a1", JobKind.ASR).copy(
                    state = JobState.RUNNING,
                    leaseOwner = "dead-process",
                    leaseExpiresAt = now - 1,
                    attemptCount = 1,
                )
            val engine = FakeAsrEngine("recovered")
            val d = dispatcher()
            d.registerAsrEngine(engine)

            d.pumpOnce()

            assertEquals(1, engine.calls)
            assertEquals(SampleState.TRANSCRIBED, samples.samples["a1"]?.state)
            assertEquals(1, artifacts.artifacts.count { it.kind == ArtifactKind.ASR_JSON })
            assertTrue(events.events.any { it.type == "LEASES_RECLAIMED" })
        }

    @Test
    fun `permanent llm failure keeps audio and asr exportable`() =
        runBlocking {
            samples.samples["a1"] = sample("a1", SampleState.QUEUED_LLM)
            writeAudio("s1", "a1")
            addAudioArtifact("a1")
            addAsrArtifact("a1")
            jobs.enqueue(job("j1", "a1", JobKind.LLM))
            val d = dispatcher()
            d.registerLlmEngine(FailingLlmEngine(EngineErrorCodes.OUTPUT_REJECTED))

            d.pumpOnce()

            assertEquals(JobState.FAILED, jobs.jobs["j1"]?.state)
            assertEquals(SampleState.READY_FOR_EXPORT, samples.samples["a1"]?.state)
            assertTrue(events.events.any { it.type == "LLM_FAILED_EXPORTABLE" })
        }

    @Test
    fun `recording priority pauses claiming`() =
        runBlocking {
            samples.samples["a1"] = sample("a1", SampleState.QUEUED_ASR)
            writeAudio("s1", "a1")
            addAudioArtifact("a1")
            jobs.enqueue(job("j1", "a1", JobKind.ASR))
            val engine = FakeAsrEngine("x")
            val d = dispatcher()
            d.registerAsrEngine(engine)
            recording = true

            d.pumpOnce()

            assertEquals(0, engine.calls)
            assertEquals(JobState.QUEUED, jobs.jobs["j1"]?.state)
        }

    @Test
    fun `duplicate job rows are rejected`() =
        runBlocking {
            jobs.enqueue(job("j1", "a1", JobKind.ASR))
            var thrown = false
            try {
                jobs.enqueue(job("j2", "a1", JobKind.ASR))
            } catch (e: IllegalStateException) {
                thrown = true
            }
            assertTrue(thrown)
        }

    // ---- helpers -----------------------------------------------------------

    private fun sample(
        id: String,
        state: SampleState,
    ): ClassroomSample =
        ClassroomSample(
            id, "s1", 1, state, "hi", null, null, null, false,
            QualityMetrics.unknown(), null, null, false, null, null, null, now, now,
        )

    private fun job(
        id: String,
        sampleId: String,
        kind: JobKind,
    ): ProcessingJob =
        ProcessingJob(
            id, sampleId, kind, JobState.QUEUED, 0, 5, null, null, null,
            null, null, null, null, null, now, now,
        )

    private fun writeAudio(
        sessionId: String,
        sampleId: String,
    ) {
        val dir = File(tmp.root, "scrappy/sessions/$sessionId/samples/$sampleId")
        dir.mkdirs()
        File(dir, "audio.wav").writeBytes("fake-wav-bytes".toByteArray())
    }

    private fun audioArtifact(
        sampleId: String,
        sha: String,
    ): Artifact =
        Artifact(
            "art-$sampleId",
            sampleId,
            ArtifactKind.AUDIO_WAV,
            "sessions/s1/samples/$sampleId/audio.wav",
            sha,
            14,
            "audio/wav",
            16000,
            1,
            1000,
            now,
        )

    private fun addAudioArtifact(sampleId: String) {
        val dir = File(tmp.root, "scrappy/sessions/s1/samples/$sampleId")
        val bytes = File(dir, "audio.wav").readBytes()
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        val sha = digest.joinToString("") { "%02x".format(it) }
        artifacts.artifacts += audioArtifact(sampleId, sha)
    }

    private fun addAsrArtifact(sampleId: String) {
        val rel = "sessions/s1/samples/$sampleId/asr.v1.json"
        val payload =
            ScrappyJson.encodeToString(
                AsrResultV1(
                    sampleId = sampleId,
                    text = "hi",
                    language = "hi",
                    modelId = "m",
                    modelVersion = "1",
                    createdAt = "t",
                ),
            )
        File(tmp.root, "scrappy/$rel").apply {
            parentFile?.mkdirs()
            writeText(payload)
        }
        artifacts.artifacts +=
            Artifact(
                "asr-$sampleId",
                sampleId,
                ArtifactKind.ASR_JSON,
                rel,
                "x",
                10,
                "application/json",
                null,
                null,
                null,
                now,
            )
    }

    class FakeAsrEngine(private val text: String) : AsrEngine {
        override val modelId = "test-asr"
        override val modelVersion = "1"
        var calls = 0

        override suspend fun transcribe(
            wavFile: File,
            language: String,
        ): AsrResultV1 {
            calls++
            return AsrResultV1(
                sampleId = "x",
                text = text,
                language = language,
                modelId = modelId,
                modelVersion = modelVersion,
                createdAt = "t",
            )
        }
    }

    class FlakyAsrEngine(private var failures: Int) : AsrEngine {
        override val modelId = "flaky"
        override val modelVersion = "1"
        var calls = 0

        override suspend fun transcribe(
            wavFile: File,
            language: String,
        ): AsrResultV1 {
            calls++
            if (failures > 0) {
                failures--
                throw EngineException(EngineErrorCodes.INFERENCE_FAILED, "boom", true)
            }
            return AsrResultV1(
                sampleId = "x",
                text = "ok",
                language = language,
                modelId = modelId,
                modelVersion = modelVersion,
                createdAt = "t",
            )
        }
    }

    class FailingAsrEngine(private val code: String, private val retryable: Boolean) : AsrEngine {
        override val modelId = "failing"
        override val modelVersion = "1"

        override suspend fun transcribe(
            wavFile: File,
            language: String,
        ): AsrResultV1 {
            throw EngineException(code, "nope", retryable)
        }
    }

    class FailingLlmEngine(private val code: String) : LlmEngine {
        override val modelId = "failing-llm"
        override val modelVersion = "1"

        override suspend fun annotate(record: PedagogicalRecordV2): AnnotationResultV1 {
            throw EngineException(code, "nope", false)
        }
    }
}
