package com.scraper.classroomcapture.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// Room entity for DeviceEvent (P2.2). Append-only diagnostics; never audio,
// transcripts, notes, or PII. No parent FK so session-scoped events survive
// even if their session row is removed by an explicit user delete (P8).
@Entity(
    tableName = "device_events",
    indices = [
        Index("sessionId"),
        Index("sampleId"),
        Index("createdAt"),
    ],
)
data class DeviceEventEntity(
    @PrimaryKey val id: String,
    val sessionId: String?,
    val sampleId: String?,
    val type: String,
    val detail: String?,
    val appVersion: String,
    val createdAt: Long,
)
