package com.hush.feature.search

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.SubcomposeAsyncImage
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Light
import com.adamglin.phosphoricons.light.MagnifyingGlass
import com.adamglin.phosphoricons.light.MusicNote
import com.adamglin.phosphoricons.light.User
import com.adamglin.phosphoricons.light.VinylRecord
import com.adamglin.phosphoricons.light.X
import com.hush.core.designsystem.BottomBarItem
import com.hush.core.designsystem.CoverTheme
import com.hush.core.designsystem.HushBottomBar
import com.hush.core.designsystem.LocalCoverPalette
import com.hush.core.designsystem.TransparentStatusBar
import com.hush.core.designsystem.fadeEdges
import com.hush.core.designsystem.glass
import com.hush.core.designsystem.ink
import com.hush.core.designsystem.pressScale
import com.hush.core.designsystem.squareArtUrl
import com.hush.core.model.Track
import com.hush.core.model.TrackSource

// The only expressive element is the flowing background, which takes its colors from the cover of
// the track that is playing. Everything on top of it is a neutral "ink" (white on dark covers, near-black
// on light ones, supplied by CoverTheme) plus that cover's accent in a few small places (cursor, focus
// ring, spinners). Surfaces are flat, translucent ink.

private val TextPrimary: Color
    @Composable @ReadOnlyComposable get() = ink()
private val TextSecondary: Color
    @Composable @ReadOnlyComposable get() = ink(0.6f)
private val TextTertiary: Color
    @Composable @ReadOnlyComposable get() = ink(0.4f)

private val PanelShape = RoundedCornerShape(12.dp)
private val TagShape = RoundedCornerShape(4.dp)
private val ArtShape = RoundedCornerShape(8.dp)

@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    resolvingIsrc: String?,
    resolutionMessage: String?,
    onTrackSelected: (Track) -> Unit,
    onLibrary: () -> Unit,
    nowPlaying: @Composable () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // The flowing background is drawn once, behind the NavHost (see MainActivity); this is only its palette.
    val palette = LocalCoverPalette.current
    TransparentStatusBar(darkIcons = palette.isLight)

    CoverTheme(palette) {
        val accent = MaterialTheme.colorScheme.primary
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp)) {
                    Row(Modifier.fillMaxWidth().padding(top = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Discover",
                            style = MaterialTheme.typography.displayLarge,
                            color = TextPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        Box(Modifier.size(44.dp).pressScale(onClick = {}), contentAlignment = Alignment.Center) {
                            Icon(PhosphorIcons.Light.User, "Profile", tint = ink(0.85f), modifier = Modifier.size(24.dp))
                        }
                    }

                    Spacer(Modifier.height(22.dp))
                    SearchField(query = state.query, onQueryChange = viewModel::updateQuery, accent = accent)
                    Spacer(Modifier.height(20.dp))

                    AnimatedVisibility(state.isLoading) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
                            CircularProgressIndicator(Modifier.size(14.dp), color = accent, strokeWidth = 1.5.dp)
                            Spacer(Modifier.width(10.dp))
                            Text("Tuning the room...", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                        }
                    }
                    state.error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp))
                    }
                    resolutionMessage?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = TextSecondary, modifier = Modifier.padding(bottom = 8.dp))
                    }

                    if (state.tracks.isEmpty() && state.query.isBlank()) {
                        // Mockup content for now, see DiscoverSection.kt.
                        DiscoverContent(Modifier.weight(1f).fillMaxWidth())
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f).fadeEdges(),
                            contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            item {
                                Text(
                                    "Results",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = TextPrimary.copy(alpha = 0.9f),
                                    modifier = Modifier.padding(vertical = 8.dp),
                                )
                            }
                            items(state.tracks, key = { it.isrc }) { track ->
                                TrackResultRow(track, resolvingIsrc == track.isrc, accent, onTrackSelected)
                            }
                        }
                    }
                }

                HushBottomBar(
                    listOf(
                        BottomBarItem("Discover", PhosphorIcons.Light.MagnifyingGlass, selected = true),
                        BottomBarItem("Library", PhosphorIcons.Light.VinylRecord, onClick = onLibrary),
                    ),
                    nowPlaying = nowPlaying,
                )
            }
        }
    }
}

/** Flat translucent field, no edge at rest. While focused it gets a hairline ring in the cover's accent. */
@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit, accent: Color) {
    var focused by remember { mutableStateOf(false) }
    val focus by animateFloatAsState(if (focused) 1f else 0f, tween(200), label = "search-focus")
    Row(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .glass(PanelShape, fill = 0.07f, borderColor = accent.copy(alpha = 0.55f * focus))
            .padding(start = 18.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(PhosphorIcons.Light.MagnifyingGlass, null, tint = ink(0.55f), modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f).onFocusChanged { focused = it.isFocused },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = TextPrimary),
            cursorBrush = SolidColor(accent),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text("Artists, songs, albums", style = MaterialTheme.typography.bodyLarge, color = TextTertiary)
                    }
                    inner()
                }
            },
        )
        if (query.isNotEmpty()) {
            IconButton(onClick = { onQueryChange("") }) {
                Icon(PhosphorIcons.Light.X, "Clear", tint = ink(0.6f), modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun TrackResultRow(track: Track, isResolving: Boolean, accent: Color, onTrackSelected: (Track) -> Unit) {
    Row(
        Modifier.fillMaxWidth().pressScale(onClick = { onTrackSelected(track) }, scaleDown = 0.98f).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // YouTube thumbnails keep their own 16:9 shape (bar-free frame), everything else is square.
        val isYoutubeThumb = track.albumArtUrl?.let { "ytimg.com" in it || "img.youtube.com" in it } == true
        SubcomposeAsyncImage(
            model = squareArtUrl(track.albumArtUrl, 300),
            contentDescription = null,
            modifier = Modifier
                .size(width = if (isYoutubeThumb) 100.dp else 56.dp, height = 56.dp)
                .shadow(3.dp, ArtShape, clip = false, ambientColor = Color.Black, spotColor = Color.Black)
                .clip(ArtShape),
            contentScale = ContentScale.Crop,
            loading = { ArtworkPlaceholder() },
            error = { ArtworkPlaceholder() },
        )
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(
                track.title,
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    track.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                // Distinguishes fan edits / bootlegs / off-catalog finds (source = YOUTUBE,
                // no MusicBrainz match) from the canonical catalog tracks above them.
                if (track.source == TrackSource.YOUTUBE) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "YouTube",
                        style = MaterialTheme.typography.labelSmall,
                        color = ink(0.75f),
                        modifier = Modifier
                            .clip(TagShape)
                            .background(ink(0.10f), TagShape)
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
            }
            if (isResolving) {
                Text("Checking a verified source", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
            }
        }
        if (isResolving) {
            CircularProgressIndicator(Modifier.size(16.dp), color = accent, strokeWidth = 1.5.dp)
        }
    }
}

@Composable
private fun ArtworkPlaceholder() {
    Box(Modifier.fillMaxSize().background(ink(0.08f)), contentAlignment = Alignment.Center) {
        Icon(PhosphorIcons.Light.MusicNote, null, tint = ink(0.45f), modifier = Modifier.size(22.dp))
    }
}