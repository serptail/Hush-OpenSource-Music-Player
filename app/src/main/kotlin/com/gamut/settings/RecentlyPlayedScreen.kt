package com.gamut.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ClockCounterClockwise
import com.gamut.core.designsystem.ArtImage
import com.gamut.core.designsystem.ExplicitMark
import com.gamut.core.designsystem.GamutMotion
import com.gamut.core.designsystem.ink
import com.gamut.core.designsystem.pressScale
import com.gamut.core.designsystem.squareArtUrl
import com.gamut.core.designsystem.thumbnailBorder
import com.gamut.core.model.Track

private enum class RecentTab(
    val label: String,
) {
    RECENT("Recent"),
    STATS("Statistics"),
}

private val ArtSize = 52.dp
private val RowGap = 14.dp
private val RecentTextInset = ArtSize + RowGap

/*
 * Recently played is a history, so it is laid out like one: a plain list straight on the page, with no card
 * behind it, and no single cover standing in for the whole. The two tabs are the title itself, two big words
 * with the active one lit, rather than a control sitting under a heading.
 */
@Composable
fun RecentlyPlayedScreen(
    tracks: List<Track>,
    onBack: () -> Unit,
    onTrackSelected: (Track, List<Track>) -> Unit,
    nowPlaying: @Composable () -> Unit,
    statsViewModel: StatsViewModel = hiltViewModel(),
) {
    val rows = remember(tracks) { tracks.distinctBy { it.isrc } }
    val select by rememberUpdatedState(onTrackSelected)
    val pick = remember { { track: Track, list: List<Track> -> select(track, list) } }
    var tab by rememberSaveable { mutableStateOf(RecentTab.RECENT) }

    SettingsScaffold(title = "", onBack = onBack, nowPlaying = nowPlaying) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 28.dp, end = 24.dp, top = 4.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(22.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                RecentTab.values().forEach { t ->
                    val color by animateColorAsState(if (t == tab) ink() else ink(0.3f), tween(220), label = "recent-tab")
                    Text(
                        t.label,
                        style = MaterialTheme.typography.headlineLarge,
                        color = color,
                        maxLines = 1,
                        modifier = Modifier.pressScale(onClick = { tab = t }, scaleDown = 0.96f),
                    )
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    RecentTab.RECENT -> RecentList(rows, pick)
                    RecentTab.STATS -> StatsTab(statsViewModel)
                }
            }
        }
    }
}

@Composable
internal fun EmptyNote(
    icon: ImageVector,
    text: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = ink(0.3f), modifier = Modifier.size(40.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = ink(0.5f),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 14.dp),
        )
    }
}

@Composable
private fun RecentList(
    rows: List<Track>,
    onPick: (Track, List<Track>) -> Unit,
) {
    if (rows.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyNote(PhosphorIcons.Regular.ClockCounterClockwise, "Songs you play will show up here.")
        }
    } else {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 24.dp)) {
            item(key = "count", contentType = "count") {
                Row(Modifier.padding(start = 4.dp, top = 2.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(PhosphorIcons.Regular.ClockCounterClockwise, null, tint = ink(0.45f), modifier = Modifier.size(16.dp))
                    Text(
                        if (rows.size == 1) "1 song" else "${rows.size} songs",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ink(0.45f),
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
            itemsIndexed(
                items = rows,
                key = { _, t -> t.isrc },
                contentType = { _, _ -> "recent-row" },
            ) { index, track ->
                val onClick = remember(track, rows) { { onPick(track, rows) } }
                Column(
                    Modifier.animateItem(
                        fadeInSpec = null,
                        placementSpec = GamutMotion.itemPlacement,
                        fadeOutSpec = null,
                    ),
                ) {
                    RecentRow(track, onClick)
                    if (index < rows.lastIndex) GroupedDivider(start = RecentTextInset)
                }
            }
            item(contentType = "bottom-space") { Spacer(Modifier.navigationBarsPadding().height(16.dp)) }
        }
    }
}

@Composable
private fun RecentRow(
    track: Track,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().pressScale(onClick = onClick, scaleDown = 0.985f).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtImage(
            url = remember(track.albumArtUrl) { squareArtUrl(track.albumArtUrl, 200) },
            modifier = Modifier.size(ArtSize).clip(RoundedCornerShape(10.dp)).thumbnailBorder(RoundedCornerShape(10.dp)),
        )
        Column(Modifier.weight(1f).padding(start = RowGap)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ExplicitMark(track.isExplicit, ink(0.6f))
                Text(
                    track.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = ink(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            Text(
                track.artist,
                style = MaterialTheme.typography.bodyMedium,
                color = ink(0.55f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}