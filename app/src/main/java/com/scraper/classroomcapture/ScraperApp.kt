package com.scraper.classroomcapture

import android.app.Application
import com.scraper.classroomcapture.di.AppContainer
import com.scraper.classroomcapture.di.DefaultAppContainer
import com.scraper.classroomcapture.permissions.NotificationChannels

/**
 * P1 skeleton Application. Owns the [AppContainer] (manual DI).
 * Recording service (P4) and processing workers (P6) will resolve
 * dependencies from here — Compose screens never own recording state.
 */
class ScraperApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = DefaultAppContainer(applicationContext)
        NotificationChannels.createAll(this)
    }
}
