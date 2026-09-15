package com.scraper.classroomcapture.data.serialization

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Shared JSON configuration for all persisted/exported structured data (P2.7).
// ignoreUnknownKeys keeps forward compatibility with Mac-side additions;
// explicitNulls preserves the schema's null-vs-absent distinction.
val ScrappyJson =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = true
        encodeDefaults = true
    }

// Provisional ASR sidecar format asr_result.v1 (asr/<uuid>.v1.json).
// Carries what the P6 dispatcher and P9 exporter need; the whisper.cpp field
// mapping is finalized in P7 without breaking this envelope.
@Serializable
data class AsrResultV1(
    val format: String = Vocab.SCHEMA_ASR_V1,
    @SerialName("sample_id")
    val sampleId: String,
    val text: String,
    val language: String,
    val segments: List<AsrSegment> = emptyList(),
    @SerialName("model_id")
    val modelId: String,
    @SerialName("model_version")
    val modelVersion: String,
    @SerialName("created_at")
    val createdAt: String,
)

@Serializable
data class AsrSegment(
    @SerialName("start_ms")
    val startMs: Long,
    @SerialName("end_ms")
    val endMs: Long,
    val text: String,
    val confidence: Double? = null,
)

// Provisional annotation sidecar format annotation_result.v1
// (annotation/<uuid>.v1.json). Every AI claim carries evidence spans plus the
// generating model identity — suggestions, never facts (schema §Vocabularies).
@Serializable
data class AnnotationResultV1(
    val format: String = Vocab.SCHEMA_ANNOTATION_V1,
    @SerialName("sample_id")
    val sampleId: String,
    val activity: String? = null,
    @SerialName("pedagogical_form")
    val pedagogicalForm: String? = null,
    @SerialName("concept_stage")
    val conceptStage: String? = null,
    val topic: String? = null,
    @SerialName("learning_objective")
    val learningObjective: String? = null,
    @SerialName("expected_learner_response")
    val expectedLearnerResponse: String? = null,
    @SerialName("evidence_spans")
    val evidenceSpans: List<EvidenceSpan> = emptyList(),
    @SerialName("generated_by")
    val generatedBy: String,
    @SerialName("model_version")
    val modelVersion: String? = null,
    @SerialName("prompt_version")
    val promptVersion: String? = null,
    @SerialName("created_at")
    val createdAt: String,
)

@Serializable
data class EvidenceSpan(
    val field: String,
    val quote: String,
    @SerialName("start_ms")
    val startMs: Long? = null,
    @SerialName("end_ms")
    val endMs: Long? = null,
)

// Device-event JSONL format device_event.v1 (events/ in the export).
// Diagnostics only — never audio, transcripts, notes, or PII.
@Serializable
data class DeviceEventV1(
    val format: String = Vocab.SCHEMA_EVENT_V1,
    @SerialName("event_id")
    val eventId: String,
    val type: String,
    @SerialName("session_id")
    val sessionId: String? = null,
    @SerialName("sample_id")
    val sampleId: String? = null,
    val detail: String? = null,
    @SerialName("app_version")
    val appVersion: String,
    @SerialName("created_at")
    val createdAt: String,
)

// Export manifest format manifest.v2 (manifest.v2.json). The records_sha256
// covers records.jsonl; each file entry carries its own checksum (P9 writes
// and verifies this file; P13 documents it).
@Serializable
data class ExportManifestV2(
    val format: String = Vocab.SCHEMA_MANIFEST_V2,
    @SerialName("export_id")
    val exportId: String,
    @SerialName("created_at")
    val createdAt: String,
    @SerialName("app_version")
    val appVersion: String,
    @SerialName("schema_version")
    val schemaVersion: String = Vocab.SCHEMA_RECORD_V2,
    @SerialName("record_count")
    val recordCount: Int,
    val files: List<ManifestFile> = emptyList(),
    @SerialName("records_sha256")
    val recordsSha256: String,
    val verification: String,
)

@Serializable
data class ManifestFile(
    @SerialName("relative_path")
    val relativePath: String,
    val sha256: String,
    @SerialName("byte_size")
    val byteSize: Long,
)
