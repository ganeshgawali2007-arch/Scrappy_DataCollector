package com.scraper.classroomcapture.audio

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.scraper.classroomcapture.ScraperApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

// On-device inventory (P5): the emulator must expose at least the built-in
// microphone, and preference round-trips through private storage.
@RunWith(AndroidJUnit4::class)
class AudioDeviceManagerTest {
    @Test
    fun builtinMicIsAlwaysListed() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ScraperApp
        val inputs = app.container.audioDevices.listInputs()
        assertTrue("no inputs at all: $inputs", inputs.isNotEmpty())
        assertTrue(
            "built-in mic missing: ${inputs.map { it.id }}",
            inputs.any { it.id == AudioInput.ID_BUILTIN && it.type == InputType.BUILTIN_MIC },
        )
    }

    @Test
    fun preferenceRoundTrips() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ScraperApp
        val manager = app.container.audioDevices
        manager.setPreferredId(AudioInput.ID_BUILTIN)
        assertEquals(AudioInput.ID_BUILTIN, manager.getPreferredId())
        manager.setPreferredId(null)
    }
}
