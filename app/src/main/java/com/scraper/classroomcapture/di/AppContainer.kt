package com.scraper.classroomcapture.di

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.scraper.classroomcapture.audio.AudioDeviceManager
import com.scraper.classroomcapture.data.local.ScraperDatabase
import com.scraper.classroomcapture.data.repository.ArtifactRepository
import com.scraper.classroomcapture.data.repository.EventRepository
import com.scraper.classroomcapture.data.repository.ExportRepository
import com.scraper.classroomcapture.data.repository.JobRepository
import com.scraper.classroomcapture.data.repository.RoomArtifactRepository
import com.scraper.classroomcapture.data.repository.RoomEventRepository
import com.scraper.classroomcapture.data.repository.RoomExportRepository
import com.scraper.classroomcapture.data.repository.RoomJobRepository
import com.scraper.classroomcapture.data.repository.RoomSampleRepository
import com.scraper.classroomcapture.data.repository.RoomSessionRepository
import com.scraper.classroomcapture.data.repository.SampleRepository
import com.scraper.classroomcapture.data.repository.SessionRepository
import com.scraper.classroomcapture.data.store.ArtifactStore
import com.scraper.classroomcapture.data.store.FileArtifactStore
import com.scraper.classroomcapture.data.store.Reconciler
import com.scraper.classroomcapture.recording.RecordingController
import com.scraper.classroomcapture.ui.viewmodel.DiagnosticsViewModel
import com.scraper.classroomcapture.ui.viewmodel.ErrorsViewModel
import com.scraper.classroomcapture.ui.viewmodel.ExportViewModel
import com.scraper.classroomcapture.ui.viewmodel.HomeViewModel
import com.scraper.classroomcapture.ui.viewmodel.NewSessionViewModel
import com.scraper.classroomcapture.ui.viewmodel.RecordingViewModel
import com.scraper.classroomcapture.ui.viewmodel.RecoveryViewModel
import com.scraper.classroomcapture.ui.viewmodel.SummaryViewModel

/**
 * Manual DI container. P2 adds the Room database + repositories (P2.6);
 * P3 ArtifactStore, P4 recording controller, P6 dispatcher, P7 ASREngine.
 * UI depends on repository abstractions, never on Room.
 */
interface AppContainer {
    val appContext: Context
    val database: ScraperDatabase
    val sessionRepository: SessionRepository
    val sampleRepository: SampleRepository
    val artifactRepository: ArtifactRepository
    val jobRepository: JobRepository
    val eventRepository: EventRepository
    val exportRepository: ExportRepository
    val artifactStore: ArtifactStore
    val reconciler: Reconciler
    val recordingController: RecordingController
    val audioDevices: AudioDeviceManager

    fun viewModelFactory(): ViewModelProvider.Factory
}

class DefaultAppContainer(override val appContext: Context) : AppContainer {
    override val database: ScraperDatabase by lazy { ScraperDatabase.build(appContext) }

    override val sessionRepository: SessionRepository by lazy {
        RoomSessionRepository(database.sessionDao())
    }
    override val sampleRepository: SampleRepository by lazy {
        RoomSampleRepository(database.sampleDao())
    }
    override val artifactRepository: ArtifactRepository by lazy {
        RoomArtifactRepository(database.artifactDao())
    }
    override val jobRepository: JobRepository by lazy {
        RoomJobRepository(database.processingJobDao())
    }
    override val eventRepository: EventRepository by lazy {
        RoomEventRepository(database.deviceEventDao())
    }
    override val exportRepository: ExportRepository by lazy {
        RoomExportRepository(database.exportDao())
    }
    override val artifactStore: ArtifactStore by lazy {
        FileArtifactStore(java.io.File(appContext.filesDir, "scrappy"))
    }
    override val reconciler: Reconciler by lazy {
        Reconciler(store = artifactStore, sessions = sessionRepository, samples = sampleRepository, artifacts = artifactRepository)
    }
    override val recordingController: RecordingController by lazy {
        RecordingController(appContext)
    }
    override val audioDevices: AudioDeviceManager by lazy {
        AudioDeviceManager(appContext)
    }

    override fun viewModelFactory(): ViewModelProvider.Factory =
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                when {
                    modelClass.isAssignableFrom(HomeViewModel::class.java) -> HomeViewModel() as T
                    modelClass.isAssignableFrom(NewSessionViewModel::class.java) -> NewSessionViewModel() as T
                    modelClass.isAssignableFrom(RecordingViewModel::class.java) -> RecordingViewModel() as T
                    modelClass.isAssignableFrom(SummaryViewModel::class.java) -> SummaryViewModel() as T
                    modelClass.isAssignableFrom(ExportViewModel::class.java) -> ExportViewModel() as T
                    modelClass.isAssignableFrom(RecoveryViewModel::class.java) -> RecoveryViewModel() as T
                    modelClass.isAssignableFrom(ErrorsViewModel::class.java) -> ErrorsViewModel() as T
                    modelClass.isAssignableFrom(DiagnosticsViewModel::class.java) -> DiagnosticsViewModel() as T
                    else -> throw IllegalArgumentException("Unknown ViewModel ${modelClass.name}")
                }
        }
}
