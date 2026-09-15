package com.scraper.classroomcapture.ui

import com.scraper.classroomcapture.domain.model.ClassroomSample
import com.scraper.classroomcapture.domain.model.SampleState

// Session form validation (P8.1, D9). Pseudocodes only, no PII. Pure logic,
// fully unit-tested; Compose binds to it.
object SessionFormValidator {
    val LANGUAGES = setOf("hi", "en", "mr")
    private val CODE_RE = Regex("[A-Za-z0-9_\\-]{1,32}")

    data class Result(val errors: Map<String, String>) {
        val isValid: Boolean get() = errors.isEmpty()
    }

    fun validate(
        grade: String,
        subject: String,
        teacherCode: String,
        schoolCode: String,
        defaultLanguage: String,
        consentAck: Boolean,
    ): Result {
        val errors = mutableMapOf<String, String>()
        if (grade.isBlank()) errors["grade"] = "Grade is required."
        if (subject.isBlank()) errors["subject"] = "Subject is required."
        if (teacherCode.isBlank()) {
            errors["teacherCode"] = "Teacher code is required."
        } else if (!CODE_RE.matches(teacherCode)) {
            errors["teacherCode"] = "Letters, digits, _ or - only."
        }
        if (schoolCode.isBlank()) {
            errors["schoolCode"] = "School code is required."
        } else if (!CODE_RE.matches(schoolCode)) {
            errors["schoolCode"] = "Letters, digits, _ or - only."
        }
        if (defaultLanguage !in LANGUAGES) errors["defaultLanguage"] = "Choose hi, en, or mr."
        if (!consentAck) errors["consentAck"] = "Operator acknowledgement is required."
        return Result(errors)
    }
}

// Per-session counts for Home/Summary/Recording (P8.2–P8.3). Derived from
// sample rows only — never from in-memory counters.
data class SessionSummary(
    val total: Int,
    val recorded: Int,
    val pending: Int,
    val failed: Int,
    val recovered: Int,
    val ready: Int,
) {
    companion object {
        fun from(samples: List<ClassroomSample>): SessionSummary {
            var pending = 0
            var failed = 0
            var recovered = 0
            var ready = 0
            samples.forEach {
                when (it.state) {
                    SampleState.QUEUED_ASR, SampleState.TRANSCRIBING,
                    SampleState.QUEUED_LLM, SampleState.ANNOTATING,
                    -> pending++
                    SampleState.ERROR -> failed++
                    SampleState.RECOVERED -> recovered++
                    SampleState.READY_FOR_EXPORT, SampleState.EXPORTED,
                    SampleState.TRANSCRIBED, SampleState.ANNOTATED,
                    -> ready++
                    else -> Unit
                }
            }
            return SessionSummary(
                total = samples.size,
                recorded = samples.size,
                pending = pending,
                failed = failed,
                recovered = recovered,
                ready = ready,
            )
        }
    }
}

// Recovery report for the P8.4 startup flow: resumable session + incomplete
// samples. Built from DB rows + reconciler event text (no filesystem access).
data class RecoveryReport(
    val recoveredSampleIds: List<String>,
    val errorSampleIds: List<String>,
    val resumableSessionId: String?,
) {
    val needsAttention: Boolean get() = recoveredSampleIds.isNotEmpty() || errorSampleIds.isNotEmpty()

    companion object {
        fun from(
            samples: List<ClassroomSample>,
            activeSessionId: String?,
        ): RecoveryReport {
            val recovered = samples.filter { it.state == SampleState.RECOVERED }.map { it.id }
            val errors = samples.filter { it.state == SampleState.ERROR }.map { it.id }
            val resumable =
                activeSessionId
                    ?: samples.firstOrNull { it.state != SampleState.EXPORTED }?.sessionId
            return RecoveryReport(recovered, errors, resumable)
        }
    }
}

// Active-session holder (P8). Single operator flow: one open session at a
// time. Set on create/resume, cleared on close. In-memory only — the DB is
// the source of truth; this is just navigation state.
object ActiveSession {
    @Volatile
    var id: String? = null
}

// Elapsed capture-time formatter (P8.2). Monotonic ms → H:MM:SS / M:SS.
// Pure logic, unit-tested; the timer never uses wall-clock adjustments.
fun formatElapsedMs(elapsedMs: Long): String {
    val totalSec = (elapsedMs.coerceAtLeast(0) / 1000).toInt()
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
