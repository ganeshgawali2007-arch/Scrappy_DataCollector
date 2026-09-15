package com.scraper.classroomcapture.llm

import com.scraper.classroomcapture.asr.ModelInstallException
import java.io.File
import java.security.MessageDigest

// GGUF model registry (P10.2, D15). Same file-picker + SHA-256 pattern as
// ASR: operator sideloads from Mac/USB, app copies into app-private storage,
// verifies hash + metadata, then marks installed. Layout:
// <root>/<modelId>.gguf + <modelId>.json (version/sha256/bytes/license).
// APK never bundles weights; recording/export work without the model.
data class GgufInfo(
    val modelId: String,
    val modelVersion: String,
    val sha256: String,
    val byteSize: Long,
    val license: String,
    val file: File,
)

sealed interface GgufStatus {
    data class Installed(val info: GgufInfo) : GgufStatus

    data object Missing : GgufStatus

    data class Corrupt(val reason: String) : GgufStatus
}

class GgufRegistry(private val modelsDir: File) {
    fun installedModels(): List<GgufInfo> {
        if (!modelsDir.isDirectory) return emptyList()
        return modelsDir.listFiles { f -> f.extension == "gguf" }.orEmpty().mapNotNull {
            readMetadata(it.nameWithoutExtension)
        }
    }

    fun status(modelId: String): GgufStatus {
        val info = readMetadata(modelId) ?: return GgufStatus.Missing
        if (!info.file.isFile) return GgufStatus.Missing
        val actual = sha256Of(info.file)
        if (!actual.equals(info.sha256, ignoreCase = true)) {
            return GgufStatus.Corrupt("SHA-256 mismatch for $modelId")
        }
        if (info.file.length() != info.byteSize) {
            return GgufStatus.Corrupt("byte-size mismatch for $modelId")
        }
        return GgufStatus.Installed(info)
    }

    fun resolve(modelId: String): File? =
        when (val s = status(modelId)) {
            is GgufStatus.Installed -> s.info.file
            else -> null
        }

    fun installFromFile(
        source: File,
        modelId: String,
        modelVersion: String,
        expectedSha256: String,
        license: String,
    ): GgufInfo {
        require(modelId.matches(MODEL_ID_RE)) { "Invalid model id: $modelId" }
        val actual = sha256Of(source)
        if (!actual.equals(expectedSha256, ignoreCase = true)) {
            throw ModelInstallException("SHA-256 mismatch: expected $expectedSha256, got $actual")
        }
        modelsDir.mkdirs()
        val dest = File(modelsDir, "$modelId.gguf")
        val tmp = File(modelsDir, "$modelId.gguf.tmp")
        source.copyTo(tmp, overwrite = true)
        if (!sha256Of(tmp).equals(expectedSha256, ignoreCase = true)) {
            tmp.delete()
            throw ModelInstallException("Copy verification failed for $modelId")
        }
        if (!tmp.renameTo(dest)) {
            tmp.delete()
            throw ModelInstallException("Atomic install failed for $modelId")
        }
        val info = GgufInfo(modelId, modelVersion, expectedSha256.lowercase(), dest.length(), license, dest)
        writeMetadata(info)
        return info
    }

    private fun metadataFile(modelId: String): File = File(modelsDir, "$modelId.json")

    private fun readMetadata(modelId: String): GgufInfo? {
        val meta = metadataFile(modelId)
        val bin = File(modelsDir, "$modelId.gguf")
        if (!meta.isFile) return null
        return try {
            val map = meta.readLines().mapNotNull { lineToEntry(it) }.toMap()
            GgufInfo(
                modelId = modelId,
                modelVersion = map["version"] ?: return null,
                sha256 = map["sha256"] ?: return null,
                byteSize = map["bytes"]?.toLongOrNull() ?: return null,
                license = map["license"] ?: "unknown",
                file = bin,
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun writeMetadata(info: GgufInfo) {
        val tmp = File(modelsDir, "${info.modelId}.json.tmp")
        tmp.writeText("version=${info.modelVersion}\nsha256=${info.sha256}\nbytes=${info.byteSize}\nlicense=${info.license}\n")
        if (!tmp.renameTo(metadataFile(info.modelId))) throw ModelInstallException("Metadata write failed for ${info.modelId}")
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
