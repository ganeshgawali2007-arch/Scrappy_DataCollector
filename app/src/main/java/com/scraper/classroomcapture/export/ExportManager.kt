package com.scraper.classroomcapture.export

import com.scraper.classroomcapture.data.repository.ArtifactRepository
import com.scraper.classroomcapture.data.repository.EventRepository
import com.scraper.classroomcapture.data.repository.ExportRepository
import com.scraper.classroomcapture.data.repository.SampleRepository
import com.scraper.classroomcapture.data.repository.SessionRepository
import com.scraper.classroomcapture.data.store.ArtifactStore
import com.scraper.classroomcapture.domain.model.ArtifactKind
import com.scraper.classroomcapture.domain.model.DeviceEvent
import com.scraper.classroomcapture.domain.model.ExportStatus
import com.scraper.classroomcapture.domain.model.ExportVerification
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import java.util.UUID

// Export orchestration (P9.3–P9.8). Validates consistency, builds + verifies
// in a temp location, streams the verified ZIP to the caller-supplied
// destination (SAF OutputStream in production, File in tests), then persists
// the ExportRecord. Source rows/files are never modified or deleted.
// Interrupted exports leave a FAILED record + temp cleanup; re-export mints
// a new exportId and never overwrites an existing file.
class ExportManager(
    private val sessions: SessionRepository,
    private val samples: SampleRepository,
    private val artifacts: ArtifactRepository,
    private val events: EventRepository,
    private val exports: ExportRepository,
    private val store: ArtifactStore,
    private val appVersion: String = "0.0.0",
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Result(
        val exportId: String,
        val recordCount: Int,
        val zipBytes: Long,
        val zipSha256: String,
        val verification: ExportVerification,
        val failures: List<PreflightFailure>,
    )

    data class Estimate(
        val sampleCount: Int,
        val audioBytes: Long,
    )

    suspend fun estimate(sessionIds: List<String>): Estimate =
        withContext(Dispatchers.IO) {
            var count = 0
            var bytes = 0L
            sessionIds.forEach { sid ->
                // observeBySession is a Flow; collect the current snapshot via
                // getByStates filtered to the session (avoids Flow collection).
                val all = samples.getByStates(exportableStates()).filter { it.sessionId == sid }
                count += all.size
                all.forEach { s ->
                    bytes += artifacts.getBySampleAndKind(s.id, ArtifactKind.AUDIO_WAV)?.byteSize ?: 0L
                }
            }
            Estimate(count, bytes)
        }

    suspend fun preflight(sessionIds: List<String>): List<PreflightFailure> =
        withContext(Dispatchers.IO) {
            val all = samples.getByStates(exportableStates()).filter { it.sessionId in sessionIds }
            val shaBySample =
                all.mapNotNull { s ->
                    val a = artifacts.getBySampleAndKind(s.id, ArtifactKind.AUDIO_WAV)
                    if (a != null) s.id to a.sha256 else null
                }.toMap()
            ExportPreflight.validate(all, shaBySample, store)
        }

    // File destination (tests + "save locally" path). Verifies by reopening
    // the ZIP (P9.5) → VERIFIED.
    suspend fun exportToFile(
        sessionIds: List<String>,
        destFile: File,
        stagingRoot: File,
    ): Result =
        withContext(Dispatchers.IO) {
            exportInternal(sessionIds, stagingRoot, ExportVerification.VERIFIED) { verifiedZip ->
                destFile.parentFile?.mkdirs()
                // Never silently overwrite: caller picks a fresh name per
                // exportId; refuse if the file already exists (P9.7).
                if (destFile.exists()) {
                    throw ExportVerifyException("Destination ${destFile.name} already exists; pick a new export file.")
                }
                verifiedZip.inputStream().buffered().use { ins ->
                    destFile.outputStream().buffered().use { out -> ins.copyTo(out, 65536) }
                }
                destFile.absolutePath
            }
        }

    // SAF destination (P9.4): caller opens the OutputStream from the document
    // picker. When readBack is null (provider cannot read back), the record
    // is WRITTEN_UNVERIFIED (D17) — never labeled VERIFIED.
    suspend fun exportToStream(
        sessionIds: List<String>,
        fileName: String,
        stagingRoot: File,
        openDestination: () -> OutputStream,
        readBack: (() -> java.io.InputStream)? = null,
    ): Result =
        withContext(Dispatchers.IO) {
            // D17: VERIFIED requires successful read-back after writing. The temp
            // ZIP is always reopen-checked (P9.5); SAF without readBack cannot
            // prove provider bytes → WRITTEN_UNVERIFIED, never VERIFIED.
            val verification = if (readBack == null) ExportVerification.WRITTEN_UNVERIFIED else ExportVerification.VERIFIED
            exportInternal(sessionIds, stagingRoot, verification) { verifiedZip ->
                openDestination().buffered().use { out ->
                    verifiedZip.inputStream().buffered().use { ins -> ins.copyTo(out, 65536) }
                }
                if (readBack != null) {
                    // Read-back hash check; mismatch fails the export.
                    val expected = ExportBuilder.sha256Of(verifiedZip).first
                    readBack().use { ins ->
                        val actual = ExportBuilder.sha256Of(ins)
                        if (!actual.equals(expected, ignoreCase = true)) {
                            throw ExportVerifyException("SAF read-back checksum mismatch.")
                        }
                    }
                }
                "saf:$fileName"
            }
        }

    private suspend fun exportInternal(
        sessionIds: List<String>,
        stagingRoot: File,
        verification: ExportVerification,
        finalize: (verifiedZip: File) -> String,
    ): Result {
        val exportId = UUID.randomUUID().toString()
        val now = clock()
        exports.start(sessionIds, now, exportId)

        suspend fun fail(e: Exception): Nothing {
            exports.markFinished(exportId, ExportStatus.FAILED, null, null, null, null, clock())
            log("EXPORT_FAILED", "export=$exportId error=${e.message?.take(120)}")
            throw e
        }
        try {
            exports.markFinished(exportId, ExportStatus.PREPARING, null, null, null, null, clock())
            val sessionRows = sessionIds.mapNotNull { sessions.get(it) }
            if (sessionRows.isEmpty()) fail(IllegalArgumentException("No sessions selected."))
            val allSamples = samples.getByStates(exportableStates()).filter { it.sessionId in sessionIds }
            if (allSamples.isEmpty()) fail(IllegalArgumentException("No samples to export."))
            val shaBySample =
                allSamples.mapNotNull { s ->
                    artifacts.getBySampleAndKind(s.id, ArtifactKind.AUDIO_WAV)?.let { s.id to it.sha256 }
                }.toMap()
            val failures = ExportPreflight.validate(allSamples, shaBySample, store)
            if (failures.isNotEmpty()) {
                exports.markFinished(exportId, ExportStatus.FAILED, null, null, null, null, clock())
                log("EXPORT_PREFLIGHT_FAILED", "export=$exportId failures=${failures.size}")
                throw ExportPreflightException(failures)
            }
            exports.markFinished(exportId, ExportStatus.WRITING, null, null, null, null, clock())
            val artsBySample =
                allSamples.associate { s ->
                    s.id to
                        listOfNotNull(
                            artifacts.getBySampleAndKind(s.id, ArtifactKind.AUDIO_WAV),
                            artifacts.getBySampleAndKind(s.id, ArtifactKind.ASR_JSON),
                            artifacts.getBySampleAndKind(s.id, ArtifactKind.ANNOTATION_JSON),
                        )
                }
            // Events scoped to these sessions (plus global queue events).
            val recentEvents = mutableListOf<DeviceEvent>()
            // observeRecent is a Flow; use a snapshot collector pattern via
            // first element. Repositories expose Flow; tests use fakes with
            // immediate values — collect with kotlinx.coroutines.flow.first().
            try {
                recentEvents += events.observeRecent(1000).first()
            } catch (_: Exception) {
            }
            val builder = ExportBuilder(store, appVersion)
            val built =
                builder.build(
                    ExportBuilder.Input(exportId, sessionRows, allSamples, artsBySample, recentEvents),
                    stagingRoot,
                )
            exports.markFinished(exportId, ExportStatus.CHECKING, null, null, null, null, clock())
            // Builder already reopen-verified the temp ZIP (P9.5).
            val outputPath =
                try {
                    finalize(built.zipFile)
                } finally {
                    try {
                        built.stagingDir.deleteRecursively()
                    } catch (_: Exception) {
                    }
                    // Keep the verified temp ZIP next to staging for debugging;
                    // remove it so interrupted exports never accumulate (P9.7).
                    try {
                        built.zipFile.delete()
                    } catch (_: Exception) {
                    }
                }
            exports.markFinished(
                exportId,
                ExportStatus.SUCCEEDED,
                verification,
                outputPath,
                built.zipSha256,
                built.zipBytes,
                clock(),
            )
            log("EXPORT_SUCCEEDED", "export=$exportId records=${built.recordCount} bytes=${built.zipBytes}")
            return Result(exportId, built.recordCount, built.zipBytes, built.zipSha256, verification, emptyList())
        } catch (e: ExportPreflightException) {
            throw e
        } catch (e: Exception) {
            fail(e)
        }
    }

    private suspend fun log(
        type: String,
        detail: String,
    ) {
        try {
            events.log(DeviceEvent(UUID.randomUUID().toString(), null, null, type, detail, appVersion, clock()))
        } catch (_: Exception) {
        }
    }

    companion object {
        // Every durable-audio state is exportable (plan invariant 4) —
        // derived data optional, ERROR rows excluded until retried/recovered.
        fun exportableStates() =
            listOf(
                com.scraper.classroomcapture.domain.model.SampleState.AUDIO_SAVED,
                com.scraper.classroomcapture.domain.model.SampleState.QUEUED_ASR,
                com.scraper.classroomcapture.domain.model.SampleState.TRANSCRIBING,
                com.scraper.classroomcapture.domain.model.SampleState.TRANSCRIBED,
                com.scraper.classroomcapture.domain.model.SampleState.QUEUED_LLM,
                com.scraper.classroomcapture.domain.model.SampleState.ANNOTATING,
                com.scraper.classroomcapture.domain.model.SampleState.ANNOTATED,
                com.scraper.classroomcapture.domain.model.SampleState.READY_FOR_EXPORT,
                com.scraper.classroomcapture.domain.model.SampleState.EXPORTED,
                com.scraper.classroomcapture.domain.model.SampleState.RECOVERED,
            )
    }
}

class ExportPreflightException(val failures: List<PreflightFailure>) :
    Exception("Export blocked: ${failures.size} sample(s) need attention.")
