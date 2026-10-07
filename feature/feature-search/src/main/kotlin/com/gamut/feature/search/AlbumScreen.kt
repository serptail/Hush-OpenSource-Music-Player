package com.gamut.feature.search

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.SubcomposeAsyncImage
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.Light
import com.adamglin.phosphoricons.fill.Play
import com.adamglin.phosphoricons.light.ArrowLeft
import com.adamglin.phosphoricons.light.VinylRecord
import com.gamut.core.designsystem.AcrylicCircleButton
import com.gamut.core.designsystem.AcrylicHost
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
 * Minimal album page. The cover is a single, crisp, rounded object floating on the flowing background (no edge-to-edge
 * bleed, no gradient fade), followed by a quiet title block and a clean numbered track list.
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
    // The cover no longer sits under the status bar, so icons simply follow the palette.
    TransparentStatusBar(darkIcons = palette.isLight)
    CoverTheme(palette) {
        AcrylicHost(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    val value = album
                    when {
                        value != null -> {
                            LazyColumn(
                                Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(bottom = 24.dp),
                            ) {
                                item(key = "hero") {
                                    AlbumHero(
                                        album = value,
                                        artistImageUrl = artistImage,
                                        onPlay = { value.tracks.firstOrNull()?.let { onTrackSelected(it, value.tracks) } },
                                    )
                                }
                                // Index in the key: duplicate ISRCs in one album would otherwise crash LazyColumn.
                                itemsIndexed(value.tracks, key = { i, t -> "${t.isrc}-$i" }) { index, track ->
                                    AlbumTrackRow(
                                        number = index + 1,
                                        track = track,
                                        showArtist = !track.artist.equals(value.artist, ignoreCase = true),
                                    ) { picked -> onTrackSelected(picked, value.tracks) }
                                }
                                item(key = "nav-space") { Box(Modifier.navigationBarsPadding()) }
                            }
                            BackButton(onBack)
                        }
                        failed -> {
                            BackButton(onBack)
                            Text(
                                "Couldn't load this album. Go back and try again.",
                                color = ink(0.6f),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                            )
                        }
                        else -> {
                            // Shimmering placeholder: the screen reacts on the very first frame of the tap.
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
private fun AlbumHero(album: AlbumDetails, artistImageUrl: String?, onPlay: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            // Leaves room for the floating back button.
            .padding(top = 68.dp),
    ) {
        // The cover: one clean object with a soft, deep shadow so it lifts off the background.
        Box(
            Modifier
                .padding(horizontal = 44.dp)
                .fillMaxWidth()
                .aspectRatio(1f)
                .shadow(
                    elevation = 28.dp,
                    shape = RoundedCornerShape(14.dp),
                    ambientColor = Color.Black.copy(alpha = 0.30f),
                    spotColor = Color.Black.copy(alpha = 0.45f),
                )
                .clip(RoundedCornerShape(14.dp))
                .background(ink(0.08f)),
        ) {
            SubcomposeAsyncImage(
                model = album.imageUrl,
                contentDescription = album.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                loading = { Box(Modifier.fillMaxSize().background(ink(0.08f))) },
                error = {
                    Box(Modifier.fillMaxSize().background(ink(0.08f)), contentAlignment = Alignment.Center) {
                        Icon(PhosphorIcons.Light.VinylRecord, null, tint = ink(0.4f), modifier = Modifier.size(48.dp))
                    }
                },
            )
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 28.dp, end = 28.dp, top = 28.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = 16.dp)) {
                Text(
                    album.title,
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = (-0.3).sp,
                    ),
                    color = ink(),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.size(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ArtistAvatar(artistImageUrl, album.artist)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        album.artist,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                        color = ink(0.85f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                Spacer(Modifier.size(2.dp))
                Text(
                    if (album.tracks.size == 1) "1 song" else "${album.tracks.size} songs",
                    style = MaterialTheme.typography.bodySmall,
                    color = ink(0.5f),
                )
            }
            PlayFab(onClick = onPlay)
        }
    }
}

/** Small inline artist photo; falls back to a quiet glass disc while loading or when no verified photo exists. */
@Composable
private fun ArtistAvatar(url: String?, artist: String) {
    val modifier = Modifier.size(24.dp).clip(CircleShape)
    if (url != null) {
        SubcomposeAsyncImage(
            model = url,
            contentDescription = artist,
            modifier = modifier.border(1.dp, ink(0.15f), CircleShape),
            contentScale = ContentScale.Crop,
            loading = { ArtistAvatarFallback() },
            error = { ArtistAvatarFallback() },
        )
    } else {
        Box(modifier) { ArtistAvatarFallback() }
    }
}

/**
 * Solid play button, filled with the text color and the icon in its inverse, so it has strong contrast on any
 * cover palette.
 */
@Composable
private fun PlayFab(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val isLight = LocalCoverPalette.current.isLight
    Box(
        modifier
            .size(58.dp)
            .shadow(12.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.2f), spotColor = Color.Black.copy(alpha = 0.3f))
            .clip(CircleShape)
            .background(ink())
            .pressScale(onClick = onClick, scaleDown = 0.94f),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            PhosphorIcons.Fill.Play,
            "Play",
            tint = if (isLight) Color.White else Color.Black,
            // A triangle's visual weight is left of its box; nudge it right so it looks centered.
            modifier = Modifier.padding(start = 3.dp).size(24.dp),
        )
    }
}

/**
 * Numbered, text-only row. Every track shares the album cover, so repeating it per row was noise. The artist is only
 * shown when it differs from the album artist (features, compilations).
 *
 * Interaction: on hover (mouse, stylus, ChromeOS) a soft tint fades in and the number turns into a play glyph. On press
 * the row squeezes down and springs back with a small overshoot, the bounce.
 */
@Composable
private fun AlbumTrackRow(number: Int, track: Track, showArtist: Boolean, onClick: (Track) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val active = hovered || pressed

    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.95f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "rowScale",
    )
    val tint by animateColorAsState(
        targetValue = ink(if (pressed) 0.10f else if (hovered) 0.06f else 0f),
        animationSpec = tween(140),
        label = "rowTint",
    )

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(12.dp))
            .background(tint)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null) { onClick(track) }
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(32.dp), contentAlignment = Alignment.CenterStart) {
            Crossfade(targetState = active, animationSpec = tween(120), label = "rowLead") { on ->
                if (on) {
                    Icon(PhosphorIcons.Fill.Play, null, tint = ink(), modifier = Modifier.size(14.dp))
                } else {
                    Text(
                        number.toString(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = ink(0.4f),
                    )
                }
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                color = ink(),
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (showArtist) {
                Text(
                    track.artist,
                    color = ink(0.55f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
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
        // Ink instead of hardcoded white: the button now floats over the background, not over a cover.
        Icon(PhosphorIcons.Light.ArrowLeft, "Back", tint = ink(), modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun ArtistAvatarFallback() {
    Box(Modifier.fillMaxSize().glass(CircleShape), contentAlignment = Alignment.Center) {
        Icon(PhosphorIcons.Light.VinylRecord, null, tint = ink(), modifier = Modifier.size(14.dp))
    }
}