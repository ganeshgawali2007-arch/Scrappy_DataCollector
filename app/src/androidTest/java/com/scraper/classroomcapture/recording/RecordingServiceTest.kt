package com.scraper.classroomcapture.recording

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.scraper.classroomcapture.ScraperApp
import com.scraper.classroomcapture.domain.model.ArtifactKind
import com.scraper.classroomcapture.domain.model.JobKind
import com.scraper.classroomcapture.domain.model.JobState
import com.scraper.classroomcapture.domain.model.SampleState
import com.scraper.classroomcapture.domain.model.Session
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

// End-to-end capture on device (todos P4): 3 s of microphone audio through
// the real service must yield a valid WAV, durable rows, and a queued ASR
// job. The emulator's virtual mic returns silence — capture must still work.
@RunWith(AndroidJUnit4::class)
class RecordingServiceTest {
    @get:Rule
    val permission: GrantPermissionRule =
        GrantPermissionRule.grant(android.Manifest.permission.RECORD_AUDIO)

    @Test
    fun recordThreeSeconds_producesValidWavAndRows(): Unit =
        runBlocking {
            val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ScraperApp
            val container = app.container
            val sessionId = "p4-test-" + UUID.randomUUID().toString().take(8)
            val now = System.currentTimeMillis()
            container.sessionRepository.create(
                Session(sessionId, "1", "mathematics", "hi", "t07", "s03", "", true, now, now, null),
            )

            container.recordingController.start(sessionId, "hi")
            awaitPhase(sessionId, RecordingStatus.Phase.RECORDING, 15_000)
            // Screen off mid-capture (P4.7): the wake lock + foreground service
            // must keep recording; screen back on before stopping.
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            automation.executeShellCommand("input keyevent 26").close()
            Thread.sleep(2_000)
            automation.executeShellCommand("input keyevent 26").close()
            Thread.sleep(1_000)
            container.recordingController.stop()
            awaitPhase(sessionId, RecordingStatus.Phase.IDLE, 20_000)

            val samples = container.sampleRepository.observeBySession(sessionId).first()
            assertEquals(1, samples.size)
            val sample = samples[0]
            assertEquals(SampleState.QUEUED_ASR, sample.state)
            assertEquals("hi", sample.sourceLanguage)
            assertTrue((sample.recordedEnd ?: 0) > (sample.recordedStart ?: 0))

            val artifact = container.artifactRepository.getBySampleAndKind(sample.id, ArtifactKind.AUDIO_WAV)
            requireNotNull(artifact)
            assertTrue(artifact.byteSize > 44)
            val bytes = container.artifactStore.readAllBytes(artifact.relativePath)
            assertEquals("RIFF", String(bytes.copyOfRange(0, 4)))
            assertEquals("WAVE", String(bytes.copyOfRange(8, 12)))
            assertTrue(sample.quality.rmsDb == null || sample.quality.rmsDb!! < 0)

            val jobs = container.jobRepository.observeBySample(sample.id).first()
            assertEquals(1, jobs.size)
            assertEquals(JobKind.ASR, jobs[0].kind)
            assertEquals(JobState.QUEUED, jobs[0].state)
        }

    private suspend fun awaitPhase(
        sessionId: String,
        target: RecordingStatus.Phase,
        timeoutMs: Long,
    ) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ScraperApp
        withTimeout(timeoutMs) {
            while (true) {
                val phase = app.container.recordingController.status.value.phase
                if (phase == target) return@withTimeout
                // Break early on sample error — the assertions below report it.
                val rows = app.container.sampleRepository.observeBySession(sessionId).first()
                if (rows.any { it.state == SampleState.ERROR }) return@withTimeout
                kotlinx.coroutines.delay(200)
            }
        }
    }
}
