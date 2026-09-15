package com.scraper.classroomcapture.data.store

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

// Crash-boundary tests for the file store (todos P3.8): every write goes
// through tmp + fsync + atomic rename, so a crash at any point leaves either
// the old file or a quarantinable tmp — never a half-written artifact.
class ArtifactStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun store(): FileArtifactStore = FileArtifactStore(File(tmp.root, "scrappy"))

    @Test
    fun `write publishes content with correct hash and size`() =
        runBlocking {
            val store = store()
            val payload = "wav-bytes".toByteArray()
            val written = store.writeAtomic("sessions/s1/samples/a1/audio.wav") { it.write(payload) }
            assertTrue(store.exists("sessions/s1/samples/a1/audio.wav"))
            assertEquals(payload.size.toLong(), written.byteSize)
            assertEquals(sha256(payload), written.sha256)
            assertEquals(sha256(payload), store.hashFile("sessions/s1/samples/a1/audio.wav").sha256)
        }

    @Test
    fun `failed write leaves no canonical file but cleans tmp`() =
        runBlocking {
            val store = store()
            try {
                store.writeAtomic("sessions/s1/samples/a1/audio.wav") { throw RuntimeException("crash mid-write") }
                fail("expected StoreException")
            } catch (e: StoreException) {
                assertEquals(StoreErrorCodes.WRITE_FAILED, e.code)
            }
            assertFalse(store.exists("sessions/s1/samples/a1/audio.wav"))
            assertTrue(store.listTmpFiles().isEmpty())
        }

    @Test
    fun `checksum is repeatable`() =
        runBlocking {
            val store = store()
            store.writeAtomic("sessions/s1/samples/a1/audio.wav") { it.write(ByteArray(100000) { 7 }) }
            val first = store.hashFile("sessions/s1/samples/a1/audio.wav")
            val second = store.hashFile("sessions/s1/samples/a1/audio.wav")
            assertEquals(first, second)
        }

    @Test
    fun `traversal paths are rejected`() =
        runBlocking {
            val store = store()
            listOf("../evil", "/abs", "", "a/../../b", "a\\b", "sessions/../x", "sessions//audio.wav").forEach { bad ->
                try {
                    store.writeAtomic(bad) { it.write(1) }
                    fail("expected rejection of $bad")
                } catch (e: StoreException) {
                    assertEquals(bad, StoreErrorCodes.PATH_TRAVERSAL, e.code)
                }
                assertFalse(bad, store.exists(bad))
            }
        }

    @Test
    fun `valid nested paths resolve under root`() =
        runBlocking {
            val store = store()
            store.writeAtomic("sessions/s-1/samples/a-1/asr.v1.json") { it.write(1) }
            assertTrue(store.exists("sessions/s-1/samples/a-1/asr.v1.json"))
        }

    @Test
    fun `quarantine moves file aside and never deletes`() =
        runBlocking {
            val store = store()
            store.writeAtomic("sessions/s1/samples/a1/audio.wav") { it.write("data".toByteArray()) }
            val to = store.quarantineFile("sessions/s1/samples/a1/audio.wav", "test-reason", 123L)
            assertTrue(to, to.startsWith("quarantine/"))
            assertFalse(store.exists("sessions/s1/samples/a1/audio.wav"))
            assertTrue(store.exists(to))
        }

    @Test
    fun `scan finds sample dirs and tmp leftovers`() =
        runBlocking {
            val store = store()
            store.writeAtomic("sessions/s1/samples/a1/audio.wav") { it.write("x".toByteArray()) }
            // Simulate a crashed write: raw tmp left in the sample dir.
            val crashTmp = File(tmp.root, "scrappy/sessions/s1/samples/a1/crash.tmp")
            crashTmp.writeBytes("partial".toByteArray())

            val scanned = store.scanSampleDirs()
            assertEquals(1, scanned.size)
            assertEquals("s1", scanned[0].sessionId)
            assertEquals("a1", scanned[0].sampleId)

            val tmps = store.listTmpFiles()
            assertTrue(tmps.any { it.endsWith("crash.tmp") })
        }

    @Test
    fun `unrecognized layout is reported`() {
        val store = store()
        File(tmp.root, "scrappy/stray.bin").writeBytes(byteArrayOf(1))
        File(tmp.root, "scrappy/sessions/s1/random.txt").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(2))
        }
        val unrecognized = store.scanUnrecognized()
        assertTrue(unrecognized.any { it.endsWith("stray.bin") })
        assertTrue(unrecognized.any { it.endsWith("random.txt") })
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
