package com.scraper.classroomcapture.data.store

import com.scraper.classroomcapture.domain.model.ArtifactKind
import java.io.OutputStream

// Store error codes (P0.4 taxonomy: stable machine strings, operator-safe
// messages kept at call sites, retryable flag drives P6/P8 behavior).
object StoreErrorCodes {
    const val PATH_TRAVERSAL = "PATH_TRAVERSAL"
    const val STORAGE_LOW = "STORAGE_LOW"
    const val WRITE_FAILED = "WRITE_FAILED"
    const val NOT_FOUND = "NOT_FOUND"
    const val HASH_IO = "HASH_IO"
}

class StoreException(
    val code: String,
    message: String,
    val retryable: Boolean,
) : Exception(message)

data class WrittenFile(
    val relativePath: String,
    val sha256: String,
    val byteSize: Long,
)

data class FileHash(
    val sha256: String,
    val byteSize: Long,
)

// One discovered sample directory (P3.7 input).
data class SampleDirRef(
    val sessionId: String,
    val sampleId: String,
    val relativeDir: String,
    val files: List<String>,
)

// Artifact file storage (P3.1–P3.4). App-private root only; all persisted
// paths are root-relative. All suspend functions perform file IO — call off
// the main thread. Implementations never delete user data silently:
// quarantine moves files aside and reports them.
interface ArtifactStore {
    fun audioRelativePath(
        sessionId: String,
        sampleId: String,
    ): String = StorePaths.audioRelativePath(sessionId, sampleId)

    fun sidecarRelativePath(
        sessionId: String,
        sampleId: String,
        kind: ArtifactKind,
    ): String {
        val file =
            when (kind) {
                ArtifactKind.AUDIO_WAV -> StorePaths.AUDIO_FILE
                ArtifactKind.ASR_JSON -> StorePaths.ASR_FILE
                ArtifactKind.ANNOTATION_JSON -> StorePaths.ANNOTATION_FILE
            }
        return StorePaths.sampleDirRelativePath(sessionId, sampleId) + "/" + file
    }

    // Atomically publishes writer output at relativePath: stream to tmp,
    // flush + fsync, hash, rename over the target, fsync the parent dir.
    // Readers never observe a half-written file. Throws StoreException.
    suspend fun writeAtomic(
        relativePath: String,
        write: suspend (OutputStream) -> Unit,
    ): WrittenFile

    suspend fun hashFile(relativePath: String): FileHash

    fun exists(relativePath: String): Boolean

    suspend fun readAllBytes(relativePath: String): ByteArray

    // Moves a corrupt/partial file under quarantine/ and returns the new
    // relative path. Never deletes (P3.2).
    suspend fun quarantineFile(
        relativePath: String,
        reason: String,
        now: Long,
    ): String

    // Moves a whole directory under quarantine/ (orphans without a session).
    suspend fun quarantineTree(
        relativeDir: String,
        reason: String,
        now: Long,
    ): String

    // Explicit user-initiated delete only (P8). Never called by recovery.
    suspend fun deleteTree(relativeDir: String)

    fun listSampleFiles(
        sessionId: String,
        sampleId: String,
    ): List<String>

    // Every valid sample dir in the store, for orphan discovery (P3.7).
    fun scanSampleDirs(): List<SampleDirRef>

    // Crash leftovers: root tmp/ plus any *.tmp inside sample dirs (P3.7).
    fun listTmpFiles(): List<String>

    // Layout entries that match no known pattern — quarantined by recovery,
    // never silently kept or deleted (P3.4).
    fun scanUnrecognized(): List<String>

    fun storageStats(): StorageStats

    fun checkPreflight(neededBytes: Long): PreflightResult
}
