package com.scraper.classroomcapture.export

import com.scraper.classroomcapture.data.repository.ExportRepository
import com.scraper.classroomcapture.data.serialization.AsrResultV1
import com.scraper.classroomcapture.data.serialization.ScrappyJson
import com.scraper.classroomcapture.data.store.FakeArtifactRepository
import com.scraper.classroomcapture.data.store.FakeSampleRepository
import com.scraper.classroomcapture.data.store.FakeSessionRepository
import com.scraper.classroomcapture.data.store.FileArtifactStore
import com.scraper.classroomcapture.domain.model.Artifact
import com.scraper.classroomcapture.domain.model.ArtifactKind
import com.scraper.classroomcapture.domain.model.ClassroomSample
import com.scraper.classroomcapture.domain.model.DeviceEvent
import com.scraper.classroomcapture.domain.model.ExportRecord
import com.scraper.classroomcapture.domain.model.ExportStatus
import com.scraper.classroomcapture.domain.model.ExportVerification
import com.scraper.classroomcapture.domain.model.QualityMetrics
import com.scraper.classroomcapture.domain.model.SampleState
import com.scraper.classroomcapture.domain.model.Session
import com.scraper.classroomcapture.processing.FakeEventRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

class FakeExportRepository : ExportRepository {
    val records = mutableMapOf<String, ExportRecord>()

    override suspend fun start(
        sessionIds: List<String>,
        now: Long,
        id: String,
    ): ExportRecord {
        val r = ExportRecord(id, sessionIds, ExportStatus.PREPARING, null, null, null, null, now, null)
        records[id] = r
        return r
    }

    override suspend fun markFinished(
        id: String,
        status: ExportStatus,
        verification: ExportVerification?,
        outputPath: String?,
        checksum: String?,
        byteSize: Long?,
        now: Long,
    ) {
        val cur = records[id] ?: return
        records[id] =
            cur.copy(
                status = status,
                verification = verification,
                outputPath = outputPath,
                checksum = checksum,
                byteSize = byteSize,
                completedAt = now,
            )
    }

    override suspend fun get(id: String): ExportRecord? = records[id]

    override fun observeAll(): Flow<List<ExportRecord>> = MutableStateFlow(records.values.toList())
}

// P9: manifest/JSONL/audio/checksums, preflight blocking, ZIP reopen
// verification, re-export without overwrite, interrupted-export recovery
// (FAILED + temp cleanup, source preserved).
class ExportTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var sessions: FakeSessionRepository
    private lateinit var samples: FakeSampleRepository
    private lateinit var artifacts: FakeArtifactRepository
    private lateinit var events: FakeEventRepository
    private lateinit var exports: FakeExportRepository
    private lateinit var store: FileArtifactStore

    @Before fun setUp() {
        sessions = FakeSessionRepository()
        samples = FakeSampleRepository()
        artifacts = FakeArtifactRepository()
        events = FakeEventRepository()
        exports = FakeExportRepository()
        store = FileArtifactStore(File(tmp.root, "scrappy"))
    }

    private fun session(id: String = "s1") = Session(id, "3", "Mathematics", "mr", "teacher_07", "school_03", "", true, 1000, 1000, null)

    private fun sample(
        id: String,
        sessionId: String = "s1",
        seq: Int = 1,
        state: SampleState = SampleState.AUDIO_SAVED,
    ) = ClassroomSample(
        id,
        sessionId,
        seq,
        state,
        "mr",
        1000,
        2000,
        "builtin_mic",
        false,
        QualityMetrics.unknown(),
        null,
        null,
        false,
        null,
        null,
        null,
        1000,
        1000,
    )

    private suspend fun writeAudio(
        sessionId: String,
        sampleId: String,
        bytes: ByteArray = ByteArray(16000 * 2) { 1 },
    ): Artifact {
        val rel = store.audioRelativePath(sessionId, sampleId)
        val written = store.writeAtomic(rel) { out -> out.write(bytes) }
        val art =
            Artifact(
                UUID.randomUUID().toString(),
                sampleId,
                ArtifactKind.AUDIO_WAV,
                written.relativePath,
                written.sha256,
                written.byteSize,
                "audio/wav",
                16000,
                1,
                1000,
                1000,
            )
        artifacts.add(art)
        return art
    }

    private suspend fun writeAsr(
        sessionId: String,
        sampleId: String,
        text: String = "hello class",
    ) {
        val asr =
            AsrResultV1(
                sampleId = sampleId,
                text = text,
                language = "mr",
                modelId = "tiny",
                modelVersion = "1.0",
                createdAt = "2026-09-15T00:00:00Z",
            )
        val rel = store.sidecarRelativePath(sessionId, sampleId, ArtifactKind.ASR_JSON)
        val written =
            store.writeAtomic(rel) { out ->
                out.write(ScrappyJson.encodeToString(asr).toByteArray())
            }
        artifacts.add(
            Artifact(
                UUID.randomUUID().toString(),
                sampleId,
                ArtifactKind.ASR_JSON,
                written.relativePath,
                written.sha256,
                written.byteSize,
                "application/json",
                null,
                null,
                null,
                1000,
            ),
        )
    }

    private fun manager() = ExportManager(sessions, samples, artifacts, events, exports, store, "test-1.0", { 2000L })

    @Test fun `export builds verified zip with manifest and checksums`() =
        runBlocking {
            sessions.create(session())
            samples.createSample(sample("a1"), emptyList())
            samples.createSample(sample("a2", seq = 2, state = SampleState.TRANSCRIBED), emptyList())
            writeAudio("s1", "a1")
            writeAudio("s1", "a2")
            writeAsr("s1", "a2")
            events.log(DeviceEvent("e1", "s1", null, "ROUTE_CHANGED", "built-in", "test", 1500))

            val dest = File(tmp.root, "exports/out-${UUID.randomUUID()}.zip")
            val res = manager().exportToFile(listOf("s1"), dest, File(tmp.root, "staging"))

            assertEquals(2, res.recordCount)
            assertEquals(ExportVerification.VERIFIED, res.verification)
            assertTrue(dest.isFile)
            ZipFile(dest).use { zip ->
                assertTrue(zip.getEntry(ExportLayout.MANIFEST_NAME) != null)
                assertTrue(zip.getEntry(ExportLayout.RECORDS_NAME) != null)
                assertTrue(zip.getEntry(ExportLayout.audioEntry("a1")) != null)
                assertTrue(zip.getEntry(ExportLayout.asrEntry("a2")) != null)
                assertTrue(zip.getEntry(ExportLayout.EVENTS_NAME) != null)
            }
            val rec = exports.records[res.exportId]!!
            assertEquals(ExportStatus.SUCCEEDED, rec.status)
            assertEquals(ExportVerification.VERIFIED, rec.verification)
            assertTrue(events.events.any { it.type == "EXPORT_SUCCEEDED" })
        }

    @Test fun `preflight blocks missing audio with actionable failure`() =
        runBlocking {
            sessions.create(session())
            samples.createSample(sample("a1"), emptyList())
            // No audio file → preflight failure, FAILED record, source preserved.
            try {
                manager().exportToFile(listOf("s1"), File(tmp.root, "x.zip"), File(tmp.root, "staging"))
                fail("expected preflight exception")
            } catch (e: ExportPreflightException) {
                assertEquals(1, e.failures.size)
                assertEquals("a1", e.failures[0].sampleId)
            }
            assertTrue(exports.records.values.any { it.status == ExportStatus.FAILED })
            assertTrue(samples.samples.containsKey("a1"))
        }

    @Test fun `re-export never overwrites existing file`() =
        runBlocking {
            sessions.create(session())
            samples.createSample(sample("a1"), emptyList())
            writeAudio("s1", "a1")
            val dest = File(tmp.root, "dup.zip")
            manager().exportToFile(listOf("s1"), dest, File(tmp.root, "staging"))
            try {
                manager().exportToFile(listOf("s1"), dest, File(tmp.root, "staging2"))
                fail("expected overwrite refusal")
            } catch (e: ExportVerifyException) {
                assertTrue(e.message!!.contains("already exists"))
            }
            // Two export records (first SUCCEEDED, second FAILED) — never merged.
            assertEquals(2, exports.records.size)
        }

    @Test fun `preflight checksum mismatch is reported`() =
        runBlocking {
            sessions.create(session())
            samples.createSample(sample("a1"), emptyList())
            writeAudio("s1", "a1")
            // Corrupt the row hash (audio changed under us).
            val art = artifacts.artifacts.first()
            artifacts.artifacts[0] = art.copy(sha256 = "deadbeef".repeat(8))
            val failures = manager().preflight(listOf("s1"))
            assertEquals(1, failures.size)
            assertEquals(ExportPreflight.CODE_CHECKSUM_MISMATCH, failures[0].code)
        }

    @Test fun `saf without readback is written_unverified`() =
        runBlocking {
            sessions.create(session())
            samples.createSample(sample("a1"), emptyList())
            writeAudio("s1", "a1")
            val outBytes = java.io.ByteArrayOutputStream()
            val res = manager().exportToStream(listOf("s1"), "s1.zip", File(tmp.root, "staging"), { outBytes }, null)
            assertEquals(ExportVerification.WRITTEN_UNVERIFIED, res.verification)
            assertTrue(outBytes.size() > 0)
        }
}
