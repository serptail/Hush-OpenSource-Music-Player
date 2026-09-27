package com.hush.feature.playerui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
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
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Light
import com.adamglin.phosphoricons.light.CaretDown
import com.adamglin.phosphoricons.light.DotsThreeVertical
import com.adamglin.phosphoricons.light.Equalizer
import com.adamglin.phosphoricons.light.Heart
import com.adamglin.phosphoricons.light.MusicNote
import com.adamglin.phosphoricons.light.Pause
import com.adamglin.phosphoricons.light.Play
import com.adamglin.phosphoricons.light.Plus
import com.adamglin.phosphoricons.light.Queue
import com.adamglin.phosphoricons.light.Repeat
import com.adamglin.phosphoricons.light.Shuffle
import com.adamglin.phosphoricons.light.SkipBack
import com.adamglin.phosphoricons.light.SkipForward
import com.adamglin.phosphoricons.light.SpeakerHigh
import com.hush.core.designsystem.AcrylicGloss
import com.hush.core.designsystem.ArtworkShapeLarge
import com.hush.core.designsystem.acrylicFrame
import com.hush.core.designsystem.adaptivePlayerGradient
import com.hush.core.designsystem.pressScale
import com.hush.core.designsystem.rememberDynamicAccent
import com.hush.core.model.PlaybackState
import com.hush.core.player.MusicPlayer

@Composable
fun PlayerScreen(player: MusicPlayer, onBack: () -> Unit = {}) {
    val state by player.playbackState.collectAsState()
    val track = (state as? PlaybackState.Playing)?.track ?: (state as? PlaybackState.Paused)?.track
    val position = (state as? PlaybackState.Playing)?.positionMs ?: (state as? PlaybackState.Paused)?.positionMs ?: 0L
    val isPlaying = state is PlaybackState.Playing

    // Three real colors pulled from the current artwork, clamped to a clean
    // lightness/saturation band and crossfaded (900ms) between tracks.
    val accent = rememberDynamicAccent(track?.albumArtUrl)

    Column(
        Modifier
            .fillMaxSize()
            .background(adaptivePlayerGradient(accent.top, accent.mid, accent.bottom))
            .padding(horizontal = 26.dp),
    ) {
        TopBar(onBack = onBack)
        Spacer(Modifier.height(30.dp))
        Artwork(url = track?.albumArtUrl, modifier = Modifier.align(Alignment.CenterHorizontally))
        Spacer(Modifier.height(32.dp))
        Text(track?.title ?: "Nothing playing", style = MaterialTheme.typography.headlineMedium, color = Color.White, maxLines = 1)
        Text(track?.artist ?: "Choose something to play", style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.8f), maxLines = 1)
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Icon(PhosphorIcons.Light.Queue, "Queue", tint = Color.White, modifier = Modifier.pressScale(onClick = {}))
            Icon(PhosphorIcons.Light.Heart, "Favorite", tint = Color.White, modifier = Modifier.pressScale(onClick = {}))
            Icon(PhosphorIcons.Light.Plus, "Add", tint = Color.White, modifier = Modifier.pressScale(onClick = {}))
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
        Icon(PhosphorIcons.Light.CaretDown, "Collapse", tint = Color.White, modifier = Modifier.pressScale(onClick = onBack).padding(8.dp))
        Spacer(Modifier.weight(1f))
        Icon(PhosphorIcons.Light.SpeakerHigh, "Volume", tint = Color.White, modifier = Modifier.pressScale(onClick = {}).padding(8.dp))
        Icon(PhosphorIcons.Light.Equalizer, "Equalizer", tint = Color.White, modifier = Modifier.pressScale(onClick = {}).padding(8.dp))
        Icon(PhosphorIcons.Light.DotsThreeVertical, "More", tint = Color.White, modifier = Modifier.pressScale(onClick = {}).padding(8.dp))
    }
}

/**
 * The big cover — wrapped in [acrylicFrame] (full strength) for the thin
 * bright outline + soft shadow "pop," with a diagonal gloss sheen on top so
 * it reads as acrylic/glass rather than a flat sticker.
 */
@Composable
private fun Artwork(url: String?, modifier: Modifier = Modifier) {
    Box(modifier.size(280.dp).acrylicFrame(ArtworkShapeLarge, strength = 1f)) {
        SubcomposeAsyncImage(
            model = url,
            contentDescription = null,
            modifier = Modifier.fillMaxSize().clip(ArtworkShapeLarge),
            contentScale = ContentScale.Crop,
            loading = { ArtworkPlaceholder() },
            error = { ArtworkPlaceholder() },
        )
        AcrylicGloss(shape = ArtworkShapeLarge, alpha = 0.20f)
    }
}

@Composable
private fun ArtworkPlaceholder() {
    Box(
        Modifier.fillMaxSize().clip(ArtworkShapeLarge).background(Color.White.copy(alpha = 0.25f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(PhosphorIcons.Light.MusicNote, null, tint = Color.White, modifier = Modifier.size(56.dp))
    }
}

@Composable
private fun TransportRow(isPlaying: Boolean, onPlayPause: () -> Unit, onNext: () -> Unit, onPrevious: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        Icon(PhosphorIcons.Light.Shuffle, "Shuffle", tint = Color.White, modifier = Modifier.pressScale(onClick = {}).padding(8.dp))
        Icon(PhosphorIcons.Light.SkipBack, "Previous", tint = Color.White, modifier = Modifier.size(30.dp).pressScale(onClick = onPrevious))
        Box(
            Modifier
                .size(64.dp)
                .pressScale(onClick = onPlayPause, scaleDown = 0.90f)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.22f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (isPlaying) PhosphorIcons.Light.Pause else PhosphorIcons.Light.Play, "Play", tint = Color.White, modifier = Modifier.size(30.dp))
        }
        Icon(PhosphorIcons.Light.SkipForward, "Next", tint = Color.White, modifier = Modifier.size(30.dp).pressScale(onClick = onNext))
        Icon(PhosphorIcons.Light.Repeat, "Repeat", tint = Color.White, modifier = Modifier.pressScale(onClick = {}).padding(8.dp))
    }
}

private fun formatTime(milliseconds: Long): String = "%d:%02d".format(milliseconds / 60_000, (milliseconds / 1_000) % 60)