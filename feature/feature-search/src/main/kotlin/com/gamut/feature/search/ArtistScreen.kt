package com.gamut.feature.search

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import androidx.compose.foundation.lazy.itemsIndexed
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Light
import com.adamglin.phosphoricons.light.ArrowLeft
import com.adamglin.phosphoricons.light.DotsThreeVertical
import com.gamut.core.designsystem.CoverTheme
import com.gamut.core.designsystem.TrackRowSkeleton
import com.gamut.core.designsystem.shimmer
import androidx.compose.foundation.layout.Spacer
import com.gamut.core.designsystem.LocalCoverPalette
import com.gamut.core.designsystem.NowPlayingBars
import com.gamut.core.designsystem.TransparentStatusBar
import com.gamut.core.designsystem.LocalNavSettled
import com.gamut.core.designsystem.squareArtUrl
import com.gamut.core.designsystem.fadeEdges
import com.gamut.core.designsystem.ink
import com.gamut.core.designsystem.pressScale
import com.gamut.core.designsystem.VerticalTabs
import com.gamut.core.designsystem.ArtPlaceholder
import com.gamut.core.designsystem.glass
import com.gamut.core.model.Track
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class ArtistViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: DiscoverRepository,
) : ViewModel() {
    private val artistId = savedStateHandle.get<String>("artistId")?.toLongOrNull()
    private val _state = MutableStateFlow<ArtistDetails?>(null)
    val state: StateFlow<ArtistDetails?> = _state.asStateFlow()
    private val _failed = MutableStateFlow(false)
    /** True when the artist could not be loaded at all (so the screen stops spinning). */
    val failed: StateFlow<Boolean> = _failed.asStateFlow()

    init {
        viewModelScope.launch {
            val id = artistId ?: repository.findArtistId(savedStateHandle.get<String>("artistName").orEmpty())
            val base = id?.let { repository.artistOverview(it) }
            _state.value = base          // the page is usable here: top songs + albums
            _failed.value = base == null
            if (base == null) return@launch
            // Similar artists load on their own, so a slow answer never holds up the page.
            launch {
                val similar = repository.similarArtists(base)
                _state.update { it?.copy(similar = similar) }
            }
            // The portrait loads on its own, whether or not the songs are already there.
            launch { loadPortrait(base) }
            if (base.songsLoaded) return@launch
            val full = repository.artistSongs(base)
            _state.update { it?.copy(songs = full.songs, latestSongs = full.latestSongs, songsLoaded = true) }
        }
    }

    /** Deezer can be slow or rate-limited, so a single empty answer is not final: retry with growing pauses. */
    private suspend fun loadPortrait(base: ArtistDetails) {
        repeat(4) { attempt ->
            val photo = repository.artistPhoto(base)
            if (photo != null) {
                _state.update { it?.copy(imageUrl = photo) }
                return
            }
            if (attempt < 3) delay(1_500L * (attempt + 1))
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
    /** Opens another artist's page by name (Similar artists; the app resolves the name when it is tapped). */
    onSimilarArtist: (String) -> Unit = {},
    /** Opens the artist page of a track (by name), from the actions sheet. */
    onTrackArtist: (Track) -> Unit = {},
    /** ISRCs of the user's favorites, for the actions sheet. */
    favoriteIsrcs: Set<String> = emptySet(),
    onToggleFavorite: (Track) -> Unit = {},
    /** The user's playlists, for "Add to playlist". */
    playlists: List<TrackPlaylistOption> = emptyList(),
    onAddToPlaylist: (Track, String) -> Unit = { _, _ -> },
    /** ISRC of the track loaded in the player: its row gets the accent title + animated bars (like Search / Discover). */
    nowPlayingIsrc: String? = null,
    nowPlaying: @Composable () -> Unit = {},
) {
    val artist by viewModel.state.collectAsStateWithLifecycle()
    val failed by viewModel.failed.collectAsStateWithLifecycle()
    val palette = LocalCoverPalette.current
    TransparentStatusBar(darkIcons = palette.isLight)
    val settled = LocalNavSettled.current
    CoverTheme(palette) {
        // Skeleton while loading AND while the slide runs; the real page composes once the slide has finished.
        if (artist == null || !settled) {
            Box(Modifier.fillMaxSize().statusBarsPadding()) {
                if (!failed) {
                    // Placeholder in the final layout (round portrait + song rows) instead of a lone spinner.
                    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Spacer(Modifier.height(78.dp))
                        Box(Modifier.fillMaxWidth(0.7f).aspectRatio(1f).clip(CircleShape).shimmer())
                        Spacer(Modifier.height(28.dp))
                        repeat(4) { TrackRowSkeleton() }
                    }
                }
                Box(Modifier.padding(start = 10.dp, top = 14.dp).size(44.dp).pressScale(onClick = onBack), contentAlignment = Alignment.Center) {
                    Icon(PhosphorIcons.Light.ArrowLeft, "Back", tint = ink(), modifier = Modifier.size(22.dp))
                }
                if (failed) {
                    Text("Couldn't load this artist.", color = ink(0.6f), modifier = Modifier.align(Alignment.Center).padding(24.dp))
                }
            }
            return@CoverTheme
        }
        val value = artist ?: return@CoverTheme
        val tabs = listOf("Overview", "Songs", "Albums", "Latest")
        // Same three-dots flow as the Search screen and Discover: actions sheet -> share / playlist picker.
        var actionsTrack by remember { mutableStateOf<Track?>(null) }
        var shareTrack by remember { mutableStateOf<Track?>(null) }
        var pickerTrack by remember { mutableStateOf<Track?>(null) }
        val accent = MaterialTheme.colorScheme.primary
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
                        0 -> ArtistOverview(
                            artist = value,
                            onTrack = { picked -> onTrackSelected(picked, value.popular) },
                            nowPlayingIsrc = nowPlayingIsrc,
                            accent = accent,
                            onMore = { actionsTrack = it },
                            onAlbum = onAlbumSelected,
                            onArtist = onSimilarArtist,
                            onViewAll = { selectedTab = 1 },
                        )
                        1 -> TrackList(value.songs, loading = !value.songsLoaded, nowPlayingIsrc = nowPlayingIsrc, accent = accent, onMore = { actionsTrack = it }) { picked -> onTrackSelected(picked, value.songs) }
                        2 -> AlbumGrid(value.albums, onAlbumSelected)
                        else -> TrackList(value.latestSongs, loading = !value.songsLoaded, nowPlayingIsrc = nowPlayingIsrc, accent = accent, onMore = { actionsTrack = it }) { picked -> onTrackSelected(picked, value.latestSongs) }
                    }
                }
            }
            nowPlaying()
        }

        actionsTrack?.let { t ->
            TrackActionsSheet(
                track = t,
                isFavorite = t.isrc in favoriteIsrcs,
                onDismiss = { actionsTrack = null },
                onShare = { actionsTrack = null; shareTrack = t },
                onToggleFavorite = { onToggleFavorite(t) },
                onAddToPlaylist = { actionsTrack = null; pickerTrack = t },
                onGoToArtist = { actionsTrack = null; onTrackArtist(t) },
            )
        }
        pickerTrack?.let { t ->
            SavedInSheet(
                track = t,
                playlists = playlists,
                accent = accent,
                showFavorites = false,
                onAddToPlaylist = { id -> onAddToPlaylist(t, id) },
                onDismiss = { pickerTrack = null },
            )
        }
        shareTrack?.let { t -> ShareSheet(track = t, onDismiss = { shareTrack = null }) }
    }
}

/** Share of the row width each Overview album card takes. A set width also stops long titles stretching a card. */
private const val OVERVIEW_ALBUM_FRACTION = 0.8f

@Composable
private fun ArtistOverview(
    artist: ArtistDetails,
    onTrack: (Track) -> Unit,
    nowPlayingIsrc: String?,
    accent: Color,
    onMore: (Track) -> Unit,
    onAlbum: (DiscoverItem) -> Unit,
    onArtist: (String) -> Unit,
    onViewAll: () -> Unit,
) {
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
        itemsIndexed(artist.popular.take(4), key = { i, t -> "${t.isrc}-$i" }) { _, t -> TrackLine(t, isPlaying = t.isrc == nowPlayingIsrc, accent = accent, onClick = onTrack, onMore = onMore) }
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
        // Last on the page: artists related to this one (collaborators, rivals, same scene).
        if (artist.similar.isNotEmpty()) item(key = "similar") {
            Text("Similar artists", style = MaterialTheme.typography.titleLarge, color = ink(), modifier = Modifier.padding(top = 24.dp, bottom = 12.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                items(artist.similar, key = { it.id }) { SimilarArtistCard(it, onArtist) }
            }
        }
    }
}

@Composable
private fun TrackList(
    tracks: List<Track>,
    loading: Boolean = false,
    nowPlayingIsrc: String?,
    accent: Color,
    onMore: (Track) -> Unit,
    onTrack: (Track) -> Unit,
) {
    if (tracks.isEmpty() && !loading) {
        Text("Nothing here yet.", color = ink(0.6f), modifier = Modifier.padding(16.dp))
        return
    }
    LazyColumn(Modifier.fillMaxSize().fadeEdges(), contentPadding = PaddingValues(start = 12.dp, end = 24.dp, bottom = 24.dp)) {
        itemsIndexed(tracks, key = { i, t -> "${t.isrc}-$i" }) { _, t -> TrackLine(t, isPlaying = t.isrc == nowPlayingIsrc, accent = accent, onClick = onTrack, onMore = onMore) }
        // The rest of the discography is still arriving: show it instead of a list that looks finished.
        if (loading) item(key = "loading") {
            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = ink(), strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
            }
        }
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
        AsyncImage(
            model = remember(album.imageUrl) { squareArtUrl(album.imageUrl, 480) },
            contentDescription = album.title,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(10.dp)).background(ink(0.08f)),
            contentScale = ContentScale.Crop,
        )
        Text(album.title, color = ink(), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        Text(album.subtitle, color = ink(0.6f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun TrackLine(track: Track, isPlaying: Boolean, accent: Color, onClick: (Track) -> Unit, onMore: (Track) -> Unit) {
    Row(
        Modifier.fillMaxWidth().pressScale(onClick = { onClick(track) }, scaleDown = 0.98f).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            // Same 200 px url as the other lists, so covers already seen elsewhere come straight from the cache.
            model = remember(track.albumArtUrl) { squareArtUrl(track.albumArtUrl, 200) },
            contentDescription = null,
            modifier = Modifier.size(54.dp).clip(RoundedCornerShape(8.dp)).background(ink(0.08f)),
            contentScale = ContentScale.Crop,
        )
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            // The playing track: accent title + animated bars, exactly like the Search results.
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isPlaying) NowPlayingBars(color = accent, modifier = Modifier.padding(end = 8.dp))
                Text(
                    track.title,
                    color = if (isPlaying) accent else ink(),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = if (isPlaying) FontWeight.SemiBold else FontWeight.Medium),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            Text(track.album ?: track.artist, color = ink(0.6f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        TrackMoreButton { onMore(track) }
    }
}

@Composable
private fun TrackMoreButton(onClick: () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClickLabel = "More options", onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(PhosphorIcons.Light.DotsThreeVertical, "More options", tint = ink(0.6f), modifier = Modifier.size(24.dp)) }
}

@Composable
private fun SimilarArtistCard(artist: DiscoverItem, onClick: (String) -> Unit) {
    Column(
        Modifier.width(96.dp).pressScale(onClick = { onClick(artist.title) }, scaleDown = 0.96f),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SubcomposeAsyncImage(
            model = artist.imageUrl,
            contentDescription = artist.title,
            modifier = Modifier.size(96.dp).clip(CircleShape),
            contentScale = ContentScale.Crop,
            loading = { ArtistPlaceholder() },
            error = { ArtistPlaceholder() },
        )
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                artist.title,
                color = ink(),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (isVerifiedArtist(artist.followers)) VerifiedBadge(Modifier.padding(start = 4.dp), size = 14.dp)
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
    // Opaque and flat on purpose: AlbumLine puts a shadow behind this, and a translucent tile let it bleed through as haze.
    ArtPlaceholder()
}