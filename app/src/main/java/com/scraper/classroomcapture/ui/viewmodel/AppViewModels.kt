package com.scraper.classroomcapture.ui.viewmodel

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// P1 lifecycle-safe ViewModel scaffolding. Real state (sessions, samples,
// queue counts) arrives in P2/P3/P6. Each screen gets its own VM so state
// survives rotation/process recreation via SavedStateHandle later.

open class StubViewModel(label: String) : ViewModel() {
    private val _status = MutableStateFlow(label)
    val status: StateFlow<String> = _status.asStateFlow()
}

class HomeViewModel : StubViewModel("home-idle")

class NewSessionViewModel : StubViewModel("new-session-idle") {
    // P2: grade/subject/teacher+school pseudocodes, default hi|en|mr, notes, consent ack.
    var grade: String = ""
    var subject: String = ""
    var teacherCode: String = ""
    var schoolCode: String = ""
    var defaultLanguage: String = "hi"
    var notes: String = ""
    var consentAck: Boolean = false
}

class RecordingViewModel : StubViewModel("recording-idle")

class SummaryViewModel : StubViewModel("summary-idle")

class ExportViewModel : StubViewModel("export-idle")

class RecoveryViewModel : StubViewModel("recovery-idle")

class ErrorsViewModel : StubViewModel("errors-idle")

class DiagnosticsViewModel : StubViewModel("diagnostics-idle")
