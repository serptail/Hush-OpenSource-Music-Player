package com.gamut.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamut.core.model.Track
import com.gamut.feature.resolver.DownloadState
import com.gamut.feature.resolver.TrackDownloadManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Thin bridge between the playlist screen and [TrackDownloadManager] (which lives for the whole process). */
@HiltViewModel
class PlaylistDownloadsViewModel @Inject constructor(
    private val manager: TrackDownloadManager,
    private val storageSettings: com.gamut.feature.resolver.StorageSettings,
) : ViewModel() {
    /** isrc -> state for every downloaded / queued / downloading track. */
    val states: StateFlow<Map<String, DownloadState>> = manager.states

    /** Overall run; [com.gamut.feature.resolver.DownloadBatch.waitingForWifi] is true while "Wi-Fi only" holds the queue back. */
    val batch = manager.batch

    /** Turns "Download on Wi-Fi only" off so a held-back queue starts on mobile data. */
    fun allowMobileData() = storageSettings.update { it.copy(wifiOnly = false) }

    /** [label] (the playlist's name) is what the "finished" toast calls the batch. */
    fun download(tracks: List<Track>, label: String? = null) = manager.download(tracks, label)
    fun cancel(isrcs: Collection<String>) = manager.cancel(isrcs)
    // NonCancellable: deleting a playlist closes this screen right away, which must not abort the file deletion.
    fun remove(isrcs: Collection<String>) { viewModelScope.launch(NonCancellable) { manager.remove(isrcs) } }
}