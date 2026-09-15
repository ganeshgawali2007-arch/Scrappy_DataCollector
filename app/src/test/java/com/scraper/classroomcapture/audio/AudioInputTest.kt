package com.scraper.classroomcapture.audio

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Test

// Pure mapping tests (P5): platform constants are compile-time ints, so no
// device objects are needed.
class AudioInputTest {
    @Test
    fun `known types map correctly`() {
        assertEquals(InputType.BUILTIN_MIC, AudioInput.mapType(AudioDeviceInfo.TYPE_BUILTIN_MIC))
        assertEquals(InputType.WIRED_HEADSET, AudioInput.mapType(AudioDeviceInfo.TYPE_WIRED_HEADSET))
        assertEquals(InputType.USB_DEVICE, AudioInput.mapType(AudioDeviceInfo.TYPE_USB_DEVICE))
        assertEquals(InputType.BLUETOOTH_SCO, AudioInput.mapType(AudioDeviceInfo.TYPE_BLUETOOTH_SCO))
        assertEquals(InputType.UNKNOWN, AudioInput.mapType(99999))
    }

    @Test
    fun `builtin id is stable regardless of address`() {
        assertEquals(
            AudioInput.ID_BUILTIN,
            AudioInput.stableId(AudioDeviceInfo.TYPE_BUILTIN_MIC, ""),
        )
        assertEquals(
            AudioInput.ID_BUILTIN,
            AudioInput.stableId(AudioDeviceInfo.TYPE_BUILTIN_MIC, "whatever"),
        )
    }

    @Test
    fun `external ids derive from type plus sanitized address`() {
        val id = AudioInput.stableId(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "AA:BB:CC:11:22:33")
        assertEquals("bluetooth_sco_AABBCC112233", id)
        // No address still yields a stable id (never blank).
        assertEquals(
            "usb_device_noaddr",
            AudioInput.stableId(AudioDeviceInfo.TYPE_USB_DEVICE, ""),
        )
    }

    @Test
    fun `ids are store safe`() {
        val id = AudioInput.stableId(AudioDeviceInfo.TYPE_WIRED_HEADSET, "../../evil")
        assertEquals("wired_headset_evil", id)
    }
}
