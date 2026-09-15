package com.scraper.classroomcapture.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

// Room entity for Session (P2.2). Pseudocodes only, never real names.
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val id: String,
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
