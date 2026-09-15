package com.scraper.classroomcapture.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.scraper.classroomcapture.domain.model.JobKind
import com.scraper.classroomcapture.domain.model.JobState

// Room entity for ProcessingJob (P2.2, P2.4). Lease columns support worker
// claim + reconciliation reclaim after process death (P6, P3).
@Entity(
    tableName = "processing_jobs",
    foreignKeys = [
        ForeignKey(
            entity = SampleEntity::class,
            parentColumns = ["id"],
            childColumns = ["sampleId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("sampleId"),
        Index(value = ["kind", "state"]),
        // One job row per (sample, kind) for the life of the sample (P6.1):
        // retries reuse the row (attemptCount grows), so a crash can never
        // duplicate work and reclaim always finds a single row.
        Index(value = ["sampleId", "kind"], unique = true),
    ],
)
data class ProcessingJobEntity(
    @PrimaryKey val id: String,
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
