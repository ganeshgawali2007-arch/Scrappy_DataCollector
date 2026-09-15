package com.scraper.classroomcapture.recording

import kotlin.math.log10
import kotlin.math.sqrt

// Incremental capture statistics (P4.8). All math is integer/Double over the
// PCM stream — no allocations per chunk beyond the caller's buffer, and the
// whole session is never retained (P4.5). Silence and clipping thresholds
// are documented constants, not tuned per device.
class MeterStats {
    var totalFrames: Long = 0
        private set
    private var sumSquares: Double = 0.0
    var peakAmplitude: Int = 0
        private set
    var clippedSamples: Long = 0
        private set
    var totalChunks: Long = 0
        private set
    var silentChunks: Long = 0
        private set
    private val clipWindow = ArrayDeque<Boolean>()

    fun addChunk(
        frames: ShortArray,
        length: Int,
    ) {
        var chunkSum = 0.0
        var chunkClipped = false
        for (i in 0 until length) {
            val s = frames[i].toInt()
            val abs = if (s < 0) -s else s
            chunkSum += s.toDouble() * s.toDouble()
            if (abs > peakAmplitude) peakAmplitude = abs
            if (abs >= CLIP_AMPLITUDE) {
                clippedSamples++
                chunkClipped = true
            }
        }
        sumSquares += chunkSum
        totalFrames += length
        totalChunks++
        if (length > 0 && sqrt(chunkSum / length) < SILENCE_AMPLITUDE) silentChunks++
        clipWindow.addLast(chunkClipped)
        if (clipWindow.size > AudioConfig.CLIP_WINDOW_CHUNKS) clipWindow.removeFirst()
    }

    fun overallRmsDb(): Double? {
        if (totalFrames == 0L) return null
        val rms = sqrt(sumSquares / totalFrames) / FULL_SCALE
        if (rms <= 0.0) return null
        return 20 * log10(rms)
    }

    fun peakDb(): Double? {
        if (peakAmplitude == 0) return null
        return 20 * log10(peakAmplitude.toDouble() / FULL_SCALE)
    }

    fun silenceRatio(): Double? {
        if (totalChunks == 0L) return null
        return silentChunks.toDouble() / totalChunks.toDouble()
    }

    // True when recent audio clips persistently — the operator should move
    // the phone farther from the speaker (P8 surfaces this live).
    fun sustainedClipping(): Boolean {
        if (clipWindow.size < AudioConfig.CLIP_WINDOW_CHUNKS) return false
        return clipWindow.count { it }.toDouble() / clipWindow.size > AudioConfig.CLIP_WINDOW_THRESHOLD
    }

    companion object {
        const val FULL_SCALE = 32768.0

        // Int16 rarely hits exactly 32767; 32760 catches real clipping.
        const val CLIP_AMPLITUDE = 32760

        // ~-50 dBFS: room tone and pauses, not speech.
        const val SILENCE_AMPLITUDE = 100.0
    }
}
