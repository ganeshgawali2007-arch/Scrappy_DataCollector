package com.scraper.classroomcapture.recording

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.scraper.classroomcapture.BuildConfig
import com.scraper.classroomcapture.R
import com.scraper.classroomcapture.audio.AudioDeviceManager
import com.scraper.classroomcapture.audio.AudioInput
import com.scraper.classroomcapture.data.repository.ArtifactRepository
import com.scraper.classroomcapture.data.repository.EventRepository
import com.scraper.classroomcapture.data.repository.JobRepository
import com.scraper.classroomcapture.data.repository.SampleRepository
import com.scraper.classroomcapture.data.repository.SessionRepository
import com.scraper.classroomcapture.data.store.ArtifactStore
import com.scraper.classroomcapture.data.store.PreflightResult
import com.scraper.classroomcapture.data.store.StoreErrorCodes
import com.scraper.classroomcapture.data.store.StoreException
import com.scraper.classroomcapture.di.AppContainer
import com.scraper.classroomcapture.domain.model.Artifact
import com.scraper.classroomcapture.domain.model.ArtifactKind
import com.scraper.classroomcapture.domain.model.ClassroomSample
import com.scraper.classroomcapture.domain.model.DeviceEvent
import com.scraper.classroomcapture.domain.model.JobKind
import com.scraper.classroomcapture.domain.model.JobState
import com.scraper.classroomcapture.domain.model.ProcessingJob
import com.scraper.classroomcapture.domain.model.QualityMetrics
import com.scraper.classroomcapture.domain.model.SampleState
import com.scraper.classroomcapture.permissions.NotificationChannels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

// Service-owned classroom recording (P4). The service — never an Activity —
// owns AudioRecord, the WAV writer, the wake lock, and the sample rows it
// creates. UI collects RecordingStatus via the binder; rotation, lock,
// backgrounding, and UI loss cannot disturb capture (P4.7).
//
// Lifecycle per segment: CREATED -> RECORDING -> SAVING -> AUDIO_SAVED, then
// an ASR job row is enqueued and the sample moves to QUEUED_ASR (P6 consumes
// it). STOP_SEGMENT finalizes the current sample and starts the next one
// with the sticky language (P4.10); STOP ends the run.
class RecordingService : Service() {
    inner class RecordingBinder : Binder() {
        fun status(): StateFlow<RecordingStatus> = status.asStateFlow()
    }

    private val binder = RecordingBinder()
    private val status = MutableStateFlow(RecordingStatus.Idle)

    private lateinit var container: AppContainer
    private lateinit var sessions: SessionRepository
    private lateinit var samples: SampleRepository
    private lateinit var artifacts: ArtifactRepository
    private lateinit var jobs: JobRepository
    private lateinit var events: EventRepository
    private lateinit var store: ArtifactStore
    private lateinit var audioDevices: AudioDeviceManager

    private val recorderDispatcher =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "scrappy-recorder")
        }.asCoroutineDispatcher()
    private val serviceScope = CoroutineScope(SupervisorJob() + recorderDispatcher)

    private var wakeLock: PowerManager.WakeLock? = null
    private val stopRequested = AtomicBoolean(false)
    private val segmentRequested = AtomicBoolean(false)
    private val runActive = AtomicBoolean(false)
    private var currentSessionId: String? = null
    private var currentLanguage: String? = null
    private var lastNotifMs = 0L

    override fun onCreate() {
        super.onCreate()
        val app = applicationContext as com.scraper.classroomcapture.ScraperApp
        container = app.container
        sessions = container.sessionRepository
        samples = container.sampleRepository
        artifacts = container.artifactRepository
        jobs = container.jobRepository
        events = container.eventRepository
        store = container.artifactStore
        audioDevices = container.audioDevices
        audioDevices.register()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        when (intent?.action) {
            ACTION_START -> {
                val sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: return START_NOT_STICKY
                val language = intent.getStringExtra(EXTRA_LANGUAGE) ?: "hi"
                serviceScope.launch { startRun(sessionId, language) }
            }
            ACTION_STOP_SEGMENT -> {
                segmentRequested.set(true)
                stopRequested.set(true)
            }
            ACTION_STOP -> {
                segmentRequested.set(false)
                stopRequested.set(true)
            }
        }
        // A killed service must not resurrect mid-segment: reconciliation
        // owns crash recovery, not the framework (P4.9).
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopRequested.set(true)
        try {
            audioDevices.unregister()
        } catch (e: Exception) {
            // Container may be gone in tests; nothing to do.
        }
        serviceScope.cancel()
        recorderDispatcher.close()
        releaseWakeLock()
        super.onDestroy()
    }

    // ---- run ----------------------------------------------------------------

    private suspend fun startRun(
        sessionId: String,
        language: String,
    ) {
        if (!runActive.compareAndSet(false, true)) return
        try {
            currentSessionId = sessionId
            currentLanguage = language
            status.value = RecordingStatus(RecordingStatus.Phase.STARTING, sessionId = sessionId)
            if (sessions.get(sessionId) == null) {
                return failRun("SESSION_NOT_FOUND", "Session not found.")
            }
            when (val preflight = store.checkPreflight(SEGMENT_ESTIMATE_BYTES)) {
                is PreflightResult.Refused -> {
                    logEvent("RECORDING_REFUSED_STORAGE", "free=${preflight.freeBytes}")
                    return failRun(
                        StoreErrorCodes.STORAGE_LOW,
                        "Storage is full. Free space to record.",
                    )
                }
                is PreflightResult.Low ->
                    logEvent("RECORDING_LOW_STORAGE", "free=${preflight.freeBytes}")
                PreflightResult.Ok -> Unit
            }
            if (!hasMicPermission()) {
                return failRun(
                    CaptureErrorCodes.MIC_PERMISSION_REVOKED,
                    "Microphone permission was revoked.",
                )
            }
            acquireWakeLock()
            val startedAt = SystemClock.uptimeMillis()
            val routeWatch =
                serviceScope.launch {
                    audioDevices.routeEvents.collect { change ->
                        if (change == null) return@collect
                        logEvent("ROUTE_CHANGED", "${change.fromId} -> ${change.toId} (${change.reason})")
                        if (change.reason == "bluetooth_disconnected") {
                            logEvent("BLUETOOTH_LOST_FALLBACK", "Capture continues on the platform route.")
                        }
                    }
                }
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(status.value, startedAt),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0,
            )
            val savedBefore = durableCount(sessionId)
            do {
                segmentRequested.set(false)
                recordOneSegment(sessionId, currentLanguage ?: language, savedBefore, startedAt)
            } while (segmentRequested.get())
            if (status.value.errorCode == null) status.value = RecordingStatus.Idle
            routeWatch.cancel()
            stopForegroundAndSelf()
        } finally {
            releaseWakeLock()
            runActive.set(false)
        }
    }

    // Records a single segment; returns after its rows are committed.
    private suspend fun recordOneSegment(
        sessionId: String,
        language: String,
        savedBefore: Int,
        startedAt: Long,
    ) {
        val now = System.currentTimeMillis()
        val sequence = samples.nextSequenceNumber(sessionId)
        val sampleId = UUID.randomUUID().toString()
        samples.createSample(
            ClassroomSample(
                id = sampleId,
                sessionId = sessionId,
                sequenceNumber = sequence,
                state = SampleState.CREATED,
                sourceLanguage = language,
                recordedStart = now,
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
            ),
            emptyList(),
        )
        if (!samples.transition(sampleId, SampleState.RECORDING, now)) {
            return failRun(CaptureErrorCodes.MIC_READ_FAILED, "Could not start recording.")
        }
        val bufferBytes = AudioConfig.recordBufferBytes()
        if (bufferBytes <= 0) {
            val at = System.currentTimeMillis()
            samples.markError(
                sampleId,
                CaptureErrorCodes.MIC_UNSUPPORTED,
                "This phone cannot record 16 kHz mono audio.",
                false,
                at,
            )
            return failRun(CaptureErrorCodes.MIC_UNSUPPORTED, "This phone cannot record 16 kHz mono audio.")
        }
        val recorder = newAudioRecord(bufferBytes)
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            val at = System.currentTimeMillis()
            samples.markError(
                sampleId,
                CaptureErrorCodes.MIC_BUSY,
                "Microphone is in use by another app.",
                true,
                at,
            )
            return failRun(CaptureErrorCodes.MIC_BUSY, "Microphone is in use by another app.")
        }
        val audioPath = store.audioRelativePath(sessionId, sampleId)
        val meter = MeterStats()
        val chunk = ShortArray(AudioConfig.CHUNK_FRAMES)
        stopRequested.set(false)
        var interrupted = false
        status.value =
            RecordingStatus(
                RecordingStatus.Phase.RECORDING,
                sessionId = sessionId,
                sampleId = sampleId,
                language = language,
                sequenceNumber = sequence,
                savedCount = savedBefore,
                pendingCount = pendingCount(sessionId),
            )
        notifyStatus(startedAt)
        try {
            recorder.startRecording()
            applyPreferredDevice(recorder)
            verifyActiveInput(sampleId, recorder)
            val written =
                store.writeAtomicFile(audioPath) { tmp ->
                    val writer = WavStreamWriter(tmp)
                    try {
                        while (!stopRequested.get()) {
                            val read = recorder.read(chunk, 0, chunk.size)
                            when {
                                read > 0 -> {
                                    writer.writePcm(chunk, read)
                                    meter.addChunk(chunk, read)
                                    publishProgress(sampleId, writer, meter, startedAt)
                                }
                                read == 0 -> Unit // No data yet; keep waiting.
                                else -> {
                                    interrupted = true
                                    logEvent("MIC_READ_ERROR", readCodeToError(read))
                                    break
                                }
                            }
                        }
                    } finally {
                        writer.finalizeAndClose()
                    }
                }
            val end = System.currentTimeMillis()
            samples.setRecordingWindow(sampleId, now, end, end)
            if (meter.totalFrames == 0L) {
                store.quarantineFile(audioPath, "empty-capture", end)
                samples.markError(sampleId, CaptureErrorCodes.MIC_NO_FRAMES, "No audio was captured.", true, end)
                return failRun(CaptureErrorCodes.MIC_NO_FRAMES, "No audio was captured.")
            }
            commitSegment(sessionId, sampleId, written.sha256, written.byteSize, meter, interrupted, end)
        } catch (e: StoreException) {
            val at = System.currentTimeMillis()
            samples.markError(sampleId, e.code, e.message ?: "Could not save recording.", e.retryable, at)
            failRun(e.code, e.message ?: "Could not save recording.")
        } finally {
            try {
                recorder.stop()
            } catch (e: Exception) {
                // Already stopped or never started; rows already reflect state.
            }
            recorder.release()
        }
    }

    // SAVING -> AUDIO_SAVED -> QUEUED_ASR with artifact + quality + job rows.
    private suspend fun commitSegment(
        sessionId: String,
        sampleId: String,
        sha256: String,
        byteSize: Long,
        meter: MeterStats,
        interrupted: Boolean,
        now: Long,
    ) {
        status.value = status.value.copy(phase = RecordingStatus.Phase.SAVING)
        notifyStatus(SystemClock.uptimeMillis())
        if (!samples.transition(sampleId, SampleState.SAVING, now)) return
        val flags =
            buildList {
                if (interrupted) add("interrupted")
                if (meter.sustainedClipping()) add("sustained_clipping")
            }
        artifacts.add(
            Artifact(
                id = UUID.randomUUID().toString(),
                sampleId = sampleId,
                kind = ArtifactKind.AUDIO_WAV,
                relativePath = store.audioRelativePath(sessionId, sampleId),
                sha256 = sha256,
                byteSize = byteSize,
                mime = "audio/wav",
                sampleRateHz = AudioConfig.SAMPLE_RATE_HZ,
                channels = 1,
                durationMs = meter.totalFrames * 1000 / AudioConfig.SAMPLE_RATE_HZ,
                createdAt = now,
            ),
        )
        samples.updateQuality(
            sampleId,
            QualityMetrics(
                rmsDb = meter.overallRmsDb(),
                peakDb = meter.peakDb(),
                clippingDetected = meter.clippedSamples > 0,
                silenceRatio = meter.silenceRatio(),
                flags = flags,
            ),
            now,
        )
        if (!samples.transition(sampleId, SampleState.AUDIO_SAVED, now)) return
        jobs.enqueue(
            ProcessingJob(
                id = UUID.randomUUID().toString(),
                sampleId = sampleId,
                kind = JobKind.ASR,
                state = JobState.QUEUED,
                attemptCount = 0,
                maxAttempts = MAX_ASR_ATTEMPTS,
                leaseOwner = null,
                leaseExpiresAt = null,
                lastHeartbeatAt = null,
                lastErrorCode = null,
                lastErrorMessage = null,
                nextAttemptAt = null,
                modelId = null,
                modelVersion = null,
                createdAt = now,
                updatedAt = now,
            ),
        )
        samples.transition(sampleId, SampleState.QUEUED_ASR, now)
        logEvent("SEGMENT_SAVED", "sample=$sampleId frames=${meter.totalFrames}")
    }

    // ---- helpers --------------------------------------------------------------

    // RECORD_AUDIO is checked in startRun before any recording path reaches
    // here; a revocation racing the check surfaces as a read error (P4.9).
    @android.annotation.SuppressLint("MissingPermission")
    private fun newAudioRecord(bufferBytes: Int): AudioRecord {
        val format =
            AudioFormat.Builder()
                .setSampleRate(AudioConfig.SAMPLE_RATE_HZ)
                .setChannelMask(AudioConfig.CHANNEL_CONFIG)
                .setEncoding(AudioConfig.AUDIO_FORMAT)
                .build()
        return AudioRecord.Builder()
            .setAudioSource(AudioConfig.SOURCE)
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferBytes)
            .build()
    }

    private fun readCodeToError(read: Int): String =
        when (read) {
            AudioRecord.ERROR_INVALID_OPERATION -> CaptureErrorCodes.MIC_PERMISSION_REVOKED
            AudioRecord.ERROR_DEAD_OBJECT -> CaptureErrorCodes.MIC_BUSY
            else -> CaptureErrorCodes.MIC_READ_FAILED
        }

    // Routes capture to the operator's chosen mic when still plugged in;
    // otherwise the platform routes (P5.2). Never fails the run.
    private fun applyPreferredDevice(recorder: AudioRecord) {
        try {
            val preferred = audioDevices.getPreferredId() ?: return
            val device = audioDevices.findDevice(preferred) ?: return
            recorder.preferredDevice = device
            serviceScope.launch { logEvent("ROUTE_PREFERRED_APPLIED", preferred) }
        } catch (e: Exception) {
            Log.w(TAG, "preferred device failed; platform routing stands", e)
        }
    }

    // The verified live input (P5.3): what AudioRecord actually routes from,
    // persisted on the sample row for the summary screen and the export.
    private fun verifyActiveInput(
        sampleId: String,
        recorder: AudioRecord,
    ) {
        try {
            val routed = recorder.routedDevice
            val id =
                if (routed != null) {
                    AudioInput.stableId(routed.type, routed.address ?: "")
                } else {
                    AudioInput.ID_UNKNOWN
                }
            serviceScope.launch {
                samples.setInputDevice(sampleId, id, System.currentTimeMillis())
                logEvent("INPUT_VERIFIED", id)
            }
        } catch (e: Exception) {
            Log.w(TAG, "routed device unreadable", e)
        }
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private suspend fun durableCount(sessionId: String): Int = samples.countBySessionAndStates(sessionId, DURABLE_FOR_COUNT)

    private suspend fun pendingCount(sessionId: String): Int = samples.countBySessionAndStates(sessionId, PENDING_FOR_COUNT)

    private fun publishProgress(
        sampleId: String,
        writer: WavStreamWriter,
        meter: MeterStats,
        startedAt: Long,
    ) {
        val current = status.value
        if (current.sampleId != sampleId) return
        status.value =
            current.copy(
                elapsedMs = writer.durationMs(),
                rmsDb = meter.overallRmsDb(),
                clippingWarning = meter.sustainedClipping(),
            )
        val now = SystemClock.uptimeMillis()
        if (now - lastNotifMs > NOTIF_UPDATE_MS) {
            lastNotifMs = now
            notifyStatus(startedAt)
        }
    }

    private fun buildNotification(
        current: RecordingStatus,
        startedAt: Long,
    ): Notification {
        val stopIntent = Intent(this, RecordingService::class.java).setAction(ACTION_STOP)
        val stop =
            PendingIntent.getService(
                this,
                0,
                stopIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val text =
            when (current.phase) {
                RecordingStatus.Phase.RECORDING ->
                    "Sample ${current.sequenceNumber} · ${formatElapsed(current.elapsedMs)}"
                RecordingStatus.Phase.SAVING -> "Saving sample ${current.sequenceNumber}…"
                else -> "Preparing microphone…"
            }
        return NotificationCompat.Builder(this, NotificationChannels.RECORDING)
            // System mic glyph until the P8 brand icon lands.
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.notification_recording_title))
            .setContentText(text)
            .setOngoing(true)
            .setUsesChronometer(current.phase == RecordingStatus.Phase.RECORDING)
            .setWhen(startedAt)
            .addAction(android.R.drawable.ic_media_pause, getString(R.string.action_stop), stop)
            .build()
    }

    private fun notifyStatus(startedAt: Long) {
        try {
            getSystemService(NotificationManager::class.java)?.notify(
                NOTIFICATION_ID,
                buildNotification(status.value, startedAt),
            )
        } catch (e: Exception) {
            Log.w(TAG, "notification update failed", e)
        }
    }

    private fun formatElapsed(ms: Long): String {
        val s = ms / 1000
        return "%02d:%02d".format(s / 60, s % 60)
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java) ?: return
        wakeLock =
            pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "scrappy:recording").apply {
                acquire(WAKELOCK_TIMEOUT_MS)
            }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (e: Exception) {
            Log.w(TAG, "wake lock release failed", e)
        }
        wakeLock = null
    }

    private suspend fun failRun(
        code: String,
        message: String,
    ) {
        status.value = RecordingStatus(RecordingStatus.Phase.IDLE, errorCode = code)
        logEvent("RECORDING_FAILED", "$code :: $message")
        stopForegroundAndSelf()
    }

    private fun stopForegroundAndSelf() {
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            Log.w(TAG, "stopForeground failed", e)
        }
        stopSelf()
    }

    private suspend fun logEvent(
        type: String,
        detail: String,
    ) {
        try {
            events.log(
                DeviceEvent(
                    id = UUID.randomUUID().toString(),
                    sessionId = currentSessionId,
                    sampleId = status.value.sampleId,
                    type = type,
                    detail = detail,
                    appVersion = BuildConfig.VERSION_NAME,
                    createdAt = System.currentTimeMillis(),
                ),
            )
        } catch (e: Exception) {
            Log.w(TAG, "event log failed", e)
        }
    }

    companion object {
        const val ACTION_START = "com.scraper.classroomcapture.recording.START"
        const val ACTION_STOP_SEGMENT = "com.scraper.classroomcapture.recording.STOP_SEGMENT"
        const val ACTION_STOP = "com.scraper.classroomcapture.recording.STOP"
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_LANGUAGE = "language"

        const val NOTIFICATION_ID = 1001

        // Planned bytes per segment for preflight (generous class-period cap).
        const val SEGMENT_ESTIMATE_BYTES = 20L * 1024 * 1024
        const val MAX_ASR_ATTEMPTS = 5
        const val NOTIF_UPDATE_MS = 5000L
        const val WAKELOCK_TIMEOUT_MS = 2L * 60 * 60 * 1000

        private const val TAG = "RecordingService"

        val DURABLE_FOR_COUNT =
            listOf(
                SampleState.AUDIO_SAVED,
                SampleState.QUEUED_ASR,
                SampleState.TRANSCRIBING,
                SampleState.TRANSCRIBED,
                SampleState.QUEUED_LLM,
                SampleState.ANNOTATING,
                SampleState.ANNOTATED,
                SampleState.READY_FOR_EXPORT,
                SampleState.EXPORTED,
            )
        val PENDING_FOR_COUNT = listOf(SampleState.QUEUED_ASR, SampleState.QUEUED_LLM)

        fun startIntent(
            context: Context,
            sessionId: String,
            language: String,
        ): Intent =
            Intent(context, RecordingService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_SESSION_ID, sessionId)
                .putExtra(EXTRA_LANGUAGE, language)
    }
}
