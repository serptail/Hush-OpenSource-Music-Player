package com.gamut.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.Light
import com.adamglin.phosphoricons.fill.CheckCircle
import com.adamglin.phosphoricons.fill.DeviceMobileCamera
import com.adamglin.phosphoricons.fill.Heart
import com.adamglin.phosphoricons.light.CaretRight
import com.adamglin.phosphoricons.light.MagnifyingGlass
import com.adamglin.phosphoricons.light.MusicNote
import com.adamglin.phosphoricons.light.PlusCircle
import com.adamglin.phosphoricons.light.Plus
import com.adamglin.phosphoricons.light.Rows
import com.adamglin.phosphoricons.light.SquaresFour
import com.adamglin.phosphoricons.light.VinylRecord
import com.gamut.core.designsystem.adaptiveGutter
import com.gamut.core.designsystem.AcrylicMaterials
import com.gamut.core.designsystem.AcrylicTone
import com.gamut.core.designsystem.BottomBarItem
import com.gamut.core.designsystem.CoverTheme
import com.gamut.core.designsystem.GamutBottomBar
import com.gamut.core.designsystem.LocalCoverPalette
import com.gamut.core.designsystem.LocalNotifier
import com.gamut.core.designsystem.TransparentStatusBar
import com.gamut.core.designsystem.acrylicFill
import com.gamut.core.designsystem.fadeEdges
import com.gamut.core.designsystem.ink
import com.gamut.core.designsystem.pressScale
import com.gamut.core.model.Track

private val TextPrimary: Color
    @Composable @ReadOnlyComposable get() = ink()
private val TextSecondary: Color
    @Composable @ReadOnlyComposable get() = ink(0.6f)

private val PanelShape = RoundedCornerShape(12.dp)
private val ButtonShape = RoundedCornerShape(8.dp)

/** An artist the user follows. Feed these in from wherever follows are stored. */
data class LibraryArtist(val id: String, val name: String, val imageUrl: String? = null)

private enum class LibraryFilter(val label: String) { Playlists("Playlists"), Artists("Artists") }

/**
 * Library: Liked songs, your playlists and the artists you follow.
 * Two filter pills (Playlists / Artists) sit under the title; tapping the active one shows everything again.
 * The top-right icon switches between a list and a 3-column grid.
 * Tapping a playlist calls [onOpenPlaylist] (navigation lives in the NavHost); Liked songs passes the id "liked".
 */
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    /** The track picked in search that is waiting to be added to a playlist, or null. */
    selectedTrack: Track?,
    onOpenPlaylist: (String) -> Unit,
    /** The "+" button: opens the New playlist screen (navigation lives in the NavHost). */
    onCreatePlaylist: () -> Unit,
    /** Height of the shared bottom bar + mini player strip (owned by HomeTabs in MainActivity). */
    barHeight: androidx.compose.ui.unit.Dp,
    /** Artists the user follows (from FollowViewModel). When empty, the Artists section shows its empty card. */
    artists: List<LibraryArtist> = emptyList(),
    /** True while the follows are still being read from the database, so the empty card doesn't flash for a moment. */
    artistsLoading: Boolean = false,
    onOpenArtist: (String) -> Unit = {},
    /** Artists whose round covers fill the empty Artists card (popular, recently played...). Placeholders show when empty. */
    suggestedArtists: List<LibraryArtist> = emptyList(),
    /** Empty-state buttons that need the search tab (e.g. "Find artists"). */
    onOpenSearch: () -> Unit = {},
    /** The "On this device" row: opens the screen with the songs stored on the phone. */
    onOpenLocal: () -> Unit = {},
) {
    val userPlaylists by viewModel.playlists.collectAsStateWithLifecycle()
    val playlistInfo by viewModel.playlistInfo.collectAsStateWithLifecycle()
    val likedOffline by viewModel.likedOffline.collectAsStateWithLifecycle()
    val notifier = LocalNotifier.current

    val palette = LocalCoverPalette.current
    // Status bar icons are set by HomeTabs (only while this layer is visible).

    var filter by rememberSaveable { mutableStateOf<LibraryFilter?>(null) }
    var isGrid by rememberSaveable { mutableStateOf(false) }
    // Playlists the waiting track was added to in this visit: their button turns into a check.
    val addedTo = remember(selectedTrack?.isrc) { mutableStateListOf<Long>() }

    CoverTheme(palette) {
        val showPlaylists = filter != LibraryFilter.Artists
        val showArtists = filter != LibraryFilter.Playlists
        val fullSpan: LazyGridItemSpanScope.() -> GridItemSpan = { GridItemSpan(maxLineSpan) }

        val gutter = adaptiveGutter()   // 0.dp on phones; centers the content column on wide windows
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().statusBarsPadding().padding(bottom = barHeight)) {
                // No horizontal padding here: the grid spans the full width so the tiles' soft shadows are not clipped at its edge.
                Column(Modifier.weight(1f).fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(start = 24.dp + gutter, end = 24.dp + gutter, top = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Library",
                            style = MaterialTheme.typography.displayLarge,
                            color = TextPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        Box(Modifier.size(44.dp).pressScale(onClick = onCreatePlaylist), contentAlignment = Alignment.Center) {
                            Icon(PhosphorIcons.Light.Plus, "Create playlist", tint = ink(0.85f), modifier = Modifier.size(24.dp))
                        }
                    }

                    // Filter bubbles, above the canvas.
                    Row(
                        Modifier.fillMaxWidth().padding(start = 24.dp + gutter, end = 24.dp + gutter, top = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        LibraryFilter.values().forEach { f ->
                            FilterPill(f.label, selected = filter == f) { filter = if (filter == f) null else f }
                        }
                        Spacer(Modifier.weight(1f))
                        // Grid / list toggle, at the end of the filter row.
                        Box(Modifier.size(36.dp).pressScale(onClick = { isGrid = !isGrid }), contentAlignment = Alignment.Center) {
                            Icon(
                                if (isGrid) PhosphorIcons.Light.Rows else PhosphorIcons.Light.SquaresFour,
                                if (isGrid) "Switch to list" else "Switch to grid",
                                tint = ink(0.85f),
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }

                    // A track is waiting to be added: say so, right under the filter pills.
                    if (selectedTrack != null) ReadyToAddCard(selectedTrack, Modifier.padding(horizontal = 24.dp + gutter).padding(top = 12.dp))

                    LazyVerticalGrid(
                        columns = GridCells.Fixed(if (isGrid) 3 else 1),
                        modifier = Modifier.weight(1f).fadeEdges(),
                        contentPadding = PaddingValues(start = 24.dp + gutter, end = 24.dp + gutter, top = 6.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(if (isGrid) 18.dp else 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        if (showPlaylists) {
                            item(key = "liked") {
                                if (isGrid) {
                                    GridCell("Liked songs", { onOpenPlaylist("liked") }, offline = likedOffline) { TileCover(null, PhosphorIcons.Fill.Heart) }
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
                                        meta = { Text("Music stored on your phone", style = MaterialTheme.typography.bodyMedium, color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
                                // A cover the user picked always wins over the art of the last song.
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
                                    // No room for an "Add" button in a tile: while a track is waiting, a tap adds it.
                                    GridCell(playlist.name, addOrOpen, offline = offline) { TileCover(cover, PhosphorIcons.Light.VinylRecord) }
                                } else {
                                    LibraryRow(
                                        title = playlist.name,
                                        // Tags replace the song count: the count only shows once you are inside the playlist.
                                        meta = if (playlist.tags.isEmpty()) null else ({ TagRow(playlist.tags) }),
                                        onClick = { onOpenPlaylist(playlist.id.toString()) },
                                        cover = { RowCover(cover, PhosphorIcons.Light.VinylRecord) },
                                        offline = offline,
                                        trailing = {
                                            if (selectedTrack != null) {
                                                // plus-circle -> check-circle once the track is in this playlist
                                                Box(
                                                    Modifier.size(44.dp).then(if (added) Modifier else Modifier.pressScale(onClick = { addOrOpen() })),
                                                    contentAlignment = Alignment.Center,
                                                ) {
                                                    Icon(
                                                        if (added) PhosphorIcons.Fill.CheckCircle else PhosphorIcons.Light.PlusCircle,
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
                                        covers = suggestedArtists.mapNotNull { it.imageUrl?.takeIf { u -> u.isNotBlank() } }.take(5).ifEmpty { EmptyStateArtistCovers.urls },
                                        circleCovers = true,
                                    )
                                }
                            }
                            items(artists, key = { "a-${it.id}" }, contentType = { "artist" }) { artist ->
                                if (isGrid) {
                                    GridCell(artist.name, { onOpenArtist(artist.id) }, circle = true) {
                                        TileCover(artist.imageUrl, PhosphorIcons.Light.MusicNote, circle = true)
                                    }
                                } else {
                                    LibraryRow(
                                        title = artist.name,
                                        onClick = { onOpenArtist(artist.id) },
                                        cover = { RowCover(artist.imageUrl, PhosphorIcons.Light.MusicNote, circle = true) },
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
private fun FilterPill(label: String, selected: Boolean, onClick: () -> Unit) {
    val light = LocalCoverPalette.current.isLight
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = if (selected) (if (light) Color.White else Color.Black) else TextPrimary,
        modifier = Modifier
            .pressScale(onClick = onClick)
            .background(if (selected) ink(0.95f) else ink(0.12f), CircleShape)
            .padding(horizontal = 18.dp, vertical = 8.dp),
    )
}

/** Grid cell: square cover (round for artists) with the title underneath. */
@Composable
private fun GridCell(title: String, onClick: () -> Unit, circle: Boolean = false, offline: Boolean = false, cover: @Composable () -> Unit) {
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
private fun TileCover(coverUrl: String?, icon: ImageVector, circle: Boolean = false) {
    val shape = if (circle) CircleShape else RoundedCornerShape(10.dp)
    Box(Modifier.fillMaxWidth()) {
        if (!coverUrl.isNullOrBlank()) {
            CoverImage(
                remember(coverUrl) { resolveCoverUrl(coverUrl, 400) },
                Modifier.fillMaxWidth().aspectRatio(1f).then(if (circle) Modifier.clip(shape) else Modifier.softCover()),
            )
        } else {
            // Same blank, opaque fill (and shadow) as a cover without art on the Favorites screen.
            Box(
                Modifier.fillMaxWidth().aspectRatio(1f).then(if (circle) Modifier.clip(shape) else Modifier.softCover()).background(flatCoverFill()),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, tint = ink(), modifier = Modifier.size(32.dp))
            }
        }
    }
}

/** The "Ready to add" banner at the top of the Library while a track from search waits for a playlist. */
@Composable
private fun ReadyToAddCard(track: Track, modifier: Modifier = Modifier) {
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
            Text("Ready to add \u00B7 pick a playlist", style = MaterialTheme.typography.labelMedium, color = ink(0.65f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track.title, style = MaterialTheme.typography.titleMedium, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            Text(track.artist, style = MaterialTheme.typography.bodyMedium, color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    Icon(PhosphorIcons.Light.CaretRight, null, tint = ink(0.45f), modifier = Modifier.size(18.dp))
}

/**
 * The cover slot of a Library row: the cover of the last song added when there is one, otherwise the icon on a flat,
 * OPAQUE square (flatCoverFill). It must be opaque: softCover's drop shadow would show through a translucent fill as a
 * dark inner box.
 */
@Composable
private fun RowCover(coverUrl: String?, icon: ImageVector, circle: Boolean = false) {
    if (!coverUrl.isNullOrBlank()) {
        CoverImage(
            remember(coverUrl) { resolveCoverUrl(coverUrl, 200) },
            Modifier.size(58.dp).then(if (circle) Modifier.clip(CircleShape) else Modifier.softCover()),
        )
    } else {
        // A flat square (round for artists) instead of a bare icon, like a cover without art on Favorites.
        Box(
            Modifier.size(58.dp).then(if (circle) Modifier.clip(CircleShape) else Modifier.softCover()).background(flatCoverFill()),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = ink(), modifier = Modifier.size(28.dp))
        }
    }
}

/**
 * A playlist's tags as small pills under its name: up to [MAX_TAGS_SHOWN], then a "+N". Blank and duplicate tags are
 * skipped. Long tags shrink with an ellipsis rather than pushing the row wider.
 */
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
private fun TagPill(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = ink(0.75f),
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .background(ink(0.10f), CircleShape)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

private const val MAX_TAGS_SHOWN = 3

/** Flat list row: cover, title, optional [meta] line (the tags), trailing chevron (or a custom trailing action). */
@Composable
private fun LibraryRow(
    title: String,
    onClick: () -> Unit,
    cover: @Composable () -> Unit,
    meta: (@Composable () -> Unit)? = null,
    /** Every song downloaded: "Available offline" goes under the name and tags. */
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