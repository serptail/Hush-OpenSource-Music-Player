package com.gamut.feature.library

import androidx.compose.foundation.focusable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalInputModeManager
import com.gamut.core.designsystem.FocusLive
import com.gamut.core.designsystem.LocalFocusLive
import androidx.compose.foundation.layout.widthIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.gamut.core.designsystem.GamutSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.Bold
import com.adamglin.phosphoricons.fill.CheckCircle
import com.adamglin.phosphoricons.fill.Heart
import com.adamglin.phosphoricons.fill.Play
import com.adamglin.phosphoricons.bold.ArrowCircleDown
import com.adamglin.phosphoricons.bold.ArrowLeft
import com.adamglin.phosphoricons.bold.BoxArrowDown
import com.adamglin.phosphoricons.bold.DotsSixVertical
import com.adamglin.phosphoricons.bold.DotsThreeVertical
import com.adamglin.phosphoricons.bold.Plus
import com.adamglin.phosphoricons.bold.ShareNetwork
import com.adamglin.phosphoricons.bold.User
import com.adamglin.phosphoricons.bold.Shuffle
import com.adamglin.phosphoricons.bold.Trash
import com.adamglin.phosphoricons.bold.TrashSimple
import com.gamut.core.designsystem.CoverTheme
import com.gamut.core.designsystem.ShareSheet
import com.gamut.core.designsystem.ArtistLine
import com.gamut.core.designsystem.ArtistPickerSheet
import com.gamut.core.designsystem.rememberArtistTap
import com.gamut.core.designsystem.splitArtists
import com.gamut.core.designsystem.LocalCoverPalette
import com.gamut.core.designsystem.LocalNotifier
import com.gamut.core.designsystem.NowPlayingBars
import com.gamut.core.designsystem.TransparentStatusBar
import com.gamut.core.designsystem.LocalNavSettled
import com.gamut.core.designsystem.glass
import com.gamut.core.designsystem.ink
import com.gamut.core.designsystem.pressScale
import com.gamut.core.model.Track
import com.gamut.feature.resolver.DownloadState
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/** How many songs fit on the wheel at once: the centered one, two above and two below. */
private const val VisibleSlots = 5

private val WheelCoverShape = RoundedCornerShape(18.dp)
private val WheelPillShape = WheelCoverShape   // same corner radius as the cover art
private val PlayButtonSize = 48.dp
private val ShufflePill = RoundedCornerShape(20.dp)

// Quick-actions sheet: background and text follow the app palette (GamutSheet + ink()).
private val SheetDestructive = Color(0xFFFF6B6B)

private val SideInset = 24.dp   // wheel edge to pill
/** On wide windows the wheel is a centered column this wide instead of stretching across the whole screen. */
private val WheelMaxWidth = 640.dp
private val PillInset = 8.dp    // pill edge to cover

/**
 * Favorites. Songs ride on a wheel: the centered song is flat, full size, in a soft pill with a Play button.
 * The others follow a big circle (they slide right) and every row points at the circle's center, like spokes,
 * so they tilt more the further they are from the middle.
 *
 * The wheel is finite: every song appears exactly once and scrolling stops at both ends. The first song sits in
 * the center, the second goes below it, the third above, the fourth below, and so on (see [wheelOrder]).
 *
 * Long-pressing a song opens a small sheet with quick actions (play, add to playlist, download, remove).
 * The three dots in the header open the manage sheet: the whole list to reorder (hold + drag), remove (tap),
 * and download for offline. Downloaded songs show an "Available offline" badge under their title on the wheel.
 *
 * The song that is playing is marked on the wheel (accent title, animated bars, "Playing now") no matter where playback
 * was started from: it is matched by [nowPlayingIsrc], not by what was tapped here. The wheel opens centered on it, and
 * a "Now playing" chip jumps back to it after you scroll away.
 */
@Composable
fun FavoritesScreen(
    tracks: List<Track>,
    onBack: () -> Unit,
    /** The picked track plus the list it was picked from (the queue). */
    onTrackSelected: (Track, List<Track>) -> Unit,
    /** Shuffle button: play [tracks] in random order. */
    onShuffle: (List<Track>) -> Unit = {},
    /** Quick action: take the track out of favorites. The caller must update [tracks]. */
    onRemove: (Track) -> Unit = {},
    /** Quick action: add the track to a playlist. The row is hidden from the sheet when null. */
    onAddToPlaylist: ((Track) -> Unit)? = null,
    /** Manage sheet: the favorites in their new order (also used to undo a removal). The caller saves it. */
    onReorder: (List<Track>) -> Unit = {},
    /** Opens the artist page of a track (by name). For several artists the picked name arrives as track.copy(artist = name). */
    onTrackArtist: (Track) -> Unit = {},
    /** ISRC of the track loaded in the player (from anywhere), or null. */
    nowPlayingIsrc: String? = null,
    downloads: PlaylistDownloadsViewModel,
    nowPlaying: @Composable () -> Unit = {},
) {
    val songs = tracks
    val downloadStates by downloads.states.collectAsStateWithLifecycle()
    val batch by downloads.batch.collectAsStateWithLifecycle()
    // Songs stored on the phone count as downloaded (no download call ever touches them), like in a playlist.
    // Local songs never go through the downloader: [remote] is everything a download call may touch.
    val remote = remember(songs) { songs.filter { !it.isLocal } }
    val localIsrcs = remember(songs) { songs.filter { it.isLocal }.map { it.isrc }.toSet() }
    // Rows / sheet: local songs have no download state at all (no ring, no line, no "Available offline" badge).
    val rowStates = remember(downloadStates, localIsrcs) { downloadStates - localIsrcs }
    // Header / manage sheet: local songs count as done.
    val allStates = remember(rowStates, localIsrcs) { rowStates + localIsrcs.associateWith { DownloadState.Done } }
    val downloadUi = remember(songs, allStates) { playlistDownloadUi(songs.map { it.isrc }.distinct(), allStates) }
    val downloading = downloadUi.status == PlaylistDownloadStatus.Active
    var showManage by remember { mutableStateOf(false) }

    val palette = LocalCoverPalette.current
    TransparentStatusBar(darkIcons = palette.isLight)
    val settled = LocalNavSettled.current

    CoverTheme(palette) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(44.dp).pressScale(onClick = onBack), contentAlignment = Alignment.Center) {
                    Icon(PhosphorIcons.Bold.ArrowLeft, "Back", tint = ink(0.85f), modifier = Modifier.size(22.dp))
                }
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Favorites",
                            style = MaterialTheme.typography.titleLarge,
                            color = ink(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Icon(
                            PhosphorIcons.Fill.Heart,
                            null,
                            tint = ink(),
                            modifier = Modifier.padding(start = 8.dp).size(16.dp),
                        )
                    }
                    val totalMs = songs.sumOf { it.durationMs }
                    Text(
                        if (downloading && batch.waitingForWifi) "Waiting for Wi\u2011Fi \u00B7 mobile data downloads are off"
                        else if (downloading) "Downloading ${minOf(downloadUi.done + 1, downloadUi.total)} of ${downloadUi.total}"
                        else (if (songs.size == 1) "1 song" else "${songs.size} songs") + if (totalMs > 0) " \u00B7 ${formatTotal(totalMs)}" else "",
                        style = MaterialTheme.typography.bodyMedium, color = ink(0.6f),
                        maxLines = if (downloading) 1 else Int.MAX_VALUE,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(
                    Modifier.pressScale(onClick = { if (songs.isNotEmpty()) onShuffle(songs) }).glass(ShufflePill).padding(horizontal = 16.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(PhosphorIcons.Bold.Shuffle, null, tint = ink(), modifier = Modifier.size(16.dp))
                    Text(
                        "Shuffle",
                        style = MaterialTheme.typography.labelLarge,
                        color = ink(),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                if (remote.isNotEmpty()) {
                    Spacer(Modifier.width(4.dp))
                    DownloadButton(
                        ui = downloadUi,
                        size = 40.dp,
                        glyphSize = 24.dp,
                        onClick = {
                            when (downloadUi.status) {
                                PlaylistDownloadStatus.Idle ->
                                    downloads.download(remote.filter { downloadStates[it.isrc] != DownloadState.Done }, label = "Favorites")
                                PlaylistDownloadStatus.Active -> downloads.cancel(remote.map { it.isrc })
                                PlaylistDownloadStatus.Done -> showManage = true
                            }
                        },
                    )
                }
                if (songs.isNotEmpty()) {
                    Spacer(Modifier.width(4.dp))
                    Box(Modifier.size(40.dp).pressScale(onClick = { showManage = true }), contentAlignment = Alignment.Center) {
                        Icon(PhosphorIcons.Bold.DotsThreeVertical, "Manage favorites", tint = ink(0.85f), modifier = Modifier.size(24.dp))
                    }
                } else {
                    Spacer(Modifier.width(12.dp))
                }
            }

            if (songs.isEmpty()) {
                Column(
                    Modifier.weight(1f).fillMaxWidth().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(Modifier.size(72.dp).background(ink(0.07f), CircleShape), contentAlignment = Alignment.Center) {
                        Icon(PhosphorIcons.Fill.Heart, null, tint = ink(0.45f), modifier = Modifier.size(30.dp))
                    }
                    Spacer(Modifier.height(18.dp))
                    Text("No favorites yet", style = MaterialTheme.typography.titleLarge, color = ink(), textAlign = TextAlign.Center)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Tap the + on any song in Discover to save it here.",
                        style = MaterialTheme.typography.bodyMedium, color = ink(0.6f), textAlign = TextAlign.Center,
                    )
                }
            } else if (!settled) {
                Spacer(Modifier.weight(1f))
            } else {
                // The wheel is its own centered column: on a tablet / TV the songs sit in the middle of the screen
                // (the wheel's rotation pivot and pill are measured against this column, not the window).
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                SongWheel(
                    songs = songs,
                    downloadStates = rowStates,
                    onTrackSelected = onTrackSelected,
                    onRemove = onRemove,
                    onAddToPlaylist = onAddToPlaylist,
                    onDownload = { if (!it.isLocal) downloads.download(listOf(it)) },
                    onRemoveDownload = { if (!it.isLocal) downloads.remove(listOf(it.isrc)) },
                    onCancelDownload = { if (!it.isLocal) downloads.cancel(listOf(it.isrc)) },
                    onTrackArtist = onTrackArtist,
                    nowPlayingIsrc = nowPlayingIsrc,
                    modifier = Modifier.widthIn(max = WheelMaxWidth).fillMaxSize(),
                )
                }
            }

            nowPlaying()
        }

        if (showManage) {
            ManageTracksSheet(
                title = "Manage favorites",
                removeDescription = "Remove from favorites",
                tracks = songs,
                downloadStates = allStates,
                onDismiss = { showManage = false },
                onReorder = onReorder,
                onRemove = onRemove,
                onDownloadAll = { downloads.download(remote.filter { downloadStates[it.isrc] != DownloadState.Done }, label = "Favorites") },
                onCancelDownloads = { downloads.cancel(remote.map { it.isrc }) },
                onRemoveDownloads = { downloads.remove(remote.map { it.isrc }) },
            )
        }
    }
}

/**
 * Where song [i] (0 = first added) sits relative to the center slot:
 * 0 -> center, 1 -> +1 (below), 2 -> -1 (above), 3 -> +2, 4 -> -2, ...
 */
private fun slotOffset(i: Int): Int = when {
    i == 0 -> 0
    i % 2 == 1 -> (i + 1) / 2
    else -> -(i / 2)
}

/** The songs in top-to-bottom order for the wheel. Each song appears exactly once. */
private fun wheelOrder(songs: List<Track>): List<Track> =
    songs.indices.sortedBy { slotOffset(it) }.map { songs[it] }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SongWheel(
    songs: List<Track>,
    downloadStates: Map<String, DownloadState>,
    onTrackSelected: (Track, List<Track>) -> Unit,
    onRemove: (Track) -> Unit,
    onAddToPlaylist: ((Track) -> Unit)?,
    onDownload: (Track) -> Unit,
    onRemoveDownload: (Track) -> Unit,
    onCancelDownload: (Track) -> Unit,
    onTrackArtist: (Track) -> Unit,
    nowPlayingIsrc: String?,
    modifier: Modifier = Modifier,
) {
    val ordered = remember(songs) { wheelOrder(songs) }
    val accent = MaterialTheme.colorScheme.primary
    val playingIndex = remember(ordered, nowPlayingIsrc) { ordered.indexOfFirst { it.isrc == nowPlayingIsrc && nowPlayingIsrc != null } }
    var menuTrack by remember { mutableStateOf<Track?>(null) }
    var shareTrack by remember { mutableStateOf<Track?>(null) }
    var artistPickTrack by remember { mutableStateOf<Track?>(null) }
    val haptics = LocalHapticFeedback.current

    BoxWithConstraints(modifier) {
        // One slot is a fifth of the free height, so exactly five songs are on the wheel.
        val itemHeight = (maxHeight / VisibleSlots).coerceIn(84.dp, 140.dp)
        // Half the free height as padding lets any item (even the first or last) reach the exact center.
        val pad = ((maxHeight - itemHeight) / 2).coerceAtLeast(0.dp)
        // Opens on the playing song when it is a favorite; otherwise the first song is the one in the center.
        // Songs above it are the 3rd, 5th, ... so there are (n - 1) / 2.
        val start = remember { if (playingIndex >= 0) playingIndex else (songs.size - 1) / 2 }
        val state = rememberLazyListState(initialFirstVisibleItemIndex = start)
        val scope = rememberCoroutineScope()

        // ---- TV remote ----
        // The wheel is ONE focus stop. Up / Down step the selected song by one and roll it to the middle with a single
        // scroll animation; OK plays it (long OK / Menu opens its options). The rows themselves are not focus targets:
        // geometry-based focus search on rotated, moving rows made the highlight jump and fight the scroll.
        // At the first / last song Up / Down are not consumed, so focus leaves the wheel (header, bottom strip).
        val wheelFocus = remember { FocusRequester() }
        var wheelFocused by remember { mutableStateOf(false) }
        var target by remember { mutableIntStateOf(start.coerceIn(0, (ordered.size - 1).coerceAtLeast(0))) }
        var enterLong by remember { mutableStateOf(false) }
        val live = LocalFocusLive.current
        val remote = LocalInputModeManager.current.inputMode == InputMode.Keyboard
        val settledNow = LocalNavSettled.current
        // Keep the selection in step with touch scrolling: when the wheel comes to rest, the centered song is the selected one.
        LaunchedEffect(state) {
            snapshotFlow {
                val info = state.layoutInfo
                val c = (info.viewportStartOffset + info.viewportEndOffset) / 2f
                info.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2f - c) }?.key to state.isScrollInProgress
            }.collect { (key, moving) -> if (!moving && key is Int) target = key }
        }
        // Remote: the wheel starts focused once the screen has finished sliding in.
        LaunchedEffect(settledNow, live) {
            if (settledNow && live && remote) runCatching { wheelFocus.requestFocus() }
        }

        // A light tick every time a new song rolls into the center.
        LaunchedEffect(state) {
            snapshotFlow {
                val info = state.layoutInfo
                val c = (info.viewportStartOffset + info.viewportEndOffset) / 2f
                info.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2f - c) }?.key
            }.distinctUntilChanged().drop(1).collect { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
        }
        // "Now playing" chip: only while the playing favorite is scrolled away from the center.
        val showJump by remember(playingIndex) { derivedStateOf { playingIndex >= 0 && state.focusOf(playingIndex) < 0.5f } }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .focusProperties { if (!live) canFocus = false }
                .focusRequester(wheelFocus)
                .onFocusChanged { wheelFocused = it.isFocused }
                .onKeyEvent { e ->
                    val last = ordered.lastIndex
                    when (e.key) {
                        Key.DirectionDown, Key.DirectionUp -> {
                            val step = if (e.key == Key.DirectionDown) 1 else -1
                            val next = target + step
                            if (next !in 0..last) false   // edge: let focus leave the wheel
                            else {
                                if (e.type == KeyEventType.KeyDown) {
                                    target = next
                                    scope.launch { state.animateScrollToItem(next) }
                                }
                                true
                            }
                        }
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                            if (e.type == KeyEventType.KeyDown) {
                                if (e.nativeKeyEvent.isLongPress) {
                                    enterLong = true
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    menuTrack = ordered.getOrNull(target)
                                }
                            } else if (e.type == KeyEventType.KeyUp) {
                                if (!enterLong) ordered.getOrNull(target)?.let { onTrackSelected(it, songs) }
                                enterLong = false
                            }
                            true
                        }
                        Key.Menu -> {
                            if (e.type == KeyEventType.KeyDown) menuTrack = ordered.getOrNull(target)
                            true
                        }
                        else -> false
                    }
                }
                .focusable(),
            state = state,
            flingBehavior = rememberSnapFlingBehavior(state),
            contentPadding = PaddingValues(vertical = pad),
        ) {
            items(count = ordered.size, key = { it }) { index ->
                val song = ordered[index]
                WheelRow(
                    song = song,
                    index = index,
                    itemHeight = itemHeight,
                    state = state,
                    offline = downloadStates[song.isrc] == DownloadState.Done,
                    downloadState = downloadStates[song.isrc],
                    onCancelDownload = { onCancelDownload(song) },
                    playing = index == playingIndex,
                    accent = accent,
                    onClick = {
                        // The centered song plays, any other song rolls to the center first.
                        if (state.focusOf(index) > 0.5f) onTrackSelected(song, songs)
                        else scope.launch { state.animateScrollToItem(index) }
                    },
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        menuTrack = song
                    },
                    onPlay = { if (state.focusOf(index) > 0.5f) onTrackSelected(song, songs) },
                    ringed = wheelFocused,
                    onArtist = { name -> onTrackArtist(song.copy(artist = name)) },
                )
            }
        }

        AnimatedVisibility(
            visible = showJump,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
            enter = fadeIn() + slideInVertically { -it / 2 },
            exit = fadeOut() + slideOutVertically { -it / 2 },
        ) {
            Row(
                Modifier
                    .pressScale(onClick = { scope.launch { state.animateScrollToItem(playingIndex) } })
                    .glass(RoundedCornerShape(50))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NowPlayingBars(color = ink())
                Text(
                    "Now playing",
                    style = MaterialTheme.typography.labelLarge,
                    color = ink(),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }

    menuTrack?.let { track ->
        TrackActionsSheet(
            track = track,
            downloadState = downloadStates[track.isrc],
            onDownload = { onDownload(track) },
            onRemoveDownload = { onRemoveDownload(track) },
            onDismiss = { menuTrack = null },
            onPlay = { onTrackSelected(track, songs) },
            onShare = { shareTrack = track },
            onAddToPlaylist = onAddToPlaylist?.let { add -> { add(track) } },
            onGoToArtist = {
                if (splitArtists(track.artist).size > 1) artistPickTrack = track else onTrackArtist(track)
            },
            onRemove = { onRemove(track) },
        )
    }
    artistPickTrack?.let { t ->
        ArtistPickerSheet(
            names = splitArtists(t.artist),
            albumTitle = t.album,
            onPick = { name -> onTrackArtist(t.copy(artist = name)) },
            onDismiss = { artistPickTrack = null },
        )
    }
    shareTrack?.let { t ->
        ShareSheet(title = t.title, artist = t.artist, artUrl = t.albumArtUrl, onDismiss = { shareTrack = null })
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WheelRow(
    song: Track,
    index: Int,
    itemHeight: Dp,
    state: LazyListState,
    offline: Boolean,
    downloadState: DownloadState?,
    onCancelDownload: () -> Unit,
    playing: Boolean,
    accent: Color,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onPlay: () -> Unit,
    /** The wheel has remote focus: the centered pill gets the focus ring (it is the only visible pill). */
    ringed: Boolean,
    onArtist: (String) -> Unit,
) {
    val pillHeight = itemHeight - 28.dp
    val coverSize = pillHeight - PillInset * 2
    // Queued or in progress (not done, not failed).
    val downloading = downloadState != null && downloadState != DownloadState.Done && downloadState !is DownloadState.Failed

    // Tap + long press on the whole row, with the same subtle press scale as pressScale().
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(if (pressed) 0.98f else 1f, label = "rowPress")

    Box(
        Modifier
            .fillMaxWidth()
            .height(itemHeight)
            // Rotate around the cover's center, so the cover sits exactly on the circle and cover, text and
            // pill all tilt together as one piece.
            .wheelTransform(state, index, itemHeight, pivotX = SideInset + PillInset + coverSize / 2)
            .graphicsLayer { scaleX = pressScale; scaleY = pressScale }
            .focusProperties { canFocus = false }   // the wheel is the focus stop, not the rows
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        FocusLive(false) {   // row buttons stay touch-only; the wheel handles the remote
        // The soft pill: only visible on the centered song. The cover lives inside it.
        Box(
            Modifier
                .fillMaxWidth()
                .height(pillHeight)
                .padding(horizontal = SideInset)
                // Only the centered song has a pill. An off-center pill (even a faint one) swings into the centered one.
                .graphicsLayer { alpha = state.focusOf(index) }
                .background(ink(if (ringed) 0.12f else 0.07f), WheelPillShape)
                .border(if (ringed) 2.dp else 1.dp, if (ringed) ink(0.9f) else ink(0.08f), WheelPillShape),
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = SideInset + PillInset),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BadgedCover(local = song.isLocal, tagSize = 24.dp) {
                CoverImage(song.albumArtUrl.orEmpty(), Modifier.size(coverSize).softCover(WheelCoverShape))
            }
            Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                Text(
                    song.title,
                    style = MaterialTheme.typography.titleLarge,
                    color = if (playing) accent else ink(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                ArtistLine(song.artist, song.album, onArtist, stackSize = 20.dp) { m ->
                    Text(
                        song.artist,
                        style = MaterialTheme.typography.bodyMedium,
                        color = ink(0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = m,
                    )
                }
                // Under the title and artist. Skipped on very short wheels, where it would not fit inside the pill.
                if (playing && itemHeight >= 96.dp) {
                    Text(
                        "Playing now",
                        style = MaterialTheme.typography.labelSmall,
                        color = accent,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                } else if (offline && itemHeight >= 96.dp) {
                    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(PhosphorIcons.Fill.CheckCircle, null, tint = ink(0.6f), modifier = Modifier.size(13.dp))
                        Text(
                            "Available offline",
                            style = MaterialTheme.typography.labelSmall,
                            color = ink(0.6f),
                            maxLines = 1,
                            modifier = Modifier.padding(start = 5.dp),
                        )
                    }
                }
                // Same thin line as the playlist rows. It takes the place of the "Available offline" badge until the
                // song is done (the playing song shows the ring below instead, so its label keeps its room).
                if (downloading && !playing && itemHeight >= 96.dp) {
                    Box(Modifier.padding(top = 5.dp).height(2.dp)) { DownloadProgressLine(downloadState) }
                }
            }
            // Progress ring while the song downloads (tap cancels), like the playlist rows. Never shown for idle / done songs.
            // Rows tilt around the cover, so the right-hand end of a neighbouring row swings into the centered pill.
            // Everything on that end therefore fades with focus (like the play button) and only the centered row's is live.
            // Neighbours show their progress with the thin line under the title instead.
            if (downloading) {
                TrackDownloadButton(
                    downloadState,
                    onDownload = {},
                    onCancel = { if (state.focusOf(index) > 0.5f) onCancelDownload() },
                    onDone = {},
                    glyph = 20.dp,
                    modifier = Modifier.graphicsLayer { alpha = state.focusOf(index) },
                )
            }
            // Just the icon, no circle behind it. Tinted with the text color, so it is always visible whatever the cover palette is.
            Box(
                Modifier
                    .size(PlayButtonSize)
                    .graphicsLayer { alpha = state.focusOf(index) }
                    .pressScale(onClick = onPlay),
                contentAlignment = Alignment.Center,
            ) {
                if (playing) NowPlayingBars(color = accent)
                else Icon(PhosphorIcons.Fill.Play, "Play", tint = ink(), modifier = Modifier.size(22.dp))
            }
        }
            }
    }
}

/** "3 hr 20 min" / "45 min". */
private fun formatTotal(ms: Long): String {
    val minutes = (ms / 60_000).coerceAtLeast(1)
    val h = minutes / 60
    val m = minutes % 60
    return if (h > 0) "$h hr $m min" else "$m min"
}

/** Small bottom sheet with quick actions for one favorite. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrackActionsSheet(
    track: Track,
    downloadState: DownloadState?,
    onDownload: () -> Unit,
    onRemoveDownload: () -> Unit,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    onShare: () -> Unit,
    onAddToPlaylist: (() -> Unit)?,
    onGoToArtist: () -> Unit,
    onRemove: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val notifier = LocalNotifier.current

    /** Slide the sheet away first, then run the action. */
    fun close(action: () -> Unit = {}) {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            onDismiss()
            action()
        }
    }

    GamutSheet(
        onDismiss = onDismiss,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle(color = ink(0.3f)) },
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BadgedCover(local = track.isLocal) {
                    CoverImage(track.albumArtUrl.orEmpty(), Modifier.size(52.dp).softCover(RoundedCornerShape(12.dp)))
                }
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(
                        track.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = ink(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        track.artist,
                        style = MaterialTheme.typography.bodyMedium,
                        color = ink(0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            SheetAction("Play", PhosphorIcons.Fill.Play) { close(onPlay) }
            if (!track.isLocal) when (downloadState) {
                DownloadState.Done -> SheetAction("Remove download", PhosphorIcons.Fill.CheckCircle) { close(onRemoveDownload) }
                null, is DownloadState.Failed -> SheetAction("Download", PhosphorIcons.Bold.ArrowCircleDown) { close(onDownload) }
                else -> Unit   // queued / downloading
            }
            SheetAction(
                if (splitArtists(track.artist).size > 1) "Go to artist\u2026" else "Go to artist",
                PhosphorIcons.Bold.User,
            ) { close(onGoToArtist) }
            SheetAction("Share", PhosphorIcons.Bold.ShareNetwork) { close(onShare) }
            if (onAddToPlaylist != null) {
                SheetAction("Add to playlist", PhosphorIcons.Bold.Plus) { close(onAddToPlaylist) }
            }
            SheetAction("Remove from favorites", PhosphorIcons.Bold.Trash, SheetDestructive) {
                close {
                    onRemove()
                    notifier.success("Removed from favorites")
                }
            }
        }
    }
}

@Composable
private fun SheetAction(
    label: String,
    icon: ImageVector,
    tint: Color = ink(),
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().pressScale(onClick = onClick, scaleDown = 0.98f).padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = tint, modifier = Modifier.padding(start = 16.dp))
    }
}

/**
 * Distance of an item's middle from the viewport middle, measured in item heights
 * (0 = centered, -1 = one slot above, +2 = two slots below). Null when the item is not on screen.
 */
private fun LazyListState.distanceFromCenter(key: Int): Float? {
    val info = layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.key == key } ?: return null
    val center = (info.viewportStartOffset + info.viewportEndOffset) / 2f
    return (item.offset + item.size / 2f - center) / item.size
}

/** 1 when the item is dead center, falling to 0 one slot away. */
private fun LazyListState.focusOf(key: Int): Float =
    (1f - abs(distanceFromCenter(key) ?: 9f)).coerceIn(0f, 1f)

/**
 * Puts the row on a big circle whose leftmost point is the center of the screen. With y = the row's distance
 * from the middle and r = the circle radius:
 *   angle = asin(y / r)             where on the circle the row is
 *   x shift = r * (1 - cos(angle))  slides right as it goes around
 *   tilt = -angle                   the row points at the circle's center (radial, like a spoke)
 * Runs in the draw phase (graphicsLayer), so scrolling never recomposes rows.
 *
 * Tuning: [radiusFactor] smaller = tighter wheel (more tilt), bigger = flatter. The fog is the last line:
 * fully visible in the center, gone at 2.6 slots away; raise 2.6f to see further, lower it for more fog.
 */
private fun Modifier.wheelTransform(
    state: LazyListState,
    key: Int,
    itemHeight: Dp,
    pivotX: Dp,
    radiusFactor: Float = 0.75f,
): Modifier = graphicsLayer {
    val d = state.distanceFromCenter(key) ?: return@graphicsLayer
    val y = d * itemHeight.toPx()
    val r = state.layoutInfo.viewportSize.height * radiusFactor
    val angle = asin((y / r).coerceIn(-0.85f, 0.85f))

    transformOrigin = TransformOrigin(pivotX.toPx() / size.width, 0.5f)
    translationX = r * (1f - cos(angle))
    rotationZ = -Math.toDegrees(angle.toDouble()).toFloat()
    val dist = abs(d)
    val s = (1f - 0.05f * dist).coerceAtLeast(0.8f)
    scaleX = s
    scaleY = s
    alpha = (1f - dist / 2.6f).coerceIn(0f, 1f).pow(0.8f)
}