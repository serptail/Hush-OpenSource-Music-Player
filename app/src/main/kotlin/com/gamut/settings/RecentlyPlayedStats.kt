package com.gamut.settings

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.CaretDown
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
private const val COLLAPSED_ARTISTS = 4

private val ArtistArt = 44.dp
private val SongArt = 44.dp
private val RankWidth = 24.dp
private val RankGap = 12.dp

/** Where the text starts in each kind of row, so the dividers tuck in under it. */
private val ArtistTextInset = GroupedRowInset + ArtistArt + GroupedIconGap
private val SongTextInset = GroupedRowInset + RankWidth + SongArt + RankGap

private fun playsLabel(plays: Int): String =
    if (plays == 1) "1 play" else "${NumberFormat.getIntegerInstance().format(plays)} plays"

@Composable
internal fun StatsTab(vm: StatsViewModel) {
    val summary by vm.summary.collectAsState()
    val period by vm.selected.collectAsState()
    var showAllSongs by rememberSaveable { mutableStateOf(false) }
    var showAllArtists by rememberSaveable { mutableStateOf(false) }
    var confirmReset by rememberSaveable { mutableStateOf(false) }
    val periods = remember { StatsPeriod.values().map { it to it.label } }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 24.dp)) {
        item(key = "periods", contentType = "periods") {
            GroupedSegmented(options = periods, selected = period, onSelect = vm::select, modifier = Modifier.padding(top = 2.dp))
        }
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
            item(key = "stats", contentType = "stats") {
                StatsBlock(
                    summary = summary,
                    showAllArtists = showAllArtists,
                    showAllSongs = showAllSongs,
                    onToggleArtists = { showAllArtists = !showAllArtists },
                    onToggleSongs = { showAllSongs = !showAllSongs },
                    onReset = { confirmReset = true },
                )
            }
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
                showAllArtists = false
                vm.reset()
            },
        ) { }
    }
}

@Composable
private fun StatsBlock(
    summary: StatsSummary,
    showAllArtists: Boolean,
    showAllSongs: Boolean,
    onToggleArtists: () -> Unit,
    onToggleSongs: () -> Unit,
    onReset: () -> Unit,
) {
    SettingsBlock(Modifier.animateContentSize()) {
        SummaryStrip(summary)

        if (summary.topArtists.isNotEmpty()) {
            GroupedHeader("Top artists")
            val artists = if (showAllArtists) summary.topArtists else summary.topArtists.take(COLLAPSED_ARTISTS)
            artists.forEachIndexed { i, a ->
                if (i > 0) GroupedDivider(start = ArtistTextInset)
                ArtistRow(a)
            }
            if (summary.topArtists.size > COLLAPSED_ARTISTS) {
                GroupedDivider(start = GroupedRowInset)
                ToggleRow(showAllArtists, onToggleArtists)
            }
        }

        if (summary.topSongs.isNotEmpty()) {
            GroupedHeader("Top songs")
            val maxSong = summary.topSongs.first().plays.coerceAtLeast(1)
            val songs = if (showAllSongs) summary.topSongs else summary.topSongs.take(COLLAPSED_SONGS)
            songs.forEachIndexed { i, s ->
                if (i > 0) GroupedDivider(start = SongTextInset)
                TopSongRow(rank = i + 1, song = s, fraction = s.plays / maxSong.toFloat())
            }
            if (summary.topSongs.size > COLLAPSED_SONGS) {
                GroupedDivider(start = GroupedRowInset)
                ToggleRow(showAllSongs, onToggleSongs)
            }
        }

        GroupedHeader("Data")
        GroupedRow("Reset statistics", PhosphorIcons.Regular.Trash, destructive = true, onClick = onReset)
        GroupedFooter("Clears your play counts. Favorites, playlists and the Recent list are not affected.")
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SummaryStrip(summary: StatsSummary) {
    Row(Modifier.fillMaxWidth().padding(vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Figure(summary.totalPlays, if (summary.totalPlays == 1) "Play" else "Plays", Modifier.weight(1f))
        Box(Modifier.width(0.5.dp).height(30.dp).background(ink(0.12f)))
        Figure(summary.uniqueSongs, if (summary.uniqueSongs == 1) "Song" else "Songs", Modifier.weight(1f))
        Box(Modifier.width(0.5.dp).height(30.dp).background(ink(0.12f)))
        Figure(summary.uniqueArtists, if (summary.uniqueArtists == 1) "Artist" else "Artists", Modifier.weight(1f))
    }
}

@Composable
private fun Figure(
    value: Int,
    label: String,
    modifier: Modifier = Modifier,
) {
    var target by remember { mutableStateOf(0) }
    LaunchedEffect(value) { target = value }
    val shown by animateIntAsState(target, tween(700, easing = FastOutSlowInEasing), label = "stats-figure")
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            NumberFormat.getIntegerInstance().format(shown),
            style = MaterialTheme.typography.headlineMedium.copy(fontFeatureSettings = "tnum"),
            color = ink(),
            maxLines = 1,
        )
        Text(label, style = MaterialTheme.typography.bodyMedium, color = ink(0.55f), modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun ArtistRow(artist: RankedArtist) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = GroupedRowInset, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtistCircle(artist, ArtistArt)
        Text(
            artist.name,
            style = MaterialTheme.typography.bodyLarge,
            color = ink(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = GroupedIconGap),
        )
        Text(
            playsLabel(artist.plays),
            style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
            color = ink(0.5f),
            maxLines = 1,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

@Composable
private fun TopSongRow(
    rank: Int,
    song: RankedSong,
    fraction: Float,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = GroupedRowInset, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            rank.toString(),
            style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
            color = if (rank == 1) MaterialTheme.colorScheme.primary else ink(0.4f),
            maxLines = 1,
            modifier = Modifier.width(RankWidth),
        )
        ArtImage(
            url = remember(song.song.artUrl) { squareArtUrl(song.song.artUrl, 200) },
            modifier = Modifier.size(SongArt).clip(RoundedCornerShape(10.dp)).thumbnailBorder(RoundedCornerShape(10.dp)),
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
            ShareBar(fraction, Modifier.padding(top = 7.dp))
        }
        Text(
            playsLabel(song.plays),
            style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
            color = ink(0.5f),
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
        Modifier.fillMaxWidth().pressScale(onClick = onClick, scaleDown = 0.985f).padding(vertical = 13.dp),
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
private fun ShareBar(
    fraction: Float,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    val progress = remember { Animatable(0f) }
    val target = fraction.coerceIn(0.03f, 1f)
    LaunchedEffect(target) { progress.animateTo(target, tween(650, easing = FastOutSlowInEasing)) }
    Box(
        modifier
            .fillMaxWidth()
            .height(3.dp)
            .clip(CircleShape)
            .background(ink(0.08f)),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = progress.value
                    transformOrigin = TransformOrigin(0f, 0.5f)
                }.background(accent.copy(alpha = 0.8f), CircleShape),
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