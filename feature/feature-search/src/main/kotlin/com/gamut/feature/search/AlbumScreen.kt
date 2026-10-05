package com.gamut.feature.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import androidx.compose.foundation.lazy.itemsIndexed
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.Light
import com.adamglin.phosphoricons.fill.Play
import com.adamglin.phosphoricons.light.ArrowLeft
import com.adamglin.phosphoricons.light.VinylRecord
import com.gamut.core.designsystem.AcrylicCircleButton
import com.gamut.core.designsystem.AcrylicHost
import com.gamut.core.designsystem.AcrylicScrim
import com.gamut.core.designsystem.acrylicSource
import com.gamut.core.designsystem.CoverTheme
import com.gamut.core.designsystem.HeroListSkeleton
import com.gamut.core.designsystem.LocalCoverPalette
import com.gamut.core.designsystem.TransparentStatusBar
import com.gamut.core.designsystem.glass
import com.gamut.core.designsystem.ink
import com.gamut.core.designsystem.pressScale
import com.gamut.core.model.Track
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class AlbumViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: DiscoverRepository,
) : ViewModel() {
    private val _state = MutableStateFlow<AlbumDetails?>(null)
    val state: StateFlow<AlbumDetails?> = _state.asStateFlow()
    private val _failed = MutableStateFlow(false)
    val failed: StateFlow<Boolean> = _failed.asStateFlow()
    private val _artistImage = MutableStateFlow<String?>(null)
    /** Verified artist photo; arrives after the album so it never delays the screen. */
    val artistImage: StateFlow<String?> = _artistImage.asStateFlow()

    init {
        val item = DiscoverItem(
            id = savedStateHandle.get<String>("id").orEmpty(),
            title = savedStateHandle.get<String>("title").orEmpty(),
            subtitle = savedStateHandle.get<String>("subtitle").orEmpty(),
            imageUrl = savedStateHandle.get<String>("art").orEmpty(),
        )
        viewModelScope.launch {
            val album = repository.album(item)
            _state.value = album
            _failed.value = album == null
            if (album != null) _artistImage.value = repository.artistPhotoFor(album.artist, album.title)
        }
    }
}

/**
 * An album, laid out like PlaylistScreen: the cover fills the top edge to edge under the status bar and fades
 * into the flowing background, with title, counts and a play button on the fade. Songs scroll below.
 */
@Composable
fun AlbumScreen(
    viewModel: AlbumViewModel,
    onBack: () -> Unit,
    /** The picked track plus the list it was picked from (the queue). */
    onTrackSelected: (Track, List<Track>) -> Unit,
    nowPlaying: @Composable () -> Unit = {},
) {
    val album by viewModel.state.collectAsStateWithLifecycle()
    val failed by viewModel.failed.collectAsStateWithLifecycle()
    val artistImage by viewModel.artistImage.collectAsStateWithLifecycle()
    val palette = LocalCoverPalette.current
    // The hero cover sits under the status bar, so light icons whenever it is showing.
    TransparentStatusBar(darkIcons = if (album != null) false else palette.isLight)
    CoverTheme(palette) {
        AcrylicHost(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    val value = album
                    when {
                        value != null -> LazyColumn(
                            Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 24.dp),
                        ) {
                            item(key = "hero") {
                                AlbumHero(
                                    album = value,
                                    artistImageUrl = artistImage,
                                    onBack = onBack,
                                    onPlay = { value.tracks.firstOrNull()?.let { onTrackSelected(it, value.tracks) } },
                                )
                            }
                            // Index in the key: duplicate ISRCs in one album would otherwise crash LazyColumn.
                            itemsIndexed(value.tracks, key = { i, t -> "${t.isrc}-$i" }) { _, track ->
                                Box(Modifier.padding(horizontal = 24.dp)) { AlbumTrackRow(track) { picked -> onTrackSelected(picked, value.tracks) } }
                            }
                            item(key = "nav-space") { Box(Modifier.navigationBarsPadding()) }
                        }
                        failed -> {
                            BackButton(onBack)
                            Text(
                                "Couldn't load this album.",
                                color = ink(0.6f),
                                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                            )
                        }
                        else -> {
                            // Shimmering placeholder in the final layout: the screen reacts on the very first frame of the tap.
                            HeroListSkeleton()
                            BackButton(onBack)
                        }
                    }
                }
                nowPlaying()
            }
        }
    }
}

@Composable
private fun AlbumHero(album: AlbumDetails, artistImageUrl: String?, onBack: () -> Unit, onPlay: () -> Unit) {
    Box(Modifier.fillMaxWidth()) {
        SubcomposeAsyncImage(
            model = album.imageUrl,
            contentDescription = album.title,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f).fadeBottom().acrylicSource(),
            contentScale = ContentScale.Crop,
            loading = { Box(Modifier.fillMaxSize().background(ink(0.08f))) },
            error = { Box(Modifier.fillMaxSize().background(ink(0.08f))) },
        )
        // Progressive blur behind the status bar: keeps the icons readable on bright covers without a dark band.
        AcrylicScrim(Modifier.fillMaxWidth().height(110.dp))
        BackButton(onBack)
        Row(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (artistImageUrl != null) {
                SubcomposeAsyncImage(
                    model = artistImageUrl,
                    contentDescription = album.artist,
                    modifier = Modifier.size(48.dp).clip(CircleShape).border(1.dp, ink(0.25f), CircleShape),
                    contentScale = ContentScale.Crop,
                    loading = { ArtistAvatarFallback() },
                    error = { ArtistAvatarFallback() },
                )
            } else {
                Box(Modifier.size(48.dp)) { ArtistAvatarFallback() }
            }
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(
                    album.title,
                    style = MaterialTheme.typography.titleLarge,
                    color = ink(),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${album.artist} \u2022 ${album.tracks.size} songs",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ink(0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            AcrylicCircleButton(onClick = onPlay, size = 56.dp) {
                Icon(PhosphorIcons.Fill.Play, "Play", tint = Color.White, modifier = Modifier.size(24.dp))
            }
        }
    }
}

@Composable
private fun AlbumTrackRow(track: Track, onClick: (Track) -> Unit) {
    Row(
        Modifier.fillMaxWidth().pressScale(onClick = { onClick(track) }, scaleDown = 0.98f).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = track.albumArtUrl,
            contentDescription = null,
            modifier = Modifier.size(54.dp).clip(RoundedCornerShape(8.dp)).background(ink(0.08f)),
            contentScale = ContentScale.Crop,
        )
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(track.title, color = ink(), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track.artist, color = ink(0.6f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun BackButton(onBack: () -> Unit) {
    AcrylicCircleButton(
        onClick = onBack,
        modifier = Modifier.statusBarsPadding().padding(start = 20.dp, top = 12.dp),
        size = 44.dp,
    ) {
        Icon(PhosphorIcons.Light.ArrowLeft, "Back", tint = Color.White, modifier = Modifier.size(22.dp))
    }
}

/** Shown while the artist photo loads, or when no verified photo exists. */
@Composable
private fun ArtistAvatarFallback() {
    Box(Modifier.fillMaxSize().glass(CircleShape), contentAlignment = Alignment.Center) {
        Icon(PhosphorIcons.Light.VinylRecord, null, tint = ink(), modifier = Modifier.size(22.dp))
    }
}

/** Fades the bottom of a layer to transparent so the flowing background shows through. */
private fun Modifier.fadeBottom(): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        drawRect(
            brush = Brush.verticalGradient(0.55f to Color.Black, 1f to Color.Transparent),
            blendMode = BlendMode.DstIn,
        )
    }