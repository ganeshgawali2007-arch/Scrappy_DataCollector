package com.scraper.classroomcapture.data.repository

import com.scraper.classroomcapture.data.local.ArtifactEntity
import com.scraper.classroomcapture.data.local.Converters
import com.scraper.classroomcapture.data.local.DeviceEventEntity
import com.scraper.classroomcapture.data.local.ExportRecordEntity
import com.scraper.classroomcapture.data.local.ProcessingJobEntity
import com.scraper.classroomcapture.data.local.QualityColumns
import com.scraper.classroomcapture.data.local.SampleEntity
import com.scraper.classroomcapture.data.local.SessionEntity
import com.scraper.classroomcapture.domain.model.Artifact
import com.scraper.classroomcapture.domain.model.ClassroomSample
import com.scraper.classroomcapture.domain.model.DeviceEvent
import com.scraper.classroomcapture.domain.model.ExportRecord
import com.scraper.classroomcapture.domain.model.ProcessingJob
import com.scraper.classroomcapture.domain.model.QualityMetrics
import com.scraper.classroomcapture.domain.model.Session

// Entity ↔ domain mapping (P2.6). UI and workers see domain models only;
// Room entities never cross the repository boundary.
internal fun Session.toEntity(): SessionEntity =
    SessionEntity(
        id = id,
        grade = grade,
        subject = subject,
        defaultLanguage = defaultLanguage,
        teacherCode = teacherCode,
        schoolCode = schoolCode,
        notes = notes,
        consentAck = consentAck,
        createdAt = createdAt,
        updatedAt = updatedAt,
        closedAt = closedAt,
    )

internal fun SessionEntity.toDomain(): Session =
    Session(
        id = id,
        grade = grade,
        subject = subject,
        defaultLanguage = defaultLanguage,
        teacherCode = teacherCode,
        schoolCode = schoolCode,
        notes = notes,
        consentAck = consentAck,
        createdAt = createdAt,
        updatedAt = updatedAt,
        closedAt = closedAt,
    )

internal fun QualityMetrics.toColumns(): QualityColumns =
    QualityColumns(
        rmsDb = rmsDb,
        peakDb = peakDb,
        clippingDetected = clippingDetected,
        silenceRatio = silenceRatio,
        flags = flags.joinToString(Converters.SEPARATOR),
    )

internal fun QualityColumns.toDomain(): QualityMetrics =
    QualityMetrics(
        rmsDb = rmsDb,
        peakDb = peakDb,
        clippingDetected = clippingDetected,
        silenceRatio = silenceRatio,
        flags = if (flags.isEmpty()) emptyList() else flags.split(Converters.SEPARATOR),
    )

internal fun ClassroomSample.toEntity(): SampleEntity =
    SampleEntity(
        id = id,
        sessionId = sessionId,
        sequenceNumber = sequenceNumber,
        state = state,
        sourceLanguage = sourceLanguage,
        recordedStart = recordedStart,
        recordedEnd = recordedEnd,
        inputDevice = inputDevice,
        codeSwitching = codeSwitching,
        quality = quality.toColumns(),
        errorCode = errorCode,
        errorMessage = errorMessage,
        errorRetryable = errorRetryable,
        recoveryReason = recoveryReason,
        recoveredAt = recoveredAt,
        priorState = priorState,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

internal fun SampleEntity.toDomain(): ClassroomSample =
    ClassroomSample(
        id = id,
        sessionId = sessionId,
        sequenceNumber = sequenceNumber,
        state = state,
        sourceLanguage = sourceLanguage,
        recordedStart = recordedStart,
        recordedEnd = recordedEnd,
        inputDevice = inputDevice,
        codeSwitching = codeSwitching,
        quality = quality.toDomain(),
        errorCode = errorCode,
        errorMessage = errorMessage,
        errorRetryable = errorRetryable,
        recoveryReason = recoveryReason,
        recoveredAt = recoveredAt,
        priorState = priorState,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

internal fun Artifact.toEntity(): ArtifactEntity =
    ArtifactEntity(
        id = id,
        sampleId = sampleId,
        kind = kind,
        relativePath = relativePath,
        sha256 = sha256,
        byteSize = byteSize,
        mime = mime,
        sampleRateHz = sampleRateHz,
        channels = channels,
        durationMs = durationMs,
        createdAt = createdAt,
    )

internal fun ArtifactEntity.toDomain(): Artifact =
    Artifact(
        id = id,
        sampleId = sampleId,
        kind = kind,
        relativePath = relativePath,
        sha256 = sha256,
        byteSize = byteSize,
        mime = mime,
        sampleRateHz = sampleRateHz,
        channels = channels,
        durationMs = durationMs,
        createdAt = createdAt,
    )

internal fun ProcessingJob.toEntity(): ProcessingJobEntity =
    ProcessingJobEntity(
        id = id,
        sampleId = sampleId,
        kind = kind,
        state = state,
        attemptCount = attemptCount,
        maxAttempts = maxAttempts,
        leaseOwner = leaseOwner,
        leaseExpiresAt = leaseExpiresAt,
        lastHeartbeatAt = lastHeartbeatAt,
        lastErrorCode = lastErrorCode,
        lastErrorMessage = lastErrorMessage,
        nextAttemptAt = nextAttemptAt,
        modelId = modelId,
        modelVersion = modelVersion,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

internal fun ProcessingJobEntity.toDomain(): ProcessingJob =
    ProcessingJob(
        id = id,
        sampleId = sampleId,
        kind = kind,
        state = state,
        attemptCount = attemptCount,
        maxAttempts = maxAttempts,
        leaseOwner = leaseOwner,
        leaseExpiresAt = leaseExpiresAt,
        lastHeartbeatAt = lastHeartbeatAt,
        lastErrorCode = lastErrorCode,
        lastErrorMessage = lastErrorMessage,
        nextAttemptAt = nextAttemptAt,
        modelId = modelId,
        modelVersion = modelVersion,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

internal fun DeviceEvent.toEntity(): DeviceEventEntity =
    DeviceEventEntity(
        id = id,
        sessionId = sessionId,
        sampleId = sampleId,
        type = type,
        detail = detail,
        appVersion = appVersion,
        createdAt = createdAt,
    )

internal fun DeviceEventEntity.toDomain(): DeviceEvent =
    DeviceEvent(
        id = id,
        sessionId = sessionId,
        sampleId = sampleId,
        type = type,
        detail = detail,
        appVersion = appVersion,
        createdAt = createdAt,
    )

internal fun ExportRecord.toEntity(): ExportRecordEntity =
    ExportRecordEntity(
        id = id,
        status = status,
        verification = verification,
        outputPath = outputPath,
        checksum = checksum,
        byteSize = byteSize,
        createdAt = createdAt,
        completedAt = completedAt,
    )

internal fun ExportRecordEntity.toDomain(sessionIds: List<String>): ExportRecord =
    ExportRecord(
        id = id,
        sessionIds = sessionIds,
        status = status,
        verification = verification,
        outputPath = outputPath,
        checksum = checksum,
        byteSize = byteSize,
        createdAt = createdAt,
        completedAt = completedAt,
    )
