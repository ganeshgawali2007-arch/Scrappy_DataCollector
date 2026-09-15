package com.scraper.classroomcapture.data.serialization

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Round-trip coverage for pedagogical_sample.v2 and sidecars (todos P2.7).
// The canonical fixture is the single source of truth in schemas/.
class SerializationTest {
    private fun fixtureText(): String =
        javaClass.classLoader
            ?.getResourceAsStream("records.example.v2.json")
            ?.bufferedReader()
            ?.readText()
            ?: error("schemas/records.example.v2.json missing from test classpath")

    @Test
    fun `canonical fixture parses and validates`() {
        val record = ScrappyJson.decodeFromString<PedagogicalRecordV2>(fixtureText())
        assertEquals("pedagogical_sample.v2", record.schemaVersion)
        assertEquals(25, record.sequenceNumber)
        assertEquals("hi", record.sourceLanguage)
        assertEquals("बच्चों, पाँच तक गिनती बोलो।", record.source)
        assertEquals("unset", record.targetLanguage)
        assertEquals(GradeValue.Numeric(1), record.grade)
        assertEquals("oral_practice", record.activity)
        assertEquals("instruction", record.pedagogicalForm)
        assertEquals("practice", record.conceptStage)
        assertEquals(38000L, record.audio.durationMs)
        assertEquals("READY_FOR_EXPORT", record.provenance.processingState)
        assertTrue(Vocab.isValidCaptureRecord(record))
    }

    @Test
    fun `round trip preserves every field`() {
        val first = ScrappyJson.decodeFromString<PedagogicalRecordV2>(fixtureText())
        val encoded = ScrappyJson.encodeToString(first)
        val second = ScrappyJson.decodeFromString<PedagogicalRecordV2>(encoded)
        assertEquals(first, second)
    }

    @Test
    fun `grade accepts preschool labels`() {
        val withLabel =
            ScrappyJson.decodeFromString<PedagogicalRecordV2>(
                fixtureText().replace("\"grade\":1", "\"grade\":\"balvatika\""),
            )
        assertEquals(GradeValue.Label("balvatika"), withLabel.grade)
        val reEncoded = Json.parseToJsonElement(ScrappyJson.encodeToString(withLabel)).jsonObject
        assertEquals("balvatika", reEncoded.getValue("grade").jsonPrimitive.content)
    }

    @Test
    fun `vocab validation rejects fabricated values`() {
        val record = ScrappyJson.decodeFromString<PedagogicalRecordV2>(fixtureText())
        assertFalse(Vocab.isValidCaptureRecord(record.copy(sourceLanguage = "xx")))
        assertFalse(Vocab.isValidCaptureRecord(record.copy(pedagogicalForm = "lecture")))
        assertFalse(Vocab.isValidCaptureRecord(record.copy(translationStatus = "done")))
        assertFalse(Vocab.isValidCaptureRecord(record.copy(targetLanguage = "Mundari translation")))
    }

    @Test
    fun `unknown future fields do not break parsing`() {
        val extended = fixtureText().replaceFirst("{", "{\"future_field\":\"kept-by-mac\",")
        val record = ScrappyJson.decodeFromString<PedagogicalRecordV2>(extended)
        assertEquals(25, record.sequenceNumber)
    }

    @Test
    fun `sidecars round trip`() {
        val asr =
            AsrResultV1(
                sampleId = "s1",
                text = "बच्चों",
                language = "hi",
                segments = listOf(AsrSegment(0, 1200, "बच्चों", 0.9)),
                modelId = "whisper-model-id",
                modelVersion = "1.0",
                createdAt = "2026-09-15T00:00:00Z",
            )
        assertEquals(asr, ScrappyJson.decodeFromString<AsrResultV1>(ScrappyJson.encodeToString(asr)))

        val annotation =
            AnnotationResultV1(
                sampleId = "s1",
                activity = "oral_practice",
                evidenceSpans = listOf(EvidenceSpan("activity", "गिनती बोलो", 1000, 3000)),
                generatedBy = "local-schema-model-id",
                createdAt = "2026-09-15T00:00:00Z",
            )
        assertEquals(
            annotation,
            ScrappyJson.decodeFromString<AnnotationResultV1>(ScrappyJson.encodeToString(annotation)),
        )

        val event =
            DeviceEventV1(
                eventId = "e1",
                type = "RECORDING_STARTED",
                appVersion = "0.1.0",
                createdAt = "2026-09-15T00:00:00Z",
            )
        assertEquals(
            event,
            ScrappyJson.decodeFromString<DeviceEventV1>(ScrappyJson.encodeToString(event)),
        )

        val manifest =
            ExportManifestV2(
                exportId = "x1",
                createdAt = "2026-09-15T00:00:00Z",
                appVersion = "0.1.0",
                recordCount = 1,
                recordsSha256 = "abc",
                verification = "VERIFIED",
            )
        assertEquals(
            manifest,
            ScrappyJson.decodeFromString<ExportManifestV2>(ScrappyJson.encodeToString(manifest)),
        )
    }
}
