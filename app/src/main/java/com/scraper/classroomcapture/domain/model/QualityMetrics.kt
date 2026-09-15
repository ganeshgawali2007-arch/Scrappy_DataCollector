package com.scraper.classroomcapture.domain.model

// Immutable value object for capture-time audio quality (P2.1).
// Embedded in the sample row; exported as the record's `quality` block.
data class QualityMetrics(
    val rmsDb: Double?,
    val peakDb: Double?,
    val clippingDetected: Boolean,
    val silenceRatio: Double?,
    val flags: List<String>,
) {
    companion object {
        fun unknown(): QualityMetrics =
            QualityMetrics(
                rmsDb = null,
                peakDb = null,
                clippingDetected = false,
                silenceRatio = null,
                flags = emptyList(),
            )
    }
}
