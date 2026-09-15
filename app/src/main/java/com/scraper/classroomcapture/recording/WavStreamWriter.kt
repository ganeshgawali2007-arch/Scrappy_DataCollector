package com.scraper.classroomcapture.recording

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

// Streaming WAV writer (P4.4): 44-byte header placeholder, PCM16 frames
// appended, header patched in place on finalize. The caller owns a fixed
// chunk buffer — a whole session is never retained in RAM (P4.5).
// Pure JVM over a plain File: fully unit-testable (P4.8).
class WavStreamWriter(file: File) {
    private val raf = RandomAccessFile(file, "rw")

    var framesWritten: Long = 0
        private set
    private var finalized = false

    init {
        raf.write(ByteArray(HEADER_BYTES))
    }

    fun writePcm(
        frames: ShortArray,
        length: Int,
    ) {
        check(!finalized) { "Writer already finalized." }
        val bytes = ByteBuffer.allocate(length * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until length) bytes.putShort(frames[i])
        raf.write(bytes.array())
        framesWritten += length
    }

    fun durationMs(): Long = framesWritten * 1000 / AudioConfig.SAMPLE_RATE_HZ

    // Patches the RIFF sizes in place, fsyncs, and closes. The file is a
    // valid WAV even if the process dies right after — at worst the sizes
    // describe a prefix of the audio (P3 adopts it).
    fun finalizeAndClose() {
        if (finalized) return
        finalized = true
        raf.seek(0)
        raf.write(buildHeader(framesWritten))
        raf.fd.sync()
        raf.close()
    }

    companion object {
        const val HEADER_BYTES = 44

        // Builds the exact 44-byte header for a frame count.
        fun buildHeader(frames: Long): ByteArray {
            val dataBytes = frames * AudioConfig.BYTES_PER_FRAME
            // Classic WAV caps sizes at 32 bits (~ Auto 4 GB / ~35 h here);
            // a class period is megabytes. Refuse rather than wrap.
            require(dataBytes <= Int.MAX_VALUE - 36) { "Sample exceeds WAV size limits." }
            val header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray())
            header.putInt((36 + dataBytes).toInt())
            header.put("WAVE".toByteArray())
            header.put("fmt ".toByteArray())
            header.putInt(16)
            header.putShort(1) // PCM
            header.putShort(1) // mono
            header.putInt(AudioConfig.SAMPLE_RATE_HZ)
            header.putInt(AudioConfig.SAMPLE_RATE_HZ * AudioConfig.BYTES_PER_FRAME)
            header.putShort(AudioConfig.BYTES_PER_FRAME.toShort())
            header.putShort(16) // bits per sample
            header.put("data".toByteArray())
            header.putInt(dataBytes.toInt())
            return header.array()
        }
    }
}
