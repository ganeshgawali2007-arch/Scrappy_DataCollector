package com.scraper.classroomcapture.data.serialization

// Controlled vocabularies for pedagogical_sample.v2 (docs/DATA_SCHEMA.md).
// Values are Strings (not enums) so a newer Mac-side value never crashes
// Android parsing — unknown values fail validation, not deserialization.
object Vocab {
    const val SCHEMA_RECORD_V2 = "pedagogical_sample.v2"
    const val SCHEMA_MANIFEST_V2 = "manifest.v2"
    const val SCHEMA_ASR_V1 = "asr_result.v1"
    const val SCHEMA_ANNOTATION_V1 = "annotation_result.v1"
    const val SCHEMA_EVENT_V1 = "device_event.v1"

    const val TARGET_UNSET = "unset"

    val sourceLanguages = setOf("hi", "en", "mr")

    val translationStatuses = setOf("pending", "machine_draft", "human_verified", "rejected")

    val activities =
        setOf(
            "oral_practice",
            "explanation",
            "question_answer",
            "demonstration",
            "reading",
            "group_work",
            "assessment",
        )

    val pedagogicalForms =
        setOf(
            "instruction",
            "question",
            "explanation",
            "example",
            "feedback",
            "story",
            "song",
        )

    val conceptStages =
        setOf(
            "introduction",
            "explanation",
            "guided_practice",
            "practice",
            "assessment",
            "recap",
        )

    val ttsStatuses = setOf("pending", "ready", "excluded")

    val asrStatuses = setOf("available", "unavailable", "failed")

    val annotationStatuses = setOf("unavailable", "available", "failed")

    val reviewStatuses = setOf("unreviewed", "in_review", "verified", "rejected")

    val pedagogicalFidelity = setOf("pending", "faithful", "partially_faithful", "unfaithful")

    // Android v1 validates what Android writes. Reviewer-side values
    // (e.g. future target languages) are validated by Mac tooling, but the
    // target_language shape is enforced here: ISO code or "unset" — never a
    // display name such as "Mundari translation" in the target text.
    fun isValidCaptureRecord(record: PedagogicalRecordV2): Boolean =
        record.schemaVersion == SCHEMA_RECORD_V2 &&
            record.sourceLanguage in sourceLanguages &&
            isTargetLanguage(record.targetLanguage) &&
            record.translationStatus in translationStatuses &&
            (record.activity == null || record.activity in activities) &&
            (record.pedagogicalForm == null || record.pedagogicalForm in pedagogicalForms) &&
            (record.conceptStage == null || record.conceptStage in conceptStages) &&
            record.tts.status in ttsStatuses &&
            record.asr.status in asrStatuses &&
            record.annotation.status in annotationStatuses &&
            record.review.status in reviewStatuses &&
            record.review.pedagogicalFidelity in pedagogicalFidelity

    fun isTargetLanguage(value: String): Boolean = value == TARGET_UNSET || ISO_CODE.matches(value)

    private val ISO_CODE = Regex("^[a-z]{2,3}$")
}
