package com.scraper.classroomcapture.domain.model

// Immutable domain model for one append-only diagnostics event (P2.1).
// Never contains audio, transcripts, notes, or PII — IDs, counts, durations,
// error codes, model/app versions, and device-route events only (P0.4).
data class DeviceEvent(
    val id: String,
    val sessionId: String?,
    val sampleId: String?,
    val type: String,
    val detail: String?,
    val appVersion: String,
    val createdAt: Long,
)
