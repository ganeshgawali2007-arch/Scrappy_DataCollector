package com.scraper.classroomcapture.ui

import com.scraper.classroomcapture.domain.model.ClassroomSample
import com.scraper.classroomcapture.domain.model.QualityMetrics
import com.scraper.classroomcapture.domain.model.SampleState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// P8.1/P8.3/P8.4 pure logic: form validation, summary counts, recovery.
class SessionUiTest {
    @Test
    fun `valid form passes`() {
        val r = SessionFormValidator.validate("3", "Mathematics", "teacher_07", "school_03", "mr", true)
        assertTrue(r.isValid)
    }

    @Test
    fun `blank fields and bad language fail`() {
        val r = SessionFormValidator.validate("", "", "bad code!", "", "fr", false)
        assertFalse(r.isValid)
        assertTrue(r.errors.containsKey("grade"))
        assertTrue(r.errors.containsKey("subject"))
        assertTrue(r.errors.containsKey("teacherCode"))
        assertTrue(r.errors.containsKey("schoolCode"))
        assertTrue(r.errors.containsKey("defaultLanguage"))
        assertTrue(r.errors.containsKey("consentAck"))
    }

    @Test
    fun `summary buckets states`() {
        val samples =
            listOf(
                sample("a", SampleState.QUEUED_ASR),
                sample("b", SampleState.TRANSCRIBING),
                sample("c", SampleState.ERROR),
                sample("d", SampleState.RECOVERED),
                sample("e", SampleState.READY_FOR_EXPORT),
                sample("f", SampleState.TRANSCRIBED),
                sample("g", SampleState.AUDIO_SAVED),
            )
        val s = SessionSummary.from(samples)
        assertEquals(7, s.total)
        assertEquals(2, s.pending)
        assertEquals(1, s.failed)
        assertEquals(1, s.recovered)
        assertEquals(2, s.ready)
    }

    @Test
    fun `recovery report finds resumable session`() {
        val samples =
            listOf(
                sample("a", SampleState.RECOVERED),
                sample("b", SampleState.ERROR),
            )
        val r = RecoveryReport.from(samples, null)
        assertTrue(r.needsAttention)
        assertEquals(listOf("a"), r.recoveredSampleIds)
        assertEquals(listOf("b"), r.errorSampleIds)
        assertEquals("s1", r.resumableSessionId)
    }

    @Test
    fun `clean recovery needs no attention`() {
        val r = RecoveryReport.from(emptyList(), "s1")
        assertFalse(r.needsAttention)
    }

    @Test
    fun `elapsed formatter is monotonic and stable`() {
        assertEquals("0:00", formatElapsedMs(0))
        assertEquals("0:05", formatElapsedMs(5_000))
        assertEquals("2:00", formatElapsedMs(120_000))
        assertEquals("1:02:03", formatElapsedMs(3_723_000))
        assertEquals("0:00", formatElapsedMs(-100))
    }

    private fun sample(
        id: String,
        state: SampleState,
    ): ClassroomSample =
        ClassroomSample(
            id, "s1", 1, state, "hi", null, null, null, false,
            QualityMetrics.unknown(), null, null, false, null, null, null, 0, 0,
        )
}
