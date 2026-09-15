package com.scraper.classroomcapture.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

// Append-only diagnostics events (P2.2). No delete/prune API — rotation, if
// ever needed, is an explicit P11 decision, never silent loss.
@Dao
interface DeviceEventDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(event: DeviceEventEntity)

    @Query("SELECT * FROM device_events ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<DeviceEventEntity>>

    @Query("SELECT * FROM device_events WHERE sessionId = :sessionId ORDER BY createdAt ASC")
    suspend fun getBySession(sessionId: String): List<DeviceEventEntity>

    @Query("SELECT COUNT(*) FROM device_events")
    suspend fun count(): Int
}
