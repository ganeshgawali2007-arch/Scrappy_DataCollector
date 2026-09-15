package com.scraper.classroomcapture.asr

import com.scraper.classroomcapture.data.serialization.AsrResultV1
import com.scraper.classroomcapture.processing.AsrEngine
import com.scraper.classroomcapture.processing.EngineErrorCodes
import com.scraper.classroomcapture.processing.EngineException
import java.io.File
import java.time.Instant

// Diagnostics per transcription (P7.7): wall time, inference time, audio
// duration, and real-time factor. Recorded by the worker boundary; the P11
// benchmark screen aggregates these (no classroom content in logs).
data class AsrDiagnostics(
    val modelId: String,
    val modelVersion: String,
    val audioDurationMs: Long,
    val inferenceMs: Long,
    val wallMs: Long,
    val realTimeFactor: Double,
)

// whisper.cpp-backed ASREngine (P7.3). Policy:
// - language must be hi|en|mr (plan §4); anything else is INPUT_INVALID.
// - WAV validated before any native call (P7.5).
// - model resolved via registry; missing/corrupt maps to MODEL_* (permanent).
// - native Throwables map to INFERENCE_FAILED (retryable) at this boundary.
// Never runs on the main thread (dispatcher uses Dispatchers.IO).
class WhisperAsrEngine(
    private val registry: FileModelRegistry,
    private val bridge: WhisperBridge,
    private val defaultModelId: String,
    private val clock: () -> Long = System::currentTimeMillis,
) : AsrEngine {
    @Volatile
    var lastDiagnostics: AsrDiagnostics? = null
        private set

    override val modelId: String
        get() = registry.installedModels().firstOrNull { it.modelId == defaultModelId }?.modelId ?: defaultModelId

    override val modelVersion: String
        get() = registry.installedModels().firstOrNull { it.modelId == defaultModelId }?.modelVersion ?: "uninstalled"

    override suspend fun transcribe(
        wavFile: File,
        language: String,
    ): AsrResultV1 {
        if (language != "hi" && language != "en" && language != "mr") {
            throw EngineException(EngineErrorCodes.INPUT_INVALID, "Unsupported language: $language.", false)
        }
        val wav = WavValidator.validate(wavFile)
        val status = registry.status(defaultModelId)
        val info =
            when (status) {
                is ModelStatus.Installed -> status.info
                is ModelStatus.Missing ->
                    throw EngineException(
                        EngineErrorCodes.MODEL_MISSING,
                        "ASR model not installed.",
                        false,
                    )
                is ModelStatus.Corrupt ->
                    throw EngineException(
                        EngineErrorCodes.MODEL_CORRUPT,
                        "ASR model corrupt.",
                        false,
                    )
            }
        if (!bridge.isAvailable()) {
            throw EngineException(EngineErrorCodes.MODEL_MISSING, "Whisper native library unavailable.", false)
        }
        val wallStart = clock()
        try {
            bridge.loadModel(info.file.absolutePath)
        } catch (e: EngineException) {
            throw e
        } catch (e: OutOfMemoryError) {
            throw EngineException(EngineErrorCodes.OUT_OF_MEMORY, "Model load out of memory.", true)
        }
        val native =
            try {
                bridge.transcribe(wavFile.absolutePath, language)
            } catch (e: EngineException) {
                throw e
            } catch (e: OutOfMemoryError) {
                throw EngineException(EngineErrorCodes.OUT_OF_MEMORY, "Inference out of memory.", true)
            } catch (e: Exception) {
                throw EngineException(EngineErrorCodes.INFERENCE_FAILED, "Transcription failed.", true)
            }
        val wallMs = (clock() - wallStart).coerceAtLeast(1)
        val rtf = native.inferenceMs.toDouble() / wav.durationMs.coerceAtLeast(1).toDouble()
        lastDiagnostics =
            AsrDiagnostics(
                modelId = info.modelId,
                modelVersion = info.modelVersion,
                audioDurationMs = wav.durationMs,
                inferenceMs = native.inferenceMs,
                wallMs = wallMs,
                realTimeFactor = rtf,
            )
        if (native.text.length > MAX_TEXT_CHARS) {
            throw EngineException(EngineErrorCodes.OUTPUT_REJECTED, "ASR output too large.", false)
        }
        return AsrResultV1(
            sampleId = wavFile.nameWithoutExtension,
            text = native.text,
            language = language,
            segments = native.segments,
            modelId = info.modelId,
            modelVersion = info.modelVersion,
            createdAt = Instant.ofEpochMilli(clock()).toString(),
        )
    }

    companion object {
        const val MAX_TEXT_CHARS = 100_000
        val SUPPORTED_LANGUAGES = setOf("hi", "en", "mr")
    }
}
