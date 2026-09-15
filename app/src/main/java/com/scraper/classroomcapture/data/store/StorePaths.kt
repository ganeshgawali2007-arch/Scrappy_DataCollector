package com.scraper.classroomcapture.data.store

import java.io.File

// On-device layout + path validation (P3.1, P3.4).
//
//   <root>/sessions/<sessionId>/samples/<sampleId>/audio.wav
//   <root>/sessions/<sessionId>/samples/<sampleId>/asr.v1.json
//   <root>/sessions/<sessionId>/samples/<sampleId>/annotation.v1.json
//   <root>/tmp/<uuid>.tmp                      (write staging only)
//   <root>/quarantine/<epoch>-<name>           (never auto-deleted)
//
// Only relative paths are ever persisted (Artifact.relativePath). Absolute
// paths are never stored, so app reinstalls/relocations cannot break rows.
object StorePaths {
    const val SESSIONS_DIR = "sessions"
    const val SAMPLES_DIR = "samples"
    const val TMP_DIR = "tmp"
    const val QUARANTINE_DIR = "quarantine"

    const val AUDIO_FILE = "audio.wav"
    const val ASR_FILE = "asr.v1.json"
    const val ANNOTATION_FILE = "annotation.v1.json"

    private val idPattern = Regex("^[A-Za-z0-9_-]{1,64}$")

    fun isValidId(value: String): Boolean = idPattern.matches(value)

    fun audioRelativePath(
        sessionId: String,
        sampleId: String,
    ): String {
        requireValidId(sessionId, "sessionId")
        requireValidId(sampleId, "sampleId")
        return "$SESSIONS_DIR/$sessionId/$SAMPLES_DIR/$sampleId/$AUDIO_FILE"
    }

    fun sampleDirRelativePath(
        sessionId: String,
        sampleId: String,
    ): String {
        requireValidId(sessionId, "sessionId")
        requireValidId(sampleId, "sampleId")
        return "$SESSIONS_DIR/$sessionId/$SAMPLES_DIR/$sampleId"
    }

    fun sessionDirRelativePath(sessionId: String): String {
        requireValidId(sessionId, "sessionId")
        return "$SESSIONS_DIR/$sessionId"
    }

    // Validates a persisted/caller-supplied relative path and resolves it
    // under root. Rejects absolute paths, "..", backslashes, empty segments,
    // and anything that escapes root after canonical resolution (P3.4).
    // Throws StoreException(PATH_TRAVERSAL) — never returns an escaped File.
    fun resolve(
        root: File,
        relativePath: String,
    ): File {
        if (relativePath.isBlank() ||
            relativePath.startsWith("/") ||
            '\\' in relativePath ||
            relativePath.split('/').any { it.isEmpty() || it == "." || it == ".." }
        ) {
            throw StoreException(
                StoreErrorCodes.PATH_TRAVERSAL,
                "Rejected unsafe artifact path.",
                retryable = false,
            )
        }
        val rootCanonical = root.canonicalFile
        val target = File(rootCanonical, relativePath).canonicalFile
        if (target != rootCanonical && !target.path.startsWith(rootCanonical.path + File.separator)) {
            throw StoreException(
                StoreErrorCodes.PATH_TRAVERSAL,
                "Rejected artifact path escaping store root.",
                retryable = false,
            )
        }
        return target
    }

    // Inverse of resolve: requires file under root, returns the persistable
    // relative path with forward slashes.
    fun relativize(
        root: File,
        file: File,
    ): String {
        val rootCanonical = root.canonicalFile
        val target = file.canonicalFile
        require(target.path.startsWith(rootCanonical.path + File.separator)) {
            "File is outside the store root."
        }
        return target.path.removePrefix(rootCanonical.path + File.separator)
    }

    private fun requireValidId(
        value: String,
        field: String,
    ) {
        if (!isValidId(value)) {
            throw StoreException(
                StoreErrorCodes.PATH_TRAVERSAL,
                "Rejected unsafe $field.",
                retryable = false,
            )
        }
    }
}
