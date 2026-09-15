package com.scraper.classroomcapture.data.store

import com.scraper.classroomcapture.domain.model.Artifact
import com.scraper.classroomcapture.domain.model.ArtifactKind
import com.scraper.classroomcapture.domain.model.ClassroomSample
import com.scraper.classroomcapture.domain.model.ProcessingJob
import com.scraper.classroomcapture.domain.model.QualityMetrics
import com.scraper.classroomcapture.domain.model.SampleState
import java.util.UUID

// Startup reconciliation between Room and the filesystem (P3.6, P3.7).
// Idempotent: re-running after a completed run changes nothing — adopted
// orphans now have DB rows, errored samples are already ERROR (never
// revisited), tmp files are gone, quarantined files stay quarantined.
// Corrupt entries (durable state, audio missing) are re-reported every run;
// they need an explicit user decision in P8, never an automatic transition.
//
// Never fabricates: unknown languages become "und" (ISO 639-2
// undetermined), unknown durations stay null, and orphans without a session
// are quarantined — never attached to a made-up session.
data class AdoptedSample(
    val sampleId: String,
    val sessionId: String,
    val relativeAudioPath: String,
)

data class QuarantinedFile(
    val from: String,
    val to: String,
    val reason: String,
)

data class CorruptEntry(
    val sampleId: String,
    val reason: String,
)

data class ReconciliationReport(
    val scannedSamples: Int,
    val adopted: List<AdoptedSample>,
    val errored: List<String>,
    val quarantined: List<QuarantinedFile>,
    val corrupt: List<CorruptEntry>,
) {
    fun isClean(): Boolean = adopted.isEmpty() && errored.isEmpty() && quarantined.isEmpty() && corrupt.isEmpty()
}

class Reconciler(
    private val store: ArtifactStore,
    private val sessions: com.scraper.classroomcapture.data.repository.SessionRepository,
    private val samples: com.scraper.classroomcapture.data.repository.SampleRepository,
    private val artifacts: com.scraper.classroomcapture.data.repository.ArtifactRepository,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    suspend fun reconcile(now: Long): ReconciliationReport {
        val adopted = mutableListOf<AdoptedSample>()
        val errored = mutableListOf<String>()
        val quarantined = mutableListOf<QuarantinedFile>()
        val corrupt = mutableListOf<CorruptEntry>()

        // 1. Crash leftovers first: tmp files can never be valid artifacts.
        store.listTmpFiles().forEach { tmp ->
            val to = store.quarantineFile(tmp, REASON_CRASH_TMP, now)
            quarantined += QuarantinedFile(tmp, to, REASON_CRASH_TMP)
        }

        // 2. Interrupted samples: adopt intact audio, error missing audio.
        val interrupted = samples.getByStates(INTERRUPTED_STATES)
        interrupted.forEach { sample ->
            val audioPath = store.audioRelativePath(sample.sessionId, sample.id)
            val hash = if (store.exists(audioPath)) store.hashFile(audioPath) else null
            if (hash == null || hash.byteSize == 0L) {
                if (hash != null) {
                    val to = store.quarantineFile(audioPath, REASON_EMPTY_AUDIO, now)
                    quarantined += QuarantinedFile(audioPath, to, REASON_EMPTY_AUDIO)
                }
                if (samples.markError(sample.id, AUDIO_MISSING, OPERATOR_AUDIO_MISSING, false, now)) {
                    errored += sample.id
                }
            } else {
                adoptIntactAudio(sample, hash, REASON_INTERRUPTED_ADOPTED, now)
                adopted += AdoptedSample(sample.id, sample.sessionId, audioPath)
            }
        }

        // 3. Durable states claiming audio that is gone: report, don't touch.
        // No legal transition covers this; P8 offers the user keep/discard.
        val durable = samples.getByStates(DURABLE_STATES)
        durable.forEach { sample ->
            val audioPath = store.audioRelativePath(sample.sessionId, sample.id)
            if (!store.exists(audioPath)) {
                corrupt += CorruptEntry(sample.id, REASON_AUDIO_GONE)
            }
        }

        // 4. Orphan audio on disk with no sample row: adopt or quarantine.
        val knownIds = (interrupted + durable).map { it.id }.toSet()
        store.scanSampleDirs().forEach { ref ->
            if (ref.sampleId in knownIds) return@forEach
            val audioPath = StorePaths.audioRelativePath(ref.sessionId, ref.sampleId)
            if (audioPath !in ref.files) return@forEach
            if (samples.get(ref.sampleId) != null) return@forEach
            val session = sessions.get(ref.sessionId)
            if (session == null) {
                val to = store.quarantineTree(ref.relativeDir, REASON_ORPHAN_NO_SESSION, now)
                quarantined += QuarantinedFile(ref.relativeDir, to, REASON_ORPHAN_NO_SESSION)
            } else {
                val hash = store.hashFile(audioPath)
                if (hash.byteSize == 0L) {
                    val to = store.quarantineFile(audioPath, REASON_EMPTY_AUDIO, now)
                    quarantined += QuarantinedFile(audioPath, to, REASON_EMPTY_AUDIO)
                } else {
                    val created =
                        ClassroomSample(
                            id = ref.sampleId,
                            sessionId = ref.sessionId,
                            sequenceNumber = samples.nextSequenceNumber(ref.sessionId),
                            state = SampleState.CREATED,
                            sourceLanguage = LANGUAGE_UNDETERMINED,
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
                            createdAt = now,
                            updatedAt = now,
                        )
                    samples.createSample(created, emptyList<ProcessingJob>())
                    adoptIntactAudio(created, hash, REASON_ORPHAN_ADOPTED, now)
                    adopted += AdoptedSample(ref.sampleId, ref.sessionId, audioPath)
                }
            }
        }

        // 5. Layout entries matching no known pattern: quarantine + report.
        store.scanUnrecognized().forEach { path ->
            val to =
                try {
                    store.quarantineTree(path, REASON_UNRECOGNIZED, now)
                } catch (e: StoreException) {
                    store.quarantineFile(path, REASON_UNRECOGNIZED, now)
                }
            quarantined += QuarantinedFile(path, to, REASON_UNRECOGNIZED)
        }

        return ReconciliationReport(
            scannedSamples = interrupted.size + durable.size,
            adopted = adopted,
            errored = errored,
            quarantined = quarantined,
            corrupt = corrupt,
        )
    }

    // Audio is intact: ensure the artifact row exists, then move the sample
    // to AUDIO_SAVED. Fresh interrupts go through RECOVERED (reconciliation-
    // only entry); samples already RECOVERED from a crashed earlier run move
    // on directly — this closes the reconcile-then-crash window.
    private suspend fun adoptIntactAudio(
        sample: ClassroomSample,
        hash: FileHash,
        reason: String,
        now: Long,
    ) {
        val audioPath = store.audioRelativePath(sample.sessionId, sample.id)
        if (artifacts.getBySampleAndKind(sample.id, ArtifactKind.AUDIO_WAV) == null) {
            artifacts.add(
                Artifact(
                    id = newId(),
                    sampleId = sample.id,
                    kind = ArtifactKind.AUDIO_WAV,
                    relativePath = audioPath,
                    sha256 = hash.sha256,
                    byteSize = hash.byteSize,
                    mime = MIME_WAV,
                    sampleRateHz = null,
                    channels = null,
                    durationMs = null,
                    createdAt = now,
                ),
            )
        }
        val current = samples.get(sample.id)?.state ?: return
        if (current == SampleState.RECOVERED) {
            samples.transition(sample.id, SampleState.AUDIO_SAVED, now)
        } else if (samples.reconcileToRecovered(sample.id, reason, now)) {
            samples.transition(sample.id, SampleState.AUDIO_SAVED, now)
        }
    }

    companion object {
        const val MIME_WAV = "audio/wav"
        const val LANGUAGE_UNDETERMINED = "und"

        const val REASON_CRASH_TMP = "crash-partial-tmp"
        const val REASON_INTERRUPTED_ADOPTED = "restart-interrupted-adopted"
        const val REASON_ORPHAN_ADOPTED = "orphan-audio-adopted"
        const val REASON_EMPTY_AUDIO = "empty-audio"
        const val REASON_AUDIO_GONE = "durable-audio-missing"
        const val REASON_ORPHAN_NO_SESSION = "orphan-without-session"
        const val REASON_UNRECOGNIZED = "unrecognized-layout"

        const val AUDIO_MISSING = "AUDIO_MISSING_AFTER_RESTART"
        const val OPERATOR_AUDIO_MISSING =
            "Recording was interrupted and its audio could not be found."

        val INTERRUPTED_STATES =
            listOf(
                SampleState.RECORDING,
                SampleState.SAVING,
                SampleState.QUEUED_ASR,
                SampleState.TRANSCRIBING,
                SampleState.QUEUED_LLM,
                SampleState.ANNOTATING,
                // RECOVERED from a crashed earlier reconciliation run.
                SampleState.RECOVERED,
            )
        val DURABLE_STATES =
            listOf(
                SampleState.AUDIO_SAVED,
                SampleState.TRANSCRIBED,
                SampleState.ANNOTATED,
                SampleState.READY_FOR_EXPORT,
            )
    }
}
