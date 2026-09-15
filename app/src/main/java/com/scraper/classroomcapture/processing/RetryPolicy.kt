package com.scraper.classroomcapture.processing

// Retry classification + exponential backoff (P6.4). Permanent errors fail
// the job immediately (no pointless native retries); retryable errors back
// off exponentially with jitter-free caps so behavior is reproducible and
// testable. Pure logic — fully unit-tested.
object RetryPolicy {
    const val BASE_DELAY_MS = 30_000L
    const val MAX_DELAY_MS = 30 * 60_000L

    fun isRetryable(code: String): Boolean =
        when (code) {
            EngineErrorCodes.INFERENCE_FAILED -> true
            EngineErrorCodes.OUT_OF_MEMORY -> true
            // Missing/corrupt models, bad inputs, rejected outputs: retrying
            // the same bytes with the same model cannot succeed.
            EngineErrorCodes.MODEL_MISSING,
            EngineErrorCodes.MODEL_LOAD_FAILED,
            EngineErrorCodes.MODEL_CORRUPT,
            EngineErrorCodes.INPUT_INVALID,
            EngineErrorCodes.OUTPUT_REJECTED,
            -> false
            // Unknown codes fail safe: do not spin forever on surprises.
            else -> false
        }

    // attempt is 1-based (first failure -> attempt 1). attempt 0 = no delay.
    fun delayMs(attempt: Int): Long {
        if (attempt <= 0) return 0
        var delay = BASE_DELAY_MS
        repeat(attempt - 1) {
            delay = (delay * 2).coerceAtMost(MAX_DELAY_MS)
        }
        return delay
    }
}
