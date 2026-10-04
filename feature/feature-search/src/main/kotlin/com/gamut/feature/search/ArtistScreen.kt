package com.gamut.feature.search

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.SubcomposeAsyncImage
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Light
import com.adamglin.phosphoricons.light.ArrowLeft
import com.adamglin.phosphoricons.light.MusicNote
import com.gamut.core.designsystem.CoverTheme
import com.gamut.core.designsystem.LocalCoverPalette
import com.gamut.core.designsystem.TransparentStatusBar
import com.gamut.core.designsystem.fadeEdges
import com.gamut.core.designsystem.ink
import com.gamut.core.designsystem.pressScale
import com.gamut.core.designsystem.VerticalTabs
import com.gamut.core.designsystem.glass
import com.gamut.core.model.Track
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class ArtistViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: DiscoverRepository,
) : ViewModel() {
    private val artistId = savedStateHandle.get<String>("artistId")?.toLongOrNull()
    private val _state = MutableStateFlow<ArtistDetails?>(null)
    val state: StateFlow<ArtistDetails?> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val id = artistId ?: repository.findArtistId(savedStateHandle.get<String>("artistName").orEmpty())
            _state.value = if (id == null) null else repository.artist(id)
        }
    }
}

@Composable
fun ArtistScreen(
    viewModel: ArtistViewModel = viewModel(),
    onBack: () -> Unit,
    /** The picked track plus the list it was picked from (the queue). */
    onTrackSelected: (Track, List<Track>) -> Unit,
    onAlbumSelected: (DiscoverItem) -> Unit = {},
    nowPlaying: @Composable () -> Unit = {},
) {
    val artist by viewModel.state.collectAsStateWithLifecycle()
    val palette = LocalCoverPalette.current
    TransparentStatusBar(darkIcons = palette.isLight)
    CoverTheme(palette) {
        if (artist == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = ink(), strokeWidth = 2.dp)
            }
            return@CoverTheme
        }
        val value = artist ?: return@CoverTheme
        val tabs = listOf("Overview", "Songs", "Albums", "Latest")
        var selectedTab by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableIntStateOf(0) }
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 10.dp, end = 24.dp, top = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(44.dp).pressScale(onClick = onBack), contentAlignment = Alignment.Center) {
                    Icon(PhosphorIcons.Light.ArrowLeft, "Back", tint = ink(), modifier = Modifier.size(22.dp))
                }
                Text(
                    value.name,
                    style = MaterialTheme.typography.displayMedium,
                    color = ink(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f).padding(start = 12.dp),
                )
            }
            Row(Modifier.weight(1f).fillMaxWidth()) {
                VerticalTabs(
                    labels = tabs,
                    selected = selectedTab,
                    onSelect = { selectedTab = it },
                    modifier = Modifier.fillMaxHeight().padding(start = 12.dp),
                )
                Crossfade(selectedTab, label = "artist-tab", modifier = Modifier.weight(1f)) { tab ->
                    when (tab) {
                        0 -> ArtistOverview(value, { picked -> onTrackSelected(picked, value.popular) }, onAlbumSelected, onViewAll = { selectedTab = 1 })
                        1 -> TrackList(value.songs) { picked -> onTrackSelected(picked, value.songs) }
                        2 -> AlbumGrid(value.albums, onAlbumSelected)
                        else -> TrackList(value.latestSongs) { picked -> onTrackSelected(picked, value.latestSongs) }
                    }
                }
            }
            nowPlaying()
        }
    }
}

/** Share of the row width each Overview album card takes. A set width also stops long titles stretching a card. */
private const val OVERVIEW_ALBUM_FRACTION = 0.8f

@Composable
private fun ArtistOverview(artist: ArtistDetails, onTrack: (Track) -> Unit, onAlbum: (DiscoverItem) -> Unit, onViewAll: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize().fadeEdges(), contentPadding = PaddingValues(start = 12.dp, end = 24.dp, bottom = 24.dp)) {
        item {
            Column(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                SubcomposeAsyncImage(
                    model = artist.imageUrl,
                    contentDescription = artist.name,
                    modifier = Modifier.fillMaxWidth(0.92f).aspectRatio(1f).clip(CircleShape),
                    contentScale = ContentScale.Crop,
                    loading = { ArtistPlaceholder() },
                    error = { ArtistPlaceholder() },
                )
                if (artist.listeners.isNotBlank()) Text(artist.listeners, color = ink(0.6f), modifier = Modifier.padding(top = 14.dp))
            }
            Row(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Popular songs", style = MaterialTheme.typography.titleLarge, color = ink(), modifier = Modifier.weight(1f))
                Text("View all", color = ink(0.6f), modifier = Modifier.pressScale(onClick = onViewAll).padding(vertical = 6.dp))
            }
        }
        items(artist.popular.take(4), key = { it.isrc }) { TrackLine(it, onTrack) }
        item {
            Text("Albums", style = MaterialTheme.typography.titleLarge, color = ink(), modifier = Modifier.padding(top = 24.dp, bottom = 12.dp))
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                // ~80% of the available width: one big cover with the next one peeking in.
                val cardWidth = maxWidth * OVERVIEW_ALBUM_FRACTION
                LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(artist.albums.take(6), key = { it.id }) { AlbumCard(it, onAlbum, Modifier.width(cardWidth)) }
                }
            }
        }
    }
}

@Composable
private fun TrackList(tracks: List<Track>, onTrack: (Track) -> Unit) {
    if (tracks.isEmpty()) {
        Text("Nothing here yet.", color = ink(0.6f), modifier = Modifier.padding(16.dp))
        return
    }
    LazyColumn(Modifier.fillMaxSize().fadeEdges(), contentPadding = PaddingValues(start = 12.dp, end = 24.dp, bottom = 24.dp)) {
        items(tracks, key = { it.isrc }) { TrackLine(it, onTrack) }
    }
}

@Composable
private fun AlbumGrid(albums: List<DiscoverItem>, onAlbum: (DiscoverItem) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize().fadeEdges(),
        contentPadding = PaddingValues(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        items(albums, key = { it.id }) { AlbumCard(it, onAlbum) }
    }
}

@Composable
private fun AlbumCard(album: DiscoverItem, onClick: (DiscoverItem) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().pressScale(onClick = { onClick(album) }, scaleDown = 0.97f)) {
        SubcomposeAsyncImage(
            model = album.imageUrl,
            contentDescription = album.title,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(10.dp)),
            contentScale = ContentScale.Crop,
            loading = { ArtistPlaceholder() },
            error = { ArtistPlaceholder() },
        )
        Text(album.title, color = ink(), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        Text(album.subtitle, color = ink(0.6f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun TrackLine(track: Track, onClick: (Track) -> Unit) {
    Row(
        Modifier.fillMaxWidth().pressScale(onClick = { onClick(track) }, scaleDown = 0.98f).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SubcomposeAsyncImage(
            model = track.albumArtUrl,
            contentDescription = null,
            modifier = Modifier.size(54.dp).clip(RoundedCornerShape(8.dp)),
            contentScale = ContentScale.Crop,
            loading = { ArtistPlaceholder() },
            error = { ArtistPlaceholder() },
        )
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(track.title, color = ink(), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track.album ?: track.artist, color = ink(0.6f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun AlbumLine(album: DiscoverItem) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        SubcomposeAsyncImage(
            model = album.imageUrl,
            contentDescription = album.title,
            modifier = Modifier.size(64.dp).shadow(3.dp, RoundedCornerShape(10.dp)).clip(RoundedCornerShape(10.dp)),
            contentScale = ContentScale.Crop,
            loading = { ArtistPlaceholder() },
            error = { ArtistPlaceholder() },
        )
        Column(Modifier.padding(horizontal = 14.dp)) {
            Text(album.title, color = ink(), style = MaterialTheme.typography.titleMedium)
            Text(album.subtitle, color = ink(0.6f), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ArtistPlaceholder() {
    Box(Modifier.fillMaxSize().background(ink(0.08f)), contentAlignment = Alignment.Center) {
        Icon(PhosphorIcons.Light.MusicNote, null, tint = ink(0.45f), modifier = Modifier.size(24.dp))
    }
}