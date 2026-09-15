package com.scraper.classroomcapture.domain.model

// Immutable domain model for one teaching utterance/activity sample (P2.1).
// Maps to one pedagogical_sample.v2 record at export (docs/DATA_SCHEMA.md).
// Recovery bookkeeping (recoveryReason/recoveredAt/priorState) is written
// only by startup reconciliation (DECISIONS.md D14).
data class ClassroomSample(
    val id: String,
    val sessionId: String,
    val sequenceNumber: Int,
    val state: SampleState,
    val sourceLanguage: String,
    val recordedStart: Long?,
    val recordedEnd: Long?,
    val inputDevice: String?,
    val codeSwitching: Boolean,
    val quality: QualityMetrics,
    val errorCode: String?,
    val errorMessage: String?,
    val errorRetryable: Boolean,
    val recoveryReason: String?,
    val recoveredAt: Long?,
    val priorState: SampleState?,
    val createdAt: Long,
    val updatedAt: Long,
)
