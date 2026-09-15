package com.scraper.classroomcapture.processing

import com.scraper.classroomcapture.data.serialization.AnnotationResultV1
import com.scraper.classroomcapture.data.serialization.AsrResultV1
import com.scraper.classroomcapture.data.serialization.PedagogicalRecordV2
import java.io.File

// Stable engine failure codes (P0.4 taxonomy). retryable decides retry vs
// permanent; callers never parse messages.
object EngineErrorCodes {
    const val MODEL_MISSING = "MODEL_MISSING"
    const val MODEL_LOAD_FAILED = "MODEL_LOAD_FAILED"
    const val MODEL_CORRUPT = "MODEL_CORRUPT"
    const val INPUT_INVALID = "INPUT_INVALID"
    const val INFERENCE_FAILED = "INFERENCE_FAILED"
    const val OUT_OF_MEMORY = "OUT_OF_MEMORY"
    const val OUTPUT_REJECTED = "OUTPUT_REJECTED"
}

class EngineException(
    val code: String,
    message: String,
    val retryable: Boolean,
) : Exception(message)

// Speech-to-text engine seam (P6 defines, P7 implements with whisper.cpp).
// Never touches the database or the filesystem beyond its inputs: the worker
// owns persistence. Runs off the main thread; implementations must be
// thread-safe for single-flight use (concurrency baseline is one job).
interface AsrEngine {
    val modelId: String
    val modelVersion: String

    suspend fun transcribe(
        wavFile: File,
        language: String,
    ): AsrResultV1
}

// Pedagogical annotation seam (P6 defines, P10 implements). Outputs are
// suggestions labeled AI_SUGGESTION with evidence spans — never facts.
interface LlmEngine {
    val modelId: String
    val modelVersion: String

    suspend fun annotate(record: PedagogicalRecordV2): AnnotationResultV1
}
