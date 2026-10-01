package com.hush.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.Light
import com.adamglin.phosphoricons.fill.Heart
import com.adamglin.phosphoricons.fill.Play
import com.adamglin.phosphoricons.light.ArrowLeft
import com.adamglin.phosphoricons.light.VinylRecord
import com.hush.core.designsystem.CoverTheme
import com.hush.core.designsystem.LocalCoverPalette
import com.hush.core.designsystem.TransparentStatusBar
import com.hush.core.designsystem.glass
import com.hush.core.designsystem.ink
import com.hush.core.designsystem.pressScale

private val TileShape = RoundedCornerShape(10.dp)

/**
 * A playlist. The first song's cover fills the top edge to edge, running under the status bar, and fades
 * into the flowing background. Title, counts and the play button sit on the fade. Songs scroll below.
 */
@Composable
fun PlaylistScreen(
    playlistId: String,
    onBack: () -> Unit,
    onPlayer: () -> Unit = {},
    nowPlaying: @Composable () -> Unit = {},
) {
    val playlist = LibraryMock.playlist(playlistId)
    val songs = playlist?.songs.orEmpty()
    val first = songs.firstOrNull()

    val palette = LocalCoverPalette.current
    // The hero image sits under the status bar, so light icons regardless of the cover palette.
    TransparentStatusBar(darkIcons = if (first != null) false else palette.isLight)

    CoverTheme(palette) {
        val accent = MaterialTheme.colorScheme.primary
        Column(Modifier.fillMaxSize()) {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                item(key = "hero") {
                    Box(Modifier.fillMaxWidth()) {
                        if (first != null) {
                            CoverImage(first.imageUrl, Modifier.fillMaxWidth().aspectRatio(1f).fadeBottom())
                            // Keeps the status bar icons readable on bright covers.
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(110.dp)
                                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.45f), Color.Transparent))),
                            )
                        } else {
                            Box(Modifier.fillMaxWidth().height(200.dp))
                        }

                        Box(
                            Modifier
                                .statusBarsPadding()
                                .padding(start = 20.dp, top = 12.dp)
                                .size(44.dp)
                                .background(Color.Black.copy(alpha = 0.45f), CircleShape)
                                .pressScale(onClick = onBack),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(PhosphorIcons.Light.ArrowLeft, "Back", tint = Color.White, modifier = Modifier.size(22.dp))
                        }

                        Row(
                            Modifier
                                .align(Alignment.BottomStart)
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier.size(48.dp).glass(TileShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                val icon: ImageVector =
                                    if (playlist?.isLiked == true) PhosphorIcons.Fill.Heart else PhosphorIcons.Light.VinylRecord
                                Icon(icon, null, tint = ink(), modifier = Modifier.size(22.dp))
                            }
                            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                                Text(
                                    playlist?.name ?: "Playlist",
                                    style = MaterialTheme.typography.titleLarge,
                                    color = ink(),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                val artists = songs.map { it.artist }.distinct().size
                                Text(
                                    "${songs.size} songs \u2022 $artists artists",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = ink(0.6f),
                                )
                            }
                            Box(
                                Modifier
                                    .size(56.dp)
                                    .background(accent, CircleShape)
                                    .pressScale(onClick = onPlayer),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    PhosphorIcons.Fill.Play,
                                    "Play",
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(24.dp),
                                )
                            }
                        }
                    }
                }

                if (songs.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            "Nothing here yet. Add songs from Discover.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = ink(0.6f),
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp),
                        )
                    }
                }

                items(songs, key = { it.id }) { song ->
                    SongRow(
                        song = song,
                        liked = playlist?.isLiked == true,
                        onClick = onPlayer,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                }

                item(key = "nav-space") { Box(Modifier.navigationBarsPadding()) }
            }
            nowPlaying()
        }
    }
}

/** Fades the bottom of a layer to transparent so whatever is behind (the flowing background) shows through. */
private fun Modifier.fadeBottom(): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        drawRect(
            brush = Brush.verticalGradient(0.55f to Color.Black, 1f to Color.Transparent),
            blendMode = BlendMode.DstIn,
        )
    }