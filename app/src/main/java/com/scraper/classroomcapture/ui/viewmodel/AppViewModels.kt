package com.scraper.classroomcapture.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.scraper.classroomcapture.asr.FileModelRegistry
import com.scraper.classroomcapture.asr.ModelStatus
import com.scraper.classroomcapture.audio.AudioDeviceManager
import com.scraper.classroomcapture.data.repository.EventRepository
import com.scraper.classroomcapture.data.repository.ExportRepository
import com.scraper.classroomcapture.data.repository.JobRepository
import com.scraper.classroomcapture.data.repository.SampleRepository
import com.scraper.classroomcapture.data.repository.SessionRepository
import com.scraper.classroomcapture.data.store.ArtifactStore
import com.scraper.classroomcapture.domain.model.ClassroomSample
import com.scraper.classroomcapture.domain.model.DeviceEvent
import com.scraper.classroomcapture.domain.model.JobKind
import com.scraper.classroomcapture.domain.model.JobState
import com.scraper.classroomcapture.domain.model.ProcessingJob
import com.scraper.classroomcapture.domain.model.SampleState
import com.scraper.classroomcapture.domain.model.Session
import com.scraper.classroomcapture.processing.ProcessingDispatcher
import com.scraper.classroomcapture.recording.RecordingController
import com.scraper.classroomcapture.ui.ActiveSession
import com.scraper.classroomcapture.ui.RecoveryReport
import com.scraper.classroomcapture.ui.SessionFormValidator
import com.scraper.classroomcapture.ui.SessionSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.util.UUID

// Real ViewModels (P8). Each screen gets lifecycle-safe state that survives
// rotation via ViewModel; process death re-reads from Room (source of truth).
// No recording state lives here — RecordingController/service owns capture.

class HomeViewModel(
    private val sessions: SessionRepository,
    private val samples: SampleRepository,
    private val jobs: JobRepository,
    private val models: FileModelRegistry,
    private val store: ArtifactStore,
) : ViewModel() {
    val sessionList: Flow<List<Session>> = sessions.observeAll()

    // True when no ASR model is installed (D15 setup banner).
    fun isModelMissing(): Boolean = models.status(DEFAULT_MODEL).let { it !is ModelStatus.Installed }

    suspend fun queueCounts(): Map<String, Int> =
        mapOf(
            "pendingAsr" to jobs.countByKindAndStates(JobKind.ASR, listOf(JobState.QUEUED, JobState.RUNNING)),
            "failedAsr" to jobs.countByKindAndStates(JobKind.ASR, listOf(JobState.FAILED)),
            "pendingLlm" to jobs.countByKindAndStates(JobKind.LLM, listOf(JobState.QUEUED, JobState.RUNNING)),
            "failedLlm" to jobs.countByKindAndStates(JobKind.LLM, listOf(JobState.FAILED)),
        )

    fun storageLow(): Boolean {
        val stats = store.storageStats()
        // Mirror P3 thresholds (warn 500 MB) for the Home banner.
        return stats.freeBytes < 500L * 1024 * 1024
    }

    // Legacy stub surface (P1 screens read .status); kept as live summary.
    val status: StateFlow<String> =
        sessionList.map { "sessions=${it.size}" }
            .stateIn(viewModelScope, SharingStarted.Eagerly, "home-idle")

    companion object {
        const val DEFAULT_MODEL = "tiny"
    }
}

class NewSessionViewModel(
    private val sessions: SessionRepository,
) : ViewModel() {
    var grade: String = ""
    var subject: String = ""
    var teacherCode: String = ""
    var schoolCode: String = ""
    var defaultLanguage: String = "hi"
    var notes: String = ""
    var consentAck: Boolean = false

    private val _status = MutableStateFlow("new-session-idle")
    val status: StateFlow<String> = _status.asStateFlow()

    suspend fun create(now: Long = System.currentTimeMillis()): String {
        val result =
            SessionFormValidator.validate(grade, subject, teacherCode, schoolCode, defaultLanguage, consentAck)
        if (!result.isValid) {
            _status.value = result.errors.values.first()
            throw IllegalArgumentException(result.errors.values.first())
        }
        val id = UUID.randomUUID().toString()
        sessions.create(
            Session(
                id = id,
                grade = grade.trim(),
                subject = subject.trim(),
                defaultLanguage = defaultLanguage,
                teacherCode = teacherCode.trim(),
                schoolCode = schoolCode.trim(),
                notes = notes.trim(),
                consentAck = true,
                createdAt = now,
                updatedAt = now,
                closedAt = null,
            ),
        )
        ActiveSession.id = id
        _status.value = "created=$id"
        return id
    }
}

class RecordingViewModel(
    private val controller: RecordingController,
    private val samples: SampleRepository,
    private val devices: AudioDeviceManager,
) : ViewModel() {
    val recording = controller.status

    // Sticky per-sample language (P4.10). Survives rotation in the VM;
    // STOP_SEGMENT chains the next sample with this value.
    private val _language = MutableStateFlow("hi")
    val language: StateFlow<String> = _language.asStateFlow()

    fun setLanguage(lang: String) {
        if (lang == "hi" || lang == "en" || lang == "mr") _language.value = lang
    }

    fun start(sessionId: String) = controller.start(sessionId, _language.value)

    fun stopSegment() = controller.stopSegment()

    fun stop() = controller.stop()

    fun samplesIn(sessionId: String): Flow<List<ClassroomSample>> = samples.observeBySession(sessionId)

    fun summary(samples: List<ClassroomSample>): SessionSummary = SessionSummary.from(samples)

    fun verifiedInput(): String = devices.getPreferredId() ?: "built-in"

    // P8.2 microphone chooser: detected inputs + persisted preference.
    // Selection is a preference until the service verifies the live input
    // via AudioRecord.getRoutedDevice (P5.3); the UI shows both.
    fun availableInputs() =
        try {
            devices.listInputs()
        } catch (e: Exception) {
            emptyList()
        }

    fun preferredInputId(): String? =
        try {
            devices.getPreferredId()
        } catch (e: Exception) {
            null
        }

    fun setPreferredInput(id: String?) =
        try {
            devices.setPreferredId(id)
        } catch (e: Exception) {
            Unit
        }

    val status: StateFlow<String> =
        recording.map { s -> "${s.phase} lang=${s.language ?: _language.value} saved=${s.savedCount}" }
            .stateIn(viewModelScope, SharingStarted.Eagerly, "recording-idle")
}

class SummaryViewModel(
    private val samples: SampleRepository,
) : ViewModel() {
    fun observe(sessionId: String): Flow<List<ClassroomSample>> = samples.observeBySession(sessionId)

    fun summary(samples: List<ClassroomSample>): SessionSummary = SessionSummary.from(samples)

    val status: StateFlow<String> = MutableStateFlow("summary-idle").asStateFlow()
}

// P9 export ViewModel: session multi-select, size estimate, preflight
// failures, and Preparing → Writing → Checking → Saved progress. Source
// recordings stay on the phone; VERIFIED requires reopen-ZIP read-back
// (D17), otherwise WRITTEN_UNVERIFIED is shown as a limitation.
class ExportViewModel(
    private val sessions: SessionRepository,
    private val samples: SampleRepository,
    private val exports: ExportRepository,
    private val manager: com.scraper.classroomcapture.export.ExportManager,
) : ViewModel() {
    // Back-compat constructor for tests that only have samples+exports.
    constructor(
        samples: SampleRepository,
        exports: ExportRepository,
    ) : this(
        sessions =
            object : SessionRepository {
                override suspend fun create(session: Session) = Unit

                override suspend fun get(id: String): Session? = null

                override fun observe(id: String): Flow<Session?> = kotlinx.coroutines.flow.flowOf(null)

                override fun observeAll(): Flow<List<Session>> = kotlinx.coroutines.flow.flowOf(emptyList())

                override suspend fun close(
                    id: String,
                    closedAt: Long,
                ) = Unit
            },
        samples = samples,
        exports = exports,
        manager =
            com.scraper.classroomcapture.export.ExportManager(
                sessions =
                    object : SessionRepository {
                        override suspend fun create(session: Session) = Unit

                        override suspend fun get(id: String): Session? = null

                        override fun observe(id: String): Flow<Session?> = kotlinx.coroutines.flow.flowOf(null)

                        override fun observeAll(): Flow<List<Session>> = kotlinx.coroutines.flow.flowOf(emptyList())

                        override suspend fun close(
                            id: String,
                            closedAt: Long,
                        ) = Unit
                    },
                samples = samples,
                artifacts =
                    object : com.scraper.classroomcapture.data.repository.ArtifactRepository {
                        override suspend fun add(artifact: com.scraper.classroomcapture.domain.model.Artifact) = Unit

                        override fun observeBySample(sampleId: String): Flow<List<com.scraper.classroomcapture.domain.model.Artifact>> =
                            kotlinx.coroutines.flow.flowOf(emptyList())

                        override suspend fun getBySampleAndKind(
                            sampleId: String,
                            kind: com.scraper.classroomcapture.domain.model.ArtifactKind,
                        ) = null
                    },
                events =
                    object : EventRepository {
                        override suspend fun log(event: DeviceEvent) = Unit

                        override fun observeRecent(limit: Int): Flow<List<DeviceEvent>> = kotlinx.coroutines.flow.flowOf(emptyList())
                    },
                exports = exports,
                store =
                    object : com.scraper.classroomcapture.data.store.ArtifactStore {
                        override suspend fun writeAtomic(
                            relativePath: String,
                            write: suspend (java.io.OutputStream) -> Unit,
                        ) = throw UnsupportedOperationException()

                        override suspend fun writeAtomicFile(
                            relativePath: String,
                            write: suspend (java.io.File) -> Unit,
                        ) = throw UnsupportedOperationException()

                        override suspend fun hashFile(relativePath: String) = throw UnsupportedOperationException()

                        override fun exists(relativePath: String) = false

                        override suspend fun readAllBytes(relativePath: String): ByteArray = throw UnsupportedOperationException()

                        override suspend fun quarantineFile(
                            relativePath: String,
                            reason: String,
                            now: Long,
                        ) = ""

                        override suspend fun quarantineTree(
                            relativeDir: String,
                            reason: String,
                            now: Long,
                        ) = ""

                        override suspend fun deleteTree(relativeDir: String) = Unit

                        override fun listSampleFiles(
                            sessionId: String,
                            sampleId: String,
                        ) = emptyList<String>()

                        override fun scanSampleDirs() = emptyList<com.scraper.classroomcapture.data.store.SampleDirRef>()

                        override fun listTmpFiles() = emptyList<String>()

                        override fun scanUnrecognized() = emptyList<String>()

                        override fun storageStats() = com.scraper.classroomcapture.data.store.StorageStats(0, 0)

                        override fun checkPreflight(neededBytes: Long) = com.scraper.classroomcapture.data.store.PreflightResult.Ok
                    },
            ),
    )

    val sessionList: Flow<List<Session>> = sessions.observeAll()

    fun observe(sessionId: String): Flow<List<ClassroomSample>> = samples.observeBySession(sessionId)

    fun observeExports(): Flow<List<com.scraper.classroomcapture.domain.model.ExportRecord>> = exports.observeAll()

    private val _phase = MutableStateFlow("idle")
    val phase: StateFlow<String> = _phase.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    suspend fun estimate(sessionIds: List<String>): com.scraper.classroomcapture.export.ExportManager.Estimate {
        return try {
            manager.estimate(sessionIds)
        } catch (_: Exception) {
            com.scraper.classroomcapture.export.ExportManager.Estimate(0, 0)
        }
    }

    suspend fun preflight(sessionIds: List<String>): List<com.scraper.classroomcapture.export.PreflightFailure> {
        return try {
            manager.preflight(sessionIds)
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun exportToFile(
        sessionIds: List<String>,
        destFile: java.io.File,
        stagingRoot: java.io.File,
    ): String {
        _phase.value = "preparing"
        _message.value = null
        return try {
            _phase.value = "writing"
            val res = manager.exportToFile(sessionIds, destFile, stagingRoot)
            _phase.value = "checking"
            _phase.value = "saved:${res.verification}"
            _message.value = "Saved ${res.recordCount} records (${res.zipBytes} bytes) — ${res.verification}. Source recordings preserved."
            res.exportId
        } catch (e: com.scraper.classroomcapture.export.ExportPreflightException) {
            _phase.value = "blocked"
            _message.value = "Export blocked: ${e.failures.size} sample(s) need attention (see Recovery/Errors)."
            throw e
        } catch (e: Exception) {
            _phase.value = "failed"
            _message.value = e.message ?: "Export failed."
            throw e
        }
    }

    // SAF streaming export (P9.4, D17). Tries destination read-back for
    // VERIFIED; falls back to WRITTEN_UNVERIFIED with a user-visible note
    // when the provider cannot be read back. Never auto-deletes source.
    suspend fun exportSaf(
        sessionIds: List<String>,
        displayName: String,
        stagingRoot: java.io.File,
        resolver: android.content.ContentResolver,
        uri: android.net.Uri,
    ): String {
        _phase.value = "preparing"
        _message.value = null
        return try {
            _phase.value = "writing"
            // Probe read-back support: providers that cannot be reopened
            // yield WRITTEN_UNVERIFIED (manager handles the hash check when
            // readBack is supplied; we supply it when open works).
            val canReadBack =
                try {
                    resolver.openInputStream(uri)?.close()
                    true
                } catch (_: Exception) {
                    false
                }
            val res =
                if (canReadBack) {
                    manager.exportToStream(
                        sessionIds,
                        displayName,
                        stagingRoot,
                        openDestination = { resolver.openOutputStream(uri) ?: throw IllegalStateException("Cannot open destination.") },
                        readBack = { resolver.openInputStream(uri) ?: throw IllegalStateException("Cannot read back.") },
                    )
                } else {
                    manager.exportToStream(
                        sessionIds,
                        displayName,
                        stagingRoot,
                        openDestination = { resolver.openOutputStream(uri) ?: throw IllegalStateException("Cannot open destination.") },
                        readBack = null,
                    )
                }
            _phase.value = "checking"
            _phase.value = "saved:${res.verification}"
            _message.value =
                if (res.verification == com.scraper.classroomcapture.domain.model.ExportVerification.VERIFIED) {
                    "Saved ${res.recordCount} records (${res.zipBytes} bytes) — Verified. Source recordings preserved."
                } else {
                    "Saved ${res.recordCount} records — Written; verification unavailable for this destination (source preserved)."
                }
            res.exportId
        } catch (e: com.scraper.classroomcapture.export.ExportPreflightException) {
            _phase.value = "blocked"
            _message.value = "Export blocked: ${e.failures.size} sample(s) need attention (see Recovery/Errors)."
            throw e
        } catch (e: Exception) {
            _phase.value = "failed"
            _message.value = e.message ?: "Export failed."
            throw e
        }
    }

    val status: StateFlow<String> = phase
}

class RecoveryViewModel(
    private val samples: SampleRepository,
    private val events: EventRepository,
) : ViewModel() {
    suspend fun report(activeSessionId: String? = ActiveSession.id): RecoveryReport {
        val all =
            samples.getByStates(
                listOf(SampleState.RECOVERED, SampleState.ERROR, SampleState.RECORDING, SampleState.SAVING),
            )
        return RecoveryReport.from(all, activeSessionId)
    }

    fun recentEvents(): Flow<List<DeviceEvent>> = events.observeRecent(50)

    val status: StateFlow<String> = MutableStateFlow("recovery-idle").asStateFlow()
}

class ErrorsViewModel(
    private val samples: SampleRepository,
    private val jobs: JobRepository,
    private val events: EventRepository,
) : ViewModel() {
    suspend fun errorSamples(): List<ClassroomSample> = samples.getByStates(listOf(SampleState.ERROR))

    suspend fun failedJobs(): List<ProcessingJob> =
        jobs.getExpiredLeases(JobKind.ASR, Long.MAX_VALUE).let {
            // Expired leases are a subset; full failed list needs counts —
            // expose via per-sample observation in the UI (P8.3).
            emptyList()
        }

    fun recentEvents(): Flow<List<DeviceEvent>> = events.observeRecent(100)

    // Retry: ERROR → QUEUED_* via the repositories (P6 retry edges live in
    // the state machine; the dispatcher picks the job up on its next poll).
    suspend fun retrySample(
        sampleId: String,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        val sample = samples.get(sampleId) ?: return false
        // ASR retry first (most common); LLM retry when already transcribed.
        return if (sample.state == SampleState.ERROR) {
            samples.transition(sampleId, SampleState.QUEUED_ASR, now) ||
                samples.transition(sampleId, SampleState.QUEUED_LLM, now)
        } else {
            false
        }
    }

    val status: StateFlow<String> = MutableStateFlow("errors-idle").asStateFlow()
}

class DiagnosticsViewModel(
    private val dispatcher: ProcessingDispatcher,
    private val models: FileModelRegistry,
    private val store: ArtifactStore,
    private val llmRegistry: com.scraper.classroomcapture.llm.GgufRegistry? = null,
) : ViewModel() {
    val queue = dispatcher.stats

    fun modelStatus(): String =
        when (val s = models.status(HomeViewModel.DEFAULT_MODEL)) {
            is ModelStatus.Installed -> "asr=${s.info.modelId} v${s.info.modelVersion}" + llmSuffix()
            is ModelStatus.Missing -> "asr=missing (install via file picker)" + llmSuffix()
            is ModelStatus.Corrupt -> "asr=corrupt (${s.reason})" + llmSuffix()
        }

    private fun llmSuffix(): String {
        val reg = llmRegistry ?: return " · llm=unknown"
        return when (val s = reg.status("schema-gen")) {
            is com.scraper.classroomcapture.llm.GgufStatus.Installed -> " · llm=${s.info.modelId} v${s.info.modelVersion}"
            is com.scraper.classroomcapture.llm.GgufStatus.Missing -> " · llm=missing (recording/export unaffected)"
            is com.scraper.classroomcapture.llm.GgufStatus.Corrupt -> " · llm=corrupt (${s.reason})"
        }
    }

    fun storage(): String {
        val stats = store.storageStats()
        val freeMb = stats.freeBytes / (1024 * 1024)
        return "free=${freeMb}MB"
    }

    val status: StateFlow<String> =
        queue.map { q -> "asr p=${q.pendingAsr} r=${q.runningAsr} d=${q.doneAsr} f=${q.failedAsr}" }
            .stateIn(viewModelScope, SharingStarted.Eagerly, "diagnostics-idle")
}
