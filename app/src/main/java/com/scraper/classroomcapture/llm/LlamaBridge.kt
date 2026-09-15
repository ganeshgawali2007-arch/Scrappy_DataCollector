package com.scraper.classroomcapture.llm

import com.scraper.classroomcapture.processing.EngineErrorCodes
import com.scraper.classroomcapture.processing.EngineException

// llama.cpp JNI seam (P10.2–P10.3). The Kotlin engine owns policy
// (validation, retry classification, persistence); the bridge only moves
// bytes across JNI. Never runs on the main thread (dispatcher uses IO).
// Raw output is untrusted JSON — always validated before persistence.
data class NativeAnnotation(
    val json: String,
    val inferenceMs: Long,
)

interface LlamaBridge {
    // False until a reviewed llama.cpp revision is vendored and the model
    // is loaded. Workers map !available to MODEL_MISSING (permanent) —
    // capture/export keep working without the LLM (P10.8).
    fun isAvailable(): Boolean

    fun loadModel(modelPath: String)

    fun unload()

    fun generate(
        prompt: String,
        maxTokens: Int,
    ): NativeAnnotation
}

// Production bridge behind System.loadLibrary. Safe load (P10.3):
// UnsatisfiedLinkError maps to MODEL_MISSING, never a crash. Until the
// vendored llama.cpp lands, the stub native lib reports unavailable.
class JniLlamaBridge : LlamaBridge {
    private var loaded = false
    private var nativeAvailable = false

    init {
        try {
            System.loadLibrary("scraper_native")
            loaded = true
            nativeAvailable = nativeIsLlamaAvailable()
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
            throw EngineException(EngineErrorCodes.MODEL_MISSING, "LLM native library unavailable.", false)
        }
        try {
            val ok = nativeLoadLlamaModel(modelPath)
            if (!ok) throw EngineException(EngineErrorCodes.MODEL_LOAD_FAILED, "LLM model load failed.", false)
        } catch (e: EngineException) {
            throw e
        } catch (e: OutOfMemoryError) {
            throw EngineException(EngineErrorCodes.OUT_OF_MEMORY, "LLM model out of memory.", true)
        } catch (e: Exception) {
            throw EngineException(EngineErrorCodes.MODEL_LOAD_FAILED, "LLM model load failed.", false)
        }
    }

    override fun unload() {
        if (!loaded) return
        try {
            nativeUnloadLlama()
        } catch (_: Exception) {
        }
    }

    override fun generate(
        prompt: String,
        maxTokens: Int,
    ): NativeAnnotation {
        if (!isAvailable()) {
            throw EngineException(EngineErrorCodes.MODEL_MISSING, "LLM native library unavailable.", false)
        }
        return try {
            nativeGenerate(prompt, maxTokens)
        } catch (e: EngineException) {
            throw e
        } catch (e: OutOfMemoryError) {
            throw EngineException(EngineErrorCodes.OUT_OF_MEMORY, "LLM inference out of memory.", true)
        } catch (e: Exception) {
            throw EngineException(EngineErrorCodes.INFERENCE_FAILED, "LLM inference failed.", true)
        }
    }

    private external fun nativeIsLlamaAvailable(): Boolean

    private external fun nativeLoadLlamaModel(modelPath: String): Boolean

    private external fun nativeUnloadLlama()

    private external fun nativeGenerate(
        prompt: String,
        maxTokens: Int,
    ): NativeAnnotation
}

// Deterministic fake for unit tests (no .so needed).
class FakeLlamaBridge(
    private val available: Boolean = true,
    private val jsonFor: (String) -> String = { prompt -> "{\"activity\":\"explanation\"}" },
    private val inferenceMs: Long = 50,
) : LlamaBridge {
    var loads = 0
    var generates = 0
    var lastModelPath: String? = null
    var lastPrompt: String? = null

    override fun isAvailable(): Boolean = available

    override fun loadModel(modelPath: String) {
        if (!available) throw EngineException(EngineErrorCodes.MODEL_MISSING, "Fake LLM unavailable.", false)
        loads++
        lastModelPath = modelPath
    }

    override fun unload() = Unit

    override fun generate(
        prompt: String,
        maxTokens: Int,
    ): NativeAnnotation {
        if (!available) throw EngineException(EngineErrorCodes.MODEL_MISSING, "Fake LLM unavailable.", false)
        generates++
        lastPrompt = prompt
        return NativeAnnotation(jsonFor(prompt), inferenceMs)
    }
}
