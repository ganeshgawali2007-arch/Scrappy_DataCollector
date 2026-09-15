package com.scraper.classroomcapture.domain.state

import com.scraper.classroomcapture.domain.model.SampleState

// Single legal-transition validator for the persisted sample state machine
// (DECISIONS.md D14, todos P2.3). All state changes — service, workers,
// reconciliation, export — must go through here. RECOVERED is entered only
// via reconcileToRecovered (startup reconciliation, P3); it never appears in
// the normal transition graph and never leads to EXPORTED.
object SampleStateTransitions {
    private val normal: Map<SampleState, Set<SampleState>> =
        mapOf(
            SampleState.CREATED to setOf(SampleState.RECORDING),
            SampleState.RECORDING to setOf(SampleState.SAVING, SampleState.ERROR),
            SampleState.SAVING to setOf(SampleState.AUDIO_SAVED, SampleState.ERROR),
            SampleState.AUDIO_SAVED to setOf(SampleState.QUEUED_ASR),
            SampleState.QUEUED_ASR to setOf(SampleState.TRANSCRIBING, SampleState.ERROR),
            SampleState.TRANSCRIBING to setOf(SampleState.TRANSCRIBED, SampleState.ERROR),
            SampleState.TRANSCRIBED to setOf(SampleState.QUEUED_LLM, SampleState.READY_FOR_EXPORT),
            SampleState.QUEUED_LLM to setOf(SampleState.ANNOTATING, SampleState.ERROR),
            SampleState.ANNOTATING to setOf(SampleState.ANNOTATED, SampleState.ERROR),
            SampleState.ANNOTATED to setOf(SampleState.READY_FOR_EXPORT),
            SampleState.READY_FOR_EXPORT to setOf(SampleState.EXPORTED),
            SampleState.EXPORTED to emptySet(),
            SampleState.ERROR to setOf(SampleState.QUEUED_ASR, SampleState.QUEUED_LLM),
            SampleState.RECOVERED to
                setOf(SampleState.AUDIO_SAVED, SampleState.QUEUED_ASR, SampleState.ERROR),
        )

    // Normal operational transition. RECOVERED entry/exit rules:
    // entry only via reconcileToRecovered; exits limited to the map above.
    fun isLegal(
        from: SampleState,
        to: SampleState,
    ): Boolean {
        if (to == SampleState.RECOVERED) return false
        return normal.getValue(from).contains(to)
    }

    fun legalTargets(from: SampleState): Set<SampleState> = normal.getValue(from)

    // Startup reconciliation may adopt any non-terminal, non-recovered state.
    // Terminal durable states (EXPORTED) need no recovery.
    fun canReconcileToRecovered(from: SampleState): Boolean = from != SampleState.RECOVERED && from != SampleState.EXPORTED

    // AUDIO_SAVED is always exportable: audio alone suffices, derived data
    // is optional (plan invariant 4). True for AUDIO_SAVED and everything
    // downstream of durable audio except ERROR/RECOVERED (unresolved).
    fun isAudioDurable(state: SampleState): Boolean =
        when (state) {
            SampleState.AUDIO_SAVED,
            SampleState.QUEUED_ASR,
            SampleState.TRANSCRIBING,
            SampleState.TRANSCRIBED,
            SampleState.QUEUED_LLM,
            SampleState.ANNOTATING,
            SampleState.ANNOTATED,
            SampleState.READY_FOR_EXPORT,
            SampleState.EXPORTED,
            -> true
            SampleState.CREATED,
            SampleState.RECORDING,
            SampleState.SAVING,
            SampleState.ERROR,
            SampleState.RECOVERED,
            -> false
        }
}
