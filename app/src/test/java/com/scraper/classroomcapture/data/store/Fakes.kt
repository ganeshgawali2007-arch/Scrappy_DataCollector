package com.scraper.classroomcapture.data.store

import com.scraper.classroomcapture.data.repository.ArtifactRepository
import com.scraper.classroomcapture.data.repository.SampleRepository
import com.scraper.classroomcapture.data.repository.SessionRepository
import com.scraper.classroomcapture.domain.model.Artifact
import com.scraper.classroomcapture.domain.model.ArtifactKind
import com.scraper.classroomcapture.domain.model.ClassroomSample
import com.scraper.classroomcapture.domain.model.ProcessingJob
import com.scraper.classroomcapture.domain.model.SampleState
import com.scraper.classroomcapture.domain.model.Session
import com.scraper.classroomcapture.domain.state.SampleStateTransitions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

// In-memory fakes honoring the same contracts as the Room implementations
// (P3.8). Transition methods apply SampleStateTransitions, so tests exercise
// reconciler orchestration against real validator semantics.
class FakeSessionRepository : SessionRepository {
    val sessions = mutableMapOf<String, Session>()

    override suspend fun create(session: Session) {
        sessions[session.id] = session
    }

    override suspend fun get(id: String): Session? = sessions[id]

    override fun observe(id: String): Flow<Session?> = MutableStateFlow(sessions[id])

    override fun observeAll(): Flow<List<Session>> = MutableStateFlow(sessions.values.toList())

    override suspend fun close(
        id: String,
        closedAt: Long,
    ) {
        sessions[id]?.let { sessions[id] = it.copy(closedAt = closedAt, updatedAt = closedAt) }
    }
}

class FakeSampleRepository : SampleRepository {
    val samples = mutableMapOf<String, ClassroomSample>()
    val jobs = mutableMapOf<String, MutableList<ProcessingJob>>()
    var writes = 0

    override suspend fun createSample(
        sample: ClassroomSample,
        jobs: List<ProcessingJob>,
    ) {
        writes++
        samples[sample.id] = sample
        this.jobs[sample.id] = jobs.toMutableList()
    }

    override fun observe(id: String): Flow<ClassroomSample?> = MutableStateFlow(samples[id])

    override fun observeBySession(sessionId: String): Flow<List<ClassroomSample>> =
        MutableStateFlow(samples.values.filter { it.sessionId == sessionId })

    override suspend fun get(id: String): ClassroomSample? = samples[id]

    override suspend fun getByStates(states: List<SampleState>): List<ClassroomSample> = samples.values.filter { it.state in states }

    override suspend fun nextSequenceNumber(sessionId: String): Int =
        (samples.values.filter { it.sessionId == sessionId }.maxOfOrNull { it.sequenceNumber } ?: 0) + 1

    override suspend fun transition(
        id: String,
        to: SampleState,
        now: Long,
    ): Boolean {
        val current = samples[id] ?: return false
        if (!SampleStateTransitions.isLegal(current.state, to)) return false
        writes++
        samples[id] = current.copy(state = to, updatedAt = now)
        return true
    }

    override suspend fun markError(
        id: String,
        code: String,
        message: String,
        retryable: Boolean,
        now: Long,
    ): Boolean {
        val current = samples[id] ?: return false
        if (!SampleStateTransitions.isLegal(current.state, SampleState.ERROR)) return false
        writes++
        samples[id] =
            current.copy(
                state = SampleState.ERROR,
                errorCode = code,
                errorMessage = message,
                errorRetryable = retryable,
                updatedAt = now,
            )
        return true
    }

    override suspend fun reconcileToRecovered(
        id: String,
        reason: String,
        now: Long,
    ): Boolean {
        val current = samples[id] ?: return false
        if (!SampleStateTransitions.canReconcileToRecovered(current.state)) return false
        writes++
        samples[id] =
            current.copy(
                state = SampleState.RECOVERED,
                recoveryReason = reason,
                recoveredAt = now,
                priorState = current.state,
                updatedAt = now,
            )
        return true
    }
}

class FakeArtifactRepository : ArtifactRepository {
    val artifacts = mutableListOf<Artifact>()
    var writes = 0

    override suspend fun add(artifact: Artifact) {
        writes++
        artifacts += artifact
    }

    override fun observeBySample(sampleId: String): Flow<List<Artifact>> = MutableStateFlow(artifacts.filter { it.sampleId == sampleId })

    override suspend fun getBySampleAndKind(
        sampleId: String,
        kind: ArtifactKind,
    ): Artifact? = artifacts.firstOrNull { it.sampleId == sampleId && it.kind == kind }
}
