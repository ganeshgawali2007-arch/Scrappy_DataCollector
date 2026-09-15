package com.scraper.classroomcapture.processing

import com.scraper.classroomcapture.data.repository.EventRepository
import com.scraper.classroomcapture.data.repository.JobRepository
import com.scraper.classroomcapture.domain.model.DeviceEvent
import com.scraper.classroomcapture.domain.model.JobKind
import com.scraper.classroomcapture.domain.model.JobState
import com.scraper.classroomcapture.domain.model.ProcessingJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

// In-memory JobRepository honoring Room semantics (P6.8 tests): unique
// (sample, kind), conditional claim, lease-gated claimable sets.
class FakeJobRepository : JobRepository {
    val jobs = mutableMapOf<String, ProcessingJob>()

    override suspend fun enqueue(job: ProcessingJob) {
        if (jobs.values.any { it.sampleId == job.sampleId && it.kind == job.kind }) {
            throw IllegalStateException("duplicate job for ${job.sampleId}/${job.kind}")
        }
        jobs[job.id] = job
    }

    override fun observeBySample(sampleId: String): Flow<List<ProcessingJob>> =
        MutableStateFlow(jobs.values.filter { it.sampleId == sampleId })

    override suspend fun claimable(
        kind: JobKind,
        now: Long,
        limit: Int,
    ): List<ProcessingJob> =
        jobs.values
            .filter {
                it.kind == kind && it.state == JobState.QUEUED &&
                    (it.nextAttemptAt == null || it.nextAttemptAt <= now) &&
                    (it.leaseExpiresAt == null || it.leaseExpiresAt <= now)
            }
            .sortedBy { it.nextAttemptAt }
            .take(limit)

    override suspend fun claim(
        id: String,
        owner: String,
        leaseMs: Long,
        now: Long,
    ): Boolean {
        val current = jobs[id] ?: return false
        if (current.state != JobState.QUEUED) return false
        jobs[id] =
            current.copy(
                state = JobState.RUNNING,
                leaseOwner = owner,
                leaseExpiresAt = now + leaseMs,
                lastHeartbeatAt = now,
                attemptCount = current.attemptCount + 1,
                updatedAt = now,
            )
        return true
    }

    override suspend fun heartbeat(
        id: String,
        now: Long,
        leaseMs: Long,
    ) {
        jobs[id]?.let {
            jobs[id] = it.copy(lastHeartbeatAt = now, leaseExpiresAt = now + leaseMs, updatedAt = now)
        }
    }

    override suspend fun finish(
        job: ProcessingJob,
        now: Long,
    ) {
        jobs[job.id] = job.copy(updatedAt = now)
    }

    override suspend fun getBySampleAndKind(
        sampleId: String,
        kind: JobKind,
    ): ProcessingJob? = jobs.values.firstOrNull { it.sampleId == sampleId && it.kind == kind }

    override suspend fun requeue(
        id: String,
        now: Long,
    ) {
        jobs[id]?.let {
            jobs[id] = it.copy(state = JobState.QUEUED, leaseOwner = null, leaseExpiresAt = null, updatedAt = now)
        }
    }

    override suspend fun getExpiredLeases(
        kind: JobKind,
        now: Long,
    ): List<ProcessingJob> =
        jobs.values.filter {
            it.kind == kind && it.state == JobState.RUNNING && (it.leaseExpiresAt ?: Long.MAX_VALUE) <= now
        }

    override suspend fun countByKindAndStates(
        kind: JobKind,
        states: List<JobState>,
    ): Int = jobs.values.count { it.kind == kind && it.state in states }
}

class FakeEventRepository : EventRepository {
    val events = mutableListOf<DeviceEvent>()

    override suspend fun log(event: DeviceEvent) {
        events += event
    }

    override fun observeRecent(limit: Int): Flow<List<DeviceEvent>> = MutableStateFlow(events.takeLast(limit))
}
