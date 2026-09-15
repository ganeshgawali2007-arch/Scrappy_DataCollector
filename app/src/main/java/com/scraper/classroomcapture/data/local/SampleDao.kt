package com.scraper.classroomcapture.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.scraper.classroomcapture.domain.model.SampleState
import kotlinx.coroutines.flow.Flow

// Sample persistence (P2.2). Multi-table writes go through @Transaction
// methods so a crash can never leave a sample without its jobs or vice
// versa. State changes must be validated by SampleStateTransitions first —
// the DAO stores what it is told.
@Dao
interface SampleDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(sample: SampleEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertJobs(jobs: List<ProcessingJobEntity>)

    @Transaction
    suspend fun insertNewSample(
        sample: SampleEntity,
        jobs: List<ProcessingJobEntity>,
    ) {
        insert(sample)
        if (jobs.isNotEmpty()) insertJobs(jobs)
    }

    @Update
    suspend fun update(sample: SampleEntity)

    @Query("UPDATE samples SET state = :state, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateState(
        id: String,
        state: SampleState,
        updatedAt: Long,
    )

    @Query(
        "UPDATE samples SET state = :state, errorCode = :code, " +
            "errorMessage = :message, errorRetryable = :retryable, updatedAt = :updatedAt " +
            "WHERE id = :id",
    )
    suspend fun markError(
        id: String,
        state: SampleState,
        code: String,
        message: String,
        retryable: Boolean,
        updatedAt: Long,
    )

    @Query(
        "UPDATE samples SET state = :state, recoveryReason = :reason, " +
            "recoveredAt = :recoveredAt, priorState = :priorState, updatedAt = :updatedAt " +
            "WHERE id = :id",
    )
    suspend fun markRecovered(
        id: String,
        state: SampleState,
        reason: String,
        recoveredAt: Long,
        priorState: SampleState,
        updatedAt: Long,
    )

    @Query("SELECT * FROM samples WHERE id = :id")
    suspend fun getById(id: String): SampleEntity?

    @Query("SELECT * FROM samples WHERE id = :id")
    fun observeById(id: String): Flow<SampleEntity?>

    @Query("SELECT * FROM samples WHERE sessionId = :sessionId ORDER BY sequenceNumber ASC")
    fun observeBySession(sessionId: String): Flow<List<SampleEntity>>

    @Query("SELECT * FROM samples WHERE state = :state ORDER BY updatedAt ASC")
    suspend fun getByState(state: SampleState): List<SampleEntity>

    @Query("SELECT * FROM samples WHERE state IN (:states) ORDER BY updatedAt ASC")
    suspend fun getByStates(states: List<SampleState>): List<SampleEntity>

    @Query("SELECT COALESCE(MAX(sequenceNumber), 0) + 1 FROM samples WHERE sessionId = :sessionId")
    suspend fun nextSequenceNumber(sessionId: String): Int

    @Query("SELECT COUNT(*) FROM samples WHERE sessionId = :sessionId AND state = :state")
    suspend fun countBySessionAndState(
        sessionId: String,
        state: SampleState,
    ): Int

    @Query("SELECT COUNT(*) FROM samples WHERE sessionId = :sessionId AND state IN (:states)")
    suspend fun countBySessionAndStates(
        sessionId: String,
        states: List<SampleState>,
    ): Int

    @Query("UPDATE samples SET recordedStart = :start, recordedEnd = :end, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setRecordingWindow(
        id: String,
        start: Long,
        end: Long,
        updatedAt: Long,
    )

    @Query(
        "UPDATE samples SET q_rmsDb = :rmsDb, q_peakDb = :peakDb, " +
            "q_clippingDetected = :clipping, q_silenceRatio = :silenceRatio, " +
            "q_flags = :flags, updatedAt = :updatedAt WHERE id = :id",
    )
    suspend fun updateQuality(
        id: String,
        rmsDb: Double?,
        peakDb: Double?,
        clipping: Boolean,
        silenceRatio: Double?,
        flags: String,
        updatedAt: Long,
    )
}
