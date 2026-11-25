package com.gamut.feature.search

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import com.gamut.core.designsystem.TablerIcons
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.SubcomposeAsyncImage
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Bold
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.bold.ArrowLeft
import com.adamglin.phosphoricons.bold.DotsThreeVertical
import com.adamglin.phosphoricons.bold.Shuffle
import com.adamglin.phosphoricons.fill.Play
import com.gamut.core.designsystem.AcrylicCircleButton
import com.gamut.core.designsystem.AcrylicHost
import com.gamut.core.designsystem.AlbumStyle
import com.gamut.core.designsystem.ArtPlaceholder
import com.gamut.core.designsystem.ArtistLine
import com.gamut.core.designsystem.ArtistPickerSheet
import com.gamut.core.designsystem.CoverTheme
import com.gamut.core.designsystem.ExplicitMark
import com.gamut.core.designsystem.HeroListSkeleton
import com.gamut.core.designsystem.LocalCoverPalette
import com.gamut.core.designsystem.LocalNavSettled
import com.gamut.core.designsystem.NowPlayingBars
import com.gamut.core.designsystem.ShareSheet
import com.gamut.core.designsystem.TransparentStatusBar
import com.gamut.core.designsystem.artTileColor
import com.gamut.core.designsystem.bounceClickable
import com.gamut.core.designsystem.bounceOnPress
import com.gamut.core.designsystem.collectIsPulsedAsState
import com.gamut.core.designsystem.focusRing
import com.gamut.core.designsystem.glass
import com.gamut.core.designsystem.ink
import com.gamut.core.designsystem.isWideLayout
import com.gamut.core.designsystem.pressScale
import com.gamut.core.designsystem.rememberArtistTap
import com.gamut.core.designsystem.splitArtists
import com.gamut.core.designsystem.thumbnailBorder
import com.gamut.core.model.Track
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AlbumViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val repository: DiscoverRepository,
    ) : ViewModel() {
        private val _state = MutableStateFlow<AlbumDetails?>(null)
        val state: StateFlow<AlbumDetails?> = _state.asStateFlow()
        private val _failed = MutableStateFlow(false)
        val failed: StateFlow<Boolean> = _failed.asStateFlow()
        private val _artistImage = MutableStateFlow<String?>(null)
        val artistImage: StateFlow<String?> = _artistImage.asStateFlow()

        init {
            val item =
                DiscoverItem(
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

@Composable
fun AlbumScreen(
    viewModel: AlbumViewModel,
    onBack: () -> Unit,
    onTrackSelected: (Track, List<Track>) -> Unit,
    onShuffle: ((List<Track>) -> Unit)? = null,
    style: AlbumStyle = AlbumStyle.Classic,
    onTrackArtist: (Track) -> Unit = {},
    favoriteIsrcs: Set<String> = emptySet(),
    onToggleFavorite: (Track) -> Unit = {},
    playlists: List<TrackPlaylistOption> = emptyList(),
    onAddToPlaylist: (Track, String) -> Unit = { _, _ -> },
    nowPlayingIsrc: String? = null,
    nowPlaying: @Composable () -> Unit = {},
) {
    val album by viewModel.state.collectAsStateWithLifecycle()
    val failed by viewModel.failed.collectAsStateWithLifecycle()
    val artistImage by viewModel.artistImage.collectAsStateWithLifecycle()
    var artistPickTrack by remember { mutableStateOf<Track?>(null) }
    val palette = LocalCoverPalette.current
    TransparentStatusBar(darkIcons = palette.isLight)
    val settled = LocalNavSettled.current
    var searching by rememberSaveable { mutableStateOf(false) }
    var songQuery by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val wide = isWideLayout()
    LaunchedEffect(searching) {
        // Bring the field into view: it sits just below the hero on a phone and at the top on a wide screen.
        if (searching) listState.animateScrollToItem(if (wide) 0 else 1)
    }
    AlbumActionsHost(
        loadTracks = { album?.tracks },
        favoriteIsrcs = favoriteIsrcs,
        onToggleFavorite = onToggleFavorite,
        playlists = playlists,
        onAddToPlaylist = onAddToPlaylist,
        onPlay = onTrackSelected,
    ) {
    CoverTheme(palette) {
        var actionsTrack by remember { mutableStateOf<Track?>(null) }
        var shareTrack by remember { mutableStateOf<Track?>(null) }
        var pickerTrack by remember { mutableStateOf<Track?>(null) }
        val accent = MaterialTheme.colorScheme.primary
        val soft = style == AlbumStyle.Soft
        AcrylicHost(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .then(
                            if (soft) {
                                // A faint wash of the cover's own colour that fades out below the artwork.
                                Modifier.drawBehind {
                                    drawRect(
                                        Brush.verticalGradient(
                                            colors = listOf(accent.copy(alpha = 0.22f), Color.Transparent),
                                            endY = size.height * 0.6f,
                                        ),
                                    )
                                }
                            } else {
                                Modifier
                            },
                        ),
                ) {
                    val value = album
                    when {
                        value != null && settled -> {
                            val onPlayAlbum = { value.tracks.firstOrNull()?.let { onTrackSelected(it, value.tracks) }; Unit }
                            val onArtistName = { name: String -> value.tracks.firstOrNull()?.let { onTrackArtist(it.copy(artist = name)) }; Unit }
                            val heroContent: @Composable () -> Unit = {
                                val shuffleAlbum = onShuffle?.let { shuffle -> { shuffle(value.tracks) } }
                                when (style) {
                                    AlbumStyle.Soft -> {
                                        SoftAlbumHero(
                                            album = value,
                                            artistImageUrl = artistImage,
                                            onPlay = onPlayAlbum,
                                            onShuffle = shuffleAlbum,
                                            onArtist = onArtistName,
                                        )
                                    }

                                    AlbumStyle.Full -> {
                                        FullAlbumHero(
                                            album = value,
                                            artistImageUrl = artistImage,
                                            onPlay = onPlayAlbum,
                                            onShuffle = shuffleAlbum,
                                            onArtist = onArtistName,
                                        )
                                    }

                                    AlbumStyle.Classic -> {
                                        AlbumHero(
                                            album = value,
                                            artistImageUrl = artistImage,
                                            onPlay = onPlayAlbum,
                                            onArtist = onArtistName,
                                        )
                                    }
                                }
                            }
                            val needle = songQuery.trim()
                            // Keep each song's place in the album so its number stays the album's number.
                            val visible =
                                remember(value.tracks, needle) {
                                    value.tracks.withIndex().filter { (_, t) ->
                                        needle.isEmpty() ||
                                            t.title.contains(needle, ignoreCase = true) ||
                                            t.artist.contains(needle, ignoreCase = true)
                                    }
                                }
                            val trackItems: LazyListScope.() -> Unit = {
                                if (searching) {
                                    item(key = "album-search") {
                                        AlbumSongSearch(
                                            query = songQuery,
                                            onQueryChange = { songQuery = it },
                                            onClose = {
                                                searching = false
                                                songQuery = ""
                                            },
                                        )
                                    }
                                    if (visible.isEmpty()) {
                                        item(key = "album-search-empty") {
                                            Text(
                                                "No songs match \u201C$needle\u201D",
                                                color = ink(0.6f),
                                                style = MaterialTheme.typography.bodyMedium,
                                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                                            )
                                        }
                                    }
                                }
                                itemsIndexed(visible, key = { _, iv -> "${iv.value.isrc}-${iv.index}" }) { position, indexed ->
                                    val index = indexed.index
                                    val track = indexed.value
                                    val showArtist = !track.artist.equals(value.artist, ignoreCase = true)
                                    val onArtist = { name: String -> onTrackArtist(track.copy(artist = name)) }
                                    val isPlaying = track.isrc == nowPlayingIsrc
                                    when (style) {
                                        AlbumStyle.Soft -> {
                                            SoftTrackRow(
                                                number = index + 1,
                                                track = track,
                                                showArtist = showArtist,
                                                first = position == 0,
                                                last = position == visible.lastIndex,
                                                onArtist = onArtist,
                                                isPlaying = isPlaying,
                                                accent = accent,
                                                onMore = { actionsTrack = it },
                                            ) { picked -> onTrackSelected(picked, value.tracks) }
                                        }

                                        AlbumStyle.Full -> {
                                            FullTrackRow(
                                                number = index + 1,
                                                track = track,
                                                showArtist = showArtist,
                                                last = position == visible.lastIndex,
                                                onArtist = onArtist,
                                                isPlaying = isPlaying,
                                                accent = accent,
                                                onMore = { actionsTrack = it },
                                            ) { picked -> onTrackSelected(picked, value.tracks) }
                                        }

                                        AlbumStyle.Classic -> {
                                            AlbumTrackRow(
                                                number = index + 1,
                                                track = track,
                                                showArtist = showArtist,
                                                onArtist = onArtist,
                                                isPlaying = isPlaying,
                                                accent = accent,
                                                onMore = { actionsTrack = it },
                                            ) { picked -> onTrackSelected(picked, value.tracks) }
                                        }
                                    }
                                }
                                item(key = "nav-space") { Box(Modifier.navigationBarsPadding()) }
                            }
                            if (isWideLayout()) {
                                Row(Modifier.fillMaxSize()) {
                                    Column(
                                        Modifier
                                            .weight(0.4f)
                                            .fillMaxHeight()
                                            .verticalScroll(rememberScrollState())
                                            .padding(bottom = 24.dp),
                                    ) {
                                        heroContent()
                                    }
                                    LazyColumn(
                                        Modifier.weight(0.6f).fillMaxHeight().statusBarsPadding(),
                                        state = listState,
                                        contentPadding = PaddingValues(top = 16.dp, end = 12.dp, bottom = 24.dp),
                                    ) { trackItems() }
                                }
                            } else {
                                LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
                                    item(key = "hero") { heroContent() }
                                    trackItems()
                                }
                            }
                            BackButton(onBack)
                            val albumActions = LocalAlbumActions.current
                            AlbumTopActions(
                                searching = searching,
                                onSearch = {
                                    searching = !searching
                                    if (!searching) songQuery = ""
                                },
                                onMore = {
                                    albumActions?.invoke(
                                        DiscoverItem(id = value.id, title = value.title, subtitle = value.artist, imageUrl = value.imageUrl.orEmpty()),
                                    )
                                },
                                modifier =
                                    Modifier
                                        .align(if (wide) Alignment.TopStart else Alignment.TopEnd)
                                        .statusBarsPadding()
                                        .padding(top = 12.dp, start = if (wide) 76.dp else 0.dp, end = if (wide) 0.dp else 20.dp),
                            )
                        }

                        value == null && failed -> {
                            BackButton(onBack)
                            Text(
                                "Couldn't load this album. Go back and try again.",
                                color = ink(0.6f),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                            )
                        }

                        else -> {
                            HeroListSkeleton()
                            BackButton(onBack)
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
        artistPickTrack?.let { t ->
            ArtistPickerSheet(
                names = splitArtists(t.artist),
                albumTitle = t.album,
                onPick = { name -> onTrackArtist(t.copy(artist = name)) },
                onDismiss = { artistPickTrack = null },
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
        shareTrack?.let { t -> ShareSheet(title = t.title, artist = t.artist, artUrl = t.albumArtUrl, onDismiss = { shareTrack = null }) }
    }
    }
}

/** Search and menu buttons shown over the top of the album, beside the back button. */
@Composable
private fun AlbumTopActions(
    searching: Boolean,
    onSearch: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        AcrylicCircleButton(onClick = onSearch, size = 44.dp) {
            Icon(
                if (searching) TablerIcons.Close else TablerIcons.Search,
                if (searching) "Close song search" else "Search songs in this album",
                tint = ink(),
                modifier = Modifier.size(20.dp),
            )
        }
        AcrylicCircleButton(onClick = onMore, size = 44.dp) {
            Icon(PhosphorIcons.Bold.DotsThreeVertical, "Album options", tint = ink(), modifier = Modifier.size(22.dp))
        }
    }
}

/** A field that filters the album's songs as you type. Takes focus when it appears. */
@Composable
private fun AlbumSongSearch(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        runCatching { focus.requestFocus() }
        keyboard?.show()
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .height(44.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(ink(0.07f))
            .padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(TablerIcons.Search, null, tint = ink(0.6f), modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f).focusRequester(focus),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = ink()),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text("Search in this album", style = MaterialTheme.typography.bodyLarge, color = ink(0.45f))
                    }
                    inner()
                }
            },
        )
        Box(
            Modifier.size(36.dp).clip(CircleShape).bounceClickable(role = Role.Button, onClickLabel = "Close search", onClick = onClose),
            contentAlignment = Alignment.Center,
        ) { Icon(TablerIcons.Close, "Close search", tint = ink(0.55f), modifier = Modifier.size(16.dp)) }
    }
}

@Composable
private fun AlbumHero(
    album: AlbumDetails,
    artistImageUrl: String?,
    onPlay: () -> Unit,
    onArtist: (String) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(top = 68.dp),
    ) {
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
                ).clip(RoundedCornerShape(14.dp))
                .background(artTileColor())
                .thumbnailBorder(RoundedCornerShape(14.dp)),
        ) {
            SubcomposeAsyncImage(
                model = album.imageUrl,
                contentDescription = album.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                loading = {},
                error = { ArtPlaceholder() },
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
                    style =
                        MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = (-0.3).sp,
                        ),
                    color = ink(),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.size(8.dp))
                ArtistLine(
                    credit = album.artist,
                    albumTitle = album.title,
                    onArtist = onArtist,
                    stackSize = 24.dp,
                    showSingle = true,
                    singlePhoto = artistImageUrl,
                ) { m ->
                    Text(
                        album.artist,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                        color = ink(0.85f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = m,
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

// Soft style tokens. The block radius and the row fill match the Favorites list so the two pages
// read as one family.
private val SoftInset = 20.dp
private val SoftBlockRadius = 22.dp
private val SoftArtShape = RoundedCornerShape(24.dp)
private val SoftPill = RoundedCornerShape(20.dp)

private fun softBlockShape(
    first: Boolean,
    last: Boolean,
): RoundedCornerShape {
    val top = if (first) SoftBlockRadius else 0.dp
    val bottom = if (last) SoftBlockRadius else 0.dp
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

private fun formatAlbumLength(ms: Long): String {
    val minutes = (ms / 60_000L).toInt().coerceAtLeast(1)
    return if (minutes >= 60) "${minutes / 60} hr ${minutes % 60} min" else "$minutes min"
}

private fun formatSongLength(ms: Long): String {
    val seconds = ms / 1000L
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}

/** Centered cover, title, artist, a one-line summary and the Play / Shuffle pills. */
@Composable
private fun SoftAlbumHero(
    album: AlbumDetails,
    artistImageUrl: String?,
    onPlay: () -> Unit,
    onShuffle: (() -> Unit)?,
    onArtist: (String) -> Unit,
) {
    val palette = LocalCoverPalette.current
    val totalMs = remember(album) { album.tracks.sumOf { it.durationMs.toLong() } }
    val count = if (album.tracks.size == 1) "1 song" else "${album.tracks.size} songs"
    val summary = if (totalMs > 0) "$count \u00B7 ${formatAlbumLength(totalMs)}" else count

    Column(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(top = 64.dp, bottom = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .padding(horizontal = 56.dp)
                .widthIn(max = 280.dp)
                .fillMaxWidth()
                .aspectRatio(1f)
                .shadow(
                    elevation = 16.dp,
                    shape = SoftArtShape,
                    ambientColor = Color.Black.copy(alpha = 0.16f),
                    spotColor = Color.Black.copy(alpha = 0.26f),
                ).clip(SoftArtShape)
                .background(artTileColor())
                .thumbnailBorder(SoftArtShape),
        ) {
            SubcomposeAsyncImage(
                model = album.imageUrl,
                contentDescription = album.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                loading = {},
                error = { ArtPlaceholder() },
            )
        }

        Text(
            album.title,
            style =
                MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-0.3).sp,
                ),
            color = ink(),
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 28.dp, end = 28.dp, top = 24.dp),
        )
        Spacer(Modifier.size(6.dp))
        ArtistLine(
            credit = album.artist,
            albumTitle = album.title,
            onArtist = onArtist,
            stackSize = 22.dp,
            showSingle = true,
            singlePhoto = artistImageUrl,
        ) { m ->
            Text(
                album.artist,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = ink(0.8f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = m,
            )
        }
        Text(
            summary,
            style = MaterialTheme.typography.bodySmall,
            color = ink(0.5f),
            modifier = Modifier.padding(top = 4.dp),
        )

        Row(
            Modifier.fillMaxWidth().padding(top = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Solid pill: the one bold action on the page.
            Row(
                Modifier
                    .pressScale(onClick = onPlay)
                    .clip(SoftPill)
                    .background(ink(0.95f))
                    .padding(horizontal = 26.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(PhosphorIcons.Fill.Play, null, tint = palette.base, modifier = Modifier.size(16.dp))
                Text(
                    "Play",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = palette.base,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            if (onShuffle != null) {
                Row(
                    Modifier
                        .pressScale(onClick = onShuffle)
                        .glass(SoftPill)
                        .padding(horizontal = 26.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(PhosphorIcons.Bold.Shuffle, null, tint = ink(), modifier = Modifier.size(16.dp))
                    Text(
                        "Shuffle",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = ink(),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}

/**
 * One song in the Soft style. Rows are stacked edge to edge into a single rounded block with a thin
 * gap between them; the song that is playing gets a faint wash of the accent.
 */
@Composable
private fun SoftTrackRow(
    number: Int,
    track: Track,
    showArtist: Boolean,
    first: Boolean,
    last: Boolean,
    onArtist: (String) -> Unit,
    isPlaying: Boolean,
    accent: Color,
    onMore: (Track) -> Unit,
    onClick: (Track) -> Unit,
) {
    val shape = softBlockShape(first, last)
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val active = hovered || pressed
    val fill by animateColorAsState(
        targetValue =
            if (isPlaying) {
                accent.copy(alpha = 0.14f)
            } else {
                ink(
                    if (pressed) {
                        0.12f
                    } else if (hovered) {
                        0.09f
                    } else {
                        0.07f
                    },
                )
            },
        animationSpec = tween(140),
        label = "softRowFill",
    )

    Column(Modifier.fillMaxWidth().padding(horizontal = SoftInset)) {
        Row(
            Modifier
                .fillMaxWidth()
                .bounceOnPress(interaction, 0.98f)
                .clip(shape)
                .background(fill)
                .focusRing(shape)
                .hoverable(interaction)
                .clickable(interactionSource = interaction, indication = null) { onClick(track) }
                .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(28.dp), contentAlignment = Alignment.CenterStart) {
                if (active) {
                    Icon(PhosphorIcons.Fill.Play, null, tint = ink(), modifier = Modifier.size(14.dp))
                } else if (isPlaying) {
                    NowPlayingBars(color = accent)
                } else {
                    Text(
                        number.toString(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = ink(0.4f),
                    )
                }
            }
            Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ExplicitMark(track.isExplicit, ink(0.55f))
                    Text(
                        track.title,
                        color = if (isPlaying) accent else ink(),
                        style =
                            MaterialTheme.typography.bodyLarge.copy(
                                fontWeight = if (isPlaying) FontWeight.SemiBold else FontWeight.Medium,
                            ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                if (showArtist || splitArtists(track.artist).size > 1) {
                    ArtistLine(track.artist, track.album, onArtist, stackSize = 16.dp, modifier = Modifier.padding(top = 2.dp)) { m ->
                        Text(
                            track.artist,
                            color = ink(0.55f),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = m,
                        )
                    }
                }
            }
            if (track.durationMs > 0) {
                Text(
                    formatSongLength(track.durationMs.toLong()),
                    style = MaterialTheme.typography.bodySmall,
                    color = ink(0.4f),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            AlbumMoreButton { onMore(track) }
        }
        if (!last) Spacer(Modifier.height(1.dp))
    }
}

/**
 * Full style hero: the cover runs edge to edge behind the status bar, fades into the page colour at
 * the bottom, and the title, artist and summary are written over that fade. Actions sit right below.
 */
@Composable
private fun FullAlbumHero(
    album: AlbumDetails,
    artistImageUrl: String?,
    onPlay: () -> Unit,
    onShuffle: (() -> Unit)?,
    onArtist: (String) -> Unit,
) {
    val palette = LocalCoverPalette.current
    val totalMs = remember(album) { album.tracks.sumOf { it.durationMs.toLong() } }
    val count = if (album.tracks.size == 1) "1 song" else "${album.tracks.size} songs"
    val summary = if (totalMs > 0) "$count \u00B7 ${formatAlbumLength(totalMs)}" else count

    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().aspectRatio(0.9f).background(artTileColor())) {
            SubcomposeAsyncImage(
                model = album.imageUrl,
                contentDescription = album.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                loading = {},
                error = { ArtPlaceholder() },
            )
            // Soft dark scrim at the top keeps the back button readable on bright covers.
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.28f), 0.22f to Color.Transparent),
                ),
            )
            // The melt: clear over the artwork, the page colour by the bottom edge.
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(0.5f to Color.Transparent, 0.92f to palette.base.copy(alpha = 0.92f), 1f to palette.base),
                ),
            )
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 24.dp, bottom = 6.dp),
            ) {
                Text(
                    album.title,
                    style =
                        MaterialTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.5).sp,
                        ),
                    color = ink(),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.size(6.dp))
                ArtistLine(
                    credit = album.artist,
                    albumTitle = album.title,
                    onArtist = onArtist,
                    stackSize = 22.dp,
                    showSingle = true,
                    singlePhoto = artistImageUrl,
                ) { m ->
                    Text(
                        album.artist,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
                        color = ink(0.85f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = m,
                    )
                }
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = ink(0.55f),
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 14.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier
                    .pressScale(onClick = onPlay)
                    .clip(CircleShape)
                    .background(ink(0.95f))
                    .padding(start = 20.dp, end = 26.dp, top = 13.dp, bottom = 13.dp),
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
            if (onShuffle != null) {
                Box(
                    Modifier
                        .size(46.dp)
                        .pressScale(onClick = onShuffle)
                        .glass(CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(PhosphorIcons.Bold.Shuffle, "Shuffle", tint = ink(), modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

/** One song in the Full style: plain rows on the page with a thin inset line between them. */
@Composable
private fun FullTrackRow(
    number: Int,
    track: Track,
    showArtist: Boolean,
    last: Boolean,
    onArtist: (String) -> Unit,
    isPlaying: Boolean,
    accent: Color,
    onMore: (Track) -> Unit,
    onClick: (Track) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val active = hovered || pressed
    val tint by animateColorAsState(
        targetValue = ink(if (pressed) 0.10f else if (hovered) 0.06f else 0f),
        animationSpec = tween(140),
        label = "fullRowTint",
    )

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .bounceOnPress(interaction, 0.985f)
                .drawBehind { drawRect(tint) }
                .focusRing(RoundedCornerShape(0.dp))
                .hoverable(interaction)
                .clickable(interactionSource = interaction, indication = null) { onClick(track) }
                .padding(start = 24.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(30.dp), contentAlignment = Alignment.CenterStart) {
                if (active) {
                    Icon(PhosphorIcons.Fill.Play, null, tint = ink(), modifier = Modifier.size(14.dp))
                } else if (isPlaying) {
                    NowPlayingBars(color = accent)
                } else {
                    Text(number.toString(), style = MaterialTheme.typography.bodyMedium, color = ink(0.4f))
                }
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ExplicitMark(track.isExplicit, ink(0.55f))
                    Text(
                        track.title,
                        color = if (isPlaying) accent else ink(),
                        style =
                            MaterialTheme.typography.bodyLarge.copy(
                                fontWeight = if (isPlaying) FontWeight.SemiBold else FontWeight.Medium,
                            ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                if (showArtist || splitArtists(track.artist).size > 1) {
                    ArtistLine(track.artist, track.album, onArtist, stackSize = 16.dp, modifier = Modifier.padding(top = 2.dp)) { m ->
                        Text(
                            track.artist,
                            color = ink(0.55f),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = m,
                        )
                    }
                }
            }
            if (track.durationMs > 0) {
                Text(
                    formatSongLength(track.durationMs.toLong()),
                    style = MaterialTheme.typography.bodySmall,
                    color = ink(0.4f),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            AlbumMoreButton { onMore(track) }
        }
        if (!last) Box(Modifier.fillMaxWidth().padding(start = 54.dp, end = 24.dp).height(1.dp).background(ink(0.07f)))
    }
}

@Composable
private fun ArtistAvatar(
    url: String?,
    artist: String,
) {
    val modifier = Modifier.size(24.dp).clip(CircleShape)
    if (url != null) {
        SubcomposeAsyncImage(
            model = url,
            contentDescription = artist,
            modifier = modifier.thumbnailBorder(CircleShape),
            contentScale = ContentScale.Crop,
            loading = { ArtistAvatarFallback() },
            error = { ArtistAvatarFallback() },
        )
    } else {
        Box(modifier) { ArtistAvatarFallback() }
    }
}

@Composable
private fun PlayFab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isLight = LocalCoverPalette.current.isLight
    Box(
        modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(ink())
            .pressScale(onClick = onClick, scaleDown = 0.92f),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            PhosphorIcons.Fill.Play,
            "Play",
            tint = if (isLight) Color.White else Color.Black,
            modifier = Modifier.padding(start = 2.dp).size(22.dp),
        )
    }
}

@Composable
private fun AlbumTrackRow(
    number: Int,
    track: Track,
    showArtist: Boolean,
    onArtist: (String) -> Unit,
    isPlaying: Boolean,
    accent: Color,
    onMore: (Track) -> Unit,
    onClick: (Track) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val pulsed by interaction.collectIsPulsedAsState()
    val active = hovered || pressed

    val scale by animateFloatAsState(
        targetValue = if (pulsed) 0.95f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "rowScale",
    )
    val tint by animateColorAsState(
        targetValue =
            ink(
                if (pressed) {
                    0.10f
                } else if (hovered) {
                    0.06f
                } else {
                    0f
                },
            ),
        animationSpec = tween(140),
        label = "rowTint",
    )

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }.clip(RoundedCornerShape(12.dp))
            .drawBehind { drawRect(tint) }
            .focusRing(RoundedCornerShape(12.dp))
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null) { onClick(track) }
            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(32.dp), contentAlignment = Alignment.CenterStart) {
            if (active) {
                Icon(PhosphorIcons.Fill.Play, null, tint = ink(), modifier = Modifier.size(14.dp))
            } else if (isPlaying) {
                NowPlayingBars(color = accent)
            } else {
                Text(
                    number.toString(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ink(0.4f),
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ExplicitMark(track.isExplicit, ink(0.55f))
                Text(
                    track.title,
                    color = if (isPlaying) accent else ink(),
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = if (isPlaying) FontWeight.SemiBold else FontWeight.Medium),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            if (showArtist || splitArtists(track.artist).size > 1) {
                ArtistLine(track.artist, track.album, onArtist, stackSize = 16.dp, modifier = Modifier.padding(vertical = 2.dp)) { m ->
                    Text(
                        track.artist,
                        color = ink(0.55f),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = m,
                    )
                }
            }
        }
        AlbumMoreButton { onMore(track) }
    }
}

@Composable
private fun AlbumMoreButton(onClick: () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .focusRing(CircleShape)
            .bounceClickable(role = Role.Button, onClickLabel = "More options", onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(PhosphorIcons.Bold.DotsThreeVertical, "More options", tint = ink(0.5f), modifier = Modifier.size(22.dp)) }
}

@Composable
private fun BackButton(onBack: () -> Unit) {
    AcrylicCircleButton(
        onClick = onBack,
        modifier = Modifier.statusBarsPadding().padding(start = 20.dp, top = 12.dp),
        size = 44.dp,
    ) {
        Icon(PhosphorIcons.Bold.ArrowLeft, "Back", tint = ink(), modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun ArtistAvatarFallback() {
    ArtPlaceholder(iconFraction = 0.6f)
}