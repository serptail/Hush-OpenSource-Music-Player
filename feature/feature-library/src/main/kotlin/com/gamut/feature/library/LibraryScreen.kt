package com.gamut.feature.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Bold
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.bold.CaretRight
import com.adamglin.phosphoricons.bold.MagnifyingGlass
import com.adamglin.phosphoricons.bold.MusicNote
import com.adamglin.phosphoricons.bold.MusicNoteSimple
import com.adamglin.phosphoricons.bold.Plus
import com.adamglin.phosphoricons.bold.PlusCircle
import com.adamglin.phosphoricons.bold.Rows
import com.adamglin.phosphoricons.bold.SquaresFour
import com.adamglin.phosphoricons.fill.CheckCircle
import com.adamglin.phosphoricons.fill.DeviceMobileCamera
import com.adamglin.phosphoricons.fill.Heart
import com.gamut.core.designsystem.AcrylicMaterials
import com.gamut.core.designsystem.AcrylicTone
import com.gamut.core.designsystem.BottomBarItem
import com.gamut.core.designsystem.CoverTheme
import com.gamut.core.designsystem.GamutBottomBar
import com.gamut.core.designsystem.LocalCoverPalette
import com.gamut.core.designsystem.LocalNotifier
import com.gamut.core.designsystem.TransparentStatusBar
import com.gamut.core.designsystem.acrylicFill
import com.gamut.core.designsystem.adaptiveGutter
import com.gamut.core.designsystem.bounceClickable
import com.gamut.core.designsystem.bounceOnTouch
import com.gamut.core.designsystem.fadeEdges
import com.gamut.core.designsystem.ink
import com.gamut.core.designsystem.isWideLayout
import com.gamut.core.designsystem.pressScale
import com.gamut.core.designsystem.thumbnailBorder
import com.gamut.core.model.Track

private val TextPrimary: Color
    @Composable @ReadOnlyComposable
    get() = ink()
private val TextSecondary: Color
    @Composable @ReadOnlyComposable
    get() = ink(0.6f)

private val PanelShape = RoundedCornerShape(12.dp)
private val ButtonShape = RoundedCornerShape(8.dp)

data class LibraryArtist(
    val id: String,
    val name: String,
    val imageUrl: String? = null,
)

enum class ImportSourceUi { Spotify, YouTubeMusic, AppleMusic }

sealed interface ImportProgressUi {
    val source: ImportSourceUi?

    data class Running(
        val title: String,
        val done: Int,
        val total: Int,
        val current: String?,
        override val source: ImportSourceUi? = null,
    ) : ImportProgressUi

    data class Finished(
        val title: String,
        val detail: String?,
        val isError: Boolean,
        override val source: ImportSourceUi? = null,
    ) : ImportProgressUi
}

private enum class LibraryFilter(
    val label: String,
) {
    Playlists("Playlists"),
    Artists("Artists"),
    Offline("Offline"),
}

@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    selectedTrack: Track?,
    onOpenPlaylist: (String) -> Unit,
    onCreatePlaylist: () -> Unit,
    barHeight: androidx.compose.ui.unit.Dp,
    onImportSpotify: () -> Unit = {},
    onImportYouTubeMusic: () -> Unit = {},
    onImportAppleMusic: () -> Unit = {},
    artists: List<LibraryArtist> = emptyList(),
    artistsLoading: Boolean = false,
    onOpenArtist: (String) -> Unit = {},
    suggestedArtists: List<LibraryArtist> = emptyList(),
    onOpenSearch: () -> Unit = {},
    onOpenLocal: () -> Unit = {},
    onPlayTrack: (Track, List<Track>) -> Unit = { _, _ -> },
    importProgress: ImportProgressUi? = null,
    onCancelImport: () -> Unit = {},
    onDismissImport: () -> Unit = {},
) {
    val downloaded by viewModel.downloadedTracks.collectAsStateWithLifecycle()
    val userPlaylists by viewModel.playlists.collectAsStateWithLifecycle()
    val playlistInfo by viewModel.playlistInfo.collectAsStateWithLifecycle()
    val likedOffline by viewModel.likedOffline.collectAsStateWithLifecycle()
    val notifier = LocalNotifier.current

    val palette = LocalCoverPalette.current

    var filter by rememberSaveable { mutableStateOf<LibraryFilter?>(null) }
    var isGrid by rememberSaveable { mutableStateOf(false) }
    var addMenuOpen by remember { mutableStateOf(false) }
    val addedTo = remember(selectedTrack?.isrc) { mutableStateListOf<Long>() }

    CoverTheme(palette) {
        val showPlaylists = filter == null || filter == LibraryFilter.Playlists
        val showArtists = filter == null || filter == LibraryFilter.Artists
        val showOffline = filter == LibraryFilter.Offline
        val fullSpan: LazyGridItemSpanScope.() -> GridItemSpan = { GridItemSpan(maxLineSpan) }

        val wide = isWideLayout()
        val gutter = if (wide) 16.dp else adaptiveGutter()
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().statusBarsPadding().padding(bottom = barHeight)) {
                Column(Modifier.weight(1f).fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 24.dp + gutter, end = 24.dp + gutter, top = 22.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Library",
                            style = MaterialTheme.typography.displayLarge,
                            color = TextPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        Box {
                            Box(Modifier.size(44.dp).pressScale(onClick = { addMenuOpen = true }), contentAlignment = Alignment.Center) {
                                Icon(
                                    PhosphorIcons.Bold.Plus,
                                    "Create or import a playlist",
                                    tint = ink(0.85f),
                                    modifier = Modifier.size(24.dp),
                                )
                            }
                            AddMenu(
                                expanded = addMenuOpen,
                                onDismiss = { addMenuOpen = false },
                                onCreatePlaylist = onCreatePlaylist,
                                onImportSpotify = onImportSpotify,
                                onImportYouTubeMusic = onImportYouTubeMusic,
                                onImportAppleMusic = onImportAppleMusic,
                            )
                        }
                    }

                    Row(
                        Modifier.fillMaxWidth().padding(start = 24.dp + gutter, end = 24.dp + gutter, top = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        LibraryFilter.values().forEach { f ->
                            FilterPill(f.label, selected = filter == f) { filter = if (filter == f) null else f }
                        }
                        Spacer(Modifier.weight(1f))
                        Box(Modifier.size(36.dp).pressScale(onClick = { isGrid = !isGrid }), contentAlignment = Alignment.Center) {
                            Icon(
                                if (isGrid) PhosphorIcons.Bold.Rows else PhosphorIcons.Bold.SquaresFour,
                                if (isGrid) "Switch to list" else "Switch to grid",
                                tint = ink(0.85f),
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }

                    importProgress?.let {
                        ImportProgressCard(
                            it,
                            onCancel = onCancelImport,
                            onDismiss = onDismissImport,
                            modifier = Modifier.padding(horizontal = 24.dp + gutter).padding(top = 12.dp),
                        )
                    }

                    if (selectedTrack !=
                        null
                    ) {
                        ReadyToAddCard(selectedTrack, Modifier.padding(horizontal = 24.dp + gutter).padding(top = 12.dp))
                    }

                    LazyVerticalGrid(
                        columns =
                            when {
                                wide -> GridCells.Adaptive(if (isGrid) 170.dp else 380.dp)
                                else -> GridCells.Fixed(if (isGrid) 3 else 1)
                            },
                        modifier = Modifier.weight(1f).fadeEdges(),
                        contentPadding = PaddingValues(start = 24.dp + gutter, end = 24.dp + gutter, top = 6.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(if (isGrid) 18.dp else 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        if (showOffline) {
                            if (downloaded.isEmpty()) {
                                item(key = "offline-empty", span = fullSpan) {
                                    EmptyStateCard(
                                        title = "No downloaded songs yet",
                                        subtitle = "Songs you download show up here and play without internet.",
                                        modifier = Modifier.padding(top = 8.dp),
                                        buttonLabel = "Find songs",
                                        onButton = onOpenSearch,
                                    )
                                }
                            }
                            items(downloaded, key = { "off-${it.isrc}" }, contentType = { "offline" }) { song ->
                                if (isGrid) {
                                    GridCell(song.title, { onPlayTrack(song, downloaded) }, offline = true) {
                                        TileCover(song.albumArtUrl, PhosphorIcons.Bold.MusicNoteSimple)
                                    }
                                } else {
                                    LibraryRow(
                                        title = song.title,
                                        meta = {
                                            Text(
                                                song.artist,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = TextSecondary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        },
                                        onClick = { onPlayTrack(song, downloaded) },
                                        cover = { RowCover(song.albumArtUrl, PhosphorIcons.Bold.MusicNoteSimple) },
                                        offline = true,
                                        trailing = {},
                                    )
                                }
                            }
                        }

                        if (showPlaylists) {
                            item(key = "liked") {
                                if (isGrid) {
                                    GridCell(
                                        "Liked songs",
                                        { onOpenPlaylist("liked") },
                                        offline = likedOffline,
                                    ) { TileCover(null, PhosphorIcons.Fill.Heart) }
                                } else {
                                    LibraryRow(
                                        title = "Liked songs",
                                        onClick = { onOpenPlaylist("liked") },
                                        cover = { RowCover(null, PhosphorIcons.Fill.Heart) },
                                        offline = likedOffline,
                                    )
                                }
                            }

                            item(key = "on-device") {
                                if (isGrid) {
                                    GridCell("On this device", onOpenLocal) { TileCover(null, PhosphorIcons.Fill.DeviceMobileCamera) }
                                } else {
                                    LibraryRow(
                                        title = "On this device",
                                        meta = {
                                            Text(
                                                "Music stored on your phone",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = TextSecondary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        },
                                        onClick = onOpenLocal,
                                        cover = { RowCover(null, PhosphorIcons.Fill.DeviceMobileCamera) },
                                    )
                                }
                            }

                            if (filter == null) item(key = "playlists-header", span = fullSpan) { SectionTitle("Your playlists") }

                            if (userPlaylists.isEmpty()) {
                                item(key = "playlists-empty", span = fullSpan) {
                                    EmptyStateCard(
                                        title = "Create your first playlist",
                                        subtitle = "Make a playlist and start filling it with songs.",
                                        modifier = Modifier.padding(top = 8.dp),
                                        buttonLabel = "Create playlist",
                                        onButton = onCreatePlaylist,
                                    )
                                }
                            }
                            items(userPlaylists, key = { "v-${it.id}" }, contentType = { "playlist" }) { playlist ->
                                val cover = playlist.coverUri ?: playlistInfo[playlist.id]?.coverUrl
                                val offline = playlistInfo[playlist.id]?.offline == true
                                val added = playlist.id in addedTo
                                val addOrOpen: () -> Unit = {
                                    if (selectedTrack != null) {
                                        if (!added) {
                                            addedTo.add(playlist.id)
                                            viewModel.addTrack(playlist.id, selectedTrack)
                                            notifier.success("Added to ${playlist.name}")
                                        }
                                    } else {
                                        onOpenPlaylist(playlist.id.toString())
                                    }
                                }
                                if (isGrid) {
                                    GridCell(
                                        playlist.name,
                                        addOrOpen,
                                        offline = offline,
                                    ) { TileCover(cover, PhosphorIcons.Bold.MusicNoteSimple) }
                                } else {
                                    LibraryRow(
                                        title = playlist.name,
                                        meta = if (playlist.tags.isEmpty()) null else ({ TagRow(playlist.tags) }),
                                        onClick = { onOpenPlaylist(playlist.id.toString()) },
                                        cover = { RowCover(cover, PhosphorIcons.Bold.MusicNoteSimple) },
                                        offline = offline,
                                        trailing = {
                                            if (selectedTrack != null) {
                                                Box(
                                                    Modifier
                                                        .size(
                                                            44.dp,
                                                        ).then(if (added) Modifier else Modifier.pressScale(onClick = { addOrOpen() })),
                                                    contentAlignment = Alignment.Center,
                                                ) {
                                                    Icon(
                                                        if (added) PhosphorIcons.Fill.CheckCircle else PhosphorIcons.Bold.PlusCircle,
                                                        if (added) "Added" else "Add to playlist",
                                                        tint = if (added) TextPrimary else ink(0.85f),
                                                        modifier = Modifier.size(28.dp),
                                                    )
                                                }
                                            } else {
                                                ChevronIcon()
                                            }
                                        },
                                    )
                                }
                            }
                        }

                        if (showArtists) {
                            if (filter == null) item(key = "artists-header", span = fullSpan) { SectionTitle("Artists you follow") }
                            if (artists.isEmpty() && !artistsLoading) {
                                item(key = "artists-empty", span = fullSpan) {
                                    EmptyStateCard(
                                        title = "You haven\u2019t followed any artists yet",
                                        subtitle = "Follow your favorites to keep them close.",
                                        modifier = Modifier.padding(top = 8.dp),
                                        buttonLabel = "Find artists",
                                        onButton = onOpenSearch,
                                        covers =
                                            suggestedArtists
                                                .mapNotNull {
                                                    it.imageUrl?.takeIf { u ->
                                                        u.isNotBlank()
                                                    }
                                                }.take(5)
                                                .ifEmpty { EmptyStateArtistCovers.urls },
                                        circleCovers = true,
                                    )
                                }
                            }
                            items(artists, key = { "a-${it.id}" }, contentType = { "artist" }) { artist ->
                                if (isGrid) {
                                    GridCell(artist.name, { onOpenArtist(artist.id) }, circle = true) {
                                        TileCover(artist.imageUrl, PhosphorIcons.Bold.MusicNote, circle = true)
                                    }
                                } else {
                                    LibraryRow(
                                        title = artist.name,
                                        onClick = { onOpenArtist(artist.id) },
                                        cover = { RowCover(artist.imageUrl, PhosphorIcons.Bold.MusicNote, circle = true) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ImportProgressCard(
    state: ImportProgressUi,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(PanelShape)
            .background(ink(0.08f))
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        when (state) {
            is ImportProgressUi.Running -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ImportSourceLogo(state.source)
                    Text(
                        state.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (state.total > 0) {
                        Text("${state.done} / ${state.total}", style = MaterialTheme.typography.labelLarge, color = TextSecondary)
                    }
                }
                state.current?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Spacer(Modifier.height(10.dp))
                if (state.total > 0) {
                    val fraction = (state.done.toFloat() / state.total).coerceIn(0f, 1f)
                    val bar by animateFloatAsState(fraction, tween(300), label = "libraryImportBar")
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(CircleShape)
                            .background(ink(0.12f)),
                    ) {
                        Box(Modifier.fillMaxWidth(bar.coerceAtLeast(0.02f)).fillMaxHeight().background(ink(0.85f), CircleShape))
                    }
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                        color = ink(0.85f),
                        trackColor = ink(0.12f),
                    )
                }
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onCancel, modifier = Modifier.bounceOnTouch()) { Text("Cancel", color = TextPrimary) }
                }
            }

            is ImportProgressUi.Finished -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ImportSourceLogo(state.source)
                    Column(Modifier.weight(1f)) {
                        Text(
                            state.title,
                            style = MaterialTheme.typography.titleSmall,
                            color = if (state.isError) MaterialTheme.colorScheme.error else TextPrimary,
                        )
                        state.detail?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                    TextButton(onClick = onDismiss, modifier = Modifier.bounceOnTouch()) { Text("Dismiss", color = TextPrimary) }
                }
            }
        }
    }
}

@Composable
private fun AddMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onCreatePlaylist: () -> Unit,
    onImportSpotify: () -> Unit,
    onImportYouTubeMusic: () -> Unit,
    onImportAppleMusic: () -> Unit,
) {
    val visibleState = remember { MutableTransitionState(false) }
    visibleState.targetState = expanded
    if (!visibleState.currentState && !visibleState.targetState) return

    val density = LocalDensity.current
    val shadowRoom = 14.dp
    val shape = RoundedCornerShape(18.dp)
    val container = ink(0.10f).compositeOver(LocalCoverPalette.current.base)
    val offset = with(density) { IntOffset(shadowRoom.roundToPx(), (44.dp + 2.dp - shadowRoom).roundToPx()) }

    Popup(
        alignment = Alignment.TopEnd,
        offset = offset,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        AnimatedVisibility(
            visibleState = visibleState,
            enter =
                fadeIn(tween(120)) +
                    scaleIn(
                        initialScale = 0.6f,
                        transformOrigin = TransformOrigin(1f, 0f),
                        animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow),
                    ),
            exit =
                fadeOut(tween(90)) +
                    scaleOut(
                        targetScale = 0.85f,
                        transformOrigin = TransformOrigin(1f, 0f),
                        animationSpec = tween(120),
                    ),
        ) {
            Column(
                Modifier
                    .padding(shadowRoom)
                    .shadow(10.dp, shape, clip = false)
                    .clip(shape)
                    .background(container)
                    .width(IntrinsicSize.Max)
                    .widthIn(min = 200.dp)
                    .padding(vertical = 8.dp),
            ) {
                AddMenuItem("Create playlist", onClick = {
                    onDismiss()
                    onCreatePlaylist()
                }) {
                    Icon(PhosphorIcons.Bold.Plus, null, tint = ink(0.85f), modifier = Modifier.size(22.dp))
                }
                AddMenuItem("Import from Spotify", onClick = {
                    onDismiss()
                    onImportSpotify()
                }) {
                    Icon(painterResource(R.drawable.ic_spotify_mono), null, tint = ink(0.85f), modifier = Modifier.size(22.dp))
                }
                AddMenuItem("Import from YouTube Music", onClick = {
                    onDismiss()
                    onImportYouTubeMusic()
                }) {
                    Icon(painterResource(R.drawable.ic_ytmusic_mono), null, tint = ink(0.85f), modifier = Modifier.size(22.dp))
                }
                AddMenuItem("Import from Apple Music", onClick = {
                    onDismiss()
                    onImportAppleMusic()
                }) {
                    Icon(painterResource(R.drawable.ic_applemusic_mono), null, tint = ink(0.85f), modifier = Modifier.size(22.dp))
                }
            }
        }
    }
}

@Composable
private fun AddMenuItem(
    label: String,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .bounceClickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon()
        Spacer(Modifier.width(14.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = TextPrimary,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun ImportSourceLogo(source: ImportSourceUi?) {
    val (icon, name) =
        when (source) {
            ImportSourceUi.Spotify -> R.drawable.ic_spotify_mono to "Spotify"
            ImportSourceUi.YouTubeMusic -> R.drawable.ic_ytmusic_mono to "YouTube Music"
            ImportSourceUi.AppleMusic -> R.drawable.ic_applemusic_mono to "Apple Music"
            null -> return
        }
    Icon(painterResource(icon), name, tint = ink(0.85f), modifier = Modifier.padding(end = 12.dp).size(26.dp))
}

@Composable
private fun FilterPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val light = LocalCoverPalette.current.isLight
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = if (selected) (if (light) Color.White else Color.Black) else TextPrimary,
        modifier =
            Modifier
                .pressScale(onClick = onClick)
                .background(if (selected) ink(0.95f) else ink(0.12f), CircleShape)
                .padding(horizontal = 18.dp, vertical = 8.dp),
    )
}

@Composable
private fun GridCell(
    title: String,
    onClick: () -> Unit,
    circle: Boolean = false,
    offline: Boolean = false,
    cover: @Composable () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().pressScale(onClick = onClick, scaleDown = 0.97f),
        horizontalAlignment = if (circle) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        cover()
        Spacer(Modifier.height(8.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = if (circle) TextAlign.Center else TextAlign.Start,
        )
        if (offline) AvailableOfflineLine(Modifier.padding(top = 3.dp))
    }
}

@Composable
private fun TileCover(
    coverUrl: String?,
    icon: ImageVector,
    circle: Boolean = false,
) {
    val shape = if (circle) CircleShape else RoundedCornerShape(10.dp)
    Box(Modifier.fillMaxWidth()) {
        if (!coverUrl.isNullOrBlank()) {
            CoverImage(
                remember(coverUrl) { resolveCoverUrl(coverUrl, 400) },
                Modifier.fillMaxWidth().aspectRatio(1f).then(if (circle) Modifier.clip(shape).thumbnailBorder(shape) else Modifier.softCover()),
            )
        } else {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(
                        1f,
                    ).then(if (circle) Modifier.clip(shape) else Modifier.softCover())
                    .background(flatCoverFill()),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, tint = ink(), modifier = Modifier.size(32.dp))
            }
        }
    }
}

@Composable
private fun ReadyToAddCard(
    track: Track,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(16.dp)
    val tone = if (LocalCoverPalette.current.isLight) AcrylicTone.Light else AcrylicTone.Dark
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .acrylicFill(AcrylicMaterials.Regular, tone)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverImage(
            remember(track.albumArtUrl) { resolveCoverUrl(track.albumArtUrl, 160) },
            Modifier.size(48.dp).softCover(RoundedCornerShape(10.dp)),
        )
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(
                "Ready to add \u00B7 pick a playlist",
                style = MaterialTheme.typography.labelMedium,
                color = ink(0.65f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                track.title,
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
            Text(
                track.artist,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        color = TextPrimary,
        modifier = Modifier.padding(top = 22.dp, bottom = 10.dp),
    )
}

@Composable
private fun ChevronIcon() {
    Icon(PhosphorIcons.Bold.CaretRight, null, tint = ink(0.45f), modifier = Modifier.size(18.dp))
}

@Composable
private fun RowCover(
    coverUrl: String?,
    icon: ImageVector,
    circle: Boolean = false,
) {
    if (!coverUrl.isNullOrBlank()) {
        CoverImage(
            remember(coverUrl) { resolveCoverUrl(coverUrl, 200) },
            Modifier.size(58.dp).then(if (circle) Modifier.clip(CircleShape).thumbnailBorder(CircleShape) else Modifier.softCover()),
        )
    } else {
        Box(
            Modifier.size(58.dp).then(if (circle) Modifier.clip(CircleShape) else Modifier.softCover()).background(flatCoverFill()),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = ink(), modifier = Modifier.size(28.dp))
        }
    }
}

@Composable
private fun TagRow(tags: List<String>) {
    val clean = remember(tags) { tags.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() } }
    if (clean.isEmpty()) return
    val shown = clean.take(MAX_TAGS_SHOWN)
    val extra = clean.size - shown.size
    Row(
        Modifier.fillMaxWidth().clipToBounds(),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        shown.forEach { tag -> TagPill(tag, Modifier.weight(1f, fill = false)) }
        if (extra > 0) TagPill("+$extra")
    }
}

@Composable
private fun TagPill(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = ink(0.75f),
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        modifier =
            modifier
                .background(ink(0.10f), CircleShape)
                .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

private const val MAX_TAGS_SHOWN = 3

@Composable
private fun LibraryRow(
    title: String,
    onClick: () -> Unit,
    cover: @Composable () -> Unit,
    meta: (@Composable () -> Unit)? = null,
    offline: Boolean = false,
    trailing: @Composable () -> Unit = { ChevronIcon() },
) {
    Row(
        Modifier.fillMaxWidth().pressScale(onClick = onClick, scaleDown = 0.98f).padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        cover()
        Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (meta != null) {
                Spacer(Modifier.height(5.dp))
                meta()
            }
            if (offline) AvailableOfflineLine(Modifier.padding(top = 6.dp))
        }
        trailing()
    }
}
