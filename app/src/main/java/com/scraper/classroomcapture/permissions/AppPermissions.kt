package com.scraper.classroomcapture.permissions

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat

/** Notification channels (P1 scaffolding; P4 wires the recording channel to the service). */
object NotificationChannels {
    const val RECORDING = "recording"
    const val ALERTS = "alerts"

    fun createAll(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                RECORDING,
                context.getString(com.scraper.classroomcapture.R.string.channel_recording),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        nm.createNotificationChannel(
            NotificationChannel(
                ALERTS,
                context.getString(com.scraper.classroomcapture.R.string.channel_alerts),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
    }
}

/** Microphone permission state for the Recording screen (P4 adds service binding). */
enum class MicPermissionState { GRANTED, DENIED, UNKNOWN }

@Composable
fun rememberMicPermission(): Pair<MicPermissionState, () -> Unit> {
    val context = androidx.compose.ui.platform.LocalContext.current
    var state by remember {
        mutableStateOf(
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                MicPermissionState.GRANTED
            } else {
                MicPermissionState.UNKNOWN
            },
        )
    }
    val launcher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            state = if (granted) MicPermissionState.GRANTED else MicPermissionState.DENIED
        }
    return state to { launcher.launch(Manifest.permission.RECORD_AUDIO) }
}

/** POST_NOTIFICATIONS is runtime-gated on API 33+; safe no-op below that. */
@Composable
fun RequestNotificationsIfNeeded() {
    if (Build.VERSION.SDK_INT < 33) return
    val context = androidx.compose.ui.platform.LocalContext.current
    val launcher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { }
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
