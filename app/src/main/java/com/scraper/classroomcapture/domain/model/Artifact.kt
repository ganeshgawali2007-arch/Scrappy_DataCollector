package com.scraper.classroomcapture.domain.model

// Immutable domain model for one persisted file artifact (P2.1).
// Paths are relative to the session/sample directory (P3 ArtifactStore);
// absolute paths are never persisted (rename-safe, traversal-safe).
enum class ArtifactKind {
    AUDIO_WAV,
    ASR_JSON,
    ANNOTATION_JSON,
}

data class Artifact(
    val id: String,
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
