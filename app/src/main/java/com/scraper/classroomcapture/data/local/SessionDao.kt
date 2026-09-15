package com.scraper.classroomcapture.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

// Session persistence (P2.2). Inserts abort on id collision — ids are UUIDs
// generated once at creation; a collision means a programming error upstream.
@Dao
interface SessionDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(session: SessionEntity)

    @Update
    suspend fun update(session: SessionEntity)

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun getById(id: String): SessionEntity?

    @Query("SELECT * FROM sessions WHERE id = :id")
    fun observeById(id: String): Flow<SessionEntity?>

    @Query("SELECT * FROM sessions ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<SessionEntity>>

    @Query("UPDATE sessions SET closedAt = :closedAt, updatedAt = :updatedAt WHERE id = :id")
    suspend fun markClosed(
        id: String,
        closedAt: Long,
        updatedAt: Long,
    )

    @Query("SELECT COUNT(*) FROM sessions")
    suspend fun count(): Int
}
