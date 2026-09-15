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

    @Test
    fun migrate1To2_keepsRowsAndEnforcesUniqueJobs() {
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
                    "VALUES ('samp-1', 'sess-1', 1, 'QUEUED_ASR', 'hi', NULL, NULL, NULL, 0, " +
                    "NULL, NULL, 0, NULL, '', NULL, NULL, 0, NULL, NULL, NULL, 1000, 1000)",
            )
            db.execSQL(
                "INSERT INTO processing_jobs (id, sampleId, kind, state, attemptCount, " +
                    "maxAttempts, leaseOwner, leaseExpiresAt, lastHeartbeatAt, lastErrorCode, " +
                    "lastErrorMessage, nextAttemptAt, modelId, modelVersion, createdAt, updatedAt) " +
                    "VALUES ('job-1', 'samp-1', 'ASR', 'QUEUED', 0, 5, NULL, NULL, NULL, NULL, " +
                    "NULL, NULL, NULL, NULL, 1000, 1000)",
            )
        }
        helper.runMigrationsAndValidate(TEST_DB, 2, true, ScraperDatabase.MIGRATION_1_2).use { db ->
            db.query("SELECT state FROM samples WHERE id = 'samp-1'", emptyArray()).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("QUEUED_ASR", cursor.getString(0))
            }
            // The v2 unique index rejects a second job for the same sample+kind.
            var rejected = false
            try {
                db.execSQL(
                    "INSERT INTO processing_jobs (id, sampleId, kind, state, attemptCount, " +
                        "maxAttempts, leaseOwner, leaseExpiresAt, lastHeartbeatAt, lastErrorCode, " +
                        "lastErrorMessage, nextAttemptAt, modelId, modelVersion, createdAt, updatedAt) " +
                        "VALUES ('job-2', 'samp-1', 'ASR', 'QUEUED', 0, 5, NULL, NULL, NULL, NULL, " +
                        "NULL, NULL, NULL, NULL, 1000, 1000)",
                )
            } catch (e: android.database.SQLException) {
                rejected = true
            }
            assertTrue("duplicate (sample, kind) job must be rejected", rejected)
        }
    }

    companion object {
        private const val TEST_DB = "migration-test.db"
    }
}
