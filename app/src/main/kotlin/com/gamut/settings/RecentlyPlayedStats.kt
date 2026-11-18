package com.gamut.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.CaretDown
import com.adamglin.phosphoricons.regular.MicrophoneStage
import com.adamglin.phosphoricons.regular.MusicNotes
import com.adamglin.phosphoricons.regular.Trash
import com.gamut.core.designsystem.ArtImage
import com.gamut.core.designsystem.LocalArtistPhotoResolver
import com.gamut.core.designsystem.ink
import com.gamut.core.designsystem.pressScale
import com.gamut.core.designsystem.squareArtUrl
import com.gamut.core.designsystem.thumbnailBorder
import com.gamut.core.model.Track
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import java.text.NumberFormat
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class StatsViewModel
    @Inject
    constructor(
        private val store: PlayStatsStore,
    ) : ViewModel() {
        private val period = MutableStateFlow(StatsPeriod.ALL)
        val selected: StateFlow<StatsPeriod> = period.asStateFlow()

        val summary: StateFlow<StatsSummary> =
            combine(store.snapshot, period) { data, p ->
                summarize(data, p, System.currentTimeMillis())
            }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsSummary())

        fun record(track: Track) = store.record(track)

        fun select(p: StatsPeriod) {
            period.value = p
        }

        fun reset() = store.clear()
    }

private const val COLLAPSED_SONGS = 5
private const val ARTIST_LIMIT = 10

private val RankArt = 46.dp
private val RankGap = 14.dp
private val RankTextInset = RankArt + RankGap
private val ArtistSize = 88.dp
private val MosaicGap = 6.dp

private fun playsLabel(plays: Int): String =
    if (plays == 1) "1 play" else "${NumberFormat.getIntegerInstance().format(plays)} plays"

/*
 * One number, then pictures. The total sits big at the top, and under it your six most played songs are laid
 * out as a cover mosaic, the favourite largest. Artists are a swipeable row of faces. Only the song list
 * carries counts, small and at the edge.
 */
@Composable
internal fun StatsTab(vm: StatsViewModel) {
    val summary by vm.summary.collectAsState()
    val period by vm.selected.collectAsState()
    var showAllSongs by rememberSaveable { mutableStateOf(false) }
    var confirmReset by rememberSaveable { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 24.dp)) {
        item(key = "periods", contentType = "periods") { PeriodRow(period, vm::select) }

        if (summary.totalPlays == 0) {
            item(key = "empty", contentType = "empty") {
                EmptyNote(
                    PhosphorIcons.Regular.MusicNotes,
                    if (period == StatsPeriod.ALL) {
                        "Play a few songs and your listening stats will show up here."
                    } else {
                        "Nothing played in this period yet."
                    },
                    Modifier.padding(vertical = 64.dp),
                )
            }
        } else {
            item(key = "hero", contentType = "hero") { Hero(summary) }
            item(key = "mosaic", contentType = "mosaic") { Mosaic(summary.topSongs) }

            if (summary.topArtists.isNotEmpty()) {
                item(key = "artists-title", contentType = "label") {
                    StatsSectionLabel(PhosphorIcons.Regular.MicrophoneStage, "Top artists")
                }
                item(key = "artists", contentType = "artists") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
                        items(summary.topArtists.take(ARTIST_LIMIT), key = { "artist-${it.name.lowercase()}" }) { a ->
                            ArtistTile(a)
                        }
                    }
                }
            }

            if (summary.topSongs.isNotEmpty()) {
                item(key = "songs-title", contentType = "label") {
                    StatsSectionLabel(PhosphorIcons.Regular.MusicNotes, "Top songs")
                }
                val songs = if (showAllSongs) summary.topSongs else summary.topSongs.take(COLLAPSED_SONGS)
                itemsIndexed(
                    songs,
                    key = { _, s -> "song-${s.song.isrc}" },
                    contentType = { _, _ -> "song" },
                ) { index, s ->
                    Column {
                        if (index > 0) GroupedDivider(start = RankTextInset)
                        TopSongRow(song = s)
                    }
                }
                if (summary.topSongs.size > COLLAPSED_SONGS) {
                    item(key = "songs-more", contentType = "more") {
                        ToggleRow(showAllSongs) { showAllSongs = !showAllSongs }
                    }
                }
            }

            item(key = "reset", contentType = "reset") { ResetRow { confirmReset = true } }
        }
        item(contentType = "bottom-space") { Spacer(Modifier.navigationBarsPadding().height(16.dp)) }
    }

    if (confirmReset) {
        GamutDialog(
            title = "Reset statistics?",
            body = "Your play counts are cleared. Favorites, playlists and the Recent list are not affected.",
            confirmLabel = "Reset",
            destructive = true,
            onDismiss = { confirmReset = false },
            onConfirm = {
                confirmReset = false
                showAllSongs = false
                vm.reset()
            },
        ) { }
    }
}

@Composable
private fun PeriodRow(
    selected: StatsPeriod,
    onSelect: (StatsPeriod) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 2.dp), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
        StatsPeriod.values().forEach { p ->
            val color by animateColorAsState(if (p == selected) ink() else ink(0.35f), tween(220), label = "stats-period")
            Text(
                p.label,
                style = MaterialTheme.typography.titleMedium,
                color = color,
                maxLines = 1,
                modifier = Modifier.pressScale(onClick = { onSelect(p) }, scaleDown = 0.96f).padding(vertical = 8.dp),
            )
        }
    }
}

/** The single big number, with the song and artist counts folded into one quiet line under it. */
@Composable
private fun Hero(summary: StatsSummary) {
    Column(Modifier.fillMaxWidth().padding(start = 4.dp, top = 18.dp, bottom = 20.dp)) {
        AnimatedCount(
            summary.totalPlays,
            MaterialTheme.typography.displayLarge.copy(fontFeatureSettings = "tnum"),
            ink(),
        )
        Text(
            buildString {
                append(if (summary.totalPlays == 1) "play" else "plays")
                append("  ·  ")
                append(countLabel(summary.uniqueSongs, "song", "songs"))
                append("  ·  ")
                append(countLabel(summary.uniqueArtists, "artist", "artists"))
            },
            style = MaterialTheme.typography.bodyMedium,
            color = ink(0.5f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun countLabel(
    n: Int,
    one: String,
    many: String,
): String = "${NumberFormat.getIntegerInstance().format(n)} ${if (n == 1) one else many}"

@Composable
private fun AnimatedCount(
    value: Int,
    style: TextStyle,
    color: Color,
) {
    var target by remember { mutableStateOf(0) }
    LaunchedEffect(value) { target = value }
    val shown by animateIntAsState(target, tween(800, easing = FastOutSlowInEasing), label = "stats-count")
    Text(NumberFormat.getIntegerInstance().format(shown), style = style, color = color, maxLines = 1)
}

/**
 * Six covers in one block: the most played takes a 2x2 corner, the next two stack beside it, and three
 * more run along the bottom. Missing songs leave a quiet empty tile so the block never collapses.
 */
@Composable
private fun Mosaic(songs: List<RankedSong>) {
    val tiles = List(6) { songs.getOrNull(it) }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val small = (maxWidth - MosaicGap * 2) / 3
        val big = small * 2 + MosaicGap
        Column(verticalArrangement = Arrangement.spacedBy(MosaicGap)) {
            Row(horizontalArrangement = Arrangement.spacedBy(MosaicGap)) {
                MosaicTile(tiles[0], big, 0, 22.dp)
                Column(verticalArrangement = Arrangement.spacedBy(MosaicGap)) {
                    MosaicTile(tiles[1], small, 1, 14.dp)
                    MosaicTile(tiles[2], small, 2, 14.dp)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(MosaicGap)) {
                for (i in 3..5) MosaicTile(tiles[i], small, i, 14.dp)
            }
        }
    }
}

@Composable
private fun MosaicTile(
    track: RankedSong?,
    size: Dp,
    index: Int,
    corner: Dp,
) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val alpha by animateFloatAsState(
        if (shown) 1f else 0f,
        tween(450, delayMillis = index * 60, easing = FastOutSlowInEasing),
        label = "mosaic-tile",
    )
    val shape = RoundedCornerShape(corner)
    Box(Modifier.size(size).graphicsLayer { this.alpha = alpha }.clip(shape).background(ink(0.07f))) {
        if (track != null) {
            ArtImage(
                url = remember(track.song.artUrl, size) { squareArtUrl(track.song.artUrl, if (size > 150.dp) 700 else 350) },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun ArtistTile(artist: RankedArtist) {
    Column(Modifier.width(ArtistSize), horizontalAlignment = Alignment.CenterHorizontally) {
        ArtistCircle(artist, ArtistSize)
        Text(
            artist.name,
            style = MaterialTheme.typography.labelLarge,
            color = ink(0.75f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun StatsSectionLabel(
    icon: ImageVector,
    text: String,
) {
    Row(Modifier.padding(start = 4.dp, top = 34.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = ink(0.5f), modifier = Modifier.size(16.dp))
        Text(
            text.uppercase(Locale.ROOT),
            style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.8.sp),
            color = ink(0.5f),
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun TopSongRow(song: RankedSong) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        ArtImage(
            url = remember(song.song.artUrl) { squareArtUrl(song.song.artUrl, 200) },
            modifier = Modifier.size(RankArt).clip(RoundedCornerShape(10.dp)).thumbnailBorder(RoundedCornerShape(10.dp)),
        )
        Column(Modifier.weight(1f).padding(start = RankGap)) {
            Text(song.song.title, style = MaterialTheme.typography.bodyLarge, color = ink(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                song.song.artist,
                style = MaterialTheme.typography.bodyMedium,
                color = ink(0.55f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            playsLabel(song.plays),
            style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
            color = ink(0.45f),
            maxLines = 1,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

@Composable
private fun ToggleRow(
    expanded: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().pressScale(onClick = onClick, scaleDown = 0.97f).padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (expanded) "Show less" else "Show more", style = MaterialTheme.typography.bodyLarge, color = ink(0.6f))
        Icon(
            PhosphorIcons.Regular.CaretDown,
            null,
            tint = ink(0.6f),
            modifier = Modifier.padding(start = 6.dp).size(14.dp).graphicsLayer { rotationZ = if (expanded) 180f else 0f },
        )
    }
}

@Composable
private fun ResetRow(onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 28.dp).pressScale(onClick = onClick, scaleDown = 0.97f).padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(PhosphorIcons.Regular.Trash, null, tint = GamutDestructive, modifier = Modifier.size(18.dp))
        Text(
            "Reset statistics",
            style = MaterialTheme.typography.bodyLarge,
            color = GamutDestructive,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun ArtistCircle(
    artist: RankedArtist,
    size: Dp,
) {
    val photo = rememberArtistPhoto(artist.name, artist.album)
    Box(Modifier.size(size).clip(CircleShape).background(ink(0.1f)).thumbnailBorder(CircleShape), contentAlignment = Alignment.Center) {
        if (photo != null) {
            ArtImage(url = photo, modifier = Modifier.fillMaxSize())
        } else {
            Text(
                artist.name
                    .trim()
                    .take(1)
                    .uppercase(),
                style = MaterialTheme.typography.titleLarge,
                color = ink(0.55f),
            )
        }
    }
}

@Composable
private fun rememberArtistPhoto(
    name: String,
    album: String?,
): String? {
    val resolver = LocalArtistPhotoResolver.current
    var url by remember(name) { mutableStateOf<String?>(null) }
    LaunchedEffect(name, album, resolver) {
        url = resolver?.invoke(name, album) ?: leadArtist(name)?.let { resolver?.invoke(it, album) }
    }
    return url
}

private fun leadArtist(name: String): String? =
    name.split(" & ", " feat. ", " ft. ", " x ").first().trim().takeIf {
        it.isNotEmpty() &&
            !it.equals(name.trim(), ignoreCase = true)
    }