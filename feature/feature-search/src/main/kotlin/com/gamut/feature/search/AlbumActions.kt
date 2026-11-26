package com.gamut.feature.search

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import com.gamut.core.designsystem.LocalNotifier
import com.gamut.core.designsystem.NoticeKind
import com.gamut.core.model.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Opens the options for an album (play it, add every song to favorites, add every song to a playlist). Album cards
 * and the album screen's menu call it; it is null outside an [AlbumActionsHost], which leaves a card plain.
 */
internal val LocalAlbumActions = staticCompositionLocalOf<((DiscoverItem) -> Unit)?> { null }

/**
 * How an album gets into the library as one group, so a playlist or the favorites shows it as an album that folds
 * and unfolds instead of as loose songs. Each function returns how many of the album's songs were new. Provided by
 * the app; without it an album is added song by song.
 */
class AlbumLibraryActions(
    val addToFavorites: suspend (List<Track>) -> Int,
    val addToPlaylist: suspend (tracks: List<Track>, playlistId: String) -> Int,
)

val LocalAlbumLibrary = staticCompositionLocalOf<AlbumLibraryActions?> { null }

/**
 * Shows the album options sheet and the playlist picker behind it, and makes [LocalAlbumActions] available to
 * [content]. [loadTracks] returns the songs of the album a card stands for, or null when they cannot be loaded.
 */
@Composable
internal fun AlbumActionsHost(
    loadTracks: suspend (DiscoverItem) -> List<Track>?,
    favoriteIsrcs: Set<String>,
    onToggleFavorite: (Track) -> Unit,
    playlists: List<TrackPlaylistOption>,
    onAddToPlaylist: (Track, String) -> Unit,
    onPlay: (Track, List<Track>) -> Unit,
    content: @Composable () -> Unit,
) {
    val notifier = LocalNotifier.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val albumLibrary = LocalAlbumLibrary.current
    val load by rememberUpdatedState(loadTracks)
    val favorites by rememberUpdatedState(favoriteIsrcs)
    val toggleFavorite by rememberUpdatedState(onToggleFavorite)
    val addToPlaylist by rememberUpdatedState(onAddToPlaylist)
    val play by rememberUpdatedState(onPlay)
    var target by remember { mutableStateOf<DiscoverItem?>(null) }
    var picker by remember { mutableStateOf<DiscoverItem?>(null) }
    var busy by remember { mutableStateOf(false) }

    val open: (DiscoverItem) -> Unit =
        remember(haptic) {
            { item ->
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                target = item
            }
        }

    // Loads the album's songs, then hands them to [then]. One load at a time, so a double tap does nothing.
    fun withTracks(
        item: DiscoverItem,
        then: (List<Track>) -> Unit,
    ) {
        if (busy) return
        busy = true
        scope.launch {
            val tracks =
                try {
                    load(item)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            busy = false
            if (tracks.isNullOrEmpty()) {
                notifier.show("Couldn\u2019t load that album.", NoticeKind.Warning)
            } else {
                then(tracks)
            }
        }
    }

    CompositionLocalProvider(LocalAlbumActions provides open) { content() }

    target?.let { item ->
        StationActionsSheet(
            item = item,
            badge = "Album",
            busy = busy,
            onDismiss = { target = null },
            onPlay = {
                withTracks(item) { tracks ->
                    target = null
                    play(tracks.first(), tracks)
                }
            },
            onAddToFavorites = {
                withTracks(item) { tracks ->
                    if (albumLibrary != null) {
                        // One group: the favorites show it as an album.
                        scope.launch {
                            val added =
                                try {
                                    albumLibrary.addToFavorites(tracks)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: Exception) {
                                    -1
                                }
                            target = null
                            when {
                                added < 0 -> notifier.show("Couldn\u2019t add that album.", NoticeKind.Warning)
                                added == 0 -> notifier.show("Already in your favorites", NoticeKind.Success)
                                added == tracks.size -> notifier.show("Added the album to favorites", NoticeKind.Success)
                                else -> notifier.show("Added $added more songs to favorites", NoticeKind.Success)
                            }
                        }
                    } else {
                        val fresh = tracks.filter { it.isrc !in favorites }
                        fresh.forEach { toggleFavorite(it) }
                        target = null
                        notifier.show(
                            if (fresh.isEmpty()) "Already in your favorites" else "Added ${fresh.size} songs to favorites",
                            NoticeKind.Success,
                        )
                    }
                }
            },
            onAddToPlaylist = {
                target = null
                picker = item
            },
        )
    }
    picker?.let { item ->
        StationPlaylistSheet(
            playlists = playlists,
            accent = MaterialTheme.colorScheme.primary,
            onDismiss = { picker = null },
            onPick = { playlistId ->
                picker = null
                withTracks(item) { tracks ->
                    if (albumLibrary != null) {
                        // One group: the playlist shows it as an album.
                        scope.launch {
                            val added =
                                try {
                                    albumLibrary.addToPlaylist(tracks, playlistId)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: Exception) {
                                    -1
                                }
                            when {
                                added < 0 -> notifier.show("Couldn\u2019t add that album.", NoticeKind.Warning)
                                added == 0 -> notifier.show("Already in that playlist", NoticeKind.Success)
                                added == tracks.size -> notifier.show("Added the album to the playlist", NoticeKind.Success)
                                else -> notifier.show("Added $added more songs to the playlist", NoticeKind.Success)
                            }
                        }
                    } else {
                        tracks.forEach { addToPlaylist(it, playlistId) }
                        notifier.show("Added ${tracks.size} songs to the playlist", NoticeKind.Success)
                    }
                }
            },
        )
    }
}
