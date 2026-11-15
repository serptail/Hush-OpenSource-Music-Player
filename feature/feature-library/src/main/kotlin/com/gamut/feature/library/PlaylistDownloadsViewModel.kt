package com.gamut.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamut.core.model.Track
import com.gamut.feature.resolver.DownloadState
import com.gamut.feature.resolver.TrackDownloadManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlaylistDownloadsViewModel
    @Inject
    constructor(
        private val manager: TrackDownloadManager,
        private val storageSettings: com.gamut.feature.resolver.StorageSettings,
    ) : ViewModel() {
        val states: StateFlow<Map<String, DownloadState>> = manager.states

        val batch = manager.batch

        fun allowMobileData() = storageSettings.update { it.copy(wifiOnly = false) }

        fun download(
            tracks: List<Track>,
            label: String? = null,
        ) = manager.download(tracks, label)

        fun cancel(isrcs: Collection<String>) = manager.cancel(isrcs)

        fun remove(isrcs: Collection<String>) {
            viewModelScope.launch(NonCancellable) { manager.remove(isrcs) }
        }
    }
