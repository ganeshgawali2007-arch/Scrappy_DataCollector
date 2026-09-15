package com.scraper.classroomcapture.domain.model

// Immutable domain model for one unit of background work (P2.1, P2.4).
// Lease fields let a worker claim a job and let reconciliation reclaim it
// after process death (P6 dispatcher, P3 reconciliation).
enum class JobKind {
    ASR,
    LLM,
}

enum class JobState {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
}

data class ProcessingJob(
    val id: String,
    val sampleId: String,
    val kind: JobKind,
    val state: JobState,
    val attemptCount: Int,
    val maxAttempts: Int,
    val leaseOwner: String?,
    val leaseExpiresAt: Long?,
    val lastHeartbeatAt: Long?,
    val lastErrorCode: String?,
    val lastErrorMessage: String?,
    val nextAttemptAt: Long?,
    val modelId: String?,
    val modelVersion: String?,
    val createdAt: Long,
    val updatedAt: Long,
)
