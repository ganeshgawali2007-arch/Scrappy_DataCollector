package com.scraper.classroomcapture.domain.model

// Immutable domain model for a classroom recording session (P2.1).
// Pseudocodes only — never real names (plan invariant: no PII).
// Timestamps are epoch millis (UTC); ISO-8601 formatting happens at export.
data class Session(
    val id: String,
    val grade: String,
    val subject: String,
    val defaultLanguage: String,
    val teacherCode: String,
    val schoolCode: String,
    val notes: String,
    val consentAck: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val closedAt: Long?,
)
