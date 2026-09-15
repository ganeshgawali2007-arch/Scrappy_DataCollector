package com.scraper.classroomcapture.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

// Migration + durability tests (todos P2.5). createDatabase validates the
// live schema against the committed app/schemas/ export, so schema drift
// fails here. The reopen test proves rows survive close/reopen — the
// process-death shape P2 must guarantee.
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            ScraperDatabase::class.java,
        )

    @Test
    fun v1_createsAllTables() {
        helper.createDatabase(TEST_DB, 1).use { db ->
            val tables =
                db.query("SELECT name FROM sqlite_master WHERE type = 'table'", emptyArray()).use { cursor ->
                    buildSet {
                        while (cursor.moveToNext()) add(cursor.getString(0))
                    }
                }
            assertTrue(
                "missing tables: $tables",
                tables.containsAll(
                    setOf(
                        "sessions",
                        "samples",
                        "artifacts",
                        "processing_jobs",
                        "device_events",
                        "export_records",
                        "export_sessions",
                    ),
                ),
            )
        }
    }

    @Test
    fun v1_rowsSurviveCloseAndReopen() {
        helper.createDatabase(TEST_DB, 1).use { db ->
            db.execSQL(
                "INSERT INTO sessions (id, grade, subject, defaultLanguage, teacherCode, " +
                    "schoolCode, notes, consentAck, createdAt, updatedAt, closedAt) " +
                    "VALUES ('sess-1', '1', 'mathematics', 'hi', 't07', 's03', '', 1, 1000, 1000, NULL)",
            )
            db.execSQL(
                "INSERT INTO samples (id, sessionId, sequenceNumber, state, sourceLanguage, " +
                    "recordedStart, recordedEnd, inputDevice, codeSwitching, q_rmsDb, q_peakDb, " +
                    "q_clippingDetected, q_silenceRatio, q_flags, errorCode, errorMessage, " +
                    "errorRetryable, recoveryReason, recoveredAt, priorState, createdAt, updatedAt) " +
                    "VALUES ('samp-1', 'sess-1', 1, 'AUDIO_SAVED', 'hi', NULL, NULL, NULL, 0, " +
                    "NULL, NULL, 0, NULL, '', NULL, NULL, 0, NULL, NULL, NULL, 1000, 1000)",
            )
        }
        helper.runMigrationsAndValidate(TEST_DB, 1, true).use { db ->
            db.query("SELECT id, state FROM samples WHERE sessionId = 'sess-1'", emptyArray()).use { cursor ->
                assertTrue("sample row lost across reopen", cursor.moveToFirst())
                assertEquals("samp-1", cursor.getString(0))
                assertEquals("AUDIO_SAVED", cursor.getString(1))
            }
        }
    }

    companion object {
        private const val TEST_DB = "migration-test.db"
    }
}
