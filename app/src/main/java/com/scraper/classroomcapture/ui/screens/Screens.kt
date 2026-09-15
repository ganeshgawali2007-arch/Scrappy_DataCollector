package com.scraper.classroomcapture.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.scraper.classroomcapture.R
import com.scraper.classroomcapture.ScraperApp
import com.scraper.classroomcapture.domain.model.ClassroomSample
import com.scraper.classroomcapture.domain.model.Session
import com.scraper.classroomcapture.permissions.MicPermissionState
import com.scraper.classroomcapture.permissions.RequestNotificationsIfNeeded
import com.scraper.classroomcapture.permissions.rememberMicPermission
import com.scraper.classroomcapture.recording.RecordingStatus
import com.scraper.classroomcapture.ui.ActiveSession
import com.scraper.classroomcapture.ui.RecoveryReport
import com.scraper.classroomcapture.ui.Routes
import com.scraper.classroomcapture.ui.ScreenScaffold
import com.scraper.classroomcapture.ui.formatElapsedMs
import com.scraper.classroomcapture.ui.viewmodel.DiagnosticsViewModel
import com.scraper.classroomcapture.ui.viewmodel.ErrorsViewModel
import com.scraper.classroomcapture.ui.viewmodel.ExportViewModel
import com.scraper.classroomcapture.ui.viewmodel.HomeViewModel
import com.scraper.classroomcapture.ui.viewmodel.NewSessionViewModel
import com.scraper.classroomcapture.ui.viewmodel.RecordingViewModel
import com.scraper.classroomcapture.ui.viewmodel.RecoveryViewModel
import com.scraper.classroomcapture.ui.viewmodel.SummaryViewModel
import kotlinx.coroutines.launch

@Composable
private fun factory() = (LocalContext.current.applicationContext as ScraperApp).container.viewModelFactory()

@Composable
fun HomeScreen(
    onNavigate: (String) -> Unit,
    vm: HomeViewModel = viewModel(factory = factory()),
) {
    val sessions by vm.sessionList.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var counts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    LaunchedEffect(Unit) {
        scope.launch { counts = vm.queueCounts() }
    }
    RequestNotificationsIfNeeded()
    val modelMissing = remember { vm.isModelMissing() }
    val lowStorage = remember { vm.storageLow() }
    val status =
        "sessions=${sessions.size} " +
            "pendingAsr=${counts["pendingAsr"] ?: 0} failed=${(counts["failedAsr"] ?: 0) + (counts["failedLlm"] ?: 0)}"
    ScreenScaffold(
        titleRes = R.string.nav_home,
        status = stringResource(R.string.status_prefix, status),
        onNavigate = onNavigate,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (modelMissing) {
                Text("ASR model not installed — recording still works. Install via file picker (see Diagnostics).")
            }
            if (lowStorage) {
                Text("Storage low — free space before recording. Completed samples are preserved.")
            }
            // P8.1: resumable session ahead of the new-session action.
            val resumable = ActiveSession.id
            if (resumable != null) {
                Button(
                    onClick = { onNavigate(Routes.RECORDING) },
                    modifier =
                        Modifier.fillMaxWidth().heightIn(min = 56.dp)
                            .semantics { contentDescription = "Continue session" },
                ) {
                    Text("Continue session")
                }
            }
            Button(
                onClick = { onNavigate(Routes.NEW_SESSION) },
                modifier =
                    Modifier.fillMaxWidth().heightIn(min = 56.dp)
                        .semantics { contentDescription = "New session" },
            ) {
                Text(stringResource(R.string.action_new_session))
            }
            sessions.takeLast(5).reversed().forEach { s: Session ->
                Button(
                    onClick = {
                        ActiveSession.id = s.id
                        onNavigate(Routes.RECORDING)
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Text("${s.grade} ${s.subject} (${s.defaultLanguage})")
                }
            }
        }
    }
}

@Composable
fun NewSessionScreen(
    onNavigate: (String) -> Unit,
    vm: NewSessionViewModel = viewModel(factory = factory()),
) {
    var grade by remember { mutableStateOf(vm.grade) }
    var subject by remember { mutableStateOf(vm.subject) }
    var teacher by remember { mutableStateOf(vm.teacherCode) }
    var school by remember { mutableStateOf(vm.schoolCode) }
    var language by remember { mutableStateOf(vm.defaultLanguage) }
    var notes by remember { mutableStateOf(vm.notes) }
    var consent by remember { mutableStateOf(vm.consentAck) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    ScreenScaffold(
        titleRes = R.string.nav_new_session,
        status = stringResource(R.string.new_session_hint),
        onNavigate = onNavigate,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SessionField(R.string.field_grade, grade) {
                grade = it
                vm.grade = it
            }
            SessionField(R.string.field_subject, subject) {
                subject = it
                vm.subject = it
            }
            SessionField(R.string.field_teacher, teacher) {
                teacher = it
                vm.teacherCode = it
            }
            SessionField(R.string.field_school, school) {
                school = it
                vm.schoolCode = it
            }
            LanguageRow(selected = language) {
                language = it
                vm.defaultLanguage = it
            }
            OutlinedTextField(
                value = notes,
                onValueChange = {
                    notes = it
                    vm.notes = it
                },
                label = { Text("Notes (optional, no names)") },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = consent,
                    onCheckedChange = {
                        consent = it
                        vm.consentAck = it
                    },
                    modifier = Modifier.semantics { contentDescription = "Operator acknowledgement" },
                )
                Text("Operator confirms consent/authorization (required)")
            }
            error?.let { Text("Error: $it") }
            Button(
                onClick = {
                    scope.launch {
                        try {
                            vm.create()
                            onNavigate(Routes.RECORDING)
                        } catch (e: IllegalArgumentException) {
                            error = e.message
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) {
                Text("Create and start recording")
            }
        }
    }
}

@Composable
private fun SessionField(
    labelRes: Int,
    value: String,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(labelRes)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun LanguageRow(
    selected: String,
    onSelect: (String) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("hi" to R.string.lang_hindi, "en" to R.string.lang_english, "mr" to R.string.lang_marathi).forEach { (code, label) ->
            Button(
                onClick = { onSelect(code) },
                enabled = selected != code,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            ) {
                Text(stringResource(label))
            }
        }
    }
}

@Composable
fun RecordingScreen(
    onNavigate: (String) -> Unit,
    vm: RecordingViewModel = viewModel(factory = factory()),
) {
    val status by vm.status.collectAsState()
    val rec by vm.recording.collectAsState()
    val lang by vm.language.collectAsState()
    val sessionId = ActiveSession.id
    val (mic, requestMic) = rememberMicPermission()
    val samples by vm.samplesIn(sessionId ?: "").collectAsState(initial = emptyList())
    val summary = remember(samples) { vm.summary(samples) }
    val isRecording = rec.phase == RecordingStatus.Phase.RECORDING
    ScreenScaffold(
        titleRes = R.string.nav_recording,
        status = stringResource(R.string.status_prefix, status),
        onNavigate = onNavigate,
        navEnabled = !isRecording,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (sessionId == null) {
                Text("No active session — create one first.")
                Button(
                    onClick = { onNavigate(Routes.NEW_SESSION) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                ) {
                    Text(stringResource(R.string.action_new_session))
                }
                return@Column
            }
            Text(
                when (mic) {
                    MicPermissionState.GRANTED -> stringResource(R.string.mic_granted)
                    MicPermissionState.DENIED -> stringResource(R.string.mic_denied)
                    MicPermissionState.UNKNOWN -> stringResource(R.string.mic_unknown)
                },
            )
            if (mic != MicPermissionState.GRANTED) {
                Button(
                    onClick = requestMic,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                ) {
                    Text(stringResource(R.string.action_request_mic))
                }
            }
            Text("Language (sticky per sample):")
            LanguageRow(selected = lang, onSelect = vm::setLanguage)
            // P8.2 verified live input + preference chooser (never claims
            // an unverified route as active).
            val inputs = remember(isRecording) { vm.availableInputs() }
            val preferred = remember(isRecording) { vm.preferredInputId() }
            Text("Mic (verified): ${vm.verifiedInput()} · Saved: ${summary.total} · Pending: ${summary.pending}")
            if (inputs.size > 1 && !isRecording) {
                Text("Microphone preference:")
                inputs.forEach { input ->
                    Button(
                        onClick = { vm.setPreferredInput(input.id) },
                        enabled = preferred != input.id,
                        modifier =
                            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .semantics { contentDescription = "Use microphone ${input.name}" },
                    ) {
                        Text(if (preferred == input.id) "✓ ${input.name}" else input.name)
                    }
                }
            }
            // P8.2 elapsed capture-time timer + modest level meter. Timer is
            // capture time (service monotonic clock), not wall clock.
            Text(
                text = formatElapsedMs(rec.elapsedMs),
                style = androidx.compose.material3.MaterialTheme.typography.displaySmall,
                modifier = Modifier.semantics { contentDescription = "Recording elapsed time ${formatElapsedMs(rec.elapsedMs)}" },
            )
            rec.rmsDb?.let { rms -> Text("Level: ${"%.1f".format(rms)} dB") }
            if (rec.clippingWarning) Text("Warning: sustained clipping — check microphone distance.")
            rec.errorCode?.let { Text("Capture error: $it (completed samples preserved)") }
            if (isRecording) Text("Recording — navigation locked until you stop this segment.")
            // Large start/stop (P8.2, P8.6: 72dp target, low-distraction single action).
            if (!isRecording) {
                Button(
                    onClick = { vm.start(sessionId) },
                    enabled = mic == MicPermissionState.GRANTED,
                    modifier =
                        Modifier.fillMaxWidth().heightIn(min = 72.dp)
                            .semantics { contentDescription = "Start recording segment in $lang" },
                ) {
                    Text("Start segment ($lang)")
                }
            } else {
                Button(
                    onClick = vm::stopSegment,
                    modifier =
                        Modifier.fillMaxWidth().heightIn(min = 72.dp)
                            .semantics { contentDescription = "Stop segment and save" },
                ) {
                    Text("Stop — save and start next ($lang)")
                }
                Button(
                    onClick = vm::stop,
                    modifier =
                        Modifier.fillMaxWidth().heightIn(min = 56.dp)
                            .semantics { contentDescription = "Finish session" },
                ) {
                    Text("Finish session")
                }
            }
        }
    }
}

@Composable
fun SummaryScreen(
    onNavigate: (String) -> Unit,
    vm: SummaryViewModel = viewModel(factory = factory()),
) {
    val sessionId = ActiveSession.id
    val samples by vm.observe(sessionId ?: "").collectAsState(initial = emptyList())
    val summary = remember(samples) { vm.summary(samples) }
    ScreenScaffold(
        titleRes = R.string.nav_summary,
        status = "total=${summary.total} ready=${summary.ready} pending=${summary.pending} failed=${summary.failed}",
        onNavigate = onNavigate,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (sessionId == null) {
                Text("No active session.")
                return@Column
            }
            // P8.3 most prominent: saved samples + total duration proxy
            // (counts from DB rows only). Processing rows are compact and
            // never block export; failures show retry guidance.
            Text(
                "${summary.total} saved · ${summary.ready} ready · ${summary.pending} awaiting transcription",
                modifier = Modifier.semantics { contentDescription = "Session progress summary" },
            )
            if (summary.failed > 0) {
                Text("Audio saved · Transcription unavailable for ${summary.failed} (see Errors → Retry). Export still works.")
            }
            if (summary.recovered > 0) {
                Text("${summary.recovered} recovered sample(s) — review in Recovery.")
            }
            samples.takeLast(20).reversed().forEach { s: ClassroomSample ->
                Text("#${s.sequenceNumber} ${s.sourceLanguage} ${s.state}" + (s.errorCode?.let { " ($it)" } ?: ""))
            }
        }
    }
}

@Composable
fun ExportScreen(
    onNavigate: (String) -> Unit,
    vm: ExportViewModel = viewModel(factory = factory()),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sessions by vm.sessionList.collectAsState(initial = emptyList())
    var selected by remember { mutableStateOf(setOf<String>()) }
    // Default selection: active session, else all (P9.1 multi-session).
    LaunchedEffect(sessions) {
        if (selected.isEmpty()) {
            val active = ActiveSession.id
            selected =
                if (active != null && sessions.any { it.id == active }) {
                    setOf(active)
                } else {
                    sessions.map { it.id }.toSet()
                }
        }
    }
    var estimate by remember { mutableStateOf<com.scraper.classroomcapture.export.ExportManager.Estimate?>(null) }
    var preflight by remember { mutableStateOf<List<com.scraper.classroomcapture.export.PreflightFailure>?>(null) }
    val phase by vm.phase.collectAsState()
    val message by vm.message.collectAsState()
    val pastExports by vm.observeExports().collectAsState(initial = emptyList())
    var lastSafUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var lastLocalPath by remember { mutableStateOf<String?>(null) }

    // SAF picker (P9.4): user-selected destination, streamed export.
    val safPicker =
        androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip"),
        ) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            lastSafUri = uri
            scope.launch {
                try {
                    val staging = java.io.File(context.cacheDir, "exports")
                    staging.mkdirs()
                    // Stream to the SAF Uri; then try read-back for VERIFIED.
                    // When the provider cannot be read back, the record is
                    // WRITTEN_UNVERIFIED (D17) — shown as a limitation.
                    val cr = context.contentResolver
                    val displayName = uri.lastPathSegment ?: "export.zip"
                    vm.exportSaf(selected.toList(), displayName, staging, cr, uri)
                } catch (_: Exception) {
                }
            }
        }

    LaunchedEffect(selected) {
        scope.launch {
            estimate = if (selected.isEmpty()) null else vm.estimate(selected.toList())
            preflight = null
        }
    }
    val est = estimate
    val status = "sessions=${selected.size} samples=${est?.sampleCount ?: 0} phase=$phase"
    ScreenScaffold(
        titleRes = R.string.nav_export,
        status = stringResource(R.string.status_prefix, status),
        onNavigate = onNavigate,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Includes audio, transcripts and session details. Source recordings stay on the phone.")
            if (sessions.isEmpty()) {
                Text("No sessions yet — record a class first.")
                return@Column
            }
            // Session multi-select (P9.1) with real counts.
            sessions.forEach { s ->
                val checked = s.id in selected
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = checked,
                        onCheckedChange = { on ->
                            selected = if (on) selected + s.id else selected - s.id
                        },
                        modifier = Modifier.semantics { contentDescription = "Select session ${s.grade} ${s.subject}" },
                    )
                    Text("${s.grade} ${s.subject} (${s.defaultLanguage})", modifier = Modifier.weight(1f))
                }
            }
            est?.let {
                val mb = it.audioBytes / (1024 * 1024)
                Text("Selected: ${it.sampleCount} samples · ~$mb MB audio. Large exports: use USB or fewer sessions.")
            }
            // Preflight check (P9.3) with actionable failures.
            Button(
                onClick = { scope.launch { preflight = vm.preflight(selected.toList()) } },
                enabled = selected.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text("Check before export")
            }
            preflight?.let { failures ->
                if (failures.isEmpty()) {
                    Text("Ready — all audio verified.")
                } else {
                    Text("Blocked: ${failures.size} sample(s) need attention:")
                    failures.take(10).forEach { f ->
                        Text("${f.sampleId}: ${f.code}")
                    }
                }
            }
            // Progress states: Preparing → Writing → Checking → Saved (P9.5).
            // Indeterminate (no invented percentages).
            when {
                phase.startsWith("preparing") -> Text("Preparing…")
                phase.startsWith("writing") -> Text("Writing file…")
                phase.startsWith("checking") -> Text("Checking file…")
                phase.startsWith("saved") -> Text("Saved — ${phase.removePrefix("saved:")}.")
                phase == "blocked" -> Text("Export blocked — fix the samples above, then retry to a new file.")
                phase == "failed" -> Text("Export failed — source data intact. Retry to a new file.")
            }
            message?.let { Text(it) }
            lastLocalPath?.let { Text("Local file: $it") }
            // Primary: SAF picker (P9.4 streaming export).
            Button(
                onClick = {
                    val name = "scrappy-${java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())}.zip"
                    safPicker.launch(name)
                },
                enabled = selected.isNotEmpty() && !phase.startsWith("writing"),
                modifier =
                    Modifier.fillMaxWidth().heightIn(min = 56.dp)
                        .semantics { contentDescription = "Save export via file picker" },
            ) {
                Text("Save export")
            }
            // Secondary: local cache file (verified, then share via SAF Uri).
            Button(
                onClick = {
                    scope.launch {
                        try {
                            val staging = java.io.File(context.cacheDir, "exports").apply { mkdirs() }
                            val name = "scrappy-${System.currentTimeMillis()}.zip"
                            val dest = java.io.File(staging, name)
                            vm.exportToFile(selected.toList(), dest, staging)
                            lastLocalPath = dest.absolutePath
                        } catch (_: Exception) {
                        }
                    }
                },
                enabled = selected.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text("Save locally (verified)")
            }
            // Share via Android share sheet (ui.readme §10). Sending through
            // an external app may need connectivity; saving locally does not.
            lastSafUri?.let { uri ->
                Button(
                    onClick = {
                        val share =
                            android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "application/zip"
                                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                        context.startActivity(android.content.Intent.createChooser(share, "Share export"))
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Text("Share file")
                }
            }
            // Past exports (P9.6): new record per export, never overwritten.
            if (pastExports.isNotEmpty()) {
                Text("Past exports:")
                pastExports.take(5).forEach { e ->
                    Text("${e.id.take(8)} ${e.status} ${e.verification ?: ""} ${e.byteSize ?: 0} bytes")
                }
            }
        }
    }
}

@Composable
fun RecoveryScreen(
    onNavigate: (String) -> Unit,
    vm: RecoveryViewModel = viewModel(factory = factory()),
) {
    var report by remember { mutableStateOf<RecoveryReport?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        scope.launch { report = vm.report() }
    }
    val events by vm.recentEvents().collectAsState(initial = emptyList())
    val recoveryStatus =
        report?.let { "recovered=${it.recoveredSampleIds.size} errors=${it.errorSampleIds.size}" } ?: "loading"
    ScreenScaffold(
        titleRes = R.string.nav_recovery,
        status = stringResource(R.string.status_prefix, recoveryStatus),
        onNavigate = onNavigate,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val r = report
            if (r == null) {
                Text("Checking for interrupted work…")
            } else if (!r.needsAttention) {
                Text("No interrupted work. All durable audio is accounted for.")
            } else {
                if (r.recoveredSampleIds.isNotEmpty()) Text("Recovered: ${r.recoveredSampleIds.size} sample(s) adopted.")
                if (r.errorSampleIds.isNotEmpty()) Text("Attention: ${r.errorSampleIds.size} sample(s) need review (see Errors).")
                r.resumableSessionId?.let {
                    Button(
                        onClick = {
                            ActiveSession.id = it
                            onNavigate(Routes.RECORDING)
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    ) {
                        Text("Resume session")
                    }
                }
            }
            events.takeLast(10).reversed().forEach { e ->
                Text("${e.type}: ${e.detail ?: ""}")
            }
        }
    }
}

@Composable
fun ErrorsScreen(
    onNavigate: (String) -> Unit,
    vm: ErrorsViewModel = viewModel(factory = factory()),
) {
    var errors by remember { mutableStateOf<List<ClassroomSample>>(emptyList()) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        scope.launch { errors = vm.errorSamples() }
    }
    val events by vm.recentEvents().collectAsState(initial = emptyList())
    ScreenScaffold(
        titleRes = R.string.nav_errors,
        status = stringResource(R.string.status_prefix, "errors=${errors.size}"),
        onNavigate = onNavigate,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (errors.isEmpty()) {
                Text("No errors. Failed work appears here with a retry action.")
            }
            errors.take(20).forEach { s ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("#${s.sequenceNumber} ${s.errorCode ?: "ERROR"}", modifier = Modifier.weight(1f))
                    Button(onClick = {
                        scope.launch {
                            vm.retrySample(s.id)
                            errors = vm.errorSamples()
                        }
                    }) {
                        Text("Retry")
                    }
                }
            }
            events.filter { it.type.contains("FAILED") || it.type.contains("RETRY") }.takeLast(10).reversed().forEach { e ->
                Text("${e.type}: ${e.detail ?: ""}")
            }
        }
    }
}

@Composable
fun DiagnosticsScreen(
    onNavigate: (String) -> Unit,
    vm: DiagnosticsViewModel = viewModel(factory = factory()),
) {
    val queue by vm.queue.collectAsState(initial = null)
    ScreenScaffold(
        titleRes = R.string.nav_diagnostics,
        status = vm.modelStatus() + " · " + vm.storage(),
        onNavigate = onNavigate,
    ) {
        val q = queue
        if (q == null) {
            Text("Queue: loading…")
        } else {
            Text(
                "ASR pending=${q.pendingAsr} running=${q.runningAsr} " +
                    "done=${q.doneAsr} failed=${q.failedAsr} · " +
                    "LLM pending=${q.pendingLlm} failed=${q.failedLlm}",
            )
        }
    }
}
