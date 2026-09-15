package com.scraper.classroomcapture.audio

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// Microphone inventory, preference, and route monitoring (P5.1–P5.5).
// Preference is a private SharedPreferences string (device id only — no
// audio, no PII). Route changes are emitted for logging (P5.4) and for the
// service to notice Bluetooth loss (P5.5). Verification of the *active*
// input happens in RecordingService via AudioRecord.getRoutedDevice (P5.3).
class AudioDeviceManager(context: Context) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _routeEvents = MutableStateFlow<RouteChange?>(null)
    val routeEvents: StateFlow<RouteChange?> = _routeEvents.asStateFlow()

    private var lastSeenIds: Set<String>? = null

    private val callback =
        object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) {
                emitDiff("devices_added")
            }

            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
                val btLost = removed.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
                emitDiff(if (btLost) "bluetooth_disconnected" else "devices_removed")
            }
        }

    fun register() {
        lastSeenIds = inputIds()
        audioManager?.registerAudioDeviceCallback(callback, null)
    }

    fun unregister() {
        try {
            audioManager?.unregisterAudioDeviceCallback(callback)
        } catch (e: Exception) {
            // Best effort; callback may already be gone.
        }
    }

    // All currently available input devices, built-in first (P5.1).
    fun listInputs(): List<AudioInput> {
        val devices =
            audioManager
                ?.getDevices(AudioManager.GET_DEVICES_INPUTS)
                ?.filter { it.isSource }
                .orEmpty()
        return devices.map { info ->
            AudioInput(
                id = AudioInput.stableId(info.type, info.address ?: ""),
                name = info.productName?.toString()?.ifBlank { null } ?: fallbackName(info.type),
                type = AudioInput.mapType(info.type),
            )
        }.sortedBy { if (it.id == AudioInput.ID_BUILTIN) 0 else 1 }
    }

    // Raw platform objects for setPreferredDevice (P5.2); null when the
    // preferred device is unplugged (caller falls back to platform routing).
    fun findDevice(id: String): AudioDeviceInfo? {
        if (id == AudioInput.ID_BUILTIN) {
            return audioManager?.getDevices(AudioManager.GET_DEVICES_INPUTS)
                ?.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
        }
        return audioManager?.getDevices(AudioManager.GET_DEVICES_INPUTS)
            ?.firstOrNull { AudioInput.stableId(it.type, it.address ?: "") == id }
    }

    fun getPreferredId(): String? = prefs.getString(KEY_PREFERRED, null)

    fun setPreferredId(id: String?) {
        prefs.edit { putString(KEY_PREFERRED, id) }
    }

    private fun inputIds(): Set<String> = listInputs().map { it.id }.toSet()

    private fun emitDiff(reason: String) {
        val current = inputIds()
        val previous = lastSeenIds
        lastSeenIds = current
        if (previous == null || previous == current) return
        val added = (current - previous).firstOrNull()
        val removed = (previous - current).firstOrNull()
        _routeEvents.value = RouteChange(removed, added, reason, System.currentTimeMillis())
    }

    private fun fallbackName(platformType: Int): String =
        when (AudioInput.mapType(platformType)) {
            InputType.BUILTIN_MIC -> "Phone microphone"
            InputType.WIRED_HEADSET -> "Wired headset"
            InputType.WIRED_HEADPHONES -> "Wired headphones"
            InputType.USB_DEVICE -> "USB microphone"
            InputType.USB_HEADSET -> "USB headset"
            InputType.BLUETOOTH_SCO -> "Bluetooth microphone"
            InputType.BLUETOOTH_A2DP -> "Bluetooth audio"
            InputType.UNKNOWN -> "Unknown input"
        }

    companion object {
        private const val PREFS = "audio_routes"
        private const val KEY_PREFERRED = "preferred_input_id"
    }
}
