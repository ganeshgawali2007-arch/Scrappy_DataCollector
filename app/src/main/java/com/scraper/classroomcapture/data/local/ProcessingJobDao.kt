package com.scraper.classroomcapture.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.scraper.classroomcapture.domain.model.JobKind
import com.scraper.classroomcapture.domain.model.JobState
import kotlinx.coroutines.flow.Flow

// Processing-job persistence (P2.2, P2.4). getClaimable returns jobs whose
// lease is free or expired and whose next attempt is due — the single query
// the P6 dispatcher uses to claim work, so two workers can never double-claim
// the same job row.
@Dao
interface ProcessingJobDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(job: ProcessingJobEntity)

    @Update
    suspend fun update(job: ProcessingJobEntity)

    @Query("SELECT * FROM processing_jobs WHERE sampleId = :sampleId")
    suspend fun getBySample(sampleId: String): List<ProcessingJobEntity>

    @Query("SELECT * FROM processing_jobs WHERE sampleId = :sampleId")
    fun observeBySample(sampleId: String): Flow<List<ProcessingJobEntity>>

    @Query(
        "SELECT * FROM processing_jobs WHERE kind = :kind AND state = 'QUEUED' " +
            "AND (nextAttemptAt IS NULL OR nextAttemptAt <= :now) " +
            "AND (leaseExpiresAt IS NULL OR leaseExpiresAt <= :now) " +
            "ORDER BY nextAttemptAt ASC LIMIT :limit",
    )
    suspend fun getClaimable(
        kind: JobKind,
        now: Long,
        limit: Int,
    ): List<ProcessingJobEntity>

    @Query(
        "UPDATE processing_jobs SET state = :state, leaseOwner = :owner, " +
            "leaseExpiresAt = :leaseExpiresAt, lastHeartbeatAt = :heartbeatAt, " +
            "attemptCount = attemptCount + 1, updatedAt = :updatedAt " +
            "WHERE id = :id AND state = 'QUEUED'",
    )
    suspend fun claim(
        id: String,
        state: JobState,
        owner: String,
        leaseExpiresAt: Long,
        heartbeatAt: Long,
        updatedAt: Long,
    ): Int

    @Query(
        "UPDATE processing_jobs SET lastHeartbeatAt = :at, leaseExpiresAt = :leaseExpiresAt, " +
            "updatedAt = :at WHERE id = :id",
    )
    suspend fun heartbeat(
        id: String,
        at: Long,
        leaseExpiresAt: Long,
    )

    @Query("SELECT * FROM processing_jobs WHERE state = 'RUNNING' AND leaseExpiresAt <= :now")
    suspend fun getExpiredLeases(now: Long): List<ProcessingJobEntity>
}
