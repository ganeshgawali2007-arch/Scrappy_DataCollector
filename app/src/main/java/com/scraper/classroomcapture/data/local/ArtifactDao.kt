package com.scraper.classroomcapture.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.scraper.classroomcapture.domain.model.ArtifactKind
import kotlinx.coroutines.flow.Flow

// Artifact persistence (P2.2). One row per (sample, kind) — re-running a
// stage replaces the row only after the new file is fully written + hashed
// (P3), so readers never see a half-written artifact reference.
@Dao
interface ArtifactDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(artifact: ArtifactEntity)

    @Query("SELECT * FROM artifacts WHERE sampleId = :sampleId")
    suspend fun getBySample(sampleId: String): List<ArtifactEntity>

    @Query("SELECT * FROM artifacts WHERE sampleId = :sampleId")
    fun observeBySample(sampleId: String): Flow<List<ArtifactEntity>>

    @Query("SELECT * FROM artifacts WHERE sampleId = :sampleId AND kind = :kind")
    suspend fun getBySampleAndKind(
        sampleId: String,
        kind: ArtifactKind,
    ): ArtifactEntity?

    @Query("DELETE FROM artifacts WHERE sampleId = :sampleId")
    suspend fun deleteBySample(sampleId: String)
}
