package com.gamut.settings

import androidx.compose.foundation.layout.Box
import com.gamut.core.designsystem.GamutMotion
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gamut.core.designsystem.ArtImage
import com.gamut.core.designsystem.ink
import com.gamut.core.designsystem.pressScale
import com.gamut.core.designsystem.squareArtUrl
import com.gamut.core.model.Track

/**
 * Songs you opened lately, newest first: plain rows split by lines, tap = play it again. No other controls.
 *
 * Built to be cheap while the screen is sliding in:
 *  - No Crossfade / extra fade of its own: the NavHost already fades the whole screen once.
 *  - Rows never fade in (that would make an alpha layer per row); they only glide when the order changes.
 *  - Row click lambdas are remembered, so scrolling and recomposition do not allocate new ones per row.
 */
@Composable
fun RecentlyPlayedScreen(
    tracks: List<Track>,
    onBack: () -> Unit,
    onTrackSelected: (Track, List<Track>) -> Unit,
    nowPlaying: @Composable () -> Unit,
) {
    // One row per song. A repeated ISRC would be a duplicate LazyColumn key and crash the list.
    val rows = remember(tracks) { tracks.distinctBy { it.isrc } }
    // MainActivity rebuilds its callbacks on recomposition; always call the latest one without re-creating row lambdas.
    val select by rememberUpdatedState(onTrackSelected)

    SettingsScaffold(title = "Recently played", onBack = onBack, nowPlaying = nowPlaying) {
        if (rows.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text("Songs you play will show up here.", style = MaterialTheme.typography.bodyLarge, color = ink(0.5f))
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp)) {
                itemsIndexed(
                    items = rows,
                    key = { _, t -> t.isrc },
                    contentType = { _, _ -> "recent-row" },
                ) { index, track ->
                    val onClick = remember(track, rows) { { select(track, rows) } }
                    // A newly played song slides to the top and the rest make room (foundation 1.7+).
                    // fade specs = null: rows appearing for the first time are NOT faded (no alpha layer per row).
                    Column(
                        Modifier.animateItem(
                            fadeInSpec = null,
                            placementSpec = GamutMotion.itemPlacement,
                            fadeOutSpec = null,
                        ),
                    ) {
                        RecentRow(track, onClick)
                        if (index < rows.lastIndex) SettingsDivider()
                    }
                }
                item(contentType = "bottom-space") { Spacer(Modifier.navigationBarsPadding().height(16.dp)) }
            }
        }
    }
}

@Composable
private fun RecentRow(track: Track, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().pressScale(onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        ArtImage(
            url = remember(track.albumArtUrl) { squareArtUrl(track.albumArtUrl, 200) },
            modifier = Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)),
        )
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(track.title, style = MaterialTheme.typography.bodyLarge, color = ink(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track.artist, style = MaterialTheme.typography.bodyMedium, color = ink(0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}