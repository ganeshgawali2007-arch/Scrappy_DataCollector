package com.scraper.classroomcapture.domain.model

// Persisted sample lifecycle (DECISIONS.md D14). Stored as a String in Room;
// transitions are validated by SampleStateTransitions — never bypass it.
enum class SampleState {
    CREATED,
    RECORDING,
    SAVING,
    AUDIO_SAVED,
    QUEUED_ASR,
    TRANSCRIBING,
    TRANSCRIBED,
    QUEUED_LLM,
    ANNOTATING,
    ANNOTATED,
    READY_FOR_EXPORT,
    EXPORTED,
    ERROR,
    RECOVERED,
}
