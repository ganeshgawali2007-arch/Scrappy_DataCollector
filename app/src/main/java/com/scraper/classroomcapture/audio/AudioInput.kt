package com.scraper.classroomcapture.audio

// Stable input-device model (P5.1). ids are derived from Android device type +
// address and are stable across process restarts for the same hardware;
// display names come from the platform and are never persisted as identity.
enum class InputType {
    BUILTIN_MIC,
    WIRED_HEADSET,
    WIRED_HEADPHONES,
    USB_DEVICE,
    USB_HEADSET,
    BLUETOOTH_SCO,
    BLUETOOTH_A2DP,
    UNKNOWN,
}

data class AudioInput(
    val id: String,
    val name: String,
    val type: InputType,
    val isAvailable: Boolean = true,
) {
    companion object {
        const val ID_BUILTIN = "builtin_mic"
        const val ID_UNKNOWN = "unknown_input"

        // Pure mapping from platform constants — unit-testable without
        // Android device objects (P5.8 tests feed raw type ints).
        fun mapType(platformType: Int): InputType =
            when (platformType) {
                android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC -> InputType.BUILTIN_MIC
                android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET -> InputType.WIRED_HEADSET
                android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> InputType.WIRED_HEADPHONES
                android.media.AudioDeviceInfo.TYPE_USB_DEVICE -> InputType.USB_DEVICE
                android.media.AudioDeviceInfo.TYPE_USB_HEADSET -> InputType.USB_HEADSET
                android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> InputType.BLUETOOTH_SCO
                android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> InputType.BLUETOOTH_A2DP
                else -> InputType.UNKNOWN
            }

        fun stableId(
            platformType: Int,
            address: String,
        ): String {
            val type = mapType(platformType)
            if (type == InputType.BUILTIN_MIC) return ID_BUILTIN
            val clean = address.filter { it.isLetterOrDigit() }.take(24)
            return "${type.name.lowercase()}_${clean.ifEmpty { "noaddr" }}"
        }
    }
}

data class RouteChange(
    val fromId: String?,
    val toId: String?,
    val reason: String,
    val at: Long,
)
