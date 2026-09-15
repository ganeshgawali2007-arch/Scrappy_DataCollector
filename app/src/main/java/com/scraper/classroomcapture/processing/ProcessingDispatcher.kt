package com.scraper.classroomcapture.processing

import android.util.Log
import com.scraper.classroomcapture.data.repository.ArtifactRepository
import com.scraper.classroomcapture.data.repository.EventRepository
import com.scraper.classroomcapture.data.repository.JobRepository
import com.scraper.classroomcapture.data.repository.SampleRepository
import com.scraper.classroomcapture.data.serialization.AnnotationResultV1
import com.scraper.classroomcapture.data.serialization.AsrBlock
import com.scraper.classroomcapture.data.serialization.AsrResultV1
import com.scraper.classroomcapture.data.serialization.AudioBlock
import com.scraper.classroomcapture.data.serialization.GradeValue
import com.scraper.classroomcapture.data.serialization.PedagogicalRecordV2
import com.scraper.classroomcapture.data.serialization.ProvenanceBlock
import com.scraper.classroomcapture.data.serialization.ScrappyJson
import com.scraper.classroomcapture.data.store.ArtifactStore
import com.scraper.classroomcapture.data.store.FileArtifactStore
import com.scraper.classroomcapture.data.store.StoreException
import com.scraper.classroomcapture.domain.model.Artifact
import com.scraper.classroomcapture.domain.model.ArtifactKind
import com.scraper.classroomcapture.domain.model.DeviceEvent
import com.scraper.classroomcapture.domain.model.JobKind
import com.scraper.classroomcapture.domain.model.JobState
import com.scraper.classroomcapture.domain.model.ProcessingJob
import com.scraper.classroomcapture.domain.model.SampleState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import java.io.File
import java.time.Instant
import java.util.UUID

// Durable processing queue (P6). Loops claim one job at a time per kind
// (native inference baseline: concurrency 1 — P6.3), run the registered
// engine, persist the sidecar atomically, and advance sample states.
// Workers are idempotent (P6.5): the audio checksum is re-verified before
// every run, and an existing sidecar for unchanged audio is adopted instead
// of re-run — a crash or kill can never duplicate output.
//
// Engines register when ready (ASR in P7, LLM in P10); unregistered kinds
// simply wait. Recording priority (P6.7): while the mic is active the loops
// idle instead of contending for CPU. Crash safety (P6.2): claims carry
// leases; expired RUNNING leases are requeued on start.
class ProcessingDispatcher(
    private val samples: SampleRepository,
    private val artifacts: ArtifactRepository,
    private val jobs: JobRepository,
    private val events: EventRepository,
    private val store: ArtifactStore,
    private val recordingActive: () -> Boolean = { false },
    private val clock: () -> Long = System::currentTimeMillis,
    private val appVersion: String = "0.0.0",
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var asrEngine: AsrEngine? = null
    private var llmEngine: LlmEngine? = null
    private var loops: List<Job> = emptyList()

    private val _stats = MutableStateFlow(QueueStats())
    val stats: Flow<QueueStats> = _stats.asStateFlow()

    fun registerAsrEngine(engine: AsrEngine) {
        asrEngine = engine
    }

    fun registerLlmEngine(engine: LlmEngine) {
        llmEngine = engine
    }

    fun start() {
        if (loops.isNotEmpty()) return
        scope.launch {
            try {
                reclaimExpired()
            } catch (e: Exception) {
                Log.w(TAG, "lease reclaim failed", e)
            }
        }
        loops =
            listOf(
                scope.launch { loop(JobKind.ASR) },
                scope.launch { loop(JobKind.LLM) },
            )
        scope.launch {
            while (true) {
                delay(STATS_MS)
                refreshStats()
            }
        }
    }

    fun stop() {
        loops.forEach { it.cancel() }
        loops = emptyList()
        scope.cancel()
    }

    // Single pass over both kinds — used by tests and diagnostics (P11).
    // Honors recording priority like the loops (P6.7).
    suspend fun pumpOnce() {
        if (recordingActive()) return
        reclaimExpired()
        runKindOnce(JobKind.ASR)
        runKindOnce(JobKind.LLM)
        refreshStats()
    }

    private suspend fun loop(kind: JobKind) {
        while (true) {
            try {
                if (!recordingActive()) runKindOnce(kind)
            } catch (e: Exception) {
                Log.w(TAG, "queue loop ($kind) failed; continuing", e)
            }
            delay(POLL_MS)
        }
    }

    private suspend fun runKindOnce(kind: JobKind) {
        if (kind == JobKind.ASR) {
            val engine = asrEngine ?: return
            val job = jobs.claimable(kind, clock(), 1).firstOrNull() ?: return
            if (!jobs.claim(job.id, OWNER, LEASE_MS, clock())) return
            // Re-read the claimed row: claim() incremented attemptCount in
            // the store, but our snapshot is stale. All finishes must carry
            // the claimed attemptCount or retries lose count (P6.2).
            val claimed = jobs.getBySampleAndKind(job.sampleId, kind) ?: return
            runAsr(claimed, engine)
        } else {
            val engine = llmEngine ?: return
            val job = jobs.claimable(kind, clock(), 1).firstOrNull() ?: return
            if (!jobs.claim(job.id, OWNER, LEASE_MS, clock())) return
            val claimed = jobs.getBySampleAndKind(job.sampleId, kind) ?: return
            runLlm(claimed, engine)
        }
    }

    // ---- ASR -----------------------------------------------------------------

    private suspend fun runAsr(
        job: ProcessingJob,
        engine: AsrEngine,
    ) {
        val sample = samples.get(job.sampleId)
        if (sample == null) {
            logEvent("JOB_FAILED", "job=${job.id} code=${EngineErrorCodes.INPUT_INVALID}")
            return
        }
        // Idempotent skip: already past transcription.
        if (isAsrDone(sample.state)) {
            jobs.finish(job.copy(state = JobState.SUCCEEDED), clock())
            return
        }
        if (sample.state != SampleState.QUEUED_ASR && sample.state != SampleState.TRANSCRIBING) return
        val audioRow = artifacts.getBySampleAndKind(sample.id, ArtifactKind.AUDIO_WAV)
        if (audioRow == null) {
            return failSample(job, sample.id, EngineErrorCodes.INPUT_INVALID, "Audio artifact missing.", false)
        }
        // Checksum gate (P6.5): refuse to transcribe audio that changed
        // under us; adopt an existing sidecar instead of re-running.
        val currentHash =
            try {
                store.hashFile(audioRow.relativePath)
            } catch (e: Exception) {
                return failSample(job, sample.id, EngineErrorCodes.INPUT_INVALID, "Audio unreadable.", false)
            }
        if (currentHash.sha256 != audioRow.sha256) {
            return failSample(job, sample.id, EngineErrorCodes.INPUT_INVALID, "Audio checksum mismatch.", false)
        }
        if (artifacts.getBySampleAndKind(sample.id, ArtifactKind.ASR_JSON) != null) {
            jobs.finish(job.copy(state = JobState.SUCCEEDED, modelId = engine.modelId, modelVersion = engine.modelVersion), clock())
            samples.transition(sample.id, SampleState.TRANSCRIBING, clock())
            samples.transition(sample.id, SampleState.TRANSCRIBED, clock())
            return
        }
        samples.transition(sample.id, SampleState.TRANSCRIBING, clock())
        try {
            val audioFile = engineFile(audioRow.relativePath)
            val result = engine.transcribe(audioFile, sample.sourceLanguage)
            val withIdentity = result.copy(modelId = engine.modelId, modelVersion = engine.modelVersion)
            val written =
                store.writeAtomic(
                    store.sidecarRelativePath(sample.sessionId, sample.id, ArtifactKind.ASR_JSON),
                ) { out ->
                    out.write(ScrappyJson.encodeToString(withIdentity).toByteArray(Charsets.UTF_8))
                }
            artifacts.add(
                Artifact(
                    id = UUID.randomUUID().toString(),
                    sampleId = sample.id,
                    kind = ArtifactKind.ASR_JSON,
                    relativePath = written.relativePath,
                    sha256 = written.sha256,
                    byteSize = written.byteSize,
                    mime = "application/json",
                    sampleRateHz = null,
                    channels = null,
                    durationMs = null,
                    createdAt = clock(),
                ),
            )
            jobs.finish(job.copy(state = JobState.SUCCEEDED, modelId = engine.modelId, modelVersion = engine.modelVersion), clock())
            samples.transition(sample.id, SampleState.TRANSCRIBED, clock())
            logEvent("ASR_SUCCEEDED", "sample=${sample.id}")
        } catch (e: EngineException) {
            onEngineFailure(job, sample.id, e.code, e.message ?: "Transcription failed.", e.retryable)
        } catch (e: StoreException) {
            onEngineFailure(job, sample.id, e.code, e.message ?: "I/O failed.", e.retryable)
        } catch (e: Exception) {
            Log.w(TAG, "ASR unexpected failure", e)
            onEngineFailure(job, sample.id, EngineErrorCodes.INFERENCE_FAILED, "Transcription failed.", true)
        }
    }

    // ---- LLM ------------------------------------------------------------------

    private suspend fun runLlm(
        job: ProcessingJob,
        engine: LlmEngine,
    ) {
        val sample = samples.get(job.sampleId)
        if (sample == null) {
            logEvent("JOB_FAILED", "job=${job.id} code=${EngineErrorCodes.INPUT_INVALID}")
            return
        }
        if (isLlmDone(sample.state)) {
            jobs.finish(job.copy(state = JobState.SUCCEEDED), clock())
            return
        }
        if (sample.state != SampleState.QUEUED_LLM && sample.state != SampleState.ANNOTATING) return
        if (artifacts.getBySampleAndKind(sample.id, ArtifactKind.ANNOTATION_JSON) != null) {
            jobs.finish(job.copy(state = JobState.SUCCEEDED, modelId = engine.modelId, modelVersion = engine.modelVersion), clock())
            samples.transition(sample.id, SampleState.ANNOTATING, clock())
            samples.transition(sample.id, SampleState.ANNOTATED, clock())
            return
        }
        samples.transition(sample.id, SampleState.ANNOTATING, clock())
        val record = buildRecordForAnnotation(sample.id)
        if (record == null) {
            // Transcript sidecar missing: back to TRANSCRIBED, job failed
            // permanently (audio + ASR stay exportable).
            jobs.finish(job.copy(state = JobState.FAILED, lastErrorCode = EngineErrorCodes.INPUT_INVALID), clock())
            samples.transition(sample.id, SampleState.TRANSCRIBED, clock())
            samples.transition(sample.id, SampleState.READY_FOR_EXPORT, clock())
            return
        }
        try {
            val result = engine.annotate(record)
            validateAnnotation(result)
            val written =
                store.writeAtomic(
                    store.sidecarRelativePath(sample.sessionId, sample.id, ArtifactKind.ANNOTATION_JSON),
                ) { out ->
                    out.write(ScrappyJson.encodeToString(result).toByteArray(Charsets.UTF_8))
                }
            artifacts.add(
                Artifact(
                    id = UUID.randomUUID().toString(),
                    sampleId = sample.id,
                    kind = ArtifactKind.ANNOTATION_JSON,
                    relativePath = written.relativePath,
                    sha256 = written.sha256,
                    byteSize = written.byteSize,
                    mime = "application/json",
                    sampleRateHz = null,
                    channels = null,
                    durationMs = null,
                    createdAt = clock(),
                ),
            )
            jobs.finish(job.copy(state = JobState.SUCCEEDED, modelId = engine.modelId, modelVersion = engine.modelVersion), clock())
            samples.transition(sample.id, SampleState.ANNOTATED, clock())
            logEvent("LLM_SUCCEEDED", "sample=${sample.id}")
        } catch (e: EngineException) {
            if (e.retryable && RetryPolicy.isRetryable(e.code)) {
                retryJob(job, sample.id, e.code, e.message ?: "Annotation failed.")
            } else {
                // Permanent LLM failure: audio + ASR stay exportable (P10.8).
                jobs.finish(job.copy(state = JobState.FAILED, lastErrorCode = e.code, lastErrorMessage = e.message), clock())
                samples.transition(sample.id, SampleState.TRANSCRIBED, clock())
                samples.transition(sample.id, SampleState.READY_FOR_EXPORT, clock())
                logEvent("LLM_FAILED_EXPORTABLE", "sample=${sample.id} code=${e.code}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "LLM unexpected failure", e)
            retryJob(job, sample.id, EngineErrorCodes.INFERENCE_FAILED, "Annotation failed.")
        }
    }

    // Every annotation must carry model identity + evidence spans (schema
    // §Vocabularies); anything else is OUTPUT_REJECTED, never persisted.
    private fun validateAnnotation(result: AnnotationResultV1) {
        if (result.generatedBy.isNullOrBlank() || result.evidenceSpans.isEmpty()) {
            throw EngineException(
                EngineErrorCodes.OUTPUT_REJECTED,
                "Annotation missing model identity or evidence spans.",
                retryable = false,
            )
        }
    }

    // ---- failure handling --------------------------------------------------------

    private suspend fun onEngineFailure(
        job: ProcessingJob,
        sampleId: String,
        code: String,
        message: String,
        retryable: Boolean,
    ) {
        val latest = jobs.getBySampleAndKind(sampleId, job.kind) ?: return
        if (retryable && RetryPolicy.isRetryable(code) && latest.attemptCount < latest.maxAttempts) {
            retryJob(latest, sampleId, code, message)
        } else {
            failSample(latest, sampleId, code, message, false)
        }
    }

    private suspend fun retryJob(
        job: ProcessingJob,
        sampleId: String,
        code: String,
        message: String,
    ) {
        val now = clock()
        // attemptCount was already incremented by claim(); the delay counts
        // the failure that just happened.
        val wait = RetryPolicy.delayMs(job.attemptCount)
        jobs.finish(
            job.copy(
                state = JobState.QUEUED,
                lastErrorCode = code,
                lastErrorMessage = message,
                nextAttemptAt = now + wait,
                leaseOwner = null,
                leaseExpiresAt = null,
            ),
            now,
        )
        samples.transition(sampleId, queueEntryFor(job.kind), now)
        logEvent("JOB_RETRY", "sample=$sampleId attempt=${job.attemptCount} wait=$wait code=$code")
    }

    private suspend fun failSample(
        job: ProcessingJob,
        sampleId: String,
        code: String,
        message: String,
        retryable: Boolean,
    ) {
        val now = clock()
        jobs.finish(job.copy(state = JobState.FAILED, lastErrorCode = code, lastErrorMessage = message), now)
        samples.markError(sampleId, code, message, retryable, now)
        logEvent("JOB_FAILED", "sample=$sampleId code=$code")
    }

    private suspend fun reclaimExpired() {
        val expiredAsr = jobs.getExpiredLeases(JobKind.ASR, clock())
        val expiredLlm = jobs.getExpiredLeases(JobKind.LLM, clock())
        (expiredAsr + expiredLlm).forEach { jobs.requeue(it.id, clock()) }
        if (expiredAsr.isNotEmpty() || expiredLlm.isNotEmpty()) {
            logEvent("LEASES_RECLAIMED", "asr=${expiredAsr.size} llm=${expiredLlm.size}")
        }
    }

    private suspend fun refreshStats() {
        _stats.value =
            QueueStats(
                pendingAsr = jobs.countByKindAndStates(JobKind.ASR, listOf(JobState.QUEUED)),
                runningAsr = jobs.countByKindAndStates(JobKind.ASR, listOf(JobState.RUNNING)),
                doneAsr = jobs.countByKindAndStates(JobKind.ASR, listOf(JobState.SUCCEEDED)),
                failedAsr = jobs.countByKindAndStates(JobKind.ASR, listOf(JobState.FAILED)),
                pendingLlm = jobs.countByKindAndStates(JobKind.LLM, listOf(JobState.QUEUED)),
                runningLlm = jobs.countByKindAndStates(JobKind.LLM, listOf(JobState.RUNNING)),
                doneLlm = jobs.countByKindAndStates(JobKind.LLM, listOf(JobState.SUCCEEDED)),
                failedLlm = jobs.countByKindAndStates(JobKind.LLM, listOf(JobState.FAILED)),
                checkedAt = clock(),
            )
    }

    private fun queueEntryFor(kind: JobKind): SampleState = if (kind == JobKind.ASR) SampleState.QUEUED_ASR else SampleState.QUEUED_LLM

    private fun isAsrDone(state: SampleState): Boolean =
        when (state) {
            SampleState.TRANSCRIBED,
            SampleState.QUEUED_LLM,
            SampleState.ANNOTATING,
            SampleState.ANNOTATED,
            SampleState.READY_FOR_EXPORT,
            SampleState.EXPORTED,
            -> true
            else -> false
        }

    private fun isLlmDone(state: SampleState): Boolean =
        when (state) {
            SampleState.ANNOTATED,
            SampleState.READY_FOR_EXPORT,
            SampleState.EXPORTED,
            -> true
            else -> false
        }

    // Annotation input envelope (P6): transcript + audio provenance. The P10
    // worker fills pedagogical fields from the LLM-validated sidecar.
    private suspend fun buildRecordForAnnotation(sampleId: String): PedagogicalRecordV2? {
        val sample = samples.get(sampleId) ?: return null
        val asrFile = artifacts.getBySampleAndKind(sampleId, ArtifactKind.ASR_JSON) ?: return null
        val text =
            try {
                val json = store.readAllBytes(asrFile.relativePath).toString(Charsets.UTF_8)
                ScrappyJson.decodeFromString<AsrResultV1>(json).text
            } catch (e: Exception) {
                return null
            }
        val audio = artifacts.getBySampleAndKind(sampleId, ArtifactKind.AUDIO_WAV) ?: return null
        return PedagogicalRecordV2(
            sampleId = sample.id,
            sessionId = sample.sessionId,
            sequenceNumber = sample.sequenceNumber,
            source = text,
            sourceLanguage = sample.sourceLanguage,
            grade = GradeValue.Label("unknown"),
            subject = "unknown",
            audio =
                AudioBlock(
                    path = audio.relativePath,
                    sha256 = audio.sha256,
                    durationMs = audio.durationMs ?: 0,
                ),
            asr =
                AsrBlock(
                    status = "available",
                    path = asrFile.relativePath,
                    text = text,
                ),
            provenance =
                ProvenanceBlock(
                    createdAt = Instant.ofEpochMilli(sample.createdAt).toString(),
                    processingState = sample.state.name,
                    appVersion = appVersion,
                ),
        )
    }

    private fun engineFile(relativePath: String): File {
        // Engines must never build paths; the store resolves them (P3.4).
        return (store as FileArtifactStore).fileFor(relativePath)
    }

    private suspend fun logEvent(
        type: String,
        detail: String,
    ) {
        try {
            events.log(
                DeviceEvent(
                    id = UUID.randomUUID().toString(),
                    sessionId = null,
                    sampleId = null,
                    type = type,
                    detail = detail,
                    appVersion = appVersion,
                    createdAt = clock(),
                ),
            )
        } catch (e: Exception) {
            Log.w(TAG, "event log failed", e)
        }
    }

    companion object {
        private const val TAG = "ProcessingDispatcher"
        private const val OWNER = "dispatcher"
        const val LEASE_MS = 10 * 60_000L
        private const val POLL_MS = 5_000L
        private const val STATS_MS = 15_000L
    }
}

data class QueueStats(
    val pendingAsr: Int = 0,
    val runningAsr: Int = 0,
    val doneAsr: Int = 0,
    val failedAsr: Int = 0,
    val pendingLlm: Int = 0,
    val runningLlm: Int = 0,
    val doneLlm: Int = 0,
    val failedLlm: Int = 0,
    val checkedAt: Long = 0,
)
