package com.scraper.classroomcapture.asr

import com.scraper.classroomcapture.data.serialization.AsrSegment
import com.scraper.classroomcapture.processing.EngineErrorCodes
import com.scraper.classroomcapture.processing.EngineException

// whisper.cpp JNI seam (P7.2). The Kotlin worker owns all policy (validation,
// retry classification, persistence); the bridge only moves bytes across JNI.
// Native crashes are caught at the worker boundary (P7 engine), never in UI.
data class NativeTranscription(
    val text: String,
    val segments: List<AsrSegment>,
    val inferenceMs: Long,
)

interface WhisperBridge {
    // False until a reviewed whisper.cpp revision is vendored (P7.1) and the
    // model is loaded. Workers map !available to MODEL_MISSING (permanent).
    fun isAvailable(): Boolean

    fun loadModel(modelPath: String)

    fun unload()

    fun transcribe(
        wavPath: String,
        language: String,
    ): NativeTranscription
}

// Production bridge behind System.loadLibrary. Safe load/unload (P7.2):
// UnsatisfiedLinkError maps to MODEL_MISSING, never a crash. Until the
// vendored whisper.cpp lands, the stub native lib reports unavailable.
class JniWhisperBridge : WhisperBridge {
    private var loaded = false
    private var nativeAvailable = false

    init {
        try {
            System.loadLibrary("scraper_native")
            loaded = true
            nativeAvailable = nativeIsWhisperAvailable()
        } catch (e: UnsatisfiedLinkError) {
            loaded = false
            nativeAvailable = false
        } catch (e: Exception) {
            nativeAvailable = false
        }
    }

    override fun isAvailable(): Boolean = loaded && nativeAvailable

    override fun loadModel(modelPath: String) {
        if (!isAvailable()) {
            throw EngineException(EngineErrorCodes.MODEL_MISSING, "Whisper native library unavailable.", false)
        }
        try {
            val ok = nativeLoadModel(modelPath)
            if (!ok) throw EngineException(EngineErrorCodes.MODEL_LOAD_FAILED, "Whisper model load failed.", false)
        } catch (e: EngineException) {
            throw e
        } catch (e: OutOfMemoryError) {
            throw EngineException(EngineErrorCodes.OUT_OF_MEMORY, "Whisper model out of memory.", true)
        } catch (e: Exception) {
            throw EngineException(EngineErrorCodes.MODEL_LOAD_FAILED, "Whisper model load failed.", false)
        }
    }

    override fun unload() {
        if (!loaded) return
        try {
            nativeUnload()
        } catch (e: Exception) {
            // Unload failures are non-fatal; the next load re-initializes.
        }
    }

    override fun transcribe(
        wavPath: String,
        language: String,
    ): NativeTranscription {
        if (!isAvailable()) {
            throw EngineException(EngineErrorCodes.MODEL_MISSING, "Whisper native library unavailable.", false)
        }
        return try {
            nativeTranscribe(wavPath, language)
        } catch (e: EngineException) {
            throw e
        } catch (e: OutOfMemoryError) {
            throw EngineException(EngineErrorCodes.OUT_OF_MEMORY, "Whisper inference out of memory.", true)
        } catch (e: Exception) {
            throw EngineException(EngineErrorCodes.INFERENCE_FAILED, "Whisper inference failed.", true)
        }
    }

    private external fun nativeIsWhisperAvailable(): Boolean

    private external fun nativeLoadModel(modelPath: String): Boolean

    private external fun nativeUnload()

    private external fun nativeTranscribe(
        wavPath: String,
        language: String,
    ): NativeTranscription
}

// Deterministic fake for unit tests and the emulator gate (no .so needed).
// Returns canned text per language with one segment covering the file.
class FakeWhisperBridge(
    private val available: Boolean = true,
    private val textFor: (String) -> String = { lang -> "fixture $lang" },
    private val inferenceMs: Long = 50,
) : WhisperBridge {
    var loads = 0
    var transcribes = 0
    var lastModelPath: String? = null

    override fun isAvailable(): Boolean = available

    override fun loadModel(modelPath: String) {
        if (!available) throw EngineException(EngineErrorCodes.MODEL_MISSING, "Fake bridge unavailable.", false)
        loads++
        lastModelPath = modelPath
    }

    override fun unload() = Unit

    override fun transcribe(
        wavPath: String,
        language: String,
    ): NativeTranscription {
        if (!available) throw EngineException(EngineErrorCodes.MODEL_MISSING, "Fake bridge unavailable.", false)
        transcribes++
        val text = textFor(language)
        return NativeTranscription(
            text = text,
            segments = listOf(AsrSegment(startMs = 0, endMs = 1000, text = text)),
            inferenceMs = inferenceMs,
        )
    }
}
