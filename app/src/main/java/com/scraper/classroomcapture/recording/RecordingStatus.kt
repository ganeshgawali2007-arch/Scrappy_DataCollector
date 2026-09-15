package com.scraper.classroomcapture.recording

// UI-facing recording state (P4.2). Published by RecordingService via its
// binder and relayed by RecordingController; screens collect this — never
// service internals. Counts back the handoff's "N saved · M awaiting"
// secondary line; errorCode is a stable machine string (P0.4).
data class RecordingStatus(
    val phase: Phase,
    val sessionId: String? = null,
    val sampleId: String? = null,
    val language: String? = null,
    val sequenceNumber: Int = 0,
    val elapsedMs: Long = 0,
    val savedCount: Int = 0,
    val pendingCount: Int = 0,
    val rmsDb: Double? = null,
    val clippingWarning: Boolean = false,
    val errorCode: String? = null,
) {
    enum class Phase {
        IDLE,
        STARTING,
        RECORDING,
        SAVING,
    }

    companion object {
        val Idle = RecordingStatus(Phase.IDLE)
    }
}
