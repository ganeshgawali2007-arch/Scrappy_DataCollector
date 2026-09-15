package com.scraper.classroomcapture

import android.app.Application
import android.util.Log
import com.scraper.classroomcapture.di.AppContainer
import com.scraper.classroomcapture.di.DefaultAppContainer
import com.scraper.classroomcapture.domain.model.DeviceEvent
import com.scraper.classroomcapture.permissions.NotificationChannels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Application. Owns the [AppContainer] (manual DI). Recording service (P4)
 * and processing workers (P6) resolve dependencies from here — Compose
 * screens never own recording state.
 *
 * Startup reconciliation (P3.6) runs on every process start, off the main
 * thread: at fresh-process start no recording can be active, so adopting or
 * erroring interrupted samples races nothing.
 */
class ScraperApp : Application() {
    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = DefaultAppContainer(applicationContext)
        NotificationChannels.createAll(this)
        appScope.launch {
            try {
                val now = System.currentTimeMillis()
                val report = container.reconciler.reconcile(now)
                container.eventRepository.log(
                    DeviceEvent(
                        id = UUID.randomUUID().toString(),
                        sessionId = null,
                        sampleId = null,
                        type = "RECONCILIATION_COMPLETED",
                        detail =
                            "adopted=${report.adopted.size} " +
                                "errored=${report.errored.size} " +
                                "quarantined=${report.quarantined.size} " +
                                "corrupt=${report.corrupt.size}",
                        appVersion = BuildConfig.VERSION_NAME,
                        createdAt = now,
                    ),
                )
                if (!report.isClean()) Log.i(TAG, "Reconciliation: $report")
            } catch (e: Exception) {
                // Reconciliation must never crash startup; P8 surfaces
                // persistent failures from the report/event log instead.
                Log.w(TAG, "Startup reconciliation failed", e)
            }
        }
    }

    companion object {
        private const val TAG = "ScraperApp"
    }
}
