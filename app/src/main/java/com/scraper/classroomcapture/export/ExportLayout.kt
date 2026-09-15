package com.scraper.classroomcapture.export

// Export directory/ZIP layout + schema versions (P9.1, D12, D18).
// The ZIP is the dataset: manifest + JSONL + per-sample JSON + audio +
// sidecars + events + model metadata + checksums. Raw audio is never
// altered; re-export creates a new exportId (never overwrite).
object ExportLayout {
    const val MANIFEST_NAME = "manifest.v2.json"
    const val RECORDS_NAME = "records.jsonl"
    const val EVENTS_NAME = "events.jsonl"
    const val MODELS_NAME = "models.json"
    const val TRANSLATION_PAIRS_NAME = "translation_pairs.v1.jsonl"
    const val TTS_PAIRS_NAME = "tts_pairs.v1.jsonl"
    const val PEDAGOGY_EVAL_NAME = "pedagogy_eval.v1.jsonl"

    const val AUDIO_DIR = "audio"
    const val ASR_DIR = "asr"
    const val ANNOTATION_DIR = "annotation"
    const val RECORDS_DIR = "records"

    fun audioEntry(sampleId: String): String = "$AUDIO_DIR/$sampleId.wav"

    fun asrEntry(sampleId: String): String = "$ASR_DIR/$sampleId.v1.json"

    fun annotationEntry(sampleId: String): String = "$ANNOTATION_DIR/$sampleId.v1.json"

    fun recordEntry(sampleId: String): String = "$RECORDS_DIR/$sampleId.v2.json"
}
