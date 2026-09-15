package com.scraper.classroomcapture.export

import com.scraper.classroomcapture.data.store.ArtifactStore
import com.scraper.classroomcapture.domain.model.ArtifactKind
import com.scraper.classroomcapture.domain.model.ClassroomSample

// Pre-export DB/filesystem consistency check (P9.3). Returns actionable
// failures — the UI shows sampleIds + reasons, never a generic error.
// Audio is mandatory; ASR/LLM sidecars are optional (plan invariant 4).
data class PreflightFailure(
    val sampleId: String,
    val code: String,
    val message: String,
)

object ExportPreflight {
    const val CODE_AUDIO_MISSING = "AUDIO_MISSING"
    const val CODE_AUDIO_UNREADABLE = "AUDIO_UNREADABLE"
    const val CODE_CHECKSUM_MISMATCH = "CHECKSUM_MISMATCH"

    suspend fun validate(
        samples: List<ClassroomSample>,
        audioShaBySample: Map<String, String>,
        store: ArtifactStore,
    ): List<PreflightFailure> {
        val failures = mutableListOf<PreflightFailure>()
        samples.forEach { sample ->
            val audioRel = store.audioRelativePath(sample.sessionId, sample.id)
            if (!store.exists(audioRel)) {
                failures +=
                    PreflightFailure(
                        sample.id,
                        CODE_AUDIO_MISSING,
                        "Audio file missing for sample ${sample.id}.",
                    )
                return@forEach
            }
            val expected = audioShaBySample[sample.id]
            if (expected != null) {
                try {
                    val actual = store.hashFile(audioRel)
                    if (!actual.sha256.equals(expected, ignoreCase = true)) {
                        failures +=
                            PreflightFailure(
                                sample.id,
                                CODE_CHECKSUM_MISMATCH,
                                "Audio checksum mismatch for sample ${sample.id}.",
                            )
                    }
                } catch (e: Exception) {
                    failures +=
                        PreflightFailure(
                            sample.id,
                            CODE_AUDIO_UNREADABLE,
                            "Audio unreadable for sample ${sample.id}.",
                        )
                }
            }
            // Sidecar kind is informational only — missing ASR/LLM never fails.
            @Suppress("UNUSED_EXPRESSION")
            ArtifactKind.AUDIO_WAV
        }
        return failures
    }
}
