package com.gamut.feature.playerui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import com.gamut.core.designsystem.ExplicitMark
import com.gamut.core.designsystem.GamutSheet
import com.gamut.core.designsystem.LocalCoverPalette
import com.gamut.core.designsystem.TablerIcons
import com.gamut.core.designsystem.bounceClickable
import com.gamut.core.designsystem.ink
import com.gamut.core.designsystem.thumbnailBorder
import com.gamut.core.model.QueueState
import com.gamut.core.model.Track

private const val MAX_AUTOSCROLL_PX = 14f

/**
 * One row of the queue as the sheet shows it. The key stays with the entry while it is dragged around, which is
 * what lets the list animate a move; it is the track plus how many copies of that track come before it, so the
 * same song queued twice still gets two different keys.
 */
private class QueueEntry(
    val key: String,
    val track: Track,
    val isCurrent: Boolean,
)

private fun entriesOf(queue: QueueState): List<QueueEntry> {
    val seen = HashMap<String, Int>()
    return queue.tracks.mapIndexed { index, track ->
        val copies = seen[track.isrc] ?: 0
        seen[track.isrc] = copies + 1
        QueueEntry("${track.isrc}#$copies", track, index == queue.currentIndex)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QueueSheet(
    queue: QueueState,
    accent: Color,
    onDismiss: () -> Unit,
    onPlay: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val haptics = LocalHapticFeedback.current
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = queue.currentIndex.coerceAtLeast(0))

    // What the list shows. It follows the player's queue, except while a row is being dragged: then it holds the
    // order the listener is building, and the player is told once, when the row is dropped.
    val draft = remember { mutableStateListOf<QueueEntry>().apply { addAll(entriesOf(queue)) } }
    var draggingKey by remember { mutableStateOf<String?>(null) }
    var dragFrom by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var dragDirection by remember { mutableIntStateOf(0) }

    LaunchedEffect(queue) {
        if (draggingKey == null) {
            draft.clear()
            draft.addAll(entriesOf(queue))
        }
    }

    fun draggedItem(): LazyListItemInfo? {
        val key = draggingKey ?: return null
        val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key } ?: return null
        return if (item.index == draft.indexOfFirst { it.key == key }) item else null
    }

    fun clampOffset(raw: Float): Float {
        val item = draggedItem() ?: return raw
        val info = listState.layoutInfo
        val min = (info.viewportStartOffset - item.offset).toFloat()
        val max = (info.viewportEndOffset - item.size - item.offset).toFloat()
        return if (max < min) raw else raw.coerceIn(min, max)
    }

    fun swapIfNeeded() {
        val current = draggedItem() ?: return
        val key = draggingKey ?: return
        val info = listState.layoutInfo.visibleItemsInfo
        val from = current.index
        val middle = current.offset + current.size / 2f + dragOffset
        val target = info.firstOrNull { it.key != key && middle >= it.offset && middle < it.offset + it.size } ?: return
        val to = draft.indexOfFirst { it.key == target.key }
        if (to < 0) return
        val firstIndex = listState.firstVisibleItemIndex
        val firstOffset = listState.firstVisibleItemScrollOffset
        draft.add(to, draft.removeAt(from))
        listState.requestScrollToItem(firstIndex, firstOffset)
        dragOffset += (current.offset - target.offset)
    }

    // Holding a row near the top or bottom edge of the list scrolls it, so a far-away spot can be reached.
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
                val speed =
                    when {
                        dragDirection < 0 && gapTop < zone -> -MAX_AUTOSCROLL_PX * ((zone - gapTop) / zone).coerceIn(0f, 1f)
                        dragDirection > 0 && gapBottom < zone -> MAX_AUTOSCROLL_PX * ((zone - gapBottom) / zone).coerceIn(0f, 1f)
                        else -> 0f
                    }
                if (speed != 0f) {
                    dragOffset += listState.scrollBy(speed)
                }
            }
            swapIfNeeded()
            withFrameNanos { }
        }
    }

    GamutSheet(onDismiss = onDismiss, sheetState = sheetState) {
        Text(
            text = "Up next",
            style = MaterialTheme.typography.titleLarge,
            color = ink(),
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        if (queue.tracks.isEmpty()) {
            Text(
                text = "The queue is empty",
                style = MaterialTheme.typography.bodyMedium,
                color = ink(0.6f),
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp),
            )
            return@GamutSheet
        }
        Text(
            text = "Tap a song to play it \u00B7 drag the handle to reorder",
            style = MaterialTheme.typography.bodyMedium,
            color = ink(0.6f),
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
        )
        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 32.dp)) {
            itemsIndexed(draft, key = { _, entry -> entry.key }) { index, entry ->
                val dragging = draggingKey == entry.key
                QueueRow(
                    entry = entry,
                    accent = accent,
                    dragging = dragging,
                    modifier =
                        if (dragging) {
                            Modifier.zIndex(1f).graphicsLayer { translationY = dragOffset }
                        } else {
                            Modifier.animateItem()
                        },
                    onPlay = { onPlay(index) },
                    onRemove = { onRemove(index) },
                    onDragStart = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        dragOffset = 0f
                        dragDirection = 0
                        dragFrom = index
                        draggingKey = entry.key
                    },
                    onDrag = { dy ->
                        if (dy != 0f) dragDirection = if (dy < 0f) -1 else 1
                        dragOffset = clampOffset(dragOffset + dy)
                        swapIfNeeded()
                    },
                    onDragEnd = {
                        val key = draggingKey
                        val to = if (key == null) -1 else draft.indexOfFirst { it.key == key }
                        val from = dragFrom
                        draggingKey = null
                        dragDirection = 0
                        dragOffset = 0f
                        dragFrom = -1
                        if (from >= 0 && to >= 0 && from != to) onMove(from, to)
                    },
                )
            }
        }
    }
}

@Composable
private fun QueueRow(
    entry: QueueEntry,
    accent: Color,
    dragging: Boolean,
    modifier: Modifier,
    onPlay: () -> Unit,
    onRemove: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    val track = entry.track
    val isCurrent = entry.isCurrent
    val start by rememberUpdatedState(onDragStart)
    val dragBy by rememberUpdatedState(onDrag)
    val end by rememberUpdatedState(onDragEnd)
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .graphicsLayer {
                val s = if (dragging) 1.03f else 1f
                scaleX = s
                scaleY = s
            }.shadow(if (dragging) 10.dp else 0.dp, shape)
            .background(if (dragging) lerp(LocalCoverPalette.current.base, ink(), 0.12f) else Color.Transparent, shape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Everything but the handle plays the song, so a tap on the handle can never start playback by mistake.
        Row(
            Modifier
                .weight(1f)
                .bounceClickable { onPlay() }
                .padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = track.albumArtUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).thumbnailBorder(RoundedCornerShape(8.dp)),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ExplicitMark(track.isExplicit, ink(0.6f))
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isCurrent) accent else ink(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                Text(
                    text = track.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = ink(0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (!isCurrent) {
            Box(
                Modifier.size(40.dp).bounceClickable { onRemove() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(TablerIcons.Close, "Remove from queue", tint = ink(0.6f), modifier = Modifier.size(20.dp))
            }
        }
        Box(
            Modifier
                .size(44.dp)
                .pointerInput(entry.key) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val grabbed = awaitVerticalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                        if (grabbed != null) {
                            start()
                            try {
                                drag(grabbed.id) { change ->
                                    val dy = change.positionChange().y
                                    change.consume()
                                    dragBy(dy)
                                }
                            } finally {
                                end()
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(TablerIcons.GripVertical, "Drag to reorder", tint = ink(0.5f), modifier = Modifier.size(18.dp))
        }
    }
}
