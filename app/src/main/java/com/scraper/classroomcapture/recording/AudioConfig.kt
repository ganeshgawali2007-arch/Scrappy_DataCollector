package com.scraper.classroomcapture.recording

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

// Capture configuration (P4.3). 16 kHz mono PCM16 is the dataset contract
// (docs/DATA_SCHEMA.md audio block). MIC source keeps the archive faithful —
// no platform voice-processing that would differ between devices (D20).
object AudioConfig {
    const val SAMPLE_RATE_HZ = 16000
    const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    const val SOURCE = MediaRecorder.AudioSource.MIC
    const val BYTES_PER_FRAME = 2

    // 100 ms per read: fine-grained metering without syscall churn.
    const val CHUNK_FRAMES = 1600

    // Sustained-clipping window: >30% clipped chunks in the last 5 s warns.
    const val CLIP_WINDOW_CHUNKS = 50
    const val CLIP_WINDOW_THRESHOLD = 0.30

    fun minBufferBytes(): Int = AudioRecord.getMinBufferSize(SAMPLE_RATE_HZ, CHANNEL_CONFIG, AUDIO_FORMAT)

    fun recordBufferBytes(): Int {
        val min = minBufferBytes()
        if (min <= 0) return -1
        return maxOf(min * 4, CHUNK_FRAMES * BYTES_PER_FRAME * 2)
    }
}

// Stable failure codes for capture start/read (P0.4 taxonomy + P4.9).
object CaptureErrorCodes {
    const val MIC_BUSY = "MIC_BUSY"
    const val MIC_PERMISSION_REVOKED = "MIC_PERMISSION_REVOKED"
    const val MIC_UNSUPPORTED = "MIC_UNSUPPORTED"
    const val MIC_READ_FAILED = "MIC_READ_FAILED"
    const val MIC_NO_FRAMES = "MIC_NO_FRAMES"
}
