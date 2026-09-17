package com.scraper.classroomcapture.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import com.scraper.classroomcapture.R
import com.scraper.classroomcapture.ui.screens.DiagnosticsScreen
import com.scraper.classroomcapture.ui.screens.ErrorsScreen
import com.scraper.classroomcapture.ui.screens.ExportScreen
import com.scraper.classroomcapture.ui.screens.HomeScreen
import com.scraper.classroomcapture.ui.screens.NewSessionScreen
import com.scraper.classroomcapture.ui.screens.RecordingScreen
import com.scraper.classroomcapture.ui.screens.RecoveryScreen
import com.scraper.classroomcapture.ui.screens.SummaryScreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScraperNav(navController: NavHostController) {
    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route ?: Routes.HOME

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(titleResFor(currentRoute))) },
            navigationIcon = {
                if (currentRoute != Routes.HOME) {
                    TextButton(
                        onClick = {
                            navController.navigate(Routes.HOME) {
                                popUpTo(Routes.HOME) { inclusive = true }
                            }
                        },
                    ) {
                        Text(stringResource(R.string.action_back))
                    }
                }
            },
            colors =
                TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            modifier = Modifier.fillMaxWidth(),
        )

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
}

private fun titleResFor(route: String): Int =
    when {
        route.startsWith(Routes.HOME) -> R.string.nav_home
        route.startsWith(Routes.NEW_SESSION) -> R.string.nav_new_session
        route.startsWith(Routes.RECORDING) -> R.string.nav_recording
        route.startsWith(Routes.SUMMARY) -> R.string.nav_summary
        route.startsWith(Routes.EXPORT) -> R.string.nav_export
        route.startsWith(Routes.RECOVERY) -> R.string.nav_recovery
        route.startsWith(Routes.ERRORS) -> R.string.nav_errors
        route.startsWith(Routes.DIAGNOSTICS) -> R.string.nav_diagnostics
        else -> R.string.nav_home
    }

/** Shared screen scaffold: scrollable, 48dp+ targets, heading for a11y. */
@Composable
fun ScreenScaffold(
    titleRes: Int,
    status: String,
    onNavigate: (String) -> Unit,
    navEnabled: Boolean = true,
    body: @Composable () -> Unit = {},
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            body()
        }
    }
}
