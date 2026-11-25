package com.gamut.feature.search

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Bold
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.bold.ArrowLeft
import com.adamglin.phosphoricons.bold.DotsThreeVertical
import com.adamglin.phosphoricons.fill.Play
import com.gamut.core.designsystem.ArtPlaceholder
import com.gamut.core.designsystem.ArtistLine
import com.gamut.core.designsystem.ArtistPickerSheet
import com.gamut.core.designsystem.ArtistStyle
import com.gamut.core.designsystem.CoverTheme
import com.gamut.core.designsystem.ExplicitMark
import com.gamut.core.designsystem.LocalCoverPalette
import com.gamut.core.designsystem.LocalNavSettled
import com.gamut.core.designsystem.NowPlayingBars
import com.gamut.core.designsystem.ShareSheet
import com.gamut.core.designsystem.TrackRowSkeleton
import com.gamut.core.designsystem.TransparentStatusBar
import com.gamut.core.designsystem.VerticalTabs
import com.gamut.core.designsystem.bounceClickable
import com.gamut.core.designsystem.fadeEdges
import com.gamut.core.designsystem.focusRing
import com.gamut.core.designsystem.glass
import com.gamut.core.designsystem.ink
import com.gamut.core.designsystem.isWideLayout
import com.gamut.core.designsystem.pressScale
import com.gamut.core.designsystem.rememberArtistTap
import com.gamut.core.designsystem.shimmer
import com.gamut.core.designsystem.splitArtists
import com.gamut.core.designsystem.squareArtUrl
import com.gamut.core.designsystem.thumbnailBorder
import com.gamut.core.model.Track
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ArtistViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val repository: DiscoverRepository,
    ) : ViewModel() {
        private val artistId = savedStateHandle.get<String>("artistId")?.toLongOrNull()
        private val _state = MutableStateFlow<ArtistDetails?>(null)
        val state: StateFlow<ArtistDetails?> = _state.asStateFlow()
        private val _followKey = MutableStateFlow<String?>(null)
        val followKey: StateFlow<String?> = _followKey.asStateFlow()
        private val _failed = MutableStateFlow(false)
        val failed: StateFlow<Boolean> = _failed.asStateFlow()

        /** The songs of an album on this page, null when they cannot be loaded. */
        suspend fun albumTracks(item: DiscoverItem): List<Track>? = repository.album(item)?.tracks

        /** Songs from similar artists, loaded once the similar-artist row is. Empty until then. */
        @Volatile private var discovery: List<Track> = emptyList()

        /**
         * [queue] with songs from similar artists mixed in behind the picked song: one for every few of the artist's
         * own, and any left over at the end. Songs before [picked] stay where they are. Returns [queue] unchanged when
         * the similar songs have not loaded, so tapping a song never waits on them.
         */
        fun withDiscovery(
            queue: List<Track>,
            picked: Track,
        ): List<Track> {
            val own = queue.mapTo(HashSet()) { it.isrc }
            val extra = discovery.filter { it.isrc !in own }
            if (extra.isEmpty()) return queue
            val at = queue.indexOfFirst { it.isrc == picked.isrc }.coerceAtLeast(0)
            val out = ArrayList<Track>(queue.size + extra.size)
            out.addAll(queue.subList(0, at + 1))
            var next = 0
            queue.subList(at + 1, queue.size).forEachIndexed { n, track ->
                out += track
                if ((n + 1) % OWN_RUN == 0 && next < extra.size) out += extra[next++]
            }
            while (next < extra.size) out += extra[next++]
            return out
        }

        init {
            viewModelScope.launch {
                val id = artistId ?: repository.findArtistId(savedStateHandle.get<String>("artistName").orEmpty())
                _followKey.value = id?.let { "deezer:$it" }
                val base = id?.let { repository.artistOverview(it) }
                _state.value = base
                _failed.value = base == null
                if (base == null) return@launch
                launch {
                    val similar = repository.similarArtists(base)
                    _state.update { it?.copy(similar = similar) }
                    discovery = runCatching { repository.similarTracks(similar) }.getOrDefault(emptyList())
                }
                launch { loadPortrait(base) }
                if (base.songsLoaded) return@launch
                val full = repository.artistSongs(base)
                _state.update { it?.copy(songs = full.songs, latestSongs = full.latestSongs, songsLoaded = true) }
            }
        }

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

        private companion object {
            /** How many of the artist's own songs play between songs from similar artists. */
            const val OWN_RUN = 4
        }
    }

@Composable
fun ArtistScreen(
    viewModel: ArtistViewModel = viewModel(),
    onBack: () -> Unit,
    onTrackSelected: (Track, List<Track>) -> Unit,
    onAlbumSelected: (DiscoverItem) -> Unit = {},
    onSimilarArtist: (String) -> Unit = {},
    onTrackArtist: (Track) -> Unit = {},
    favoriteIsrcs: Set<String> = emptySet(),
    onToggleFavorite: (Track) -> Unit = {},
    playlists: List<TrackPlaylistOption> = emptyList(),
    onAddToPlaylist: (Track, String) -> Unit = { _, _ -> },
    nowPlayingIsrc: String? = null,
    nowPlaying: @Composable () -> Unit = {},
    followedKeys: Set<String> = emptySet(),
    onToggleFollow: (key: String, name: String, imageUrl: String?) -> Unit = { _, _, _ -> },
    style: ArtistStyle = ArtistStyle.Classic,
) {
    val playFrom: (Track, List<Track>) -> Unit = { picked, queue -> onTrackSelected(picked, viewModel.withDiscovery(queue, picked)) }
    val artist by viewModel.state.collectAsStateWithLifecycle()
    val failed by viewModel.failed.collectAsStateWithLifecycle()
    val palette = LocalCoverPalette.current
    TransparentStatusBar(darkIcons = palette.isLight)
    val settled = LocalNavSettled.current
    val wide = isWideLayout()
    CoverTheme(palette) {
        AlbumActionsHost(
            loadTracks = viewModel::albumTracks,
            favoriteIsrcs = favoriteIsrcs,
            onToggleFavorite = onToggleFavorite,
            playlists = playlists,
            onAddToPlaylist = onAddToPlaylist,
            onPlay = onTrackSelected,
        ) {
        if (artist == null || !settled) {
            Box(Modifier.fillMaxSize().statusBarsPadding()) {
                if (!failed) {
                    if (wide) {
                        Row(Modifier.fillMaxSize().padding(top = 78.dp, start = 56.dp, end = 24.dp, bottom = 24.dp)) {
                            Box(Modifier.weight(0.38f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                                Box(
                                    Modifier
                                        .widthIn(max = 300.dp)
                                        .fillMaxWidth(0.92f)
                                        .aspectRatio(1f)
                                        .clip(CircleShape)
                                        .shimmer(),
                                )
                            }
                            Column(Modifier.weight(0.62f).padding(start = 12.dp)) { repeat(4) { TrackRowSkeleton() } }
                        }
                    } else {
                        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Spacer(Modifier.height(78.dp))
                            Box(
                                Modifier
                                    .fillMaxWidth(0.7f)
                                    .aspectRatio(1f)
                                    .clip(CircleShape)
                                    .shimmer(),
                            )
                            Spacer(Modifier.height(28.dp))
                            repeat(4) { TrackRowSkeleton() }
                        }
                    }
                }
                Box(
                    Modifier.padding(start = 10.dp, top = 14.dp).size(44.dp).pressScale(onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(PhosphorIcons.Bold.ArrowLeft, "Back", tint = ink(), modifier = Modifier.size(22.dp))
                }
                if (failed) {
                    Text("Couldn't load this artist.", color = ink(0.6f), modifier = Modifier.align(Alignment.Center).padding(24.dp))
                }
            }
            return@AlbumActionsHost
        }
        val value = artist ?: return@AlbumActionsHost
        val followKey by viewModel.followKey.collectAsStateWithLifecycle()
        val notifier = com.gamut.core.designsystem.LocalNotifier.current
        val following = followKey != null && followKey in followedKeys
        val onFollowClick: () -> Unit = {
            followKey?.let { key ->
                onToggleFollow(key, value.name, value.imageUrl)
                if (following) notifier.info("Unfollowed ${value.name}") else notifier.success("Following ${value.name}")
            }
        }
        val tabs = listOf("Overview", "Songs", "Albums", "Latest")
        var actionsTrack by remember { mutableStateOf<Track?>(null) }
        var shareTrack by remember { mutableStateOf<Track?>(null) }
        var artistPickTrack by remember { mutableStateOf<Track?>(null) }
        var pickerTrack by remember { mutableStateOf<Track?>(null) }
        val accent = MaterialTheme.colorScheme.primary
        var selectedTab by androidx.compose.runtime.saveable
            .rememberSaveable { androidx.compose.runtime.mutableIntStateOf(0) }
        if (style == ArtistStyle.Full) {
            FullArtistPage(
                artist = value,
                tabs = tabs,
                selectedTab = selectedTab,
                onTab = { selectedTab = it },
                wide = wide,
                onBack = onBack,
                onTrack = playFrom,
                onAlbum = onAlbumSelected,
                onSimilarArtist = onSimilarArtist,
                nowPlayingIsrc = nowPlayingIsrc,
                accent = accent,
                onMore = { actionsTrack = it },
                following = following,
                onFollowClick = onFollowClick,
                nowPlaying = nowPlaying,
            )
        } else if (style == ArtistStyle.Showcase) {
            ShowcaseArtistPage(
                artist = value,
                wide = wide,
                onBack = onBack,
                onTrack = playFrom,
                onAlbum = onAlbumSelected,
                onSimilarArtist = onSimilarArtist,
                nowPlayingIsrc = nowPlayingIsrc,
                accent = accent,
                onMore = { actionsTrack = it },
                following = following,
                onFollowClick = onFollowClick,
                nowPlaying = nowPlaying,
            )
        } else {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 10.dp, end = 24.dp, top = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(44.dp).pressScale(onClick = onBack), contentAlignment = Alignment.Center) {
                        Icon(PhosphorIcons.Bold.ArrowLeft, "Back", tint = ink(), modifier = Modifier.size(22.dp))
                    }
                    Text(
                        value.name,
                        style = MaterialTheme.typography.displayMedium,
                        color = ink(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = if (wide) TextAlign.Start else TextAlign.End,
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
                            0 -> {
                                ArtistOverview(
                                    artist = value,
                                    wide = wide,
                                    onTrack = { picked -> playFrom(picked, value.popular) },
                                    nowPlayingIsrc = nowPlayingIsrc,
                                    accent = accent,
                                    onMore = { actionsTrack = it },
                                    onAlbum = onAlbumSelected,
                                    onArtist = onSimilarArtist,
                                    onViewAll = { selectedTab = 1 },
                                    following = following,
                                    onFollowClick = onFollowClick,
                                )
                            }

                            1 -> {
                                TrackList(value.songs, loading = !value.songsLoaded, wide = wide, nowPlayingIsrc = nowPlayingIsrc, accent = accent, onMore = {
                                    actionsTrack =
                                        it
                                }) { picked -> playFrom(picked, value.songs) }
                            }

                            2 -> {
                                AlbumGrid(value.albums, onAlbumSelected, wide)
                            }

                            else -> {
                                TrackList(value.latestSongs, loading = !value.songsLoaded, wide = wide, nowPlayingIsrc = nowPlayingIsrc, accent = accent, onMore = {
                                    actionsTrack =
                                        it
                                }) { picked -> playFrom(picked, value.latestSongs) }
                            }
                        }
                    }
                }
                nowPlaying()
            }
        }

        actionsTrack?.let { t ->
            TrackActionsSheet(
                track = t,
                isFavorite = t.isrc in favoriteIsrcs,
                onDismiss = { actionsTrack = null },
                onShare = {
                    actionsTrack = null
                    shareTrack = t
                },
                onToggleFavorite = { onToggleFavorite(t) },
                onAddToPlaylist = {
                    actionsTrack = null
                    pickerTrack = t
                },
                onGoToArtist = {
                    actionsTrack = null
                    if (splitArtists(t.artist).size > 1) artistPickTrack = t else onTrackArtist(t)
                },
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
        artistPickTrack?.let { t ->
            ArtistPickerSheet(
                names = splitArtists(t.artist),
                albumTitle = t.album,
                onPick = { name -> onTrackArtist(t.copy(artist = name)) },
                onDismiss = { artistPickTrack = null },
            )
        }
        shareTrack?.let { t -> ShareSheet(title = t.title, artist = t.artist, artUrl = t.albumArtUrl, onDismiss = { shareTrack = null }) }
            }
}
}

private const val OVERVIEW_ALBUM_FRACTION = 0.8f

@Composable
private fun ArtistOverview(
    artist: ArtistDetails,
    wide: Boolean = false,
    onTrack: (Track) -> Unit,
    nowPlayingIsrc: String?,
    accent: Color,
    onMore: (Track) -> Unit,
    onAlbum: (DiscoverItem) -> Unit,
    onArtist: (String) -> Unit,
    onViewAll: () -> Unit,
    following: Boolean,
    onFollowClick: () -> Unit,
) {
    val portrait: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            SubcomposeAsyncImage(
                model = artist.imageUrl,
                contentDescription = artist.name,
                modifier =
                    Modifier
                        .then(
                            if (wide) Modifier.widthIn(max = 300.dp) else Modifier,
                        ).fillMaxWidth(0.92f)
                        .aspectRatio(1f)
                        .clip(CircleShape)
                        .thumbnailBorder(CircleShape),
                contentScale = ContentScale.Crop,
                loading = { ArtistPlaceholder() },
                error = { ArtistPlaceholder() },
            )
            FollowButton(following, onFollowClick, Modifier.padding(top = 14.dp))
        }
    }
    if (wide) {
        Row(Modifier.fillMaxSize()) {
            BoxWithConstraints(Modifier.weight(0.38f).fillMaxHeight()) {
                val paneHeight = maxHeight
                Column(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(
                            rememberScrollState(),
                        ).heightIn(min = paneHeight)
                        .padding(start = 12.dp, end = 12.dp, bottom = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) { portrait() }
            }
            LazyColumn(
                Modifier.weight(0.62f).fillMaxHeight().fadeEdges(),
                contentPadding = PaddingValues(start = 12.dp, bottom = 24.dp),
            ) { overviewBody(artist, true, onTrack, nowPlayingIsrc, accent, onMore, onAlbum, onArtist, onViewAll, endPad = 24.dp) }
        }
    } else {
        LazyColumn(
            Modifier.fillMaxSize().fadeEdges(),
            contentPadding = PaddingValues(start = 12.dp, end = 24.dp, bottom = 24.dp),
        ) {
            item { portrait() }
            overviewBody(artist, false, onTrack, nowPlayingIsrc, accent, onMore, onAlbum, onArtist, onViewAll)
        }
    }
}

private fun LazyListScope.overviewBody(
    artist: ArtistDetails,
    wide: Boolean,
    onTrack: (Track) -> Unit,
    nowPlayingIsrc: String?,
    accent: Color,
    onMore: (Track) -> Unit,
    onAlbum: (DiscoverItem) -> Unit,
    onArtist: (String) -> Unit,
    onViewAll: () -> Unit,
    endPad: Dp = 0.dp,
) {
    item {
        Row(
            Modifier.fillMaxWidth().padding(top = if (wide) 12.dp else 24.dp, bottom = 6.dp, end = endPad),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Popular songs", style = MaterialTheme.typography.titleLarge, color = ink(), modifier = Modifier.weight(1f))
            Text("View all", color = ink(0.6f), modifier = Modifier.pressScale(onClick = onViewAll).padding(vertical = 6.dp))
        }
    }
    if (wide) {
        item(key = "popular") {
            BoxWithConstraints(Modifier.fillMaxWidth().padding(end = endPad)) {
                val columns = ((maxWidth + 20.dp) / (380.dp + 20.dp)).toInt().coerceIn(1, 2)
                Column {
                    artist.popular.take(4).chunked(columns).forEach { rowTracks ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                            rowTracks.forEach { t ->
                                TrackLine(
                                    t,
                                    isPlaying = t.isrc == nowPlayingIsrc,
                                    accent = accent,
                                    onClick = onTrack,
                                    onMore = onMore,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            repeat(columns - rowTracks.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
    } else {
        itemsIndexed(artist.popular.take(4), key = {
            i,
            t,
            ->
            "${t.isrc}-$i"
        }) { _, t -> TrackLine(t, isPlaying = t.isrc == nowPlayingIsrc, accent = accent, onClick = onTrack, onMore = onMore) }
    }
    item {
        Text("Albums", style = MaterialTheme.typography.titleLarge, color = ink(), modifier = Modifier.padding(top = 24.dp, bottom = 12.dp))
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val cardWidth = (maxWidth * OVERVIEW_ALBUM_FRACTION).let { if (wide) it.coerceAtMost(220.dp) else it }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(end = endPad)) {
                items(artist.albums.take(6), key = { it.id }) { AlbumCard(it, onAlbum, Modifier.width(cardWidth)) }
            }
        }
    }
    if (artist.similar.isNotEmpty()) {
        item(key = "similar") {
            Text(
                "Similar artists",
                style = MaterialTheme.typography.titleLarge,
                color = ink(),
                modifier = Modifier.padding(top = 24.dp, bottom = 12.dp),
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(end = endPad)) {
                items(artist.similar, key = { it.id }) { SimilarArtistCard(it, onArtist) }
            }
        }
    }
}

@Composable
private fun TrackList(
    tracks: List<Track>,
    loading: Boolean = false,
    wide: Boolean = false,
    nowPlayingIsrc: String?,
    accent: Color,
    onMore: (Track) -> Unit,
    onTrack: (Track) -> Unit,
) {
    if (tracks.isEmpty() && !loading) {
        Text("Nothing here yet.", color = ink(0.6f), modifier = Modifier.padding(16.dp))
        return
    }
    if (wide) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(380.dp),
            modifier = Modifier.fillMaxSize().fadeEdges(),
            contentPadding = PaddingValues(start = 12.dp, end = 24.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            itemsIndexed(tracks, key = {
                i,
                t,
                ->
                "${t.isrc}-$i"
            }) { _, t -> TrackLine(t, isPlaying = t.isrc == nowPlayingIsrc, accent = accent, onClick = onTrack, onMore = onMore) }
            if (loading) {
                item(key = "loading", span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = ink(), strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                    }
                }
            }
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize().fadeEdges(), contentPadding = PaddingValues(start = 12.dp, end = 24.dp, bottom = 24.dp)) {
        itemsIndexed(tracks, key = {
            i,
            t,
            ->
            "${t.isrc}-$i"
        }) { _, t -> TrackLine(t, isPlaying = t.isrc == nowPlayingIsrc, accent = accent, onClick = onTrack, onMore = onMore) }
        if (loading) {
            item(key = "loading") {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = ink(), strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                }
            }
        }
    }
}

@Composable
private fun AlbumGrid(
    albums: List<DiscoverItem>,
    onAlbum: (DiscoverItem) -> Unit,
    wide: Boolean = false,
) {
    LazyVerticalGrid(
        columns = if (wide) GridCells.Adaptive(170.dp) else GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize().fadeEdges(),
        contentPadding = PaddingValues(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        items(albums, key = { it.id }) { AlbumCard(it, onAlbum) }
    }
}

@Composable
private fun AlbumCard(
    album: DiscoverItem,
    onClick: (DiscoverItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val albumActions = LocalAlbumActions.current
    Column(
        modifier.fillMaxWidth().pressScale(
            onClick = { onClick(album) },
            scaleDown = 0.97f,
            onLongClick = albumActions?.let { open -> { open(album) } },
        ),
    ) {
        AsyncImage(
            model = remember(album.imageUrl) { squareArtUrl(album.imageUrl, 480) },
            contentDescription = album.title,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(ink(0.08f))
                    .thumbnailBorder(RoundedCornerShape(10.dp)),
            contentScale = ContentScale.Crop,
        )
        Text(
            album.title,
            color = ink(),
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(album.subtitle, color = ink(0.6f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun TrackLine(
    track: Track,
    isPlaying: Boolean,
    accent: Color,
    onClick: (Track) -> Unit,
    onMore: (Track) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().pressScale(onClick = { onClick(track) }, scaleDown = 0.98f).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = remember(track.albumArtUrl) { squareArtUrl(track.albumArtUrl, 200) },
            contentDescription = null,
            modifier = Modifier.size(54.dp).clip(RoundedCornerShape(8.dp)).background(ink(0.08f)).thumbnailBorder(RoundedCornerShape(8.dp)),
            contentScale = ContentScale.Crop,
        )
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isPlaying) NowPlayingBars(color = accent, modifier = Modifier.padding(end = 8.dp))
                ExplicitMark(track.isExplicit, ink(0.6f))
                Text(
                    track.title,
                    color = if (isPlaying) accent else ink(),
                    style =
                        MaterialTheme.typography.titleMedium.copy(
                            fontWeight = if (isPlaying) FontWeight.SemiBold else FontWeight.Medium,
                        ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            Text(
                track.album ?: track.artist,
                color = ink(0.6f),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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
            .focusRing(CircleShape)
            .bounceClickable(role = Role.Button, onClickLabel = "More options", onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(PhosphorIcons.Bold.DotsThreeVertical, "More options", tint = ink(0.6f), modifier = Modifier.size(24.dp)) }
}

@Composable
private fun SimilarArtistCard(
    artist: DiscoverItem,
    onClick: (String) -> Unit,
) {
    Column(
        Modifier.width(96.dp).pressScale(onClick = { onClick(artist.title) }, scaleDown = 0.96f),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SubcomposeAsyncImage(
            model = artist.imageUrl,
            contentDescription = artist.title,
            modifier = Modifier.size(96.dp).clip(CircleShape).thumbnailBorder(CircleShape),
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
            modifier = Modifier.size(64.dp).shadow(3.dp, RoundedCornerShape(10.dp)).clip(RoundedCornerShape(10.dp)).thumbnailBorder(RoundedCornerShape(10.dp)),
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

// ---------------------------------------------------------------------------------------------
// Full style: the portrait bleeds behind the status bar and melts into the page, tab chips sit
// under it, and everything scrolls as one page.
// ---------------------------------------------------------------------------------------------

private val FullSide = 24.dp

@Composable
private fun FullArtistPage(
    artist: ArtistDetails,
    tabs: List<String>,
    selectedTab: Int,
    onTab: (Int) -> Unit,
    wide: Boolean,
    onBack: () -> Unit,
    onTrack: (Track, List<Track>) -> Unit,
    onAlbum: (DiscoverItem) -> Unit,
    onSimilarArtist: (String) -> Unit,
    nowPlayingIsrc: String?,
    accent: Color,
    onMore: (Track) -> Unit,
    following: Boolean,
    onFollowClick: () -> Unit,
    nowPlaying: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                item(key = "hero") {
                    FullArtistHero(
                        artist = artist,
                        wide = wide,
                        following = following,
                        onFollowClick = onFollowClick,
                        onPlay = { artist.popular.firstOrNull()?.let { onTrack(it, artist.popular) } },
                    )
                }
                item(key = "tabs") { FullArtistTabs(tabs, selectedTab, onTab) }
                when (selectedTab) {
                    0 -> {
                        fullOverview(
                            artist = artist,
                            wide = wide,
                            onTrack = { picked -> onTrack(picked, artist.popular) },
                            nowPlayingIsrc = nowPlayingIsrc,
                            accent = accent,
                            onMore = onMore,
                            onAlbum = onAlbum,
                            onSimilarArtist = onSimilarArtist,
                            onViewAll = { onTab(1) },
                        )
                    }

                    1 -> {
                        fullTracks("songs", artist.songs, !artist.songsLoaded, nowPlayingIsrc, accent, onMore) { picked ->
                            onTrack(picked, artist.songs)
                        }
                    }

                    2 -> {
                        fullAlbums(artist.albums, wide, onAlbum)
                    }

                    else -> {
                        fullTracks("latest", artist.latestSongs, !artist.songsLoaded, nowPlayingIsrc, accent, onMore) { picked ->
                            onTrack(picked, artist.latestSongs)
                        }
                    }
                }
            }
            // Floating back button: the portrait is behind it, so it gets its own dark disc.
            Box(
                Modifier
                    .statusBarsPadding()
                    .padding(start = 10.dp, top = 8.dp)
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.32f))
                    .pressScale(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(PhosphorIcons.Bold.ArrowLeft, "Back", tint = Color.White, modifier = Modifier.size(22.dp))
            }
        }
        nowPlaying()
    }
}

@Composable
private fun FullArtistHero(
    artist: ArtistDetails,
    wide: Boolean,
    following: Boolean,
    onFollowClick: () -> Unit,
    onPlay: () -> Unit,
) {
    val palette = LocalCoverPalette.current
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().aspectRatio(if (wide) 1.8f else 0.95f).background(ink(0.08f))) {
            SubcomposeAsyncImage(
                model = artist.imageUrl,
                contentDescription = artist.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
                loading = { ArtistPlaceholder() },
                error = { ArtistPlaceholder() },
            )
            // Soft dark scrim at the top keeps the back button readable on bright portraits.
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.28f), 0.22f to Color.Transparent),
                ),
            )
            // The melt: clear over the portrait, the page colour by the bottom edge.
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(0.45f to Color.Transparent, 0.92f to palette.base.copy(alpha = 0.92f), 1f to palette.base),
                ),
            )
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = FullSide, end = FullSide, bottom = 4.dp)) {
                Text(
                    artist.name,
                    style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold),
                    color = ink(),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (artist.albums.isNotEmpty()) {
                    Text(
                        if (artist.albums.size == 1) "1 album" else "${artist.albums.size} albums",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ink(0.6f),
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(start = FullSide, end = FullSide, top = 14.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (artist.popular.isNotEmpty()) {
                Row(
                    Modifier
                        .pressScale(onClick = onPlay, scaleDown = 0.96f)
                        .clip(CircleShape)
                        .background(ink(0.95f))
                        .padding(start = 20.dp, end = 26.dp, top = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(PhosphorIcons.Fill.Play, null, tint = palette.base, modifier = Modifier.size(18.dp))
                    Text(
                        "Play",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = palette.base,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
            Box(
                Modifier
                    .pressScale(onClick = onFollowClick, scaleDown = 0.96f)
                    .then(
                        if (following) {
                            Modifier.clip(CircleShape).background(ink(0.12f)).border(1.dp, ink(0.35f), CircleShape)
                        } else {
                            Modifier.glass(CircleShape)
                        },
                    ).padding(horizontal = 24.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (following) "Following" else "Follow",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = ink(),
                )
            }
        }
    }
}

@Composable
private fun FullArtistTabs(
    tabs: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    val palette = LocalCoverPalette.current
    LazyRow(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        contentPadding = PaddingValues(horizontal = FullSide),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(tabs) { index, label ->
            val isSelected = index == selected
            Box(
                Modifier
                    .clip(CircleShape)
                    .background(if (isSelected) ink(0.95f) else ink(0.08f))
                    .pressScale(onClick = { onSelect(index) }, scaleDown = 0.96f)
                    .padding(horizontal = 18.dp, vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    color = if (isSelected) palette.base else ink(0.75f),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                )
            }
        }
    }
}

private fun LazyListScope.fullOverview(
    artist: ArtistDetails,
    wide: Boolean,
    onTrack: (Track) -> Unit,
    nowPlayingIsrc: String?,
    accent: Color,
    onMore: (Track) -> Unit,
    onAlbum: (DiscoverItem) -> Unit,
    onSimilarArtist: (String) -> Unit,
    onViewAll: () -> Unit,
) {
    item(key = "popular-header") {
        Row(
            Modifier.fillMaxWidth().padding(start = FullSide, end = FullSide, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Popular", style = MaterialTheme.typography.titleLarge, color = ink(), modifier = Modifier.weight(1f))
            Text("View all", color = ink(0.6f), modifier = Modifier.pressScale(onClick = onViewAll).padding(vertical = 6.dp))
        }
    }
    itemsIndexed(artist.popular.take(5), key = { i, t -> "popular-${t.isrc}-$i" }) { _, t ->
        TrackLine(
            t,
            isPlaying = t.isrc == nowPlayingIsrc,
            accent = accent,
            onClick = onTrack,
            onMore = onMore,
            modifier = Modifier.padding(start = FullSide, end = 12.dp),
        )
    }
    if (artist.albums.isNotEmpty()) {
        item(key = "albums-header") {
            Text(
                "Albums",
                style = MaterialTheme.typography.titleLarge,
                color = ink(),
                modifier = Modifier.padding(start = FullSide, top = 24.dp, bottom = 12.dp),
            )
        }
        item(key = "albums-row") {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(horizontal = FullSide)) {
                items(artist.albums.take(8), key = { it.id }) { AlbumCard(it, onAlbum, Modifier.width(if (wide) 190.dp else 150.dp)) }
            }
        }
    }
    if (artist.similar.isNotEmpty()) {
        item(key = "similar-header") {
            Text(
                "Similar artists",
                style = MaterialTheme.typography.titleLarge,
                color = ink(),
                modifier = Modifier.padding(start = FullSide, top = 24.dp, bottom = 12.dp),
            )
        }
        item(key = "similar-row") {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(horizontal = FullSide)) {
                items(artist.similar, key = { it.id }) { SimilarArtistCard(it, onSimilarArtist) }
            }
        }
    }
}

private fun LazyListScope.fullTracks(
    prefix: String,
    tracks: List<Track>,
    loading: Boolean,
    nowPlayingIsrc: String?,
    accent: Color,
    onMore: (Track) -> Unit,
    onTrack: (Track) -> Unit,
) {
    if (tracks.isEmpty() && !loading) {
        item(key = "$prefix-empty") {
            Text("Nothing here yet.", color = ink(0.6f), modifier = Modifier.padding(FullSide))
        }
    }
    itemsIndexed(tracks, key = { i, t -> "$prefix-${t.isrc}-$i" }) { _, t ->
        TrackLine(
            t,
            isPlaying = t.isrc == nowPlayingIsrc,
            accent = accent,
            onClick = onTrack,
            onMore = onMore,
            modifier = Modifier.padding(start = FullSide, end = 12.dp),
        )
    }
    if (loading) {
        item(key = "$prefix-loading") {
            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = ink(), strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
            }
        }
    }
}

private fun LazyListScope.fullAlbums(
    albums: List<DiscoverItem>,
    wide: Boolean,
    onAlbum: (DiscoverItem) -> Unit,
) {
    if (albums.isEmpty()) {
        item(key = "albums-empty") {
            Text("Nothing here yet.", color = ink(0.6f), modifier = Modifier.padding(FullSide))
        }
        return
    }
    val columns = if (wide) 4 else 2
    itemsIndexed(albums.chunked(columns), key = { i, _ -> "albums-grid-$i" }) { _, rowAlbums ->
        Row(
            Modifier.fillMaxWidth().padding(horizontal = FullSide, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            rowAlbums.forEach { AlbumCard(it, onAlbum, Modifier.weight(1f)) }
            repeat(columns - rowAlbums.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Showcase style: a compact header, then the albums as big swipeable covers, then the songs.
// ---------------------------------------------------------------------------------------------

private const val SHOWCASE_COVER_FRACTION = 0.74f
private const val SHOWCASE_SONG_PREVIEW = 5

@Composable
private fun ShowcaseArtistPage(
    artist: ArtistDetails,
    wide: Boolean,
    onBack: () -> Unit,
    onTrack: (Track, List<Track>) -> Unit,
    onAlbum: (DiscoverItem) -> Unit,
    onSimilarArtist: (String) -> Unit,
    nowPlayingIsrc: String?,
    accent: Color,
    onMore: (Track) -> Unit,
    following: Boolean,
    onFollowClick: () -> Unit,
    nowPlaying: @Composable () -> Unit,
) {
    val palette = LocalCoverPalette.current
    var showAll by androidx.compose.runtime.saveable
        .rememberSaveable { mutableStateOf(false) }
    val canExpand = artist.songs.size > SHOWCASE_SONG_PREVIEW
    val songs = if (showAll && canExpand) artist.songs else artist.popular.take(SHOWCASE_SONG_PREVIEW)
    val songsKey = if (showAll && canExpand) "all" else "top"
    val sectionTitle = MaterialTheme.typography.titleLarge

    Column(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .drawBehind {
                    // A faint wash of the artist's colours that fades out below the header.
                    drawRect(
                        Brush.verticalGradient(
                            colors = listOf(accent.copy(alpha = 0.20f), Color.Transparent),
                            endY = size.height * 0.45f,
                        ),
                    )
                },
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                Modifier.widthIn(max = 760.dp).fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                item(key = "header") {
                    Column(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 64.dp)) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = FullSide),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SubcomposeAsyncImage(
                                model = artist.imageUrl,
                                contentDescription = artist.name,
                                modifier = Modifier.size(84.dp).clip(CircleShape).thumbnailBorder(CircleShape),
                                contentScale = ContentScale.Crop,
                                alignment = Alignment.TopCenter,
                                loading = { ArtistPlaceholder() },
                                error = { ArtistPlaceholder() },
                            )
                            Column(Modifier.weight(1f).padding(start = 16.dp)) {
                                Text(
                                    artist.name,
                                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                                    color = ink(),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (artist.albums.isNotEmpty()) {
                                    Text(
                                        if (artist.albums.size == 1) "1 album" else "${artist.albums.size} albums",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = ink(0.6f),
                                    )
                                }
                            }
                        }
                        Row(
                            Modifier.fillMaxWidth().padding(start = FullSide, end = FullSide, top = 18.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (artist.popular.isNotEmpty()) {
                                Row(
                                    Modifier
                                        .pressScale(onClick = { artist.popular.firstOrNull()?.let { onTrack(it, artist.popular) } }, scaleDown = 0.96f)
                                        .clip(CircleShape)
                                        .background(ink(0.95f))
                                        .padding(start = 20.dp, end = 26.dp, top = 12.dp, bottom = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(PhosphorIcons.Fill.Play, null, tint = palette.base, modifier = Modifier.size(18.dp))
                                    Text(
                                        "Play",
                                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                                        color = palette.base,
                                        modifier = Modifier.padding(start = 8.dp),
                                    )
                                }
                            }
                            Box(
                                Modifier
                                    .pressScale(onClick = onFollowClick, scaleDown = 0.96f)
                                    .then(
                                        if (following) {
                                            Modifier.clip(CircleShape).background(ink(0.12f)).border(1.dp, ink(0.35f), CircleShape)
                                        } else {
                                            Modifier.glass(CircleShape)
                                        },
                                    ).padding(horizontal = 24.dp, vertical = 12.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    if (following) "Following" else "Follow",
                                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                                    color = ink(),
                                )
                            }
                        }
                    }
                }

                if (artist.albums.isNotEmpty()) {
                    item(key = "albums-header") {
                        Text(
                            "Albums",
                            style = sectionTitle,
                            color = ink(),
                            modifier = Modifier.padding(start = FullSide, top = 28.dp),
                        )
                    }
                    item(key = "albums-covers") {
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            val cover = (maxWidth * SHOWCASE_COVER_FRACTION).coerceAtMost(320.dp)
                            val listState = rememberLazyListState()
                            LazyRow(
                                state = listState,
                                flingBehavior = rememberSnapFlingBehavior(listState),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                // The vertical padding gives the cover shadows room inside the row's clip.
                                contentPadding = PaddingValues(start = FullSide, end = FullSide, top = 14.dp, bottom = 14.dp),
                            ) {
                                items(artist.albums, key = { it.id }) { BigAlbumCover(it, cover, onAlbum) }
                            }
                        }
                    }
                }

                item(key = "songs-header") {
                    Text(
                        "Popular",
                        style = sectionTitle,
                        color = ink(),
                        modifier = Modifier.padding(start = FullSide, top = 16.dp, bottom = 4.dp),
                    )
                }
                itemsIndexed(songs, key = { i, t -> "songs-$songsKey-${t.isrc}-$i" }) { _, t ->
                    TrackLine(
                        t,
                        isPlaying = t.isrc == nowPlayingIsrc,
                        accent = accent,
                        onClick = { picked -> onTrack(picked, if (showAll && canExpand) artist.songs else artist.popular) },
                        onMore = onMore,
                        modifier = Modifier.padding(start = FullSide, end = 12.dp),
                    )
                }
                if (canExpand) {
                    item(key = "songs-toggle") {
                        Text(
                            if (showAll) "Show less" else "Show all songs",
                            color = ink(0.7f),
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                            modifier =
                                Modifier
                                    .padding(start = FullSide - 4.dp, top = 4.dp)
                                    .pressScale(onClick = { showAll = !showAll }, scaleDown = 0.96f)
                                    .padding(horizontal = 4.dp, vertical = 8.dp),
                        )
                    }
                }

                if (artist.latestSongs.isNotEmpty()) {
                    item(key = "latest-header") {
                        Text(
                            "Latest",
                            style = sectionTitle,
                            color = ink(),
                            modifier = Modifier.padding(start = FullSide, top = 20.dp, bottom = 4.dp),
                        )
                    }
                    itemsIndexed(artist.latestSongs.take(3), key = { i, t -> "latest-${t.isrc}-$i" }) { _, t ->
                        TrackLine(
                            t,
                            isPlaying = t.isrc == nowPlayingIsrc,
                            accent = accent,
                            onClick = { picked -> onTrack(picked, artist.latestSongs) },
                            onMore = onMore,
                            modifier = Modifier.padding(start = FullSide, end = 12.dp),
                        )
                    }
                }

                if (artist.similar.isNotEmpty()) {
                    item(key = "similar-header") {
                        Text(
                            "Similar artists",
                            style = sectionTitle,
                            color = ink(),
                            modifier = Modifier.padding(start = FullSide, top = 24.dp, bottom = 12.dp),
                        )
                    }
                    item(key = "similar-row") {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            contentPadding = PaddingValues(horizontal = FullSide),
                        ) {
                            items(artist.similar, key = { it.id }) { SimilarArtistCard(it, onSimilarArtist) }
                        }
                    }
                }
            }
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(start = 10.dp, top = 14.dp)
                    .size(44.dp)
                    .pressScale(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(PhosphorIcons.Bold.ArrowLeft, "Back", tint = ink(), modifier = Modifier.size(22.dp))
            }
        }
        nowPlaying()
    }
}

/** A big album cover for the Showcase carousel, with the title and year line under it. */
@Composable
private fun BigAlbumCover(
    album: DiscoverItem,
    size: Dp,
    onClick: (DiscoverItem) -> Unit,
) {
    val shape = RoundedCornerShape(22.dp)
    val albumActions = LocalAlbumActions.current
    Column(
        Modifier.width(size).pressScale(
            onClick = { onClick(album) },
            scaleDown = 0.97f,
            onLongClick = albumActions?.let { open -> { open(album) } },
        ),
    ) {
        AsyncImage(
            model = remember(album.imageUrl) { squareArtUrl(album.imageUrl, 800) },
            contentDescription = album.title,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .shadow(
                        elevation = 14.dp,
                        shape = shape,
                        ambientColor = Color.Black.copy(alpha = 0.14f),
                        spotColor = Color.Black.copy(alpha = 0.24f),
                    ).clip(shape)
                    .background(ink(0.08f))
                    .thumbnailBorder(shape),
            contentScale = ContentScale.Crop,
        )
        Text(
            album.title,
            color = ink(),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 12.dp, start = 4.dp),
        )
        Text(
            album.subtitle,
            color = ink(0.6f),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

@Composable
private fun ArtistPlaceholder() {
    ArtPlaceholder()
}

@Composable
private fun FollowButton(
    following: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fg = ink()
    val onFg = if (fg.luminance() > 0.5f) Color.Black else Color.White
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .clip(shape)
            .background(if (following) ink(0.12f) else fg, shape)
            .then(if (following) Modifier.border(1.dp, ink(0.35f), shape) else Modifier)
            .pressScale(onClick = onClick, scaleDown = 0.96f)
            .padding(horizontal = 28.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (following) "Following" else "Follow",
            color = if (following) fg else onFg,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}