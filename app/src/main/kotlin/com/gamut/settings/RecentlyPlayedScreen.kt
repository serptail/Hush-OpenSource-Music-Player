package com.gamut.settings

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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Light
import com.adamglin.phosphoricons.light.MusicNote
import com.gamut.core.designsystem.ArtImage
import com.gamut.core.designsystem.ink
import com.gamut.core.designsystem.pressScale
import com.gamut.core.designsystem.squareArtUrl
import com.gamut.core.model.Track

/** Songs you opened lately, newest first: plain rows split by lines, tap = play it again. No other controls. */
@Composable
fun RecentlyPlayedScreen(
    tracks: List<Track>,
    onBack: () -> Unit,
    onTrackSelected: (Track, List<Track>) -> Unit,
    nowPlaying: @Composable () -> Unit,
) {
    SettingsScaffold(title = "Recently played", onBack = onBack, nowPlaying = nowPlaying) {
        if (tracks.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text("Songs you play will show up here.", style = MaterialTheme.typography.bodyLarge, color = ink(0.5f))
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp)) {
                itemsIndexed(tracks, key = { _, t -> t.isrc }) { index, track ->
                    RecentRow(track) { onTrackSelected(track, tracks) }
                    if (index < tracks.lastIndex) SettingsDivider()
                }
                item { Spacer(Modifier.navigationBarsPadding().height(16.dp)) }
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
            fallback = { Icon(PhosphorIcons.Light.MusicNote, null, tint = ink(0.45f), modifier = Modifier.size(22.dp)) },
        )
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(track.title, style = MaterialTheme.typography.bodyLarge, color = ink(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track.artist, style = MaterialTheme.typography.bodyMedium, color = ink(0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
