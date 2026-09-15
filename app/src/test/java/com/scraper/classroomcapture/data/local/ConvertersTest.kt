package com.scraper.classroomcapture.data.local

import com.scraper.classroomcapture.domain.model.ArtifactKind
import com.scraper.classroomcapture.domain.model.ExportStatus
import com.scraper.classroomcapture.domain.model.ExportVerification
import com.scraper.classroomcapture.domain.model.JobKind
import com.scraper.classroomcapture.domain.model.JobState
import com.scraper.classroomcapture.domain.model.SampleState
import org.junit.Assert.assertEquals
import org.junit.Test

// Converter round-trips (todos P2.2). Enums persist as names so future values
// cannot shift existing rows; flags survive the separator encoding.
class ConvertersTest {
    private val converters = Converters()

    @Test
    fun `enums round trip as stable names`() {
        SampleState.entries.forEach {
            assertEquals(it, converters.stringToSampleState(converters.sampleStateToString(it)))
        }
        ArtifactKind.entries.forEach {
            assertEquals(it, converters.stringToArtifactKind(converters.artifactKindToString(it)))
        }
        JobKind.entries.forEach {
            assertEquals(it, converters.stringToJobKind(converters.jobKindToString(it)))
        }
        JobState.entries.forEach {
            assertEquals(it, converters.stringToJobState(converters.jobStateToString(it)))
        }
        ExportStatus.entries.forEach {
            assertEquals(it, converters.stringToExportStatus(converters.exportStatusToString(it)))
        }
        ExportVerification.entries.forEach {
            assertEquals(it, converters.stringToExportVerification(converters.exportVerificationToString(it)))
        }
    }

    @Test
    fun `null verification survives`() {
        assertEquals(null, converters.stringToExportVerification(converters.exportVerificationToString(null)))
    }

    @Test
    fun `flags round trip including empty`() {
        assertEquals(emptyList<String>(), converters.stringToFlags(converters.flagsToString(emptyList())))
        val flags = listOf("low_storage", "mic_unverified", "code_switching")
        assertEquals(flags, converters.stringToFlags(converters.flagsToString(flags)))
    }
}
