package com.scraper.classroomcapture.data.store

import com.scraper.classroomcapture.domain.model.ClassroomSample
import com.scraper.classroomcapture.domain.model.QualityMetrics
import com.scraper.classroomcapture.domain.model.SampleState
import com.scraper.classroomcapture.domain.model.Session
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

// Reconciliation behavior tests with fake repositories (todos P3.6–P3.8):
// adoption, erroring, quarantine, corrupt reporting, and idempotency.
class ReconcilerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var store: FileArtifactStore
    private lateinit var sessions: FakeSessionRepository
    private lateinit var samples: FakeSampleRepository
    private lateinit var artifacts: FakeArtifactRepository
    private lateinit var reconciler: Reconciler

    @Before
    fun setUp() {
        store = FileArtifactStore(File(tmp.root, "scrappy"))
        sessions = FakeSessionRepository()
        samples = FakeSampleRepository()
        artifacts = FakeArtifactRepository()
        reconciler = Reconciler(store, sessions, samples, artifacts) { "id-${counter++}" }
        counter = 0
    }

    @Test
    fun `interrupted sample with intact audio is adopted to audio saved`() =
        runBlocking {
            sessions.create(session("s1"))
            samples.createSample(sample("a1", "s1", SampleState.RECORDING), emptyList())
            writeAudio("s1", "a1", "audio-bytes".toByteArray())

            val report = reconciler.reconcile(NOW)

            assertEquals(listOf("a1"), report.adopted.map { it.sampleId })
            assertEquals(SampleState.AUDIO_SAVED, samples.get("a1")?.state)
            assertEquals("restart-interrupted-adopted", samples.get("a1")?.recoveryReason)
            assertEquals(SampleState.RECORDING, samples.get("a1")?.priorState)
            assertEquals(1, artifacts.artifacts.size)
        }

    @Test
    fun `interrupted sample with missing audio moves to error`() =
        runBlocking {
            sessions.create(session("s1"))
            samples.createSample(sample("a1", "s1", SampleState.TRANSCRIBING), emptyList())

            val report = reconciler.reconcile(NOW)

            assertEquals(listOf("a1"), report.errored)
            assertEquals(SampleState.ERROR, samples.get("a1")?.state)
            assertEquals("AUDIO_MISSING_AFTER_RESTART", samples.get("a1")?.errorCode)
        }

    @Test
    fun `zero byte audio is quarantined and errored`() =
        runBlocking {
            sessions.create(session("s1"))
            samples.createSample(sample("a1", "s1", SampleState.SAVING), emptyList())
            writeAudio("s1", "a1", ByteArray(0))

            val report = reconciler.reconcile(NOW)

            assertEquals(listOf("a1"), report.errored)
            assertEquals(1, report.quarantined.size)
            assertEquals("empty-audio", report.quarantined[0].reason)
        }

    @Test
    fun `crash tmp files are quarantined`() =
        runBlocking {
            File(tmp.root, "scrappy/tmp/leftover.tmp").writeBytes("partial".toByteArray())

            val report = reconciler.reconcile(NOW)

            assertEquals(1, report.quarantined.size)
            assertEquals("crash-partial-tmp", report.quarantined[0].reason)
            assertTrue(store.listTmpFiles().isEmpty())
        }

    @Test
    fun `orphan audio with a session is adopted without fabrication`() =
        runBlocking {
            sessions.create(session("s1"))
            writeAudio("s1", "ghost", "orphan".toByteArray())

            val report = reconciler.reconcile(NOW)

            assertEquals(listOf("ghost"), report.adopted.map { it.sampleId })
            val adopted = samples.get("ghost")
            assertEquals(SampleState.AUDIO_SAVED, adopted?.state)
            assertEquals("und", adopted?.sourceLanguage)
            assertEquals(1, adopted?.sequenceNumber)
            assertEquals("orphan-audio-adopted", adopted?.recoveryReason)
        }

    @Test
    fun `orphan audio without a session is quarantined never fabricated`() =
        runBlocking {
            writeAudio("no-such-session", "ghost", "orphan".toByteArray())

            val report = reconciler.reconcile(NOW)

            assertTrue(report.adopted.isEmpty())
            assertNull(samples.get("ghost"))
            assertEquals(1, report.quarantined.size)
            assertEquals("orphan-without-session", report.quarantined[0].reason)
        }

    @Test
    fun `durable state with missing audio is reported corrupt without transition`() =
        runBlocking {
            sessions.create(session("s1"))
            samples.createSample(sample("a1", "s1", SampleState.TRANSCRIBED), emptyList())

            val report = reconciler.reconcile(NOW)

            assertEquals(1, report.corrupt.size)
            assertEquals("a1", report.corrupt[0].sampleId)
            // No legal transition covers this — the row must not move.
            assertEquals(SampleState.TRANSCRIBED, samples.get("a1")?.state)
        }

    @Test
    fun `stuck recovered sample moves on to audio saved`() =
        runBlocking {
            sessions.create(session("s1"))
            val stuck =
                sample("a1", "s1", SampleState.RECOVERED).copy(
                    recoveryReason = "restart-interrupted-adopted",
                    recoveredAt = NOW - 1000,
                    priorState = SampleState.RECORDING,
                )
            samples.createSample(stuck, emptyList())
            writeAudio("s1", "a1", "audio".toByteArray())

            val report = reconciler.reconcile(NOW)

            assertEquals(SampleState.AUDIO_SAVED, samples.get("a1")?.state)
            assertEquals(1, report.adopted.size)
        }

    @Test
    fun `second run changes nothing`() =
        runBlocking {
            sessions.create(session("s1"))
            samples.createSample(sample("a1", "s1", SampleState.RECORDING), emptyList())
            writeAudio("s1", "a1", "audio".toByteArray())
            File(tmp.root, "scrappy/tmp/leftover.tmp").writeBytes("partial".toByteArray())
            writeAudio("s1", "ghost", "orphan".toByteArray())

            val first = reconciler.reconcile(NOW)
            assertEquals(2, first.adopted.size)
            assertEquals(1, first.quarantined.size)

            val writesAfterFirst = samples.writes + artifacts.writes
            val second = reconciler.reconcile(NOW + 1000)

            assertTrue(second.adopted.isEmpty())
            assertTrue(second.errored.isEmpty())
            assertTrue(second.quarantined.isEmpty())
            assertEquals(writesAfterFirst, samples.writes + artifacts.writes)
        }

    private fun session(id: String): Session = Session(id, "1", "mathematics", "hi", "t07", "s03", "", true, NOW, NOW, null)

    private fun sample(
        id: String,
        sessionId: String,
        state: SampleState,
    ): ClassroomSample =
        ClassroomSample(
            id = id,
            sessionId = sessionId,
            sequenceNumber = 1,
            state = state,
            sourceLanguage = "hi",
            recordedStart = null,
            recordedEnd = null,
            inputDevice = null,
            codeSwitching = false,
            quality = QualityMetrics.unknown(),
            errorCode = null,
            errorMessage = null,
            errorRetryable = false,
            recoveryReason = null,
            recoveredAt = null,
            priorState = null,
            createdAt = NOW,
            updatedAt = NOW,
        )

    private fun writeAudio(
        sessionId: String,
        sampleId: String,
        bytes: ByteArray,
    ) {
        val dir = File(tmp.root, "scrappy/sessions/$sessionId/samples/$sampleId")
        dir.mkdirs()
        File(dir, "audio.wav").writeBytes(bytes)
    }

    companion object {
        private const val NOW = 1758000000000L
        private var counter = 0
    }
}
