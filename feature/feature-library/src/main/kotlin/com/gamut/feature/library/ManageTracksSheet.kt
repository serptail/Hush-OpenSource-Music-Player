package com.gamut.feature.library

import com.gamut.core.designsystem.GamutSheet
import com.gamut.core.designsystem.LocalCoverPalette
import com.gamut.core.designsystem.ink
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.zIndex
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.Bold
import com.adamglin.phosphoricons.fill.Archive
import com.adamglin.phosphoricons.fill.CheckCircle
import com.adamglin.phosphoricons.bold.BoxArrowDown
import com.adamglin.phosphoricons.bold.DotsSixVertical
import com.adamglin.phosphoricons.bold.Trash
import com.adamglin.phosphoricons.bold.TrashSimple
import com.gamut.core.designsystem.CoverPalette
import com.gamut.core.designsystem.pressScale
import com.gamut.core.model.Track
import com.gamut.feature.resolver.DownloadState

/** Background of every bottom sheet (same as the queue sheet: LocalCoverPalette.current.base). */
internal val LibrarySheetBackground: Color @Composable get() = LocalCoverPalette.current.base
private val ManageTextSecondary: Color @Composable get() = ink(0.6f)
private val ManageDestructive = Color(0xFFFF6B6B)

/** Fastest auto-scroll while dragging at an edge, in pixels per frame. */
private const val MAX_AUTOSCROLL_PX = 14f

// ---------------------------------------------------------------------------------------------------------------
// Manage sheet
// ---------------------------------------------------------------------------------------------------------------

private fun songsLabel(n: Int) = if (n == 1) "1 song" else "$n songs"

/**
 * Manage a list of songs (favorites or a playlist): every song in a list, in order.
 *  - Hold a song, then move it up or down: the others make room, the new order is saved when you let go.
 *  - Tap a song to remove it from favorites (an Undo pill appears at the bottom).
 *  - Download everything for offline listening, or remove the downloads.
 * The list shown is a local draft while you drag; the database is written once, on drop.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ManageTracksSheet(
    title: String,
    removeDescription: String,
    tracks: List<Track>,
    downloadStates: Map<String, DownloadState>,
    onDismiss: () -> Unit,
    onReorder: (List<Track>) -> Unit,
    onRemove: (Track) -> Unit,
    onDownloadAll: () -> Unit,
    onCancelDownloads: () -> Unit,
    onRemoveDownloads: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val haptics = LocalHapticFeedback.current
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val listState = rememberLazyListState()

    val draft = remember { mutableStateListOf<Track>().apply { addAll(tracks) } }
    var draggingKey by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    // Which way the finger last moved (-1 up, 1 down, 0 not yet): the list only auto-scrolls the way you are heading.
    var dragDirection by remember { mutableIntStateOf(0) }
    var undo by remember { mutableStateOf<Pair<Int, Track>?>(null) }
    var confirmRemoveDownloads by remember { mutableStateOf(false) }

    // Follow the database (a removal, an undo, a like from elsewhere), but never while a drag is in progress.
    LaunchedEffect(tracks) {
        if (draggingKey == null) {
            draft.clear()
            draft.addAll(tracks)
        }
    }

    // Songs stored on the phone have nothing to download: the download row only counts the streamed ones.
    val isrcs = remember(tracks) { tracks.filter { !it.isLocal }.map { it.isrc }.distinct() }
    val downloadUi = remember(isrcs, downloadStates) { playlistDownloadUi(isrcs, downloadStates) }
    val downloadedCount = remember(isrcs, downloadStates) { isrcs.count { downloadStates[it] == DownloadState.Done } }

    /**
     * The dragged row's slot in the list, but only when the layout has caught up with the last swap (otherwise its
     * numbers are one row stale). Null = not dragging, scrolled out of view, or still catching up.
     */
    fun draggedItem(): androidx.compose.foundation.lazy.LazyListItemInfo? {
        val key = draggingKey ?: return null
        val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key } ?: return null
        return if (item.index == draft.indexOfFirst { it.isrc == key }) item else null
    }

    /** Boundary: the dragged row can never leave the list, top or bottom (it stops at the edge, it does not float out). */
    fun clampOffset(raw: Float): Float {
        val item = draggedItem() ?: return raw
        val info = listState.layoutInfo
        val min = (info.viewportStartOffset - item.offset).toFloat()
        val max = (info.viewportEndOffset - item.size - item.offset).toFloat()
        return if (max < min) raw else raw.coerceIn(min, max)
    }

    /** Moves the dragged song when its middle crosses into a neighbour. Rows are all the same height. */
    fun swapIfNeeded() {
        val current = draggedItem() ?: return
        val key = draggingKey ?: return
        val info = listState.layoutInfo.visibleItemsInfo
        val from = current.index
        val middle = current.offset + current.size / 2f + dragOffset
        val target = info.firstOrNull { it.key != key && middle >= it.offset && middle < it.offset + it.size } ?: return
        val to = draft.indexOfFirst { it.isrc == target.key }
        if (to < 0) return
        // A LazyColumn keeps its first visible ITEM (by key) in place when the list is reordered. If the swap moves
        // that item (dragging near the top), the whole list would jump by a row and throw the row off your finger.
        // So remember the scroll position and pin it again after the swap.
        val firstIndex = listState.firstVisibleItemIndex
        val firstOffset = listState.firstVisibleItemScrollOffset
        draft.add(to, draft.removeAt(from))
        listState.requestScrollToItem(firstIndex, firstOffset)
        // The row now sits where the target was: shift the finger offset so it stays under the finger.
        dragOffset += (current.offset - target.offset)
    }

    // Near the top or bottom edge the list scrolls by itself, in the direction you are dragging, faster the closer
    // you get to the edge. Nothing happens until you actually move towards an edge.
    LaunchedEffect(draggingKey) {
        if (draggingKey == null) return@LaunchedEffect
        while (draggingKey != null) {
            val current = draggedItem()
            if (current != null && dragDirection != 0) {
                val info = listState.layoutInfo
                val top = current.offset + dragOffset
                val gapTop = top - info.viewportStartOffset
                val gapBottom = info.viewportEndOffset - (top + current.size)
                val zone = current.size * 0.75f
                val speed = when {
                    dragDirection < 0 && gapTop < zone -> -MAX_AUTOSCROLL_PX * ((zone - gapTop) / zone).coerceIn(0f, 1f)
                    dragDirection > 0 && gapBottom < zone -> MAX_AUTOSCROLL_PX * ((zone - gapBottom) / zone).coerceIn(0f, 1f)
                    else -> 0f
                }
                if (speed != 0f) {
                    // The content moves under the row: add what was really scrolled, so the row stays under the finger.
                    dragOffset += listState.scrollBy(speed)
                }
            }
            swapIfNeeded()
            androidx.compose.runtime.withFrameNanos { }
        }
    }

    val onDownloadClick: () -> Unit = {
        when (downloadUi.status) {
            PlaylistDownloadStatus.Idle -> onDownloadAll()
            PlaylistDownloadStatus.Active -> onCancelDownloads()
            PlaylistDownloadStatus.Done -> confirmRemoveDownloads = true
        }
    }

    GamutSheet(
        onDismiss = onDismiss,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle(color = ink(0.3f)) },
    ) {
        Column(Modifier.fillMaxWidth().height(screenHeight * 0.86f).navigationBarsPadding()) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                color = ink(),
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Text(
                "Hold and drag to reorder \u00B7 trash to remove",
                style = MaterialTheme.typography.bodyMedium,
                color = ManageTextSecondary,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 2.dp, bottom = 14.dp),
            )

            // Download everything: the whole row and the button on it do the same thing.
            if (isrcs.isNotEmpty()) Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(ink(0.07f))
                    .pressScale(onClick = onDownloadClick, scaleDown = 0.98f)
                    .padding(start = 18.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        when (downloadUi.status) {
                            PlaylistDownloadStatus.Idle -> "Download all"
                            PlaylistDownloadStatus.Active -> "Downloading ${minOf(downloadUi.done + 1, downloadUi.total)} of ${downloadUi.total}"
                            PlaylistDownloadStatus.Done -> "Available offline"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = ink(),
                    )
                    Text(
                        when (downloadUi.status) {
                            PlaylistDownloadStatus.Idle ->
                                if (downloadedCount > 0) "$downloadedCount of ${isrcs.size} available offline" else "${songsLabel(isrcs.size)} for offline listening"
                            PlaylistDownloadStatus.Active -> "Tap to stop"
                            PlaylistDownloadStatus.Done -> "All ${songsLabel(isrcs.size)} are downloaded"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = ManageTextSecondary,
                    )
                }
                DownloadButton(
                    downloadUi,
                    onClick = onDownloadClick,
                    size = 40.dp,
                    glyphSize = 22.dp,
                    tint = ink(0.85f),
                    accent = ink(),
                    idleIcon = PhosphorIcons.Bold.BoxArrowDown,   // all of them: box-arrow-down -> (ring) -> archive
                    doneIcon = PhosphorIcons.Fill.Archive,
                )
            }
            if (downloadedCount > 0 && downloadUi.status != PlaylistDownloadStatus.Active) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .pressScale(onClick = { confirmRemoveDownloads = true }, scaleDown = 0.98f)
                        .padding(horizontal = 24.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(PhosphorIcons.Bold.Trash, null, tint = ManageDestructive, modifier = Modifier.size(18.dp))
                    Text("Remove downloads", style = MaterialTheme.typography.titleMedium, color = ManageDestructive, modifier = Modifier.padding(start = 18.dp))
                }
            }

            Box(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp).height(1.dp).background(ink(0.10f)))

            LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(vertical = 4.dp)) {
                items(draft, key = { it.isrc }) { track ->
                    val dragging = draggingKey == track.isrc
                    ManageRow(
                        track = track,
                        removeDescription = removeDescription,
                        offline = track.isLocal || downloadStates[track.isrc] == DownloadState.Done,
                        dragging = dragging,
                        modifier = if (dragging) {
                            Modifier.zIndex(1f).graphicsLayer { translationY = dragOffset }
                        } else {
                            Modifier.animateItem()
                        },
                        onRemove = {
                            val at = draft.indexOfFirst { it.isrc == track.isrc }
                            if (at >= 0) {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                draft.removeAt(at)
                                undo = at to track
                                onRemove(track)
                            }
                        },
                        onDragStart = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            dragOffset = 0f
                            dragDirection = 0
                            draggingKey = track.isrc
                        },
                        onDrag = { dy ->
                            if (dy != 0f) dragDirection = if (dy < 0f) -1 else 1
                            dragOffset = clampOffset(dragOffset + dy)
                            swapIfNeeded()
                        },
                        onDragEnd = {
                            draggingKey = null
                            dragDirection = 0
                            dragOffset = 0f
                            if (draft.map { it.isrc } != tracks.map { it.isrc }) onReorder(draft.toList())
                        },
                    )
                }
            }

            undo?.let { (at, removed) ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(ink(0.10f))
                        .padding(start = 18.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Removed \u201C${removed.title}\u201D",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ink(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "Undo",
                        style = MaterialTheme.typography.titleMedium,
                        color = ink(),
                        modifier = Modifier
                            .pressScale(onClick = {
                                draft.add(at.coerceIn(0, draft.size), removed)
                                undo = null
                                onReorder(draft.toList())   // re-adds it, in its old place
                            })
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                    )
                }
            }
        }
    }

    if (confirmRemoveDownloads) {
        ConfirmRemoveDialog(
            onConfirm = { confirmRemoveDownloads = false; onRemoveDownloads() },
            onDismiss = { confirmRemoveDownloads = false },
        )
    }
}

/**
 * One favorite in the manage list. Tapping the row does nothing (no accidental removal). Reordering:
 *  - touch the grip on the right and move: the row follows the finger at once;
 *  - or hold anywhere else on the row, then move.
 * The trash button removes the song (the sheet offers Undo). All of it is one gesture handler so the zones
 * (row / trash / grip) never fight over the same touch.
 */
@Composable
private fun ManageRow(
    track: Track,
    removeDescription: String,
    offline: Boolean,
    dragging: Boolean,
    modifier: Modifier,
    onRemove: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    val start by rememberUpdatedState(onDragStart)
    val dragBy by rememberUpdatedState(onDrag)
    val end by rememberUpdatedState(onDragEnd)
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .graphicsLayer { val s = if (dragging) 1.03f else 1f; scaleX = s; scaleY = s }
            .shadow(if (dragging) 10.dp else 0.dp, shape)
            .background(if (dragging) androidx.compose.ui.graphics.lerp(LocalCoverPalette.current.base, ink(), 0.12f) else Color.Transparent, shape)
            .pointerInput(track.isrc) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // Right edge: [trash 44dp][grip 44dp][12dp padding].
                    val gripStart = size.width - 56.dp.toPx()
                    val trashStart = gripStart - 44.dp.toPx()
                    // The trash button handles its own tap.
                    if (down.position.x in trashStart..gripStart) return@awaitEachGesture
                    val grabbed = if (down.position.x >= gripStart) {
                        awaitVerticalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                    } else {
                        awaitLongPressOrCancellation(down.id)
                    }
                    if (grabbed != null) {
                        start()
                        try {
                            drag(grabbed.id) { change ->
                                // Read the movement BEFORE consuming: a consumed change reports zero movement.
                                val dy = change.positionChange().y
                                change.consume()
                                dragBy(dy)
                            }
                        } finally {
                            end()
                        }
                    }
                }
            }
            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BadgedCover(local = track.isLocal) {
            CoverImage(track.albumArtUrl.orEmpty(), Modifier.size(46.dp).softCover(RoundedCornerShape(10.dp)))
        }
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(track.title, style = MaterialTheme.typography.titleMedium, color = ink(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track.artist, style = MaterialTheme.typography.bodyMedium, color = ManageTextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (offline) {
            Icon(PhosphorIcons.Fill.CheckCircle, "Available offline", tint = ManageTextSecondary, modifier = Modifier.padding(end = 6.dp).size(15.dp))
        }
        Box(Modifier.size(44.dp).pressScale(onClick = onRemove), contentAlignment = Alignment.Center) {
            Icon(PhosphorIcons.Bold.TrashSimple, removeDescription, tint = ManageDestructive, modifier = Modifier.size(18.dp))
        }
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            Icon(PhosphorIcons.Bold.DotsSixVertical, "Drag to reorder", tint = ink(0.5f), modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun ConfirmRemoveDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(LibrarySheetBackground).padding(24.dp)) {
            Text("Remove downloads?", style = MaterialTheme.typography.titleLarge, color = ink())
            Text(
                "Your favorites stay, but won't play offline until you download them again.",
                style = MaterialTheme.typography.bodyMedium,
                color = ManageTextSecondary,
                modifier = Modifier.padding(top = 10.dp),
            )
            Row(Modifier.fillMaxWidth().padding(top = 22.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End) {
                Text(
                    "Cancel",
                    style = MaterialTheme.typography.titleMedium,
                    color = ink(),
                    modifier = Modifier.pressScale(onClick = onDismiss).padding(horizontal = 16.dp, vertical = 10.dp),
                )
                Text(
                    "Remove",
                    style = MaterialTheme.typography.titleMedium,
                    color = ManageDestructive,
                    modifier = Modifier.pressScale(onClick = onConfirm).padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
    }
}