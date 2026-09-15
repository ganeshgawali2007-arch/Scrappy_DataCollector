package com.scraper.classroomcapture.data.local

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import com.scraper.classroomcapture.domain.model.ExportStatus
import com.scraper.classroomcapture.domain.model.ExportVerification
import kotlinx.coroutines.flow.Flow

// Export-record persistence (P2.2, D17). insertExportWithSessions is the only
// way to create an export: record + covered sessions commit atomically.
@Dao
interface ExportDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(record: ExportRecordEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSessionRefs(refs: List<ExportSessionCrossRef>)

    @Transaction
    suspend fun insertExportWithSessions(
        record: ExportRecordEntity,
        sessionIds: List<String>,
    ) {
        insert(record)
        insertSessionRefs(sessionIds.map { ExportSessionCrossRef(record.id, it) })
    }

    @Query(
        "UPDATE export_records SET status = :status, verification = :verification, " +
            "outputPath = :outputPath, checksum = :checksum, byteSize = :byteSize, " +
            "completedAt = :completedAt WHERE id = :id",
    )
    suspend fun markFinished(
        id: String,
        status: ExportStatus,
        verification: ExportVerification?,
        outputPath: String?,
        checksum: String?,
        byteSize: Long?,
        completedAt: Long,
    )

    @Query("SELECT * FROM export_records WHERE id = :id")
    suspend fun getById(id: String): ExportRecordEntity?

    @Query("SELECT sessionId FROM export_sessions WHERE exportId = :exportId")
    suspend fun getSessionIds(exportId: String): List<String>

    @Query("SELECT * FROM export_records ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<ExportRecordEntity>>

    @Transaction
    @Query("SELECT * FROM export_records ORDER BY createdAt DESC")
    fun observeAllWithSessions(): Flow<List<ExportWithSessions>>
}

// One export row plus the sessions it covers, loaded atomically (P2.2).
data class ExportWithSessions(
    @Embedded val record: ExportRecordEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "exportId",
        entity = ExportSessionCrossRef::class,
        projection = ["sessionId"],
    )
    val sessionIds: List<String>,
)
