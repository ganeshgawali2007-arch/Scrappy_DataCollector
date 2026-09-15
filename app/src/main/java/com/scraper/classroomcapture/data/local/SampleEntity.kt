package com.scraper.classroomcapture.data.local

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.scraper.classroomcapture.domain.model.SampleState

// Room entity for ClassroomSample (P2.2, P2.4). Quality is embedded;
// recovery columns are written only by startup reconciliation (D14).
@Entity(
    tableName = "samples",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("sessionId"),
        Index("state"),
        Index(value = ["sessionId", "sequenceNumber"], unique = true),
    ],
)
data class SampleEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val sequenceNumber: Int,
    val state: SampleState,
    val sourceLanguage: String,
    val recordedStart: Long?,
    val recordedEnd: Long?,
    val inputDevice: String?,
    val codeSwitching: Boolean,
    @Embedded(prefix = "q_") val quality: QualityColumns,
    val errorCode: String?,
    val errorMessage: String?,
    val errorRetryable: Boolean,
    val recoveryReason: String?,
    val recoveredAt: Long?,
    val priorState: SampleState?,
    val createdAt: Long,
    val updatedAt: Long,
)

data class QualityColumns(
    val rmsDb: Double?,
    val peakDb: Double?,
    val clippingDetected: Boolean,
    val silenceRatio: Double?,
    // Machine tokens joined by \u001F (see Converters); never free text.
    val flags: String,
)
