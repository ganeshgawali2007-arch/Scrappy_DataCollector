package com.scraper.classroomcapture.data.repository

import com.scraper.classroomcapture.data.local.ArtifactDao
import com.scraper.classroomcapture.data.local.DeviceEventDao
import com.scraper.classroomcapture.data.local.ExportDao
import com.scraper.classroomcapture.data.local.ProcessingJobDao
import com.scraper.classroomcapture.data.local.SampleDao
import com.scraper.classroomcapture.data.local.SessionDao
import com.scraper.classroomcapture.domain.model.Artifact
import com.scraper.classroomcapture.domain.model.ArtifactKind
import com.scraper.classroomcapture.domain.model.ClassroomSample
import com.scraper.classroomcapture.domain.model.DeviceEvent
import com.scraper.classroomcapture.domain.model.ExportRecord
import com.scraper.classroomcapture.domain.model.ExportStatus
import com.scraper.classroomcapture.domain.model.ExportVerification
import com.scraper.classroomcapture.domain.model.JobKind
import com.scraper.classroomcapture.domain.model.JobState
import com.scraper.classroomcapture.domain.model.ProcessingJob
import com.scraper.classroomcapture.domain.model.SampleState
import com.scraper.classroomcapture.domain.model.Session
import com.scraper.classroomcapture.domain.state.SampleStateTransitions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// Repository contracts (P2.6). Suspend + Flow APIs; UI and workers depend on
// these, never on Room. State changes on samples go through transition() so
// the D14 validator is enforced at the persistence boundary.
interface SessionRepository {
    suspend fun create(session: Session)

    fun observe(id: String): Flow<Session?>

    fun observeAll(): Flow<List<Session>>

    suspend fun close(
        id: String,
        closedAt: Long,
    )
}

interface SampleRepository {
    suspend fun createSample(
        sample: ClassroomSample,
        jobs: List<ProcessingJob>,
    )

    fun observe(id: String): Flow<ClassroomSample?>

    fun observeBySession(sessionId: String): Flow<List<ClassroomSample>>

    suspend fun get(id: String): ClassroomSample?

    suspend fun nextSequenceNumber(sessionId: String): Int

    // Validated transition; false + no write when illegal.
    suspend fun transition(
        id: String,
        to: SampleState,
        now: Long,
    ): Boolean

    suspend fun markError(
        id: String,
        code: String,
        message: String,
        retryable: Boolean,
        now: Long,
    ): Boolean

    // Reconciliation-only entry to RECOVERED (P3); false when not allowed.
    suspend fun reconcileToRecovered(
        id: String,
        reason: String,
        now: Long,
    ): Boolean
}

interface ArtifactRepository {
    suspend fun add(artifact: Artifact)

    fun observeBySample(sampleId: String): Flow<List<Artifact>>

    suspend fun getBySampleAndKind(
        sampleId: String,
        kind: ArtifactKind,
    ): Artifact?
}

interface JobRepository {
    suspend fun enqueue(job: ProcessingJob)

    fun observeBySample(sampleId: String): Flow<List<ProcessingJob>>

    suspend fun claimable(
        kind: JobKind,
        now: Long,
        limit: Int,
    ): List<ProcessingJob>

    // True only when this caller won the claim (single-row conditional update).
    suspend fun claim(
        id: String,
        owner: String,
        leaseMs: Long,
        now: Long,
    ): Boolean

    suspend fun heartbeat(
        id: String,
        now: Long,
        leaseMs: Long,
    )

    suspend fun finish(
        job: ProcessingJob,
        now: Long,
    )
}

interface EventRepository {
    suspend fun log(event: DeviceEvent)

    fun observeRecent(limit: Int): Flow<List<DeviceEvent>>
}

interface ExportRepository {
    suspend fun start(
        sessionIds: List<String>,
        now: Long,
        id: String,
    ): ExportRecord

    suspend fun markFinished(
        id: String,
        status: ExportStatus,
        verification: ExportVerification?,
        outputPath: String?,
        checksum: String?,
        byteSize: Long?,
        now: Long,
    )

    suspend fun get(id: String): ExportRecord?

    fun observeAll(): Flow<List<ExportRecord>>
}

class RoomSessionRepository(private val dao: SessionDao) : SessionRepository {
    override suspend fun create(session: Session) {
        dao.insert(session.toEntity())
    }

    override fun observe(id: String): Flow<Session?> = dao.observeById(id).map { it?.toDomain() }

    override fun observeAll(): Flow<List<Session>> = dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override suspend fun close(
        id: String,
        closedAt: Long,
    ) {
        val current = dao.getById(id) ?: return
        dao.update(current.copy(closedAt = closedAt, updatedAt = closedAt))
    }
}

class RoomSampleRepository(private val dao: SampleDao) : SampleRepository {
    override suspend fun createSample(
        sample: ClassroomSample,
        jobs: List<ProcessingJob>,
    ) {
        dao.insertNewSample(sample.toEntity(), jobs.map { it.toEntity() })
    }

    override fun observe(id: String): Flow<ClassroomSample?> = dao.observeById(id).map { it?.toDomain() }

    override fun observeBySession(sessionId: String): Flow<List<ClassroomSample>> =
        dao.observeBySession(sessionId).map { rows -> rows.map { it.toDomain() } }

    override suspend fun get(id: String): ClassroomSample? = dao.getById(id)?.toDomain()

    override suspend fun nextSequenceNumber(sessionId: String): Int = dao.nextSequenceNumber(sessionId)

    override suspend fun transition(
        id: String,
        to: SampleState,
        now: Long,
    ): Boolean {
        val current = dao.getById(id) ?: return false
        if (!SampleStateTransitions.isLegal(current.state, to)) return false
        dao.updateState(id, to, now)
        return true
    }

    override suspend fun markError(
        id: String,
        code: String,
        message: String,
        retryable: Boolean,
        now: Long,
    ): Boolean {
        val current = dao.getById(id) ?: return false
        if (!SampleStateTransitions.isLegal(current.state, SampleState.ERROR)) return false
        dao.markError(id, SampleState.ERROR, code, message, retryable, now)
        return true
    }

    override suspend fun reconcileToRecovered(
        id: String,
        reason: String,
        now: Long,
    ): Boolean {
        val current = dao.getById(id) ?: return false
        if (!SampleStateTransitions.canReconcileToRecovered(current.state)) return false
        dao.markRecovered(id, SampleState.RECOVERED, reason, now, current.state, now)
        return true
    }
}

class RoomArtifactRepository(private val dao: ArtifactDao) : ArtifactRepository {
    override suspend fun add(artifact: Artifact) {
        dao.insert(artifact.toEntity())
    }

    override fun observeBySample(sampleId: String): Flow<List<Artifact>> =
        dao.observeBySample(sampleId).map { rows -> rows.map { it.toDomain() } }

    override suspend fun getBySampleAndKind(
        sampleId: String,
        kind: ArtifactKind,
    ): Artifact? = dao.getBySampleAndKind(sampleId, kind)?.toDomain()
}

class RoomJobRepository(private val dao: ProcessingJobDao) : JobRepository {
    override suspend fun enqueue(job: ProcessingJob) {
        dao.insert(job.toEntity())
    }

    override fun observeBySample(sampleId: String): Flow<List<ProcessingJob>> =
        dao.observeBySample(sampleId).map { rows -> rows.map { it.toDomain() } }

    override suspend fun claimable(
        kind: JobKind,
        now: Long,
        limit: Int,
    ): List<ProcessingJob> = dao.getClaimable(kind, now, limit).map { it.toDomain() }

    override suspend fun claim(
        id: String,
        owner: String,
        leaseMs: Long,
        now: Long,
    ): Boolean = dao.claim(id, JobState.RUNNING, owner, now + leaseMs, now, now) == 1

    override suspend fun heartbeat(
        id: String,
        now: Long,
        leaseMs: Long,
    ) {
        dao.heartbeat(id, now, now + leaseMs)
    }

    override suspend fun finish(
        job: ProcessingJob,
        now: Long,
    ) {
        dao.update(job.copy(updatedAt = now).toEntity())
    }
}

class RoomEventRepository(private val dao: DeviceEventDao) : EventRepository {
    override suspend fun log(event: DeviceEvent) {
        dao.insert(event.toEntity())
    }

    override fun observeRecent(limit: Int): Flow<List<DeviceEvent>> = dao.observeRecent(limit).map { rows -> rows.map { it.toDomain() } }
}

class RoomExportRepository(private val dao: ExportDao) : ExportRepository {
    override suspend fun start(
        sessionIds: List<String>,
        now: Long,
        id: String,
    ): ExportRecord {
        val record =
            ExportRecord(
                id = id,
                sessionIds = sessionIds,
                status = ExportStatus.PREPARING,
                verification = null,
                outputPath = null,
                checksum = null,
                byteSize = null,
                createdAt = now,
                completedAt = null,
            )
        dao.insertExportWithSessions(record.toEntity(), sessionIds)
        return record
    }

    override suspend fun markFinished(
        id: String,
        status: ExportStatus,
        verification: ExportVerification?,
        outputPath: String?,
        checksum: String?,
        byteSize: Long?,
        now: Long,
    ) {
        dao.markFinished(id, status, verification, outputPath, checksum, byteSize, now)
    }

    override suspend fun get(id: String): ExportRecord? {
        val row = dao.getById(id) ?: return null
        return row.toDomain(dao.getSessionIds(id))
    }

    override fun observeAll(): Flow<List<ExportRecord>> =
        dao.observeAllWithSessions().map { rows ->
            rows.map { it.record.toDomain(it.sessionIds) }
        }
}
