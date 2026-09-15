package com.scraper.classroomcapture.llm

import com.scraper.classroomcapture.data.serialization.PedagogicalRecordV2

// Strict versioned annotation prompt (P10.4, P10.7). The template version +
// exact prompt + model identity + settings are persisted with the output
// (AnnotationResultV1 carries promptVersion/modelVersion) so Mac review can
// reproduce the suggestion. Outputs are labeled AI_SUGGESTION — never facts.
object PromptTemplate {
    const val VERSION = "annotation-prompt.v1"
    const val MAX_TOKENS = 512
    const val TEMPERATURE = 0.0

    fun build(record: PedagogicalRecordV2): String {
        val transcript = record.source.trim().take(MAX_TRANSCRIPT_CHARS)
        return buildString {
            appendLine("You are a classroom assistant. Suggest metadata ONLY from the transcript.")
            appendLine("Rules:")
            appendLine(
                "- Output STRICT JSON with keys: activity, pedagogical_form, " +
                    "concept_stage, topic, learning_objective, " +
                    "expected_learner_response, evidence_spans.",
            )
            appendLine(
                "- evidence_spans: array of {field, quote} where quote is an " +
                    "EXACT substring of the transcript.",
            )
            appendLine(
                "- If the transcript is empty or too short, output " +
                    "{\"insufficient_audio_evidence\": true} and nothing else.",
            )
            appendLine(
                "- activity: oral_practice, explanation, question_answer, " +
                    "demonstration, reading, group_work, assessment.",
            )
            appendLine(
                "- pedagogical_form: instruction, question, explanation, " +
                    "example, feedback, story, song.",
            )
            appendLine(
                "- concept_stage: introduction, explanation, guided_practice, " +
                    "practice, assessment, recap.",
            )
            appendLine("- Never invent names, grades, or content. Unknown is null.")
            appendLine("Transcript [${record.sourceLanguage}]:")
            appendLine(transcript.ifEmpty { "(empty)" })
            appendLine("Grade: ${record.grade} Subject: ${record.subject}")
            appendLine("Template: $VERSION")
        }
    }

    const val MAX_TRANSCRIPT_CHARS = 2000
}
