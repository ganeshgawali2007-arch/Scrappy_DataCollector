package com.scraper.classroomcapture.data.serialization

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

// Canonical export record pedagogical_sample.v2 (docs/DATA_SCHEMA.md, P2.7).
// Field-for-field match with the schema; snake_case wire names are pinned by
// @SerialName so Kotlin renames can never silently change the dataset.
// Timestamps are ISO-8601 UTC strings; statuses are validated Strings.
@Serializable
data class PedagogicalRecordV2(
    @SerialName("schema_version")
    val schemaVersion: String = Vocab.SCHEMA_RECORD_V2,
    @SerialName("sample_id")
    val sampleId: String,
    @SerialName("session_id")
    val sessionId: String,
    @SerialName("sequence_number")
    val sequenceNumber: Int,
    val source: String,
    @SerialName("source_language")
    val sourceLanguage: String,
    val target: String = "",
    @SerialName("target_language")
    val targetLanguage: String = Vocab.TARGET_UNSET,
    @SerialName("translation_status")
    val translationStatus: String = "pending",
    val grade: GradeValue,
    val subject: String,
    val topic: String? = null,
    val activity: String? = null,
    @SerialName("learning_objective")
    val learningObjective: String? = null,
    @SerialName("pedagogical_form")
    val pedagogicalForm: String? = null,
    @SerialName("interaction_type")
    val interactionType: String? = null,
    @SerialName("concept_stage")
    val conceptStage: String? = null,
    val examples: List<String> = emptyList(),
    @SerialName("expected_learner_response")
    val expectedLearnerResponse: String? = null,
    @SerialName("translation_notes")
    val translationNotes: String = "",
    val tts: TtsBlock = TtsBlock(),
    val audio: AudioBlock,
    val asr: AsrBlock,
    val annotation: AnnotationBlock = AnnotationBlock(),
    val quality: QualityBlock = QualityBlock(),
    val provenance: ProvenanceBlock,
    val review: ReviewBlock = ReviewBlock(),
)

@Serializable
data class TtsBlock(
    val text: String = "",
    @SerialName("normalized_text")
    val normalizedText: String = "",
    @SerialName("pronunciation_notes")
    val pronunciationNotes: String = "",
    @SerialName("speaker_id")
    val speakerId: String = "",
    val status: String = "pending",
)

@Serializable
data class AudioBlock(
    val path: String,
    val sha256: String,
    val mime: String = "audio/wav",
    @SerialName("sample_rate_hz")
    val sampleRateHz: Int = 16000,
    val channels: Int = 1,
    @SerialName("duration_ms")
    val durationMs: Long,
)

@Serializable
data class AsrBlock(
    val status: String = "unavailable",
    val path: String? = null,
    val text: String = "",
    @SerialName("model_id")
    val modelId: String? = null,
    @SerialName("model_version")
    val modelVersion: String? = null,
    val verified: Boolean = false,
)

@Serializable
data class AnnotationBlock(
    val status: String = "unavailable",
    val path: String? = null,
    @SerialName("claims_verified")
    val claimsVerified: Boolean = false,
    @SerialName("generated_by")
    val generatedBy: String? = null,
)

@Serializable
data class QualityBlock(
    @SerialName("rms_db")
    val rmsDb: Double? = null,
    @SerialName("peak_db")
    val peakDb: Double? = null,
    @SerialName("clipping_detected")
    val clippingDetected: Boolean = false,
    @SerialName("silence_ratio")
    val silenceRatio: Double? = null,
    val flags: List<String> = emptyList(),
)

@Serializable
data class ProvenanceBlock(
    @SerialName("created_at")
    val createdAt: String,
    @SerialName("recorded_start")
    val recordedStart: String? = null,
    @SerialName("recorded_end")
    val recordedEnd: String? = null,
    @SerialName("input_device")
    val inputDevice: String? = null,
    @SerialName("code_switching")
    val codeSwitching: Boolean = false,
    @SerialName("processing_state")
    val processingState: String,
    @SerialName("app_version")
    val appVersion: String,
)

@Serializable
data class ReviewBlock(
    val status: String = "unreviewed",
    @SerialName("reviewer_id")
    val reviewerId: String = "",
    @SerialName("reviewed_at")
    val reviewedAt: String? = null,
    @SerialName("source_audio_checked")
    val sourceAudioChecked: Boolean = false,
    @SerialName("pedagogical_fidelity")
    val pedagogicalFidelity: String = "pending",
    val notes: String = "",
)

// Schema allows an integer grade or a documented preschool label.
// Numbers decode to Numeric, strings to Label — never coerced or fabricated.
@Serializable(with = GradeValueSerializer::class)
sealed interface GradeValue {
    @Serializable
    data class Numeric(val value: Int) : GradeValue

    @Serializable
    data class Label(val value: String) : GradeValue
}

object GradeValueSerializer : KSerializer<GradeValue> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("GradeValue", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: GradeValue,
    ) {
        require(encoder is JsonEncoder)
        when (value) {
            is GradeValue.Numeric -> encoder.encodeJsonElement(JsonPrimitive(value.value))
            is GradeValue.Label -> encoder.encodeString(value.value)
        }
    }

    override fun deserialize(decoder: Decoder): GradeValue {
        require(decoder is JsonDecoder)
        val primitive = decoder.decodeJsonElement().jsonPrimitive
        return if (primitive.isString) {
            GradeValue.Label(primitive.content)
        } else {
            GradeValue.Numeric(primitive.int)
        }
    }
}
