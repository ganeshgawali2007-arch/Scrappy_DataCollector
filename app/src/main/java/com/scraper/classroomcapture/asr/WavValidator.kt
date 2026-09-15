package com.scraper.classroomcapture.asr

import com.scraper.classroomcapture.processing.EngineErrorCodes
import com.scraper.classroomcapture.processing.EngineException
import com.scraper.classroomcapture.recording.AudioConfig
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

// WAV validation before native inference (P7.5). Rejects anything that is
// not 16 kHz mono PCM16 within duration bounds — the whisper.cpp bridge
// never sees a malformed file. Pure JVM, fully unit-tested.
object WavValidator {
    const val MIN_DURATION_MS = 200L
    const val MAX_DURATION_MS = 10 * 60_000L

    data class ValidWav(
        val durationMs: Long,
        val frames: Long,
        val byteSize: Long,
    )

    fun validate(file: File): ValidWav {
        if (!file.isFile) {
            throw EngineException(EngineErrorCodes.INPUT_INVALID, "Audio file missing.", false)
        }
        if (file.length() < 44 + 2) {
            throw EngineException(EngineErrorCodes.INPUT_INVALID, "Audio file too small.", false)
        }
        if (file.length() > Int.MAX_VALUE) {
            throw EngineException(EngineErrorCodes.INPUT_INVALID, "Audio file too large.", false)
        }
        try {
            RandomAccessFile(file, "r").use { raf ->
                val header = ByteArray(44)
                raf.readFully(header)
                val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                val riff = ByteArray(4).also { buf.get(it) }
                buf.int // chunk size
                val wave = ByteArray(4).also { buf.get(it) }
                val fmt = ByteArray(4).also { buf.get(it) }
                val fmtSize = buf.int
                val audioFormat = buf.short
                val channels = buf.short
                val sampleRate = buf.int
                buf.int // byte rate
                buf.short // block align
                val bitsPerSample = buf.short
                if (String(riff) != "RIFF" || String(wave) != "WAVE" || String(fmt) != "fmt ") {
                    throw EngineException(EngineErrorCodes.INPUT_INVALID, "Not a WAV file.", false)
                }
                if (fmtSize != 16) {
                    throw EngineException(EngineErrorCodes.INPUT_INVALID, "Unsupported WAV fmt chunk.", false)
                }
                if (audioFormat.toInt() != 1) {
                    throw EngineException(EngineErrorCodes.INPUT_INVALID, "WAV must be PCM.", false)
                }
                if (channels.toInt() != 1) {
                    throw EngineException(EngineErrorCodes.INPUT_INVALID, "WAV must be mono.", false)
                }
                if (sampleRate != AudioConfig.SAMPLE_RATE_HZ) {
                    throw EngineException(
                        EngineErrorCodes.INPUT_INVALID,
                        "WAV must be ${AudioConfig.SAMPLE_RATE_HZ} Hz.",
                        false,
                    )
                }
                if (bitsPerSample.toInt() != 16) {
                    throw EngineException(EngineErrorCodes.INPUT_INVALID, "WAV must be 16-bit.", false)
                }
                // Find the data chunk (our writer puts it at byte 36).
                raf.seek(36)
                val dataTag = ByteArray(4)
                raf.readFully(dataTag)
                if (String(dataTag) != "data") {
                    throw EngineException(EngineErrorCodes.INPUT_INVALID, "WAV data chunk missing.", false)
                }
                val dataBytesBytes = ByteArray(4)
                raf.readFully(dataBytesBytes)
                val dataBytes = ByteBuffer.wrap(dataBytesBytes).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
                val frames = dataBytes / AudioConfig.BYTES_PER_FRAME
                if (frames <= 0) {
                    throw EngineException(EngineErrorCodes.INPUT_INVALID, "Audio is empty.", false)
                }
                val durationMs = frames * 1000 / AudioConfig.SAMPLE_RATE_HZ
                if (durationMs < MIN_DURATION_MS) {
                    throw EngineException(EngineErrorCodes.INPUT_INVALID, "Audio too short.", false)
                }
                if (durationMs > MAX_DURATION_MS) {
                    throw EngineException(EngineErrorCodes.INPUT_INVALID, "Audio exceeds 10-minute limit.", false)
                }
                // Header size must agree with the file length (truncation check).
                val expected = 44 + dataBytes
                if (file.length() < expected) {
                    throw EngineException(EngineErrorCodes.INPUT_INVALID, "WAV truncated.", false)
                }
                return ValidWav(durationMs = durationMs, frames = frames, byteSize = file.length())
            }
        } catch (e: EngineException) {
            throw e
        } catch (e: Exception) {
            throw EngineException(EngineErrorCodes.INPUT_INVALID, "Unreadable WAV.", false)
        }
    }
}
