package com.scraper.classroomcapture.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.scraper.classroomcapture.R
import com.scraper.classroomcapture.permissions.MicPermissionState
import com.scraper.classroomcapture.permissions.RequestNotificationsIfNeeded
import com.scraper.classroomcapture.permissions.rememberMicPermission
import com.scraper.classroomcapture.ui.Routes
import com.scraper.classroomcapture.ui.ScreenScaffold
import com.scraper.classroomcapture.ui.viewmodel.DiagnosticsViewModel
import com.scraper.classroomcapture.ui.viewmodel.ErrorsViewModel
import com.scraper.classroomcapture.ui.viewmodel.ExportViewModel
import com.scraper.classroomcapture.ui.viewmodel.HomeViewModel
import com.scraper.classroomcapture.ui.viewmodel.NewSessionViewModel
import com.scraper.classroomcapture.ui.viewmodel.RecordingViewModel
import com.scraper.classroomcapture.ui.viewmodel.RecoveryViewModel
import com.scraper.classroomcapture.ui.viewmodel.SummaryViewModel

@Composable
fun HomeScreen(
    onNavigate: (String) -> Unit,
    vm: HomeViewModel = viewModel(),
) {
    val status by vm.status.collectAsState()
    RequestNotificationsIfNeeded()
    ScreenScaffold(
        titleRes = R.string.nav_home,
        status = stringResource(R.string.status_prefix, status),
        onNavigate = onNavigate,
    ) {
        Button(
            onClick = { onNavigate(Routes.NEW_SESSION) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) {
            Text(stringResource(R.string.action_new_session))
        }
    }
}

@Composable
fun NewSessionScreen(
    onNavigate: (String) -> Unit,
    vm: NewSessionViewModel = viewModel(),
) {
    var grade by remember { mutableStateOf(vm.grade) }
    var subject by remember { mutableStateOf(vm.subject) }
    var teacher by remember { mutableStateOf(vm.teacherCode) }
    var school by remember { mutableStateOf(vm.schoolCode) }
    ScreenScaffold(
        titleRes = R.string.nav_new_session,
        status = stringResource(R.string.new_session_hint),
        onNavigate = onNavigate,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // P2 binds these to the Session entity + validation. P1 keeps them as plain fields
            // so rotation/recreation survival can be verified early.
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
            Button(
                onClick = { onNavigate(Routes.RECORDING) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) {
                Text(stringResource(R.string.action_start_session))
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
fun RecordingScreen(
    onNavigate: (String) -> Unit,
    vm: RecordingViewModel = viewModel(),
) {
    val status by vm.status.collectAsState()
    val (mic, requestMic) = rememberMicPermission()
    ScreenScaffold(
        titleRes = R.string.nav_recording,
        status = stringResource(R.string.status_prefix, status),
        onNavigate = onNavigate,
    ) {
        Text(
            when (mic) {
                MicPermissionState.GRANTED -> stringResource(R.string.mic_granted)
                MicPermissionState.DENIED -> stringResource(R.string.mic_denied)
                MicPermissionState.UNKNOWN -> stringResource(R.string.mic_unknown)
            },
        )
        Button(
            onClick = requestMic,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) {
            Text(stringResource(R.string.action_request_mic))
        }
        // P4 replaces this stub with service-owned start/stop. Large segmented control reserved.
        Button(
            onClick = { },
            enabled = mic == MicPermissionState.GRANTED,
            modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
        ) {
            Text(stringResource(R.string.action_start_stop_stub))
        }
    }
}

@Composable
fun SummaryScreen(
    onNavigate: (String) -> Unit,
    vm: SummaryViewModel = viewModel(),
) {
    val status by vm.status.collectAsState()
    ScreenScaffold(
        titleRes = R.string.nav_summary,
        status = stringResource(R.string.status_prefix, status),
        onNavigate = onNavigate,
    )
}

@Composable
fun ExportScreen(
    onNavigate: (String) -> Unit,
    vm: ExportViewModel = viewModel(),
) {
    val status by vm.status.collectAsState()
    ScreenScaffold(
        titleRes = R.string.nav_export,
        status = stringResource(R.string.status_prefix, status),
        onNavigate = onNavigate,
    )
}

@Composable
fun RecoveryScreen(
    onNavigate: (String) -> Unit,
    vm: RecoveryViewModel = viewModel(),
) {
    val status by vm.status.collectAsState()
    ScreenScaffold(
        titleRes = R.string.nav_recovery,
        status = stringResource(R.string.status_prefix, status),
        onNavigate = onNavigate,
    )
}

@Composable
fun ErrorsScreen(
    onNavigate: (String) -> Unit,
    vm: ErrorsViewModel = viewModel(),
) {
    val status by vm.status.collectAsState()
    ScreenScaffold(
        titleRes = R.string.nav_errors,
        status = stringResource(R.string.status_prefix, status),
        onNavigate = onNavigate,
    )
}

@Composable
fun DiagnosticsScreen(
    onNavigate: (String) -> Unit,
    vm: DiagnosticsViewModel = viewModel(),
) {
    val status by vm.status.collectAsState()
    ScreenScaffold(
        titleRes = R.string.nav_diagnostics,
        status =
            stringResource(R.string.status_prefix, status) +
                " (native stub wires in P7/P10)",
        onNavigate = onNavigate,
    )
}
