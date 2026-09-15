package com.scraper.classroomcapture.asr

import java.io.File
import java.security.MessageDigest

// Model registry (P7.4, D15). App-private model storage, user-installed via
// file picker (USB/Mac sideload) — never bundled, never downloaded.
// Layout: <root>/<modelId>.bin + <modelId>.json (metadata). Paths are never
// hard-coded in app code; callers resolve through here.
data class ModelInfo(
    val modelId: String,
    val modelVersion: String,
    val sha256: String,
    val byteSize: Long,
    val license: String,
    val file: File,
)

sealed interface ModelStatus {
    data class Installed(val info: ModelInfo) : ModelStatus

    data object Missing : ModelStatus

    data class Corrupt(val reason: String) : ModelStatus
}

class FileModelRegistry(private val modelsDir: File) {
    fun installedModels(): List<ModelInfo> {
        if (!modelsDir.isDirectory) return emptyList()
        return modelsDir.listFiles { f -> f.extension == "bin" }.orEmpty().mapNotNull { bin ->
            readMetadata(bin.nameWithoutExtension)
        }
    }

    fun status(modelId: String): ModelStatus {
        val info = readMetadata(modelId) ?: return ModelStatus.Missing
        if (!info.file.isFile) return ModelStatus.Missing
        val actual = sha256Of(info.file)
        if (!actual.equals(info.sha256, ignoreCase = true)) {
            return ModelStatus.Corrupt("SHA-256 mismatch for $modelId")
        }
        if (info.file.length() != info.byteSize) {
            return ModelStatus.Corrupt("byte-size mismatch for $modelId")
        }
        return ModelStatus.Installed(info)
    }

    fun resolve(modelId: String): File? =
        when (val s = status(modelId)) {
            is ModelStatus.Installed -> s.info.file
            else -> null
        }

    // Copies a user-picked file into app-private storage, verifies SHA-256,
    // and writes the metadata sidecar atomically. Throws ModelInstallException
    // (never leaves a half-installed model).
    fun installFromFile(
        source: File,
        modelId: String,
        modelVersion: String,
        expectedSha256: String,
        license: String,
    ): ModelInfo {
        require(modelId.matches(MODEL_ID_RE)) { "Invalid model id: $modelId" }
        val actual = sha256Of(source)
        if (!actual.equals(expectedSha256, ignoreCase = true)) {
            throw ModelInstallException("SHA-256 mismatch: expected $expectedSha256, got $actual")
        }
        modelsDir.mkdirs()
        val dest = File(modelsDir, "$modelId.bin")
        val tmp = File(modelsDir, "$modelId.bin.tmp")
        source.copyTo(tmp, overwrite = true)
        val copiedHash = sha256Of(tmp)
        if (!copiedHash.equals(expectedSha256, ignoreCase = true)) {
            tmp.delete()
            throw ModelInstallException("Copy verification failed for $modelId")
        }
        if (!tmp.renameTo(dest)) {
            tmp.delete()
            throw ModelInstallException("Atomic install failed for $modelId")
        }
        val info =
            ModelInfo(
                modelId = modelId,
                modelVersion = modelVersion,
                sha256 = expectedSha256.lowercase(),
                byteSize = dest.length(),
                license = license,
                file = dest,
            )
        writeMetadata(info)
        return info
    }

    private fun metadataFile(modelId: String): File = File(modelsDir, "$modelId.json")

    private fun readMetadata(modelId: String): ModelInfo? {
        val meta = metadataFile(modelId)
        val bin = File(modelsDir, "$modelId.bin")
        if (!meta.isFile) return null
        return try {
            // Minimal line-based format (no extra dependency): key=value.
            val map = meta.readLines().mapNotNull { lineToEntry(it) }.toMap()
            ModelInfo(
                modelId = modelId,
                modelVersion = map["version"] ?: return null,
                sha256 = map["sha256"] ?: return null,
                byteSize = map["bytes"]?.toLongOrNull() ?: return null,
                license = map["license"] ?: "unknown",
                file = bin,
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun writeMetadata(info: ModelInfo) {
        val tmp = File(modelsDir, "${info.modelId}.json.tmp")
        tmp.writeText(
            "version=${info.modelVersion}\n" +
                "sha256=${info.sha256}\n" +
                "bytes=${info.byteSize}\n" +
                "license=${info.license}\n",
        )
        val dest = metadataFile(info.modelId)
        if (!tmp.renameTo(dest)) throw ModelInstallException("Metadata write failed for ${info.modelId}")
    }

    companion object {
        private val MODEL_ID_RE = Regex("[a-z0-9][a-z0-9._-]{1,63}")

        fun sha256Of(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    digest.update(buf, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        private fun lineToEntry(line: String): Pair<String, String>? {
            val idx = line.indexOf('=')
            if (idx <= 0) return null
            return line.substring(0, idx) to line.substring(idx + 1)
        }
    }
}

class ModelInstallException(message: String) : Exception(message)
