package com.scraper.classroomcapture.di

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.scraper.classroomcapture.ui.viewmodel.DiagnosticsViewModel
import com.scraper.classroomcapture.ui.viewmodel.ErrorsViewModel
import com.scraper.classroomcapture.ui.viewmodel.ExportViewModel
import com.scraper.classroomcapture.ui.viewmodel.HomeViewModel
import com.scraper.classroomcapture.ui.viewmodel.NewSessionViewModel
import com.scraper.classroomcapture.ui.viewmodel.RecordingViewModel
import com.scraper.classroomcapture.ui.viewmodel.RecoveryViewModel
import com.scraper.classroomcapture.ui.viewmodel.SummaryViewModel

/**
 * Manual DI container (P1 scaffolding). P2 will add Room repositories,
 * P3 ArtifactStore, P4 recording controller, P6 dispatcher, P7 ASREngine.
 * Kept interface-segregated so UI depends on abstractions, not Room.
 */
interface AppContainer {
    val appContext: Context

    fun viewModelFactory(): ViewModelProvider.Factory
}

class DefaultAppContainer(override val appContext: Context) : AppContainer {
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
