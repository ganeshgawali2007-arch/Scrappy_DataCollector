package com.scraper.classroomcapture.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.scraper.classroomcapture.domain.model.ArtifactKind

// Room entity for Artifact (P2.2, P2.4). relativePath is relative to the
// session/sample directory — absolute paths are never persisted.
@Entity(
    tableName = "artifacts",
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
        Index(value = ["sampleId", "kind"], unique = true),
    ],
)
data class ArtifactEntity(
    @PrimaryKey val id: String,
    val sampleId: String,
    val kind: ArtifactKind,
    val relativePath: String,
    val sha256: String,
    val byteSize: Long,
    val mime: String,
    val sampleRateHz: Int?,
    val channels: Int?,
    val durationMs: Long?,
    val createdAt: Long,
)
