package com.scraper.classroomcapture.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.scraper.classroomcapture.domain.model.ExportStatus
import com.scraper.classroomcapture.domain.model.ExportVerification

// Room entity for ExportRecord (P2.2, P2.4, D17). One row per export attempt;
// sessions covered by the export live in export_sessions (no silent overwrite).
@Entity(
    tableName = "export_records",
    indices = [Index("createdAt")],
)
data class ExportRecordEntity(
    @PrimaryKey val id: String,
    val status: ExportStatus,
    val verification: ExportVerification?,
    val outputPath: String?,
    val checksum: String?,
    val byteSize: Long?,
    val createdAt: Long,
    val completedAt: Long?,
)

@Entity(
    tableName = "export_sessions",
    primaryKeys = ["exportId", "sessionId"],
    foreignKeys = [
        ForeignKey(
            entity = ExportRecordEntity::class,
            parentColumns = ["id"],
            childColumns = ["exportId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId")],
)
data class ExportSessionCrossRef(
    val exportId: String,
    val sessionId: String,
)
