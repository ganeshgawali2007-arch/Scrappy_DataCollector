package com.scraper.classroomcapture.llm

import com.scraper.classroomcapture.data.serialization.AnnotationResultV1
import com.scraper.classroomcapture.data.serialization.EvidenceSpan
import com.scraper.classroomcapture.data.serialization.ScrappyJson
import com.scraper.classroomcapture.data.serialization.Vocab
import com.scraper.classroomcapture.processing.EngineErrorCodes
import com.scraper.classroomcapture.processing.EngineException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Strict versioned annotation schema validation (P10.4–P10.6). Rejects
// malformed JSON, unsupported vocab, missing evidence spans, or missing
// model identity — nothing invalid is ever persisted (P10.5).
// insufficient_audio_evidence (P10.6) maps to INPUT_INVALID (permanent) so
// the dispatcher falls back to TRANSCRIBED → READY_FOR_EXPORT (P10.8).
@Serializable
private data class RawAnnotation(
    val activity: String? = null,
    @SerialName("pedagogical_form") val pedagogicalForm: String? = null,
    @SerialName("concept_stage") val conceptStage: String? = null,
    val topic: String? = null,
    @SerialName("learning_objective") val learningObjective: String? = null,
    @SerialName("expected_learner_response") val expectedLearnerResponse: String? = null,
    @SerialName("evidence_spans") val evidenceSpans: List<EvidenceSpan>? = null,
    @SerialName("insufficient_audio_evidence") val insufficientAudioEvidence: Boolean = false,
)

object AnnotationValidator {
    fun parseAndValidate(
        json: String,
        sampleId: String,
        transcript: String,
        modelId: String,
        modelVersion: String,
        promptVersion: String,
        createdAt: String,
    ): AnnotationResultV1 {
        val raw =
            try {
                ScrappyJson.decodeFromString<RawAnnotation>(json)
            } catch (e: Exception) {
                throw EngineException(EngineErrorCodes.OUTPUT_REJECTED, "Annotation is not valid JSON.", false)
            }
        if (raw.insufficientAudioEvidence) {
            throw EngineException(EngineErrorCodes.INPUT_INVALID, "insufficient_audio_evidence.", false)
        }
        if (raw.activity != null && raw.activity !in Vocab.activities) {
            throw EngineException(EngineErrorCodes.OUTPUT_REJECTED, "Unsupported activity ${raw.activity}.", false)
        }
        if (raw.pedagogicalForm != null && raw.pedagogicalForm !in Vocab.pedagogicalForms) {
            throw EngineException(EngineErrorCodes.OUTPUT_REJECTED, "Unsupported pedagogical_form.", false)
        }
        if (raw.conceptStage != null && raw.conceptStage !in Vocab.conceptStages) {
            throw EngineException(EngineErrorCodes.OUTPUT_REJECTED, "Unsupported concept_stage.", false)
        }
        val spans = raw.evidenceSpans.orEmpty()
        if (spans.isEmpty()) {
            throw EngineException(EngineErrorCodes.OUTPUT_REJECTED, "Annotation missing evidence spans.", false)
        }
        // Every quote must be an exact transcript substring (anti-hallucination).
        spans.forEach { span ->
            if (span.field.isBlank() || span.quote.isBlank() || !transcript.contains(span.quote)) {
                throw EngineException(EngineErrorCodes.OUTPUT_REJECTED, "Evidence span not grounded in transcript.", false)
            }
        }
        if (modelId.isBlank()) {
            throw EngineException(EngineErrorCodes.OUTPUT_REJECTED, "Annotation missing model identity.", false)
        }
        return AnnotationResultV1(
            sampleId = sampleId,
            activity = raw.activity,
            pedagogicalForm = raw.pedagogicalForm,
            conceptStage = raw.conceptStage,
            topic = raw.topic,
            learningObjective = raw.learningObjective,
            expectedLearnerResponse = raw.expectedLearnerResponse,
            evidenceSpans = spans,
            generatedBy = "AI_SUGGESTION:$modelId",
            modelVersion = modelVersion,
            createdAt = createdAt,
            promptVersion = promptVersion,
        )
    }
}
