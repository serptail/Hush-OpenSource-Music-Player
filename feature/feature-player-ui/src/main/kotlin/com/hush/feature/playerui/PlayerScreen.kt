package com.hush.feature.playerui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import com.hush.core.designsystem.ArtworkShapeLarge
import com.hush.core.designsystem.rememberDynamicAccent
import com.hush.core.designsystem.softPlayerGradient
import com.hush.core.model.PlaybackState
import com.hush.core.player.MusicPlayer

@Composable
fun PlayerScreen(player: MusicPlayer, onBack: () -> Unit = {}) {
    val state by player.playbackState.collectAsState()
    val track = (state as? PlaybackState.Playing)?.track ?: (state as? PlaybackState.Paused)?.track
    val position = (state as? PlaybackState.Playing)?.positionMs ?: (state as? PlaybackState.Paused)?.positionMs ?: 0L
    val isPlaying = state is PlaybackState.Playing

    // Two soft pastel tones, eased from the current artwork's palette; falls
    // back to the calm rose/peach default when nothing is playing.
    val accent = rememberDynamicAccent(track?.albumArtUrl)

    Column(
        Modifier
            .fillMaxSize()
            .background(softPlayerGradient(accent.top, accent.bottom))
            .padding(horizontal = 26.dp),
    ) {
        TopBar(onBack = onBack)
        Spacer(Modifier.height(28.dp))
        Artwork(url = track?.albumArtUrl, modifier = Modifier.align(Alignment.CenterHorizontally))
        Spacer(Modifier.height(30.dp))
        Text(track?.title ?: "Nothing playing", style = MaterialTheme.typography.headlineMedium, color = Color.White, maxLines = 1)
        Text(track?.artist ?: "Choose something to play", style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.8f), maxLines = 1)
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.QueueMusic, "Queue", tint = Color.White)
            Icon(Icons.Default.FavoriteBorder, "Favorite", tint = Color.White)
            Icon(Icons.Default.Add, "Add", tint = Color.White)
        }
        Spacer(Modifier.height(14.dp))
        Slider(
            value = position.toFloat(),
            onValueChange = { player.seekTo(it.toLong()) },
            enabled = track != null,
            valueRange = 0f..(track?.durationMs ?: 1L).coerceAtLeast(1L).toFloat(),
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White,
                inactiveTrackColor = Color.White.copy(alpha = 0.35f),
            ),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(position), color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelMedium)
            Text(formatTime(track?.durationMs ?: 0L), color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.height(18.dp))
        TransportRow(
            isPlaying = isPlaying,
            onPlayPause = { if (isPlaying) player.pause() else player.resume() },
            onNext = player::skipNext,
            onPrevious = player::skipPrevious,
        )
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun TopBar(onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.Default.ExpandMore, "Collapse", tint = Color.White) }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = {}) { Icon(Icons.Default.VolumeUp, "Volume", tint = Color.White) }
        IconButton(onClick = {}) { Icon(Icons.Default.Equalizer, "Equalizer", tint = Color.White) }
        IconButton(onClick = {}) { Icon(Icons.Default.MoreVert, "More", tint = Color.White) }
    }
}

@Composable
private fun Artwork(url: String?, modifier: Modifier = Modifier) {
    SubcomposeAsyncImage(
        model = url,
        contentDescription = null,
        modifier = modifier.size(280.dp).clip(ArtworkShapeLarge),
        contentScale = ContentScale.Crop,
        loading = { ArtworkPlaceholder() },
        error = { ArtworkPlaceholder() },
    )
}

@Composable
private fun ArtworkPlaceholder() {
    androidx.compose.foundation.layout.Box(
        Modifier.fillMaxSize().clip(ArtworkShapeLarge).background(Color.White.copy(alpha = 0.25f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Default.MusicNote, null, tint = Color.White, modifier = Modifier.size(56.dp))
    }
}

@Composable
private fun TransportRow(isPlaying: Boolean, onPlayPause: () -> Unit, onNext: () -> Unit, onPrevious: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = {}) { Icon(Icons.Default.Shuffle, "Shuffle", tint = Color.White) }
        IconButton(onClick = onPrevious) { Icon(Icons.Default.SkipPrevious, "Previous", tint = Color.White, modifier = Modifier.size(30.dp)) }
        IconButton(onClick = onPlayPause, modifier = Modifier.size(64.dp)) {
            Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Play", tint = Color.White, modifier = Modifier.size(40.dp))
        }
        IconButton(onClick = onNext) { Icon(Icons.Default.SkipNext, "Next", tint = Color.White, modifier = Modifier.size(30.dp)) }
        IconButton(onClick = {}) { Icon(Icons.Default.Repeat, "Repeat", tint = Color.White) }
    }
}

private fun formatTime(milliseconds: Long): String = "%d:%02d".format(milliseconds / 60_000, (milliseconds / 1_000) % 60)