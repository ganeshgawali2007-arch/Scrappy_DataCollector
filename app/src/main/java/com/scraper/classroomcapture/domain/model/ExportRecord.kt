package com.scraper.classroomcapture.domain.model

// Immutable domain model for one export attempt (P2.1, DECISIONS.md D17).
// One row per export — never silently overwrite; source data is never
// auto-deleted after export. WRITTEN_UNVERIFIED is legal only when the SAF
// provider cannot read back the completed output.
enum class ExportVerification {
    VERIFIED,
    WRITTEN_UNVERIFIED,
}

enum class ExportStatus {
    PREPARING,
    WRITING,
    CHECKING,
    SUCCEEDED,
    FAILED,
}

data class ExportRecord(
    val id: String,
    val sessionIds: List<String>,
    val status: ExportStatus,
    val verification: ExportVerification?,
    val outputPath: String?,
    val checksum: String?,
    val byteSize: Long?,
    val createdAt: Long,
    val completedAt: Long?,
)
