package com.scraper.classroomcapture.llm

import com.scraper.classroomcapture.data.serialization.AnnotationResultV1
import com.scraper.classroomcapture.data.serialization.PedagogicalRecordV2
import com.scraper.classroomcapture.processing.EngineErrorCodes
import com.scraper.classroomcapture.processing.EngineException
import com.scraper.classroomcapture.processing.LlmEngine
import java.time.Instant

// Local LLM diagnostics per annotation (P10.3, P11.3): wall time, inference
// time, prompt chars. Recorded by the worker boundary; no transcript content
// in logs (P0.4 redaction).
data class LlmDiagnostics(
    val modelId: String,
    val modelVersion: String,
    val promptVersion: String,
    val promptChars: Int,
    val inferenceMs: Long,
    val wallMs: Long,
)

// llama.cpp-backed LlmEngine (P10.3). Policy:
// - transcript empty/shorter than MIN_CHARS → insufficient_audio_evidence
//   (INPUT_INVALID, permanent) — audio + ASR stay exportable (P10.8).
// - model resolved via GgufRegistry; missing/corrupt → MODEL_* (permanent).
// - bridge unavailable → MODEL_MISSING (permanent, never blocks capture).
// - native Throwables → INFERENCE_FAILED (retryable); OOM → OUT_OF_MEMORY.
// - raw JSON validated (P10.5); malformed/ungrounded → OUTPUT_REJECTED.
// Never runs on the main thread (dispatcher uses Dispatchers.IO).
class LocalLlmEngine(
    private val registry: GgufRegistry,
    private val bridge: LlamaBridge,
    private val defaultModelId: String,
    private val clock: () -> Long = System::currentTimeMillis,
) : LlmEngine {
    @Volatile var lastDiagnostics: LlmDiagnostics? = null
        private set

    override val modelId: String
        get() = registry.installedModels().firstOrNull { it.modelId == defaultModelId }?.modelId ?: defaultModelId

    override val modelVersion: String
        get() = registry.installedModels().firstOrNull { it.modelId == defaultModelId }?.modelVersion ?: "uninstalled"

    override suspend fun annotate(record: PedagogicalRecordV2): AnnotationResultV1 {
        val transcript = record.source.trim()
        // P10.6: too little evidence — do not hallucinate.
        if (transcript.length < MIN_TRANSCRIPT_CHARS) {
            throw EngineException(EngineErrorCodes.INPUT_INVALID, "insufficient_audio_evidence.", false)
        }
        val status = registry.status(defaultModelId)
        val info =
            when (status) {
                is GgufStatus.Installed -> status.info
                is GgufStatus.Missing -> throw EngineException(EngineErrorCodes.MODEL_MISSING, "LLM model not installed.", false)
                is GgufStatus.Corrupt -> throw EngineException(EngineErrorCodes.MODEL_CORRUPT, "LLM model corrupt.", false)
            }
        if (!bridge.isAvailable()) {
            throw EngineException(EngineErrorCodes.MODEL_MISSING, "LLM native library unavailable.", false)
        }
        val prompt = PromptTemplate.build(record)
        val wallStart = clock()
        try {
            bridge.loadModel(info.file.absolutePath)
        } catch (e: EngineException) {
            throw e
        } catch (e: OutOfMemoryError) {
            throw EngineException(EngineErrorCodes.OUT_OF_MEMORY, "LLM model out of memory.", true)
        }
        val native =
            try {
                bridge.generate(prompt, PromptTemplate.MAX_TOKENS)
            } catch (e: EngineException) {
                throw e
            } catch (e: OutOfMemoryError) {
                throw EngineException(EngineErrorCodes.OUT_OF_MEMORY, "LLM inference out of memory.", true)
            } catch (e: Exception) {
                throw EngineException(EngineErrorCodes.INFERENCE_FAILED, "LLM inference failed.", true)
            }
        val wallMs = (clock() - wallStart).coerceAtLeast(1)
        lastDiagnostics = LlmDiagnostics(info.modelId, info.modelVersion, PromptTemplate.VERSION, prompt.length, native.inferenceMs, wallMs)
        if (native.json.length > MAX_JSON_CHARS) {
            throw EngineException(EngineErrorCodes.OUTPUT_REJECTED, "LLM output too large.", false)
        }
        // P10.5 + P10.7: strict validation; prompt/model/settings identity
        // persisted on the sidecar (generated_by + model_version +
        // prompt_version), labeled AI_SUGGESTION.
        return AnnotationValidator.parseAndValidate(
            json = native.json,
            sampleId = record.sampleId,
            transcript = transcript,
            modelId = info.modelId,
            modelVersion = info.modelVersion,
            promptVersion = PromptTemplate.VERSION,
            createdAt = Instant.ofEpochMilli(clock()).toString(),
        )
    }

    companion object {
        // Below this, a suggestion would be fabrication (P10.6).
        const val MIN_TRANSCRIPT_CHARS = 3
        const val MAX_JSON_CHARS = 50_000
    }
}
