package com.gamut.feature.library

import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.Light
import com.adamglin.phosphoricons.fill.CheckCircle
import com.adamglin.phosphoricons.fill.Heart
import com.adamglin.phosphoricons.fill.Play
import com.adamglin.phosphoricons.light.ArrowCircleDown
import com.adamglin.phosphoricons.light.ArrowLeft
import com.adamglin.phosphoricons.light.BoxArrowDown
import com.adamglin.phosphoricons.light.DotsSixVertical
import com.adamglin.phosphoricons.light.DotsThreeVertical
import com.adamglin.phosphoricons.light.Plus
import com.adamglin.phosphoricons.light.Shuffle
import com.adamglin.phosphoricons.light.Trash
import com.adamglin.phosphoricons.light.TrashSimple
import com.gamut.core.designsystem.CoverTheme
import com.gamut.core.designsystem.LocalCoverPalette
import com.gamut.core.designsystem.LocalNotifier
import com.gamut.core.designsystem.TransparentStatusBar
import com.gamut.core.designsystem.glass
import com.gamut.core.designsystem.ink
import com.gamut.core.designsystem.pressScale
import com.gamut.core.model.Track
import com.gamut.feature.resolver.DownloadState
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlinx.coroutines.launch

/** How many songs fit on the wheel at once: the centered one, two above and two below. */
private const val VisibleSlots = 5

private val WheelCoverShape = RoundedCornerShape(18.dp)
private val WheelPillShape = RoundedCornerShape(30.dp)
private val PlayButtonSize = 52.dp
private val ShufflePill = RoundedCornerShape(20.dp)

// Quick-actions sheet: always dark, whatever the cover palette is, with light text on top.
private val SheetBackground: Color get() = LibrarySheetBackground
private val SheetText = Color.White
private val SheetTextSecondary = Color.White.copy(alpha = 0.6f)
private val SheetDestructive = Color(0xFFFF6B6B)

private val SideInset = 20.dp   // screen edge to pill
private val PillInset = 10.dp   // pill edge to cover

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
    downloads: PlaylistDownloadsViewModel,
    nowPlaying: @Composable () -> Unit = {},
) {
    val songs = tracks
    val downloadStates by downloads.states.collectAsStateWithLifecycle()
    var showManage by remember { mutableStateOf(false) }

    val palette = LocalCoverPalette.current
    TransparentStatusBar(darkIcons = palette.isLight)

    CoverTheme(palette) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(44.dp).pressScale(onClick = onBack), contentAlignment = Alignment.Center) {
                    Icon(PhosphorIcons.Light.ArrowLeft, "Back", tint = ink(0.85f), modifier = Modifier.size(22.dp))
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
                    Text("${songs.size} songs", style = MaterialTheme.typography.bodyMedium, color = ink(0.6f))
                }
                Row(
                    Modifier.pressScale(onClick = { if (songs.isNotEmpty()) onShuffle(songs) }).glass(ShufflePill).padding(horizontal = 16.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(PhosphorIcons.Light.Shuffle, null, tint = ink(), modifier = Modifier.size(16.dp))
                    Text(
                        "Shuffle",
                        style = MaterialTheme.typography.labelLarge,
                        color = ink(),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                if (songs.isNotEmpty()) {
                    Spacer(Modifier.width(4.dp))
                    Box(Modifier.size(40.dp).pressScale(onClick = { showManage = true }), contentAlignment = Alignment.Center) {
                        Icon(PhosphorIcons.Light.DotsThreeVertical, "Manage favorites", tint = ink(0.85f), modifier = Modifier.size(24.dp))
                    }
                } else {
                    Spacer(Modifier.width(12.dp))
                }
            }

            if (songs.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth().padding(24.dp)) {
                    Text(
                        "Nothing here yet. Like songs from Discover.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ink(0.6f),
                    )
                }
            } else {
                SongWheel(
                    songs = songs,
                    downloadStates = downloadStates,
                    onTrackSelected = onTrackSelected,
                    onRemove = onRemove,
                    onAddToPlaylist = onAddToPlaylist,
                    onDownload = { downloads.download(listOf(it)) },
                    onRemoveDownload = { downloads.remove(listOf(it.isrc)) },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            }

            nowPlaying()
        }

        if (showManage) {
            ManageTracksSheet(
                title = "Manage favorites",
                removeDescription = "Remove from favorites",
                tracks = songs,
                downloadStates = downloadStates,
                onDismiss = { showManage = false },
                onReorder = onReorder,
                onRemove = onRemove,
                onDownloadAll = { downloads.download(songs, label = "Favorites") },
                onCancelDownloads = { downloads.cancel(songs.map { it.isrc }) },
                onRemoveDownloads = { downloads.remove(songs.map { it.isrc }) },
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
    modifier: Modifier = Modifier,
) {
    val ordered = remember(songs) { wheelOrder(songs) }
    var menuTrack by remember { mutableStateOf<Track?>(null) }
    val haptics = LocalHapticFeedback.current

    BoxWithConstraints(modifier) {
        // One slot is a fifth of the free height, so exactly five songs are on the wheel.
        val itemHeight = (maxHeight / VisibleSlots).coerceIn(84.dp, 140.dp)
        // Half the free height as padding lets any item (even the first or last) reach the exact center.
        val pad = ((maxHeight - itemHeight) / 2).coerceAtLeast(0.dp)
        // The first song is the one in the center. Songs above it are the 3rd, 5th, ... so there are (n - 1) / 2.
        val start = remember { (songs.size - 1) / 2 }
        val state = rememberLazyListState(initialFirstVisibleItemIndex = start)
        val scope = rememberCoroutineScope()

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
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
            onAddToPlaylist = onAddToPlaylist?.let { add -> { add(track) } },
            onRemove = { onRemove(track) },
        )
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
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onPlay: () -> Unit,
) {
    val pillHeight = itemHeight - 20.dp
    val coverSize = pillHeight - PillInset * 2

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
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        // The soft pill: only visible on the centered song. The cover lives inside it.
        Box(
            Modifier
                .fillMaxWidth()
                .height(pillHeight)
                .padding(horizontal = SideInset)
                .graphicsLayer { alpha = state.focusOf(index) }
                .background(ink(0.07f), WheelPillShape)
                .border(1.dp, ink(0.08f), WheelPillShape),
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = SideInset + PillInset),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CoverImage(song.albumArtUrl.orEmpty(), Modifier.size(coverSize).softCover(WheelCoverShape))
            Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                Text(
                    song.title,
                    style = MaterialTheme.typography.titleLarge,
                    color = ink(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    song.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = ink(0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // Under the title and artist. Skipped on very short wheels, where it would not fit inside the pill.
                if (offline && itemHeight >= 96.dp) {
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
            }
            // Tinted with the text color, so it is always visible whatever the cover palette is.
            Box(
                Modifier
                    .size(PlayButtonSize)
                    .graphicsLayer { alpha = state.focusOf(index) }
                    .background(ink(0.14f), CircleShape)
                    .pressScale(onClick = onPlay),
                contentAlignment = Alignment.Center,
            ) {
                Icon(PhosphorIcons.Fill.Play, "Play", tint = ink(), modifier = Modifier.size(22.dp))
            }
        }
    }
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
    onAddToPlaylist: (() -> Unit)?,
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

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SheetBackground,
        contentColor = SheetText,
        dragHandle = { BottomSheetDefaults.DragHandle(color = Color.White.copy(alpha = 0.3f)) },
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CoverImage(track.albumArtUrl.orEmpty(), Modifier.size(52.dp).softCover(RoundedCornerShape(12.dp)))
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(
                        track.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = SheetText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        track.artist,
                        style = MaterialTheme.typography.bodyMedium,
                        color = SheetTextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            SheetAction("Play", PhosphorIcons.Fill.Play) { close(onPlay) }
            when (downloadState) {
                DownloadState.Done -> SheetAction("Remove download", PhosphorIcons.Fill.CheckCircle) { close(onRemoveDownload) }
                null, is DownloadState.Failed -> SheetAction("Download", PhosphorIcons.Light.ArrowCircleDown) { close(onDownload) }
                else -> Unit   // queued / downloading
            }
            if (onAddToPlaylist != null) {
                SheetAction("Add to playlist", PhosphorIcons.Light.Plus) { close(onAddToPlaylist) }
            }
            SheetAction("Remove from favorites", PhosphorIcons.Light.Trash, SheetDestructive) {
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
    tint: Color = SheetText,
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