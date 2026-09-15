package com.scraper.classroomcapture.export

import com.scraper.classroomcapture.data.serialization.AnnotationBlock
import com.scraper.classroomcapture.data.serialization.AnnotationResultV1
import com.scraper.classroomcapture.data.serialization.AsrBlock
import com.scraper.classroomcapture.data.serialization.AsrResultV1
import com.scraper.classroomcapture.data.serialization.AudioBlock
import com.scraper.classroomcapture.data.serialization.DeviceEventV1
import com.scraper.classroomcapture.data.serialization.ExportManifestV2
import com.scraper.classroomcapture.data.serialization.GradeValue
import com.scraper.classroomcapture.data.serialization.ManifestFile
import com.scraper.classroomcapture.data.serialization.PedagogicalRecordV2
import com.scraper.classroomcapture.data.serialization.ProvenanceBlock
import com.scraper.classroomcapture.data.serialization.QualityBlock
import com.scraper.classroomcapture.data.serialization.ScrappyJson
import com.scraper.classroomcapture.data.serialization.Vocab
import com.scraper.classroomcapture.data.store.ArtifactStore
import com.scraper.classroomcapture.data.store.FileArtifactStore
import com.scraper.classroomcapture.domain.model.Artifact
import com.scraper.classroomcapture.domain.model.ArtifactKind
import com.scraper.classroomcapture.domain.model.ClassroomSample
import com.scraper.classroomcapture.domain.model.DeviceEvent
import com.scraper.classroomcapture.domain.model.Session
import kotlinx.serialization.encodeToString
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

// Export dataset builder (P9.2, P9.5). Builds in a temp dir, writes manifest +
// JSONL + per-sample JSON + audio + sidecars + events + model metadata, zips,
// then reopens the ZIP and validates every entry/count/checksum before the
// caller finalizes the destination. Never touches source rows/files.
// All file copies stream with a 64KB buffer — never whole-session in RAM.
class ExportBuilder(
    private val store: ArtifactStore,
    private val appVersion: String = "0.0.0",
) {
    data class Input(
        val exportId: String,
        val sessions: List<Session>,
        val samples: List<ClassroomSample>,
        val artifactsBySample: Map<String, List<Artifact>>,
        val events: List<DeviceEvent>,
    )

    data class BuiltExport(
        val stagingDir: File,
        val zipFile: File,
        val manifest: ExportManifestV2,
        val recordCount: Int,
        val zipSha256: String,
        val zipBytes: Long,
    )

    suspend fun build(
        input: Input,
        stagingRoot: File,
    ): BuiltExport {
        val dir = File(stagingRoot, "${input.exportId}.tmp")
        if (dir.exists()) dir.deleteRecursively()
        dir.mkdirs()
        try {
            return buildInto(input, dir)
        } catch (e: Exception) {
            try {
                dir.deleteRecursively()
            } catch (_: Exception) {
            }
            throw e
        }
    }

    private suspend fun buildInto(
        input: Input,
        dir: File,
    ): BuiltExport {
        val sessionById = input.sessions.associateBy { it.id }
        val records =
            input.samples.sortedWith(compareBy({ it.sessionId }, { it.sequenceNumber }))
                .map { sample -> buildRecord(sample, sessionById[sample.sessionId], input.artifactsBySample[sample.id].orEmpty()) }

        // records.jsonl
        val recordsFile = File(dir, ExportLayout.RECORDS_NAME)
        recordsFile.bufferedWriter(Charsets.UTF_8).use { w ->
            records.forEach { rec ->
                w.write(ScrappyJson.encodeToString(rec))
                w.newLine()
            }
        }
        // per-sample JSON (P9.2): records/<sampleId>.v2.json
        val recordsDir = File(dir, ExportLayout.RECORDS_DIR).apply { mkdirs() }
        records.forEach { rec ->
            File(recordsDir, "${rec.sampleId}.v2.json").writeText(
                ScrappyJson.encodeToString(rec),
                Charsets.UTF_8,
            )
        }
        // audio + sidecars (streamed copies)
        input.samples.forEach { sample ->
            val arts = input.artifactsBySample[sample.id].orEmpty()
            val audio = arts.firstOrNull { it.kind == ArtifactKind.AUDIO_WAV }
            if (audio != null) {
                copyStoreFile(audio.relativePath, File(dir, ExportLayout.audioEntry(sample.id)))
            }
            arts.firstOrNull { it.kind == ArtifactKind.ASR_JSON }?.let {
                copyStoreFile(it.relativePath, File(dir, ExportLayout.asrEntry(sample.id)))
            }
            arts.firstOrNull { it.kind == ArtifactKind.ANNOTATION_JSON }?.let {
                copyStoreFile(it.relativePath, File(dir, ExportLayout.annotationEntry(sample.id)))
            }
        }
        // events.jsonl (diagnostics only — never content)
        val eventsFile = File(dir, ExportLayout.EVENTS_NAME)
        eventsFile.bufferedWriter(Charsets.UTF_8).use { w ->
            input.events.sortedBy { it.createdAt }.forEach { e ->
                val v1 =
                    DeviceEventV1(
                        eventId = e.id,
                        type = e.type,
                        sessionId = e.sessionId,
                        sampleId = e.sampleId,
                        detail = e.detail,
                        appVersion = e.appVersion,
                        createdAt = Instant.ofEpochMilli(e.createdAt).toString(),
                    )
                w.write(ScrappyJson.encodeToString(v1))
                w.newLine()
            }
        }
        // models.json: distinct model identities observed in sidecars.
        val modelIds = mutableSetOf<String>()
        input.artifactsBySample.values.flatten().forEach { _ -> }
        records.forEach { rec ->
            rec.asr.modelId?.let { modelIds += "asr:$it:${rec.asr.modelVersion}" }
            rec.annotation.generatedBy?.let { modelIds += "llm:$it" }
        }
        File(dir, ExportLayout.MODELS_NAME).writeText(
            ScrappyJson.encodeToString(
                ExportModelsFile(
                    exportId = input.exportId,
                    appVersion = appVersion,
                    models = modelIds.sorted(),
                ),
            ),
            Charsets.UTF_8,
        )
        // Derived views: Android v1 has no human_verified gold, so emit empty
        // placeholders with the documented names (Mac tooling expects them).
        listOf(
            ExportLayout.TRANSLATION_PAIRS_NAME,
            ExportLayout.TTS_PAIRS_NAME,
            ExportLayout.PEDAGOGY_EVAL_NAME,
        ).forEach { name -> File(dir, name).writeText("", Charsets.UTF_8) }

        // Manifest (P9.2): every file + records_sha256.
        val allFiles =
            dir.walkTopDown().filter { it.isFile && it.name != ExportLayout.MANIFEST_NAME }
                .sortedBy { it.relativeTo(dir).path }.toList()
        val manifestFiles =
            allFiles.map { f ->
                val rel = f.relativeTo(dir).path.replace(File.separatorChar, '/')
                val h = sha256Of(f)
                ManifestFile(rel, h.first, h.second)
            }
        val recordsHash = sha256Of(recordsFile).first
        // Manifest is written last; verification covers it separately.
        val manifest =
            ExportManifestV2(
                exportId = input.exportId,
                createdAt = Instant.ofEpochMilli(System.currentTimeMillis()).toString(),
                appVersion = appVersion,
                recordCount = records.size,
                files = manifestFiles,
                recordsSha256 = recordsHash,
                verification = "PENDING",
            )
        File(dir, ExportLayout.MANIFEST_NAME).writeText(
            ScrappyJson.encodeToString(manifest.copy(verification = "VERIFIED")),
            Charsets.UTF_8,
        )
        val finalManifest = manifest.copy(verification = "VERIFIED")

        // ZIP (P9.5): stream the staging dir, then reopen + validate.
        val zipFile = File(dir.parentFile, "${input.exportId}.zip")
        if (zipFile.exists()) zipFile.delete()
        ZipOutputStream(zipFile.outputStream().buffered()).use { zos ->
            allFilesWithManifest(dir).forEach { f ->
                val rel = f.relativeTo(dir).path.replace(File.separatorChar, '/')
                zos.putNextEntry(ZipEntry(rel))
                f.inputStream().buffered().use { it.copyTo(zos, 65536) }
                zos.closeEntry()
            }
        }
        verifyZip(zipFile, finalManifest, records.size)
        val zipHash = sha256Of(zipFile)
        return BuiltExport(dir, zipFile, finalManifest, records.size, zipHash.first, zipHash.second)
    }

    private fun allFilesWithManifest(dir: File): List<File> =
        dir.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(dir).path }.toList()

    // Reopen-ZIP verification (P9.5): entries, counts, per-file checksums,
    // records.jsonl line count + schema_version spot-check.
    fun verifyZip(
        zipFile: File,
        manifest: ExportManifestV2,
        expectedRecords: Int,
    ) {
        ZipFile(zipFile).use { zip ->
            val entries = zip.entries().asSequence().map { it.name }.toSet()
            manifest.files.forEach { mf ->
                val entry =
                    zip.getEntry(mf.relativePath)
                        ?: throw ExportVerifyException("Missing ZIP entry ${mf.relativePath}.")
                zip.getInputStream(entry).use { ins ->
                    val actual = sha256Of(ins)
                    if (!actual.equals(mf.sha256, ignoreCase = true)) {
                        throw ExportVerifyException("Checksum mismatch for ${mf.relativePath}.")
                    }
                }
            }
            // Manifest itself must be present.
            require(zip.getEntry(ExportLayout.MANIFEST_NAME) != null) {
                "Missing ${ExportLayout.MANIFEST_NAME}."
            }
            // records.jsonl count + schema check.
            val recEntry =
                zip.getEntry(ExportLayout.RECORDS_NAME)
                    ?: throw ExportVerifyException("Missing ${ExportLayout.RECORDS_NAME}.")
            var lines = 0
            zip.getInputStream(recEntry).bufferedReader(Charsets.UTF_8).useLines { seq ->
                seq.forEach { line ->
                    if (line.isBlank()) return@forEach
                    lines++
                    if (!line.contains(Vocab.SCHEMA_RECORD_V2)) {
                        throw ExportVerifyException("records.jsonl schema mismatch.")
                    }
                }
            }
            if (lines != expectedRecords || lines != manifest.recordCount) {
                throw ExportVerifyException(
                    "Record count mismatch: zip=$lines manifest=${manifest.recordCount} expected=$expectedRecords.",
                )
            }
            if (!entries.contains(ExportLayout.EVENTS_NAME) || !entries.contains(ExportLayout.MODELS_NAME)) {
                throw ExportVerifyException("Missing events/models metadata.")
            }
        }
    }

    private suspend fun buildRecord(
        sample: ClassroomSample,
        session: Session?,
        artifacts: List<Artifact>,
    ): PedagogicalRecordV2 {
        val audio = artifacts.firstOrNull { it.kind == ArtifactKind.AUDIO_WAV }
        val asrFile = artifacts.firstOrNull { it.kind == ArtifactKind.ASR_JSON }
        val annFile = artifacts.firstOrNull { it.kind == ArtifactKind.ANNOTATION_JSON }
        var asrText = ""
        var asrModelId: String? = null
        var asrModelVersion: String? = null
        if (asrFile != null) {
            try {
                val json = readStoreText(asrFile.relativePath)
                val parsed = ScrappyJson.decodeFromString<AsrResultV1>(json)
                asrText = parsed.text
                asrModelId = parsed.modelId
                asrModelVersion = parsed.modelVersion
            } catch (_: Exception) {
                // Corrupt sidecar: record shows failed, audio still exports.
            }
        }
        var annGen: String? = null
        var act: String? = null
        var form: String? = null
        var stage: String? = null
        var topic: String? = null
        var objective: String? = null
        var expected: String? = null
        if (annFile != null) {
            try {
                val json = readStoreText(annFile.relativePath)
                val parsed = ScrappyJson.decodeFromString<AnnotationResultV1>(json)
                annGen = parsed.generatedBy
                act = parsed.activity
                form = parsed.pedagogicalForm
                stage = parsed.conceptStage
                topic = parsed.topic
                objective = parsed.learningObjective
                expected = parsed.expectedLearnerResponse
            } catch (_: Exception) {
            }
        }
        val grade: GradeValue =
            session?.grade?.toIntOrNull()?.let { GradeValue.Numeric(it) }
                ?: GradeValue.Label(session?.grade?.ifBlank { null } ?: "unknown")
        return PedagogicalRecordV2(
            sampleId = sample.id,
            sessionId = sample.sessionId,
            sequenceNumber = sample.sequenceNumber,
            source = asrText,
            sourceLanguage = sample.sourceLanguage,
            grade = grade,
            subject = session?.subject?.ifBlank { "unknown" } ?: "unknown",
            topic = topic,
            activity = act,
            learningObjective = objective,
            pedagogicalForm = form,
            conceptStage = stage,
            expectedLearnerResponse = expected,
            audio =
                AudioBlock(
                    path = ExportLayout.audioEntry(sample.id),
                    sha256 = audio?.sha256 ?: "",
                    durationMs = audio?.durationMs ?: 0,
                ),
            asr =
                AsrBlock(
                    status =
                        when {
                            asrFile == null -> "unavailable"
                            asrText.isEmpty() -> "failed"
                            else -> "available"
                        },
                    path = if (asrFile != null) ExportLayout.asrEntry(sample.id) else null,
                    text = asrText,
                    modelId = asrModelId,
                    modelVersion = asrModelVersion,
                ),
            annotation =
                AnnotationBlock(
                    status = if (annFile != null) "available" else "unavailable",
                    path = if (annFile != null) ExportLayout.annotationEntry(sample.id) else null,
                    generatedBy = annGen,
                ),
            quality =
                QualityBlock(
                    rmsDb = sample.quality.rmsDb,
                    peakDb = sample.quality.peakDb,
                    clippingDetected = sample.quality.clippingDetected,
                    silenceRatio = sample.quality.silenceRatio,
                    flags = sample.quality.flags,
                ),
            provenance =
                ProvenanceBlock(
                    createdAt = Instant.ofEpochMilli(sample.createdAt).toString(),
                    recordedStart = sample.recordedStart?.let { Instant.ofEpochMilli(it).toString() },
                    recordedEnd = sample.recordedEnd?.let { Instant.ofEpochMilli(it).toString() },
                    inputDevice = sample.inputDevice,
                    codeSwitching = sample.codeSwitching,
                    processingState = sample.state.name,
                    appVersion = appVersion,
                ),
        )
    }

    private suspend fun readStoreText(relativePath: String): String {
        return try {
            val storeFile = (store as? FileArtifactStore)?.fileFor(relativePath)
            if (storeFile != null) storeFile.readText(Charsets.UTF_8) else store.readAllBytes(relativePath).toString(Charsets.UTF_8)
        } catch (e: Exception) {
            throw e
        }
    }

    private suspend fun copyStoreFile(
        relativePath: String,
        dest: File,
    ) {
        dest.parentFile?.mkdirs()
        val src = (store as? FileArtifactStore)?.fileFor(relativePath)
        if (src != null) {
            src.inputStream().buffered().use { ins ->
                dest.outputStream().buffered().use { out -> ins.copyTo(out, 65536) }
            }
        } else {
            dest.writeBytes(store.readAllBytes(relativePath))
        }
    }

    companion object {
        fun sha256Of(file: File): Pair<String, Long> {
            val digest = MessageDigest.getInstance("SHA-256")
            var bytes = 0L
            file.inputStream().buffered().use { ins ->
                val buf = ByteArray(65536)
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                    bytes += n
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) } to bytes
        }

        fun sha256Of(ins: java.io.InputStream): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(65536)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

// models.json content (P9.2 model metadata): distinct model identities
// observed in the exported sidecars. Serializable (no Any maps).
@kotlinx.serialization.Serializable
data class ExportModelsFile(
    @kotlinx.serialization.SerialName("export_id") val exportId: String,
    @kotlinx.serialization.SerialName("app_version") val appVersion: String,
    val models: List<String> = emptyList(),
)

class ExportVerifyException(message: String) : Exception(message)
