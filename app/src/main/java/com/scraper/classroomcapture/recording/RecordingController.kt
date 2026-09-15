package com.scraper.classroomcapture.recording

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// App-process handle to RecordingService (P4.2). Owns the bind, relays the
// service's RecordingStatus, and sends start/stop intents. Survives rotation
// (app-scoped); after process death it re-binds to IDLE while the P3
// reconciler owns any interrupted rows — the controller never guesses.
class RecordingController(private val appContext: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _status = MutableStateFlow(RecordingStatus.Idle)
    val status: StateFlow<RecordingStatus> = _status.asStateFlow()

    private var binder: RecordingService.RecordingBinder? = null
    private var bound = false

    private val connection =
        object : ServiceConnection {
            override fun onServiceConnected(
                name: ComponentName?,
                service: IBinder?,
            ) {
                binder = service as? RecordingService.RecordingBinder
                val flow = binder?.status() ?: return
                scope.launch {
                    flow.collect { _status.value = it }
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                binder = null
                _status.value = RecordingStatus.Idle
            }
        }

    fun ensureBound() {
        if (bound) return
        bound =
            appContext.bindService(
                Intent(appContext, RecordingService::class.java),
                connection,
                Context.BIND_AUTO_CREATE,
            )
    }

    fun start(
        sessionId: String,
        language: String,
    ) {
        ensureBound()
        val intent = RecordingService.startIntent(appContext, sessionId, language)
        if (Build.VERSION.SDK_INT >= 26) {
            appContext.startForegroundService(intent)
        } else {
            appContext.startService(intent)
        }
    }

    fun stopSegment() {
        appContext.startService(
            Intent(appContext, RecordingService::class.java)
                .setAction(RecordingService.ACTION_STOP_SEGMENT),
        )
    }

    fun stop() {
        appContext.startService(
            Intent(appContext, RecordingService::class.java)
                .setAction(RecordingService.ACTION_STOP),
        )
    }

    fun close() {
        if (bound) {
            try {
                appContext.unbindService(connection)
            } catch (e: Exception) {
                // Already unbound; nothing to do.
            }
            bound = false
        }
        scope.cancel()
    }
}
