package com.scraper.classroomcapture.data.store

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

// File-backed ArtifactStore (P3.1–P3.4). Root is app-private (filesDir/scrappy).
// Hashing streams with a fixed 64KB buffer so multi-minute WAVs never sit
// fully in memory. Directory fsync after rename is best-effort (some
// filesystems disallow opening dirs) — the rename itself is the atomicity.
class FileArtifactStore(
    private val root: File,
    private val freeSpace: FreeSpaceProvider = FreeSpaceProvider.System,
) : ArtifactStore {
    init {
        root.mkdirs()
        File(root, StorePaths.TMP_DIR).mkdirs()
        File(root, StorePaths.QUARANTINE_DIR).mkdirs()
        File(root, StorePaths.SESSIONS_DIR).mkdirs()
    }

    override suspend fun writeAtomic(
        relativePath: String,
        write: suspend (OutputStream) -> Unit,
    ): WrittenFile =
        withContext(Dispatchers.IO) {
            val target = StorePaths.resolve(root, relativePath)
            target.parentFile?.mkdirs()
            val tmp = File(File(root, StorePaths.TMP_DIR), UUID.randomUUID().toString() + ".tmp")
            try {
                FileOutputStream(tmp).use { out ->
                    write(out)
                    out.flush()
                    out.fd.sync()
                }
                val hash = hashOf(tmp)
                moveAtomic(tmp, target)
                fsyncDir(target.parentFile)
                WrittenFile(relativePath, hash.sha256, hash.byteSize)
            } catch (e: StoreException) {
                tmp.delete()
                throw e
            } catch (e: Exception) {
                tmp.delete()
                throw StoreException(
                    StoreErrorCodes.WRITE_FAILED,
                    "Could not write recording file. Storage may be full.",
                    retryable = true,
                )
            }
        }

    override suspend fun hashFile(relativePath: String): FileHash =
        withContext(Dispatchers.IO) {
            try {
                hashOf(StorePaths.resolve(root, relativePath))
            } catch (e: StoreException) {
                throw e
            } catch (e: Exception) {
                throw StoreException(StoreErrorCodes.HASH_IO, "Could not read file for verification.", retryable = true)
            }
        }

    override fun exists(relativePath: String): Boolean =
        try {
            StorePaths.resolve(root, relativePath).isFile
        } catch (e: StoreException) {
            false
        }

    override suspend fun readAllBytes(relativePath: String): ByteArray =
        withContext(Dispatchers.IO) {
            val file = StorePaths.resolve(root, relativePath)
            if (!file.isFile) {
                throw StoreException(StoreErrorCodes.NOT_FOUND, "Recording file not found.", retryable = false)
            }
            file.readBytes()
        }

    override suspend fun quarantineFile(
        relativePath: String,
        reason: String,
        now: Long,
    ): String =
        withContext(Dispatchers.IO) {
            val file = StorePaths.resolve(root, relativePath)
            moveToQuarantine(file, reason, now)
        }

    override suspend fun quarantineTree(
        relativeDir: String,
        reason: String,
        now: Long,
    ): String =
        withContext(Dispatchers.IO) {
            val dir = StorePaths.resolve(root, relativeDir)
            moveToQuarantine(dir, reason, now)
        }

    override suspend fun deleteTree(relativeDir: String): Unit =
        withContext(Dispatchers.IO) {
            val dir = StorePaths.resolve(root, relativeDir)
            if (dir.exists()) dir.deleteRecursively()
        }

    override fun listSampleFiles(
        sessionId: String,
        sampleId: String,
    ): List<String> {
        val dir = StorePaths.resolve(root, StorePaths.sampleDirRelativePath(sessionId, sampleId))
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles()?.filter { it.isFile }?.map { StorePaths.relativize(root, it) }.orEmpty()
    }

    override fun scanSampleDirs(): List<SampleDirRef> {
        val sessions = File(root, StorePaths.SESSIONS_DIR)
        if (!sessions.isDirectory) return emptyList()
        val out = mutableListOf<SampleDirRef>()
        sessions.listFiles()?.filter { it.isDirectory }?.forEach { sessionDir ->
            val sessionId = sessionDir.name
            if (!StorePaths.isValidId(sessionId)) return@forEach
            File(sessionDir, StorePaths.SAMPLES_DIR).listFiles()
                ?.filter { it.isDirectory && StorePaths.isValidId(it.name) }
                ?.forEach { sampleDir ->
                    val files =
                        sampleDir.listFiles()
                            ?.filter { it.isFile }
                            ?.map { StorePaths.relativize(root, it) }
                            .orEmpty()
                    out +=
                        SampleDirRef(
                            sessionId = sessionId,
                            sampleId = sampleDir.name,
                            relativeDir = StorePaths.relativize(root, sampleDir),
                            files = files,
                        )
                }
        }
        return out
    }

    override fun listTmpFiles(): List<String> {
        val out = mutableListOf<String>()
        File(root, StorePaths.TMP_DIR).listFiles()
            ?.filter { it.isFile }
            ?.forEach { out += StorePaths.relativize(root, it) }
        scanSampleDirs().forEach { ref ->
            ref.files.filter { it.endsWith(".tmp") }.forEach { out += it }
        }
        return out
    }

    override fun scanUnrecognized(): List<String> {
        val knownTop = setOf(StorePaths.SESSIONS_DIR, StorePaths.TMP_DIR, StorePaths.QUARANTINE_DIR)
        val out = mutableListOf<String>()
        root.listFiles()?.forEach { child ->
            if (child.name !in knownTop) out += StorePaths.relativize(root, child)
        }
        // Session dirs holding non-sample entries, and sample dirs are
        // recognized by shape; anything else inside sessions/ is unexpected.
        File(root, StorePaths.SESSIONS_DIR).listFiles()?.forEach { sessionDir ->
            if (!sessionDir.isDirectory || !StorePaths.isValidId(sessionDir.name)) {
                out += StorePaths.relativize(root, sessionDir)
                return@forEach
            }
            sessionDir.listFiles()?.forEach { child ->
                if (child.name != StorePaths.SAMPLES_DIR) {
                    out += StorePaths.relativize(root, child)
                }
            }
        }
        return out
    }

    override fun storageStats(): StorageStats = StorageStats(root.usableSpace, root.totalSpace)

    override fun checkPreflight(neededBytes: Long): PreflightResult = StoragePreflight.check(freeSpace.freeBytes(root), neededBytes)

    private fun hashOf(file: File): FileHash {
        if (!file.isFile) {
            throw StoreException(StoreErrorCodes.NOT_FOUND, "Recording file not found.", retryable = false)
        }
        val digest = MessageDigest.getInstance("SHA-256")
        var bytes = 0L
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
                bytes += read
            }
        }
        return FileHash(digest.digest().joinToString("") { "%02x".format(it) }, bytes)
    }

    private fun moveAtomic(
        tmp: File,
        target: File,
    ) {
        try {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            if (target.exists() && !target.delete()) {
                throw StoreException(StoreErrorCodes.WRITE_FAILED, "Could not replace recording file.", retryable = true)
            }
            if (!tmp.renameTo(target)) {
                throw StoreException(StoreErrorCodes.WRITE_FAILED, "Could not save recording file.", retryable = true)
            }
        }
    }

    private fun fsyncDir(dir: File?) {
        if (dir == null || !dir.isDirectory) return
        try {
            RandomAccessFile(dir, "r").use { it.fd.sync() }
        } catch (e: Exception) {
            // Best effort: the atomic rename already happened.
        }
    }

    private fun moveToQuarantine(
        file: File,
        reason: String,
        now: Long,
    ): String {
        if (!file.exists()) {
            throw StoreException(StoreErrorCodes.NOT_FOUND, "File to quarantine not found.", retryable = false)
        }
        val safeName = file.name.replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(80)
        val dest = File(File(root, StorePaths.QUARANTINE_DIR), "$now-${reason.take(24)}-$safeName")
        dest.parentFile?.mkdirs()
        Files.move(file.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
        return StorePaths.relativize(root, dest)
    }
}
