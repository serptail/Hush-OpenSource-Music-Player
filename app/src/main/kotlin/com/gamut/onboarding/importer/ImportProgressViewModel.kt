package com.gamut.onboarding.importer

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamut.feature.library.ImportProgressUi
import com.gamut.feature.library.ImportSourceUi
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Feeds the import's progress to the Library screen. The importer outlives every screen, so this only reads its
 * state, turns it into what the card shows, and passes cancel and dismiss back.
 */
@HiltViewModel
class ImportProgressViewModel @Inject constructor(
    private val importer: LibraryImporter,
) : ViewModel() {
    val progress: StateFlow<ImportProgressUi?> = importer.state
        .map(::toUi)
        .stateIn(viewModelScope, SharingStarted.Eagerly, toUi(importer.state.value))

    fun cancel() = importer.cancel()

    /** Pasted open.spotify.com links (public playlists and albums). */
    fun importSpotifyLinks(text: String) = importer.startFromSpotifyLinks(text)

    /** Spotify's data download (.zip or .json) or Exportify .csv files picked from storage. */
    fun importSpotifyFiles(uris: List<Uri>) = importer.startFromFiles(uris)

    /** Pasted YouTube Music playlist links (public or unlisted). No sign-in. */
    fun importYouTubeLinks(text: String) = importer.startFromYouTubeLinks(text)

    /** Pasted Apple Music playlist or album links (public). No sign-in. */
    fun importAppleMusicLinks(text: String) = importer.startFromAppleMusicLinks(text)

    /** Clears a finished or failed result once it has been read. */
    fun dismiss() = importer.acknowledge()

    private fun toUi(state: ImportState): ImportProgressUi? = when (state) {
        ImportState.Idle -> null
        is ImportState.Reading -> ImportProgressUi.Running(
            title = "Reading ${state.what}\u2026",
            done = 0,
            total = 0,
            current = null,
            source = state.source.toUi(),
        )
        is ImportState.Matching -> ImportProgressUi.Running(
            title = state.playlist?.let { "Importing \u201C$it\u201D" } ?: "Importing your liked songs",
            done = state.done,
            total = state.total,
            current = state.current,
            source = state.source.toUi(),
        )
        is ImportState.Done -> ImportProgressUi.Finished(
            title = when {
                state.songs == 0 -> "Nothing could be imported"
                state.cancelled -> "Import stopped after ${state.songs} songs"
                else -> "Imported ${state.songs} songs" + if (state.playlists > 0) " into ${state.playlists} playlists" else ""
            },
            detail = buildList {
                if (state.missed > 0) add("${state.missed} songs weren\u2019t found.")
                if (state.unreadable > 0) add("${state.unreadable} links couldn\u2019t be read.")
            }.joinToString(" ").ifEmpty { null },
            isError = state.songs == 0,
            source = state.source.toUi(),
        )
        is ImportState.Failed -> ImportProgressUi.Finished(title = state.message, detail = null, isError = true, source = state.source.toUi())
    }

    private fun ImportSource?.toUi(): ImportSourceUi? = when (this) {
        ImportSource.Spotify -> ImportSourceUi.Spotify
        ImportSource.YouTubeMusic -> ImportSourceUi.YouTubeMusic
        ImportSource.AppleMusic -> ImportSourceUi.AppleMusic
        null -> null
    }
}