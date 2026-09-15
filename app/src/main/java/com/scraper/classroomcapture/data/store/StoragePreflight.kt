package com.scraper.classroomcapture.data.store

import java.io.File

// Storage-space preflight (P3.5). Thresholds are deliberately conservative:
// a 38 s mono 16 kHz sample is ~1.2 MB, so 100 MB headroom covers a full
// class plus processing sidecars; 500 MB warns early enough to act.
// Pure functions over injected byte counts — fully unit-testable.
data class StorageStats(
    val freeBytes: Long,
    val totalBytes: Long,
)

sealed interface PreflightResult {
    data object Ok : PreflightResult

    // Proceed, but the UI must show a low-storage warning (P8 banner).
    data class Low(val freeBytes: Long) : PreflightResult

    // Refuse new recordings; surface STORAGE_LOW with a resolution action.
    data class Refused(val freeBytes: Long) : PreflightResult
}

object StoragePreflight {
    const val REFUSE_FREE_BYTES = 100L * 1024 * 1024
    const val WARN_FREE_BYTES = 500L * 1024 * 1024

    fun check(
        freeBytes: Long,
        neededBytes: Long,
    ): PreflightResult {
        val projected = freeBytes - neededBytes.coerceAtLeast(0)
        return when {
            projected < REFUSE_FREE_BYTES -> PreflightResult.Refused(freeBytes)
            projected < WARN_FREE_BYTES -> PreflightResult.Low(freeBytes)
            else -> PreflightResult.Ok
        }
    }
}

// Indirection so JVM tests can inject free-space values (P3.8).
fun interface FreeSpaceProvider {
    fun freeBytes(path: File): Long

    object System : FreeSpaceProvider {
        override fun freeBytes(path: File): Long = path.usableSpace
    }
}
