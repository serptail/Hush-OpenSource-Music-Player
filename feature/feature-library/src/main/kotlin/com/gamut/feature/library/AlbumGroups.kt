package com.gamut.feature.library

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Bold
import com.adamglin.phosphoricons.bold.DotsThreeVertical
import com.gamut.core.designsystem.ArtistLine
import com.gamut.core.designsystem.ExplicitMark
import com.gamut.core.designsystem.LocalCoverPalette
import com.gamut.core.designsystem.NowPlayingBars
import com.gamut.core.designsystem.TablerIcons
import com.gamut.core.designsystem.ink
import com.gamut.core.designsystem.pressScale
import com.gamut.core.designsystem.splitArtists
import com.gamut.core.model.GroupedTrack
import com.gamut.core.model.Track
import java.util.Collections

// ---------------------------------------------------------------------------------------------
// Model. A playlist or the favorites is a list of entries: a loose song, or an album that was
// added as one group and is shown and managed as one.
// ---------------------------------------------------------------------------------------------

internal sealed interface ListEntry {
    /** A song that was added on its own. [item] carries the song's place in the whole list. */
    data class Single(
        val item: IndexedValue<Track>,
    ) : ListEntry

    /** An album group: its songs in order, each with its place in the whole list. */
    data class Album(
        val id: String,
        val items: List<IndexedValue<Track>>,
    ) : ListEntry {
        /** Place in the whole list of the group's first song. Distinguishes two blocks that share an id. */
        val start: Int get() = items.first().index

        val key: String get() = "album:$id:$start"

        val title: String =
            items.firstNotNullOfOrNull { it.value.album?.trim()?.takeIf { name -> name.isNotEmpty() } } ?: "Album"

        /** The artist most of the songs are credited to. */
        val artist: String =
            items
                .groupingBy { splitArtists(it.value.artist).firstOrNull() ?: it.value.artist }
                .eachCount()
                .maxByOrNull { it.value }
                ?.key
                .orEmpty()

        val coverUrl: String? = items.firstNotNullOfOrNull { it.value.albumArtUrl?.takeIf { url -> url.isNotBlank() } }

        val totalMs: Long = items.sumOf { it.value.durationMs.coerceAtLeast(0L) }

        val tracks: List<Track> get() = items.map { it.value }

        fun holds(isrc: String?): Boolean = isrc != null && items.any { it.value.isrc == isrc }
    }
}

/** Splits [rows] into entries. Neighbouring songs with the same album group become one [ListEntry.Album]. */
internal fun buildEntries(rows: List<GroupedTrack>): List<ListEntry> {
    val out = ArrayList<ListEntry>()
    var i = 0
    while (i < rows.size) {
        val group = rows[i].albumGroupId
        if (group == null) {
            out += ListEntry.Single(IndexedValue(i, rows[i].track))
            i++
        } else {
            val items = ArrayList<IndexedValue<Track>>()
            while (i < rows.size && rows[i].albumGroupId == group) {
                items += IndexedValue(i, rows[i].track)
                i++
            }
            out += ListEntry.Album(group, items)
        }
    }
    return out
}

/** The same songs with no grouping, for a view that filters the list (a search shows matching songs, not albums). */
internal fun flatEntries(items: List<IndexedValue<Track>>): List<ListEntry> = items.map { ListEntry.Single(it) }

private fun ListEntry.songs(): List<Track> =
    when (this) {
        is ListEntry.Single -> listOf(item.value)
        is ListEntry.Album -> tracks
    }

private fun moveEntry(
    entries: List<ListEntry>,
    from: Int,
    delta: Int,
): List<Track>? {
    val to = from + delta
    if (from < 0 || to !in entries.indices) return null
    val moved = entries.toMutableList()
    moved.add(to, moved.removeAt(from))
    return moved.flatMap { it.songs() }
}

/**
 * The song order after moving the song at [index] one step ([delta] is -1 or +1), or null if it cannot move.
 * A song inside an album group only moves inside the group. A loose song steps over a whole group in one go.
 */
internal fun reorderTrack(
    rows: List<GroupedTrack>,
    index: Int,
    delta: Int,
): List<Track>? {
    val row = rows.getOrNull(index) ?: return null
    val group = row.albumGroupId
    if (group != null) {
        val to = index + delta
        if (rows.getOrNull(to)?.albumGroupId != group) return null
        return rows.map { it.track }.toMutableList().also { Collections.swap(it, index, to) }
    }
    val entries = buildEntries(rows)
    return moveEntry(entries, entries.indexOfFirst { it is ListEntry.Single && it.item.index == index }, delta)
}

/** The song order after moving an album group one step past a loose song or another group, or null if it cannot. */
internal fun reorderAlbum(
    rows: List<GroupedTrack>,
    albumId: String,
    delta: Int,
): List<Track>? {
    val entries = buildEntries(rows)
    return moveEntry(entries, entries.indexOfFirst { it is ListEntry.Album && it.id == albumId }, delta)
}

internal fun canMoveTrack(
    rows: List<GroupedTrack>,
    index: Int,
    delta: Int,
): Boolean = reorderTrack(rows, index, delta) != null

internal fun canMoveAlbum(
    rows: List<GroupedTrack>,
    albumId: String,
    delta: Int,
): Boolean = reorderAlbum(rows, albumId, delta) != null

// ---------------------------------------------------------------------------------------------
// Fold state. Which albums are unfolded. Survives rotation and coming back to the screen.
// ---------------------------------------------------------------------------------------------

@Stable
internal class AlbumFolds(
    initial: Set<String>,
) {
    var open by mutableStateOf(initial)
        private set

    fun isOpen(id: String): Boolean = id in open

    fun toggle(id: String) {
        open = if (id in open) open - id else open + id
    }
}

@Composable
internal fun rememberAlbumFolds(): AlbumFolds =
    rememberSaveable(
        saver = listSaver<AlbumFolds, String>(save = { it.open.toList() }, restore = { AlbumFolds(it.toSet()) }),
    ) { AlbumFolds(emptySet()) }

/**
 * Emits an album group into a lazy list: its header, then one item per song while it is unfolded. The songs reuse
 * the `"<isrc>-<index>"` keys of loose songs, so a song keeps its identity whether it is shown loose or in a group.
 */
internal fun LazyListScope.albumEntry(
    album: ListEntry.Album,
    expanded: Boolean,
    header: @Composable LazyItemScope.() -> Unit,
    child: @Composable LazyItemScope.(position: Int, item: IndexedValue<Track>) -> Unit,
) {
    item(key = album.key) { header() }
    if (expanded) {
        items(album.items.size, key = { "${album.items[it].value.isrc}-${album.items[it].index}" }) { position ->
            // The wrapper carries the unfold animation: songs fade in under the header and the rows below make room.
            Box(Modifier.fillMaxWidth().animateItem()) { child(position, album.items[position]) }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// The thread line, drawn the way a comment thread draws its replies: one line drops from the album,
// runs down the left of every song in it, and each song branches off it through a small curve.
// ---------------------------------------------------------------------------------------------

private val RailWidth = 2.dp
private val RailRadius = 12.dp
private val RailBranch = 10.dp
private val RailGap = 8.dp

/** Distance from the rail to where a song's content starts. */
internal val RailChildInset: Dp = RailRadius + RailBranch + RailGap

/** Colour of the rail: quiet normally, the accent while a song of the album is playing. */
@Composable
internal fun railColor(
    accent: Color,
    playing: Boolean,
): Color = if (playing) accent.copy(alpha = 0.75f) else ink(0.22f)

/** Draws [draw] behind the content, or on top of it when [over] is set (a row with its own fill needs that). */
private fun Modifier.railLayer(
    over: Boolean,
    draw: DrawScope.() -> Unit,
): Modifier =
    if (over) {
        drawWithContent {
            drawContent()
            draw()
        }
    } else {
        drawBehind(draw)
    }

/**
 * The part of the rail that belongs to an unfolded album's header: it starts under the cover ([fromY] from the top)
 * and runs to the bottom so the songs below pick it up. [x] is where the line sits from the left edge. Set [over]
 * when the row has a fill of its own, so the line is drawn on top of it instead of behind.
 */
internal fun Modifier.albumRailHeader(
    x: Dp,
    fromY: Dp,
    color: Color,
    over: Boolean = false,
): Modifier =
    railLayer(over) {
        val lineX = x.toPx()
        drawLine(color, Offset(lineX, fromY.toPx()), Offset(lineX, size.height), strokeWidth = RailWidth.toPx())
    }

/**
 * The part of the rail beside one song. The line runs the full height and a curve leaves it at the middle of the
 * row toward the song. On the [last] song the line ends in the curve instead of going on.
 */
internal fun Modifier.albumRailChild(
    x: Dp,
    last: Boolean,
    color: Color,
    over: Boolean = false,
): Modifier =
    railLayer(over) {
        val width = RailWidth.toPx()
        val lineX = x.toPx()
        val radius = RailRadius.toPx()
        val branch = RailBranch.toPx()
        val midY = size.height / 2f
        drawLine(
            color,
            Offset(lineX, 0f),
            Offset(lineX, if (last) midY - radius else size.height),
            strokeWidth = width,
        )
        val elbow =
            Path().apply {
                moveTo(lineX, midY - radius)
                arcTo(Rect(lineX, midY - radius * 2f, lineX + radius * 2f, midY), 180f, -90f, false)
                lineTo(lineX + radius + branch, midY)
            }
        drawPath(elbow, color, style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }

// ---------------------------------------------------------------------------------------------
// Rows. The content of an album header and of a song inside an album, without any padding, fill,
// shape or click handling: every list style wraps them in its own.
// ---------------------------------------------------------------------------------------------

private fun runtimeLabel(ms: Long): String {
    val minutes = (ms / 60_000L).coerceAtLeast(0L)
    return if (minutes < 60) "$minutes min" else "${minutes / 60} h ${minutes % 60} min"
}

private fun songCountLabel(n: Int) = if (n == 1) "1 song" else "$n songs"

private fun lengthLabel(ms: Long): String {
    val seconds = ms / 1000L
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}

/** Album title with the count and running time under it, then the fold arrow and, when given, a menu button. */
@Composable
internal fun RowScope.AlbumHeaderContent(
    album: ListEntry.Album,
    expanded: Boolean,
    playing: Boolean,
    accent: Color,
    cover: @Composable () -> Unit,
    onMore: (() -> Unit)?,
    titleStyle: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.titleMedium,
    subtitleStyle: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyMedium,
    titleColor: Color = ink(),
    subtitleColor: Color = ink(0.6f),
) {
    cover()
    Column(Modifier.weight(1f).padding(start = 14.dp, end = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (playing) NowPlayingBars(color = accent, modifier = Modifier.padding(end = 8.dp))
            Text(
                album.title,
                style = titleStyle.copy(fontWeight = if (playing) FontWeight.SemiBold else FontWeight.Medium),
                color = if (playing) accent else titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        val parts =
            listOfNotNull(
                album.artist.takeIf { it.isNotBlank() },
                songCountLabel(album.items.size),
                runtimeLabel(album.totalMs).takeIf { album.totalMs > 0 },
            )
        Text(
            parts.joinToString(" \u00B7 "),
            style = subtitleStyle,
            color = subtitleColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 1.dp),
        )
    }
    val turn by animateFloatAsState(if (expanded) 180f else 0f, tween(200), label = "album-fold-arrow")
    Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
        Icon(
            TablerIcons.ChevronDown,
            if (expanded) "Fold album" else "Unfold album",
            tint = ink(0.7f),
            modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = turn },
        )
    }
    if (onMore != null) {
        Box(Modifier.size(40.dp).pressScale(onClick = onMore), contentAlignment = Alignment.Center) {
            Icon(PhosphorIcons.Bold.DotsThreeVertical, "Album options", tint = ink(0.7f), modifier = Modifier.size(20.dp))
        }
    }
}

/**
 * One song inside an unfolded album: its number in the album, title, the artist when it differs from the album's,
 * and its length. There is no cover; the album's cover is on the header. [trailing] adds the style's own buttons.
 */
@Composable
internal fun RowScope.AlbumChildContent(
    number: Int,
    track: Track,
    showArtist: Boolean,
    playing: Boolean,
    accent: Color,
    onArtist: (String) -> Unit,
    belowTitle: @Composable () -> Unit = {},
    showLength: Boolean = true,
    trailing: @Composable RowScope.() -> Unit = {},
    titleStyle: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyLarge,
) {
    Box(Modifier.width(26.dp), contentAlignment = Alignment.CenterStart) {
        if (playing) {
            NowPlayingBars(color = accent)
        } else {
            Text(number.toString(), style = MaterialTheme.typography.bodyMedium, color = ink(0.4f))
        }
    }
    Column(Modifier.weight(1f).padding(end = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ExplicitMark(track.isExplicit, ink(0.6f), size = 13.dp)
            Text(
                track.title,
                style = titleStyle.copy(fontWeight = if (playing) FontWeight.SemiBold else FontWeight.Medium),
                color = if (playing) accent else ink(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        if (showArtist) {
            ArtistLine(track.artist, track.album, onArtist, stackSize = 16.dp, modifier = Modifier.padding(top = 2.dp)) { m ->
                Text(
                    track.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = ink(0.55f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = m,
                )
            }
        }
        belowTitle()
    }
    if (showLength && track.durationMs > 0) {
        Text(
            lengthLabel(track.durationMs),
            style = MaterialTheme.typography.bodySmall,
            color = ink(0.4f),
            modifier = Modifier.padding(start = 4.dp),
        )
    }
    trailing()
}

/** Whether a song should show its artist inside an album: only when it is not just the album's own artist. */
internal fun showsArtistInAlbum(
    track: Track,
    album: ListEntry.Album,
): Boolean = !splitArtists(track.artist).let { it.size == 1 && it.first().equals(album.artist, ignoreCase = true) }

// ---------------------------------------------------------------------------------------------
// The album's own menu.
// ---------------------------------------------------------------------------------------------

private val MenuShape = RoundedCornerShape(16.dp)
private val MenuDanger = Color(0xFFFF6B6B)

/**
 * Options for an album group: play it, move it, split it into loose songs, or take it out. Taking it out asks
 * first, because it removes every song of the album at once.
 */
@Composable
internal fun AlbumGroupMenu(
    album: ListEntry.Album,
    coverShape: Shape,
    removeLabel: String,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    showMove: Boolean,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onUngroup: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    val base = LocalCoverPalette.current.base
    var confirming by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(MenuShape)
                .background(base)
                .background(ink(0.08f))
                .padding(vertical = 8.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                CoverImage(
                    resolveCoverUrl(album.coverUrl, 160),
                    Modifier.size(44.dp).softCover(coverShape),
                )
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(album.title, style = MaterialTheme.typography.titleMedium, color = ink(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(album.artist.takeIf { it.isNotBlank() }, songCountLabel(album.items.size)).joinToString(" \u00B7 "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = ink(0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(ink(0.10f)))
            if (confirming) {
                Text(
                    "Remove ${songCountLabel(album.items.size)} of \u201C${album.title}\u201D?",
                    style = MaterialTheme.typography.titleMedium,
                    color = ink(),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                )
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Cancel",
                        style = MaterialTheme.typography.titleMedium,
                        color = ink(0.8f),
                        modifier = Modifier.pressScale(onClick = { confirming = false }).padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                    Text(
                        "Remove",
                        style = MaterialTheme.typography.titleMedium,
                        color = MenuDanger,
                        modifier =
                            Modifier
                                .pressScale(onClick = {
                                    onRemove()
                                    onDismiss()
                                }).padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            } else {
                MenuAction(TablerIcons.PlayRegular, "Play album", true) {
                    onPlay()
                    onDismiss()
                }
                MenuAction(TablerIcons.Shuffle, "Shuffle album", true) {
                    onShuffle()
                    onDismiss()
                }
                if (showMove) {
                    MenuAction(TablerIcons.ArrowUp, "Move up", canMoveUp) {
                        onMoveUp()
                        onDismiss()
                    }
                    MenuAction(TablerIcons.ArrowDown, "Move down", canMoveDown) {
                        onMoveDown()
                        onDismiss()
                    }
                }
                MenuAction(TablerIcons.Stack, "Ungroup songs", true) {
                    onUngroup()
                    onDismiss()
                }
                MenuAction(TablerIcons.Trash, removeLabel, true, danger = true) { confirming = true }
            }
        }
    }
}

@Composable
private fun MenuAction(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val alpha = if (enabled) 1f else 0.3f
    val tint = if (danger) MenuDanger else ink()
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier.pressScale(onClick = onClick, scaleDown = 0.98f) else Modifier)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint.copy(alpha = tint.alpha * 0.85f * alpha), modifier = Modifier.size(22.dp))
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            color = tint.copy(alpha = tint.alpha * alpha),
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}
