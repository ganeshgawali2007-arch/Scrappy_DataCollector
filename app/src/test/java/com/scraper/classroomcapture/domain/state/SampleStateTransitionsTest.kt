package com.scraper.classroomcapture.domain.state

import com.scraper.classroomcapture.domain.model.SampleState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Exhaustive coverage of the D14 transition graph (todos P2.3).
class SampleStateTransitionsTest {
    @Test
    fun `happy path capture to export is legal`() {
        val path =
            listOf(
                SampleState.CREATED to SampleState.RECORDING,
                SampleState.RECORDING to SampleState.SAVING,
                SampleState.SAVING to SampleState.AUDIO_SAVED,
                SampleState.AUDIO_SAVED to SampleState.QUEUED_ASR,
                SampleState.QUEUED_ASR to SampleState.TRANSCRIBING,
                SampleState.TRANSCRIBING to SampleState.TRANSCRIBED,
                SampleState.TRANSCRIBED to SampleState.QUEUED_LLM,
                SampleState.QUEUED_LLM to SampleState.ANNOTATING,
                SampleState.ANNOTATING to SampleState.ANNOTATED,
                SampleState.ANNOTATED to SampleState.READY_FOR_EXPORT,
                SampleState.READY_FOR_EXPORT to SampleState.EXPORTED,
            )
        path.forEach { (from, to) ->
            assertTrue("$from -> $to must be legal", SampleStateTransitions.isLegal(from, to))
        }
    }

    @Test
    fun `llm is skippable from transcribed`() {
        assertTrue(
            SampleStateTransitions.isLegal(SampleState.TRANSCRIBED, SampleState.READY_FOR_EXPORT),
        )
    }

    @Test
    fun `error exits exist from every processing state`() {
        listOf(
            SampleState.RECORDING,
            SampleState.SAVING,
            SampleState.QUEUED_ASR,
            SampleState.TRANSCRIBING,
            SampleState.QUEUED_LLM,
            SampleState.ANNOTATING,
        ).forEach { from ->
            assertTrue("$from -> ERROR must be legal", SampleStateTransitions.isLegal(from, SampleState.ERROR))
        }
    }

    @Test
    fun `error retries requeue asr or llm`() {
        assertTrue(SampleStateTransitions.isLegal(SampleState.ERROR, SampleState.QUEUED_ASR))
        assertTrue(SampleStateTransitions.isLegal(SampleState.ERROR, SampleState.QUEUED_LLM))
        assertFalse(SampleStateTransitions.isLegal(SampleState.ERROR, SampleState.EXPORTED))
    }

    @Test
    fun `recovered exits are limited to audio saved queued asr or error`() {
        assertTrue(SampleStateTransitions.isLegal(SampleState.RECOVERED, SampleState.AUDIO_SAVED))
        assertTrue(SampleStateTransitions.isLegal(SampleState.RECOVERED, SampleState.QUEUED_ASR))
        assertTrue(SampleStateTransitions.isLegal(SampleState.RECOVERED, SampleState.ERROR))
        assertFalse(SampleStateTransitions.isLegal(SampleState.RECOVERED, SampleState.EXPORTED))
        assertFalse(SampleStateTransitions.isLegal(SampleState.RECOVERED, SampleState.READY_FOR_EXPORT))
        assertFalse(SampleStateTransitions.isLegal(SampleState.RECOVERED, SampleState.TRANSCRIBED))
    }

    @Test
    fun `recovered is never entered via normal transition`() {
        SampleState.entries.forEach { from ->
            assertFalse(
                "$from -> RECOVERED must not be a normal transition",
                SampleStateTransitions.isLegal(from, SampleState.RECOVERED),
            )
        }
    }

    @Test
    fun `reconciliation adopts non terminal states only`() {
        assertTrue(SampleStateTransitions.canReconcileToRecovered(SampleState.RECORDING))
        assertTrue(SampleStateTransitions.canReconcileToRecovered(SampleState.SAVING))
        assertTrue(SampleStateTransitions.canReconcileToRecovered(SampleState.TRANSCRIBING))
        assertTrue(SampleStateTransitions.canReconcileToRecovered(SampleState.QUEUED_ASR))
        assertFalse(SampleStateTransitions.canReconcileToRecovered(SampleState.EXPORTED))
        assertFalse(SampleStateTransitions.canReconcileToRecovered(SampleState.RECOVERED))
    }

    @Test
    fun `terminal and backward edges are illegal`() {
        assertFalse(SampleStateTransitions.isLegal(SampleState.EXPORTED, SampleState.QUEUED_ASR))
        assertFalse(SampleStateTransitions.isLegal(SampleState.AUDIO_SAVED, SampleState.RECORDING))
        assertFalse(SampleStateTransitions.isLegal(SampleState.CREATED, SampleState.EXPORTED))
        assertFalse(SampleStateTransitions.isLegal(SampleState.READY_FOR_EXPORT, SampleState.ANNOTATED))
    }

    @Test
    fun `audio is durable from audio saved downstream`() {
        assertFalse(SampleStateTransitions.isAudioDurable(SampleState.CREATED))
        assertFalse(SampleStateTransitions.isAudioDurable(SampleState.RECORDING))
        assertFalse(SampleStateTransitions.isAudioDurable(SampleState.SAVING))
        assertFalse(SampleStateTransitions.isAudioDurable(SampleState.ERROR))
        assertFalse(SampleStateTransitions.isAudioDurable(SampleState.RECOVERED))
        listOf(
            SampleState.AUDIO_SAVED,
            SampleState.QUEUED_ASR,
            SampleState.TRANSCRIBING,
            SampleState.TRANSCRIBED,
            SampleState.QUEUED_LLM,
            SampleState.ANNOTATING,
            SampleState.ANNOTATED,
            SampleState.READY_FOR_EXPORT,
            SampleState.EXPORTED,
        ).forEach { state ->
            assertTrue("$state must count as durable audio", SampleStateTransitions.isAudioDurable(state))
        }
    }

    @Test
    fun `legal targets match the graph`() {
        assertEquals(
            setOf(SampleState.RECORDING),
            SampleStateTransitions.legalTargets(SampleState.CREATED),
        )
        assertEquals(
            emptySet<SampleState>(),
            SampleStateTransitions.legalTargets(SampleState.EXPORTED),
        )
    }
}
