package com.scraper.classroomcapture.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.scraper.classroomcapture.R
import com.scraper.classroomcapture.ui.screens.DiagnosticsScreen
import com.scraper.classroomcapture.ui.screens.ErrorsScreen
import com.scraper.classroomcapture.ui.screens.ExportScreen
import com.scraper.classroomcapture.ui.screens.HomeScreen
import com.scraper.classroomcapture.ui.screens.NewSessionScreen
import com.scraper.classroomcapture.ui.screens.RecordingScreen
import com.scraper.classroomcapture.ui.screens.RecoveryScreen
import com.scraper.classroomcapture.ui.screens.SummaryScreen

@Composable
fun ScraperNav(navController: NavHostController) {
    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) { HomeScreen(onNavigate = navController::navigate) }
        composable(Routes.NEW_SESSION) { NewSessionScreen(onNavigate = navController::navigate) }
        composable(Routes.RECORDING) { RecordingScreen(onNavigate = navController::navigate) }
        composable(Routes.SUMMARY) { SummaryScreen(onNavigate = navController::navigate) }
        composable(Routes.EXPORT) { ExportScreen(onNavigate = navController::navigate) }
        composable(Routes.RECOVERY) { RecoveryScreen(onNavigate = navController::navigate) }
        composable(Routes.ERRORS) { ErrorsScreen(onNavigate = navController::navigate) }
        composable(Routes.DIAGNOSTICS) { DiagnosticsScreen(onNavigate = navController::navigate) }
    }
}

/** Shared large-control screen scaffold: scrollable, 48dp+ targets, heading for a11y. */
@Composable
fun ScreenScaffold(
    titleRes: Int,
    status: String,
    onNavigate: (String) -> Unit,
    body: @Composable () -> Unit = {},
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = stringResource(titleRes), style = MaterialTheme.typography.headlineMedium)
        Text(text = status, style = MaterialTheme.typography.bodySmall)
        body()
        NavButtons(onNavigate)
    }
}

@Composable
private fun NavButtons(onNavigate: (String) -> Unit) {
    val destinations =
        listOf(
            Routes.HOME to R.string.nav_home,
            Routes.NEW_SESSION to R.string.nav_new_session,
            Routes.RECORDING to R.string.nav_recording,
            Routes.SUMMARY to R.string.nav_summary,
            Routes.EXPORT to R.string.nav_export,
            Routes.RECOVERY to R.string.nav_recovery,
            Routes.ERRORS to R.string.nav_errors,
            Routes.DIAGNOSTICS to R.string.nav_diagnostics,
        )
    destinations.forEach { (route, label) ->
        Button(
            onClick = { onNavigate(route) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            Text(stringResource(label))
        }
    }
}
