package com.gamut.feature.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Bold
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.bold.ArrowLeft
import com.adamglin.phosphoricons.bold.DotsThreeVertical
import com.adamglin.phosphoricons.bold.Shuffle
import com.adamglin.phosphoricons.fill.CheckCircle
import com.adamglin.phosphoricons.fill.Heart
import com.adamglin.phosphoricons.fill.Play
import com.gamut.core.designsystem.ArtistLine
import com.gamut.core.designsystem.ArtistPickerSheet
import com.gamut.core.designsystem.CoverTheme
import com.gamut.core.designsystem.ExplicitMark
import com.gamut.core.designsystem.FavoritesStyle
import com.gamut.core.designsystem.GamutMotion
import com.gamut.core.designsystem.GamutSheet
import com.gamut.core.designsystem.LocalCoverPalette
import com.gamut.core.designsystem.LocalFocusLive
import com.gamut.core.designsystem.LocalNavSettled
import com.gamut.core.designsystem.LocalNotifier
import com.gamut.core.designsystem.NowPlayingBars
import com.gamut.core.designsystem.ShareSheet
import com.gamut.core.designsystem.TablerIcons
import com.gamut.core.designsystem.TransparentStatusBar
import com.gamut.core.designsystem.collectIsPulsedAsState
import com.gamut.core.designsystem.glass
import com.gamut.core.designsystem.ink
import com.gamut.core.designsystem.pressScale
import com.gamut.core.designsystem.splitArtists
import com.gamut.core.model.Track
import com.gamut.feature.resolver.DownloadState
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

// Layout tokens. The block radius and the dark bezel are the same ones the Appearance screen uses
// for its setting blocks and its device preview, so the two pages read as one family.
private val PageInset = 20.dp
private val PageMaxWidth = 640.dp
private val BlockRadius = 22.dp
private val RowCover = 52.dp
private val RowCoverShape = RoundedCornerShape(12.dp)
private val PillShape = RoundedCornerShape(20.dp)
private val Bezel = Color(0xFF0E0E10)
private val SheetDestructive = Color(0xFFFF6B6B)

@Composable
fun FavoritesScreen(
    tracks: List<Track>,
    onBack: () -> Unit,
    onTrackSelected: (Track, List<Track>) -> Unit,
    onShuffle: (List<Track>) -> Unit = {},
    onRemove: (Track) -> Unit = {},
    onAddToPlaylist: ((Track) -> Unit)? = null,
    onReorder: (List<Track>) -> Unit = {},
    onTrackArtist: (Track) -> Unit = {},
    nowPlayingIsrc: String? = null,
    downloads: PlaylistDownloadsViewModel,
    style: FavoritesStyle = FavoritesStyle.Grouped,
    nowPlaying: @Composable () -> Unit = {},
) {
    val songs = tracks
    val downloadStates by downloads.states.collectAsStateWithLifecycle()
    val batch by downloads.batch.collectAsStateWithLifecycle()
    val remote = remember(songs) { songs.filter { !it.isLocal } }
    val localIsrcs = remember(songs) { songs.filter { it.isLocal }.map { it.isrc }.toSet() }
    val rowStates = remember(downloadStates, localIsrcs) { downloadStates - localIsrcs }
    val allStates = remember(rowStates, localIsrcs) { rowStates + localIsrcs.associateWith { DownloadState.Done } }
    val downloadUi = remember(songs, allStates) { playlistDownloadUi(songs.map { it.isrc }.distinct(), allStates) }
    val downloading = downloadUi.status == PlaylistDownloadStatus.Active

    var showManage by remember { mutableStateOf(false) }
    var menuTrack by remember { mutableStateOf<Track?>(null) }
    var shareTrack by remember { mutableStateOf<Track?>(null) }
    var artistPickTrack by remember { mutableStateOf<Track?>(null) }

    val palette = LocalCoverPalette.current
    val settled = LocalNavSettled.current
    val haptics = LocalHapticFeedback.current

    // The heart gives a single beat when the screen opens and whenever the count changes.
    val beat = remember { Animatable(1f) }
    LaunchedEffect(songs.size) {
        beat.animateTo(1.3f, tween(140))
        beat.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
    }
    TransparentStatusBar(darkIcons = palette.isLight)

    val subtitle =
        if (downloading && batch.waitingForWifi) {
            "Waiting for Wi\u2011Fi \u00B7 mobile data downloads are off"
        } else if (downloading) {
            "Downloading ${minOf(downloadUi.done + 1, downloadUi.total)} of ${downloadUi.total}"
        } else {
            val totalMs = songs.sumOf { it.durationMs }
            (if (songs.size == 1) "1 song" else "${songs.size} songs") +
                if (totalMs > 0) " \u00B7 ${formatTotal(totalMs)}" else ""
        }

    val onDownloadClick: () -> Unit = {
        when (downloadUi.status) {
            PlaylistDownloadStatus.Idle -> {
                downloads.download(remote.filter { downloadStates[it.isrc] != DownloadState.Done }, label = "Favorites")
            }

            PlaylistDownloadStatus.Active -> {
                downloads.cancel(remote.map { it.isrc })
            }

            PlaylistDownloadStatus.Done -> {
                showManage = true
            }
        }
    }
    val openMenu: (Track) -> Unit = {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        menuTrack = it
    }

    CoverTheme(palette) {
        val accent = MaterialTheme.colorScheme.primary
        val wheel = style == FavoritesStyle.Wheel
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            FavoritesTopBar(
                onBack = onBack,
                hasSongs = songs.isNotEmpty(),
                onManage = { showManage = true },
                download = {
                    if (remote.isNotEmpty()) {
                        DownloadButton(ui = downloadUi, size = 40.dp, glyphSize = 24.dp, onClick = onDownloadClick)
                        Spacer(Modifier.width(4.dp))
                    }
                },
                middle = {
                    if (wheel) {
                        // The wheel keeps the title up here; the list style puts it in the page.
                        Column(Modifier.weight(1f).padding(start = 8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "Favorites",
                                    style = MaterialTheme.typography.headlineMedium,
                                    color = ink(),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Icon(
                                    PhosphorIcons.Fill.Heart,
                                    null,
                                    tint = accent,
                                    modifier =
                                        Modifier.padding(start = 8.dp).size(18.dp).graphicsLayer {
                                            scaleX = beat.value
                                            scaleY = beat.value
                                        },
                                )
                            }
                            Text(
                                subtitle,
                                style = MaterialTheme.typography.bodyMedium,
                                color = ink(0.6f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (songs.isNotEmpty()) {
                            Row(
                                Modifier
                                    .pressScale(onClick = { onShuffle(songs) })
                                    .clip(PillShape)
                                    .background(ink(0.95f))
                                    .padding(horizontal = 16.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(PhosphorIcons.Bold.Shuffle, null, tint = palette.base, modifier = Modifier.size(16.dp))
                                Text(
                                    "Shuffle",
                                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                                    color = palette.base,
                                    modifier = Modifier.padding(start = 8.dp),
                                )
                            }
                        }
                        Spacer(Modifier.width(4.dp))
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                },
            )

            when {
                songs.isEmpty() -> {
                    EmptyFavorites(beat = beat.value, tint = accent, modifier = Modifier.weight(1f))
                }

                !settled -> {
                    Spacer(Modifier.weight(1f))
                }

                wheel -> {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        FavoritesWheel(
                            songs = songs,
                            downloadStates = rowStates,
                            nowPlayingIsrc = nowPlayingIsrc,
                            accent = accent,
                            onTrackClick = { onTrackSelected(it, songs) },
                            onTrackMenu = openMenu,
                            onTrackArtist = onTrackArtist,
                            onCancelDownload = { if (!it.isLocal) downloads.cancel(listOf(it.isrc)) },
                            modifier = Modifier.widthIn(max = PageMaxWidth).fillMaxSize(),
                        )
                    }
                }

                else -> {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                        FavoritesList(
                            songs = songs,
                            downloadStates = rowStates,
                            nowPlayingIsrc = nowPlayingIsrc,
                            accent = accent,
                            beat = beat.value,
                            subtitle = subtitle,
                            onPlay = { onTrackSelected(songs.first(), songs) },
                            onShuffle = { onShuffle(songs) },
                            onTrackClick = { onTrackSelected(it, songs) },
                            onTrackMenu = openMenu,
                            onTrackArtist = onTrackArtist,
                            onCancelDownload = { if (!it.isLocal) downloads.cancel(listOf(it.isrc)) },
                            modifier = Modifier.widthIn(max = PageMaxWidth).fillMaxSize(),
                        )
                    }
                }
            }

            nowPlaying()
        }

        if (showManage) {
            ManageTracksSheet(
                title = "Manage favorites",
                removeDescription = "Remove from favorites",
                tracks = songs,
                downloadStates = allStates,
                onDismiss = { showManage = false },
                onReorder = onReorder,
                onRemove = onRemove,
                onDownloadAll = {
                    downloads.download(
                        remote.filter { downloadStates[it.isrc] != DownloadState.Done },
                        label = "Favorites",
                    )
                },
                onCancelDownloads = { downloads.cancel(remote.map { it.isrc }) },
                onRemoveDownloads = { downloads.remove(remote.map { it.isrc }) },
            )
        }

        menuTrack?.let { track ->
            TrackActionsSheet(
                track = track,
                downloadState = rowStates[track.isrc],
                onDownload = { if (!track.isLocal) downloads.download(listOf(track)) },
                onRemoveDownload = { if (!track.isLocal) downloads.remove(listOf(track.isrc)) },
                onDismiss = { menuTrack = null },
                onPlay = { onTrackSelected(track, songs) },
                onShare = { shareTrack = track },
                onAddToPlaylist = onAddToPlaylist?.let { add -> { add(track) } },
                onGoToArtist = {
                    if (splitArtists(track.artist).size > 1) artistPickTrack = track else onTrackArtist(track)
                },
                onRemove = { onRemove(track) },
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
        shareTrack?.let { t ->
            ShareSheet(title = t.title, artist = t.artist, artUrl = t.albumArtUrl, onDismiss = { shareTrack = null })
        }
    }
}

@Composable
private fun FavoritesTopBar(
    onBack: () -> Unit,
    hasSongs: Boolean,
    onManage: () -> Unit,
    download: @Composable () -> Unit,
    middle: @Composable RowScope.() -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).pressScale(onClick = onBack), contentAlignment = Alignment.Center) {
            Icon(PhosphorIcons.Bold.ArrowLeft, "Back", tint = ink(0.85f), modifier = Modifier.size(22.dp))
        }
        middle()
        download()
        if (hasSongs) {
            Box(Modifier.size(40.dp).pressScale(onClick = onManage), contentAlignment = Alignment.Center) {
                Icon(PhosphorIcons.Bold.DotsThreeVertical, "Manage favorites", tint = ink(0.85f), modifier = Modifier.size(24.dp))
            }
        } else {
            Spacer(Modifier.width(12.dp))
        }
    }
}

@Composable
private fun EmptyFavorites(
    beat: Float,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(120.dp).border(1.dp, ink(0.06f), CircleShape), contentAlignment = Alignment.Center) {
            Box(Modifier.size(92.dp).background(ink(0.07f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(
                    PhosphorIcons.Fill.Heart,
                    null,
                    tint = tint,
                    modifier = Modifier.size(38.dp).graphicsLayer { scaleX = beat; scaleY = beat },
                )
            }
        }
        Spacer(Modifier.height(18.dp))
        Text("No favorites yet", style = MaterialTheme.typography.headlineMedium, color = ink(), textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(
            "Tap the + on any song in Discover and it will show up here.",
            style = MaterialTheme.typography.bodyMedium,
            color = ink(0.6f),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun FavoritesList(
    songs: List<Track>,
    downloadStates: Map<String, DownloadState>,
    nowPlayingIsrc: String?,
    accent: Color,
    beat: Float,
    subtitle: String,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onTrackClick: (Track) -> Unit,
    onTrackMenu: (Track) -> Unit,
    onTrackArtist: (Track) -> Unit,
    onCancelDownload: (Track) -> Unit,
    modifier: Modifier = Modifier,
) {
    val playingIndex = remember(songs, nowPlayingIsrc) { songs.indexOfFirst { nowPlayingIsrc != null && it.isrc == nowPlayingIsrc } }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = 0)

    // TV and remote: land focus on the first row once the page has settled.
    val firstRowFocus = remember { FocusRequester() }
    val live = LocalFocusLive.current
    val remoteInput = LocalInputModeManager.current.inputMode == InputMode.Keyboard
    val settled = LocalNavSettled.current
    LaunchedEffect(settled, live, remoteInput) {
        if (settled && live && remoteInput) runCatching { firstRowFocus.requestFocus() }
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(bottom = 28.dp),
    ) {
        item(key = "hero") {
            Hero(
                songs = songs,
                subtitle = subtitle,
                beat = beat,
                tint = accent,
                onPlay = onPlay,
                onShuffle = onShuffle,
            )
        }
        item(key = "header") { SectionHeader("Songs") }
        itemsIndexed(songs, key = { index, song -> "${song.isrc}:$index" }) { index, song ->
            FavoriteRow(
                song = song,
                first = index == 0,
                last = index == songs.lastIndex,
                playing = index == playingIndex,
                offline = downloadStates[song.isrc] == DownloadState.Done,
                downloadState = downloadStates[song.isrc],
                accent = accent,
                focusRequester = if (index == 0) firstRowFocus else null,
                onClick = { onTrackClick(song) },
                onLongClick = { onTrackMenu(song) },
                onCancelDownload = { onCancelDownload(song) },
                onArtist = { name -> onTrackArtist(song.copy(artist = name)) },
            )
        }
        item(key = "footer") {
            Text(
                "Press and hold a song for more options. Reorder or remove songs from Manage.",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Normal),
                color = ink(0.5f),
                modifier = Modifier.padding(horizontal = PageInset + 6.dp, vertical = 10.dp),
            )
        }
    }
}

/**
 * Title, a framed mosaic of the favorites' covers and the two primary actions. The frame is the
 * Appearance preview's device bezel: the covers sit behind a thin dark edge with a hairline outline.
 */
@Composable
private fun Hero(
    songs: List<Track>,
    subtitle: String,
    beat: Float,
    tint: Color,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
) {
    val palette = LocalCoverPalette.current
    Column(Modifier.fillMaxWidth().padding(horizontal = PageInset)) {
        Row(Modifier.padding(top = 4.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Favorites",
                style = MaterialTheme.typography.headlineLarge,
                color = ink(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Icon(
                PhosphorIcons.Fill.Heart,
                null,
                tint = tint,
                modifier = Modifier.padding(start = 10.dp).size(24.dp).graphicsLayer { scaleX = beat; scaleY = beat },
            )
        }
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = ink(0.6f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp, start = 4.dp),
        )

        CoverMosaic(songs, Modifier.padding(top = 18.dp).align(Alignment.CenterHorizontally))

        Row(
            Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Solid pill: the one bold action on the screen.
            Row(
                Modifier
                    .pressScale(onClick = onPlay)
                    .clip(PillShape)
                    .background(ink(0.95f))
                    .padding(horizontal = 24.dp, vertical = 12.dp),
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
            Row(
                Modifier
                    .pressScale(onClick = onShuffle)
                    .glass(PillShape)
                    .padding(horizontal = 24.dp, vertical = 12.dp),
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

@Composable
private fun CoverMosaic(
    songs: List<Track>,
    modifier: Modifier = Modifier,
) {
    val art = remember(songs) { songs.mapNotNull { it.albumArtUrl?.takeIf(String::isNotBlank) }.distinct().take(4) }
    val frame = RoundedCornerShape(26.dp)
    val inner = RoundedCornerShape(21.dp)
    Box(
        modifier
            .size(188.dp)
            .clip(frame)
            .background(Bezel)
            .border(1.dp, ink(0.14f), frame)
            .padding(5.dp)
            .clip(inner)
            .background(ink(0.07f)),
    ) {
        when {
            art.size >= 4 -> {
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.weight(1f)) {
                        CoverImage(art[0], Modifier.weight(1f).fillMaxSize())
                        CoverImage(art[1], Modifier.weight(1f).fillMaxSize())
                    }
                    Row(Modifier.weight(1f)) {
                        CoverImage(art[2], Modifier.weight(1f).fillMaxSize())
                        CoverImage(art[3], Modifier.weight(1f).fillMaxSize())
                    }
                }
            }

            art.isNotEmpty() -> {
                CoverImage(art[0], Modifier.fillMaxSize())
            }

            else -> {
                Icon(
                    PhosphorIcons.Fill.Heart,
                    null,
                    tint = ink(0.35f),
                    modifier = Modifier.align(Alignment.Center).size(40.dp),
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = ink(0.85f),
        modifier = Modifier.padding(start = PageInset + 6.dp, end = PageInset, top = 14.dp, bottom = 8.dp),
    )
}

private fun blockShape(
    first: Boolean,
    last: Boolean,
): RoundedCornerShape {
    val top = if (first) BlockRadius else 0.dp
    val bottom = if (last) BlockRadius else 0.dp
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

/**
 * One song. Rows are stacked edge to edge into a single rounded block with a hairline between them,
 * the way the Appearance page groups its settings. Tap plays, press-and-hold (or the Menu key) opens
 * the actions sheet, and a focus ring shows where a remote is.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FavoriteRow(
    song: Track,
    first: Boolean,
    last: Boolean,
    playing: Boolean,
    offline: Boolean,
    downloadState: DownloadState?,
    accent: Color,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onCancelDownload: () -> Unit,
    onArtist: (String) -> Unit,
) {
    val shape = blockShape(first, last)
    val downloading = downloadState != null && downloadState != DownloadState.Done && downloadState !is DownloadState.Failed

    val interaction = remember { MutableInteractionSource() }
    val pulsed by interaction.collectIsPulsedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (pulsed) 0.985f else 1f, label = "rowPress")
    val fill by animateColorAsState(
        if (playing) accent.copy(alpha = 0.14f) else ink(if (focused) 0.12f else 0.07f),
        tween(GamutMotion.CONTENT_MS),
        label = "rowFill",
    )

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = PageInset)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .background(fill)
            .then(if (focused) Modifier.border(2.dp, ink(0.9f), shape) else Modifier)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onKeyEvent { e ->
                if (e.key == Key.Menu && e.type == KeyEventType.KeyDown) {
                    onLongClick()
                    true
                } else {
                    false
                }
            }.combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
            ),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BadgedCover(local = song.isLocal, tagSize = 20.dp) {
                CoverImage(song.albumArtUrl.orEmpty(), Modifier.size(RowCover).softCover(RowCoverShape))
            }
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ExplicitMark(song.isExplicit, ink(0.6f), size = 14.dp)
                    Text(
                        song.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (playing) accent else ink(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                ArtistLine(song.artist, song.album, onArtist, stackSize = 18.dp) { m ->
                    Text(
                        song.artist,
                        style = MaterialTheme.typography.bodyMedium,
                        color = ink(0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = m,
                    )
                }
                if (downloading && !playing) {
                    DownloadProgressLine(downloadState, Modifier.padding(top = 6.dp))
                }
            }
            when {
                downloading -> {
                    TrackDownloadButton(
                        downloadState,
                        onDownload = {},
                        onCancel = onCancelDownload,
                        onDone = {},
                        glyph = 20.dp,
                    )
                }

                playing -> {
                    Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) { NowPlayingBars(color = accent) }
                }

                else -> {
                    if (offline) {
                        Icon(PhosphorIcons.Fill.CheckCircle, "Available offline", tint = ink(0.5f), modifier = Modifier.size(16.dp))
                    }
                    if (song.durationMs > 0) {
                        Text(
                            formatClock(song.durationMs),
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Normal),
                            color = ink(0.5f),
                            maxLines = 1,
                            modifier = Modifier.padding(start = if (offline) 8.dp else 0.dp),
                        )
                    }
                }
            }
        }
        if (!last) {
            Box(
                Modifier
                    .padding(start = 12.dp + RowCover + 14.dp)
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(ink(0.1f)),
            )
        }
    }
}

private fun formatTotal(ms: Long): String {
    val minutes = (ms / 60_000).coerceAtLeast(1)
    val h = minutes / 60
    val m = minutes % 60
    return if (h > 0) "$h hr $m min" else "$m min"
}

private fun formatClock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val m = total / 60
    val s = total % 60
    return "$m:${s.toString().padStart(2, '0')}"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrackActionsSheet(
    track: Track,
    downloadState: DownloadState?,
    onDownload: () -> Unit,
    onRemoveDownload: () -> Unit,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    onShare: () -> Unit,
    onAddToPlaylist: (() -> Unit)?,
    onGoToArtist: () -> Unit,
    onRemove: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val notifier = LocalNotifier.current

    fun close(action: () -> Unit = {}) {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            onDismiss()
            action()
        }
    }

    GamutSheet(
        onDismiss = onDismiss,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle(color = ink(0.3f)) },
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BadgedCover(local = track.isLocal) {
                    CoverImage(track.albumArtUrl.orEmpty(), Modifier.size(52.dp).softCover(RoundedCornerShape(12.dp)))
                }
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ExplicitMark(track.isExplicit, ink(0.6f))
                        Text(
                            track.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = ink(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                    Text(
                        track.artist,
                        style = MaterialTheme.typography.bodyMedium,
                        color = ink(0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            SheetAction("Play", TablerIcons.PlayRegular) { close(onPlay) }
            if (!track.isLocal) {
                when (downloadState) {
                    DownloadState.Done -> SheetAction("Remove download", TablerIcons.CircleCheck) { close(onRemoveDownload) }
                    null, is DownloadState.Failed -> SheetAction("Download", TablerIcons.CircleArrowDown) { close(onDownload) }
                    else -> Unit
                }
            }
            SheetAction(
                if (splitArtists(track.artist).size > 1) "Go to artist\u2026" else "Go to artist",
                TablerIcons.User,
            ) { close(onGoToArtist) }
            SheetAction("Share", TablerIcons.Share) { close(onShare) }
            if (onAddToPlaylist != null) {
                SheetAction("Add to playlist", TablerIcons.Plus) { close(onAddToPlaylist) }
            }
            Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp).height(0.5.dp).background(ink(0.1f)))
            SheetAction("Remove from favorites", TablerIcons.Trash, SheetDestructive) {
                close {
                    onRemove()
                    notifier.success("Removed from favorites")
                }
            }
        }
    }
}

@Composable
private fun SheetAction(
    label: String,
    icon: ImageVector,
    tint: Color = ink(),
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().pressScale(onClick = onClick, scaleDown = 0.98f).padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = tint, modifier = Modifier.padding(start = 16.dp))
    }
}
// ---------------------------------------------------------------------------------------------
// Wheel style
//
// The songs ride a record. A thin disc (two concentric arcs and a faint band between them) sits
// behind the list, and every row is laid along that band: its left end on the outer arc, its
// right end on the inner one, so the rows are docked in the disc rather than floating. Only the
// row in the lit slot answers to touch. Every other row is inert, so you move by scrolling and
// can't start a song by accident.
// ---------------------------------------------------------------------------------------------

private const val VisibleSlots = 5
private val SideInset = 24.dp
private val PlayButtonSize = 44.dp

// Radius of the inner arc. Larger means a flatter, calmer disc; smaller pinches the rows together.
private val DiscInnerRadius = 250.dp

@Composable
private fun FavoritesWheel(
    songs: List<Track>,
    downloadStates: Map<String, DownloadState>,
    nowPlayingIsrc: String?,
    accent: Color,
    onTrackClick: (Track) -> Unit,
    onTrackMenu: (Track) -> Unit,
    onTrackArtist: (Track) -> Unit,
    onCancelDownload: (Track) -> Unit,
    modifier: Modifier = Modifier,
) {
    val playingIndex = remember(songs, nowPlayingIsrc) { songs.indexOfFirst { nowPlayingIsrc != null && it.isrc == nowPlayingIsrc } }
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val arcColor = ink(0.16f)
    val bandColor = ink(0.035f)
    val slotColor = ink(0.06f)
    val litColor = ink(0.85f)

    BoxWithConstraints(modifier) {
        val itemHeight = (maxHeight / VisibleSlots).coerceIn(76.dp, 112.dp)
        val pad = ((maxHeight - itemHeight) / 2).coerceAtLeast(0.dp)
        val rowWidth = (maxWidth - SideInset * 2).coerceIn(0.dp, 520.dp)
        val rMid = DiscInnerRadius + rowWidth / 2
        val start = if (playingIndex >= 0) playingIndex else 0
        val state = rememberLazyListState(initialFirstVisibleItemIndex = start)

        val wheelFocus = remember { FocusRequester() }
        var wheelFocused by remember { mutableStateOf(false) }
        var target by remember { mutableIntStateOf(start.coerceIn(0, songs.lastIndex)) }
        var enterLong by remember { mutableStateOf(false) }
        val live = LocalFocusLive.current
        val remoteInput = LocalInputModeManager.current.inputMode == InputMode.Keyboard
        val settled = LocalNavSettled.current

        // Which row sits in the lit slot, tracked for the remote's select key.
        LaunchedEffect(state) {
            snapshotFlow { state.centerKey() to state.isScrollInProgress }
                .collect { (key, moving) -> if (!moving && key != null) target = key }
        }
        LaunchedEffect(settled, live, remoteInput) {
            if (settled && live && remoteInput) runCatching { wheelFocus.requestFocus() }
        }
        // A tick each time a new row passes through the lit slot.
        LaunchedEffect(state) {
            snapshotFlow { state.centerKey() }
                .distinctUntilChanged()
                .drop(1)
                .collect { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
        }

        // The one row that answers to touch: the one in the lit slot, and only once the wheel is still.
        val activeIndex by remember {
            derivedStateOf { if (state.isScrollInProgress) -1 else state.centerKey() ?: -1 }
        }
        val showJump by remember(playingIndex) { derivedStateOf { playingIndex >= 0 && state.focusOf(playingIndex) < 0.5f } }

        // The disc: a faint band, two hairline arcs, and the lit slot at the centre.
        Canvas(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
            val r = rMid.toPx()
            val band = rowWidth.toPx()
            val c = Offset(size.width / 2f + r, size.height / 2f)
            val outer = r + band / 2f
            val inner = r - band / 2f
            drawCircle(bandColor, radius = r, center = c, style = Stroke(width = band))
            drawCircle(arcColor, radius = outer, center = c, style = Stroke(width = 1.dp.toPx()))
            drawCircle(arcColor, radius = inner, center = c, style = Stroke(width = 1.dp.toPx()))

            val half = Math.toDegrees((itemHeight.toPx() / 2f / r).toDouble()).toFloat()
            drawArc(
                color = slotColor,
                startAngle = 180f - half,
                sweepAngle = half * 2f,
                useCenter = false,
                topLeft = Offset(c.x - r, c.y - r),
                size = Size(r * 2f, r * 2f),
                style = Stroke(width = band),
            )
            val lit = Stroke(width = (if (wheelFocused) 3.dp else 2.dp).toPx(), cap = StrokeCap.Round)
            listOf(outer, inner).forEach { rad ->
                drawArc(
                    color = litColor,
                    startAngle = 180f - half,
                    sweepAngle = half * 2f,
                    useCenter = false,
                    topLeft = Offset(c.x - rad, c.y - rad),
                    size = Size(rad * 2f, rad * 2f),
                    style = lit,
                )
            }
            // Let the disc fade out toward the top and bottom edges.
            drawRect(
                brush =
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.16f to Color.Black,
                        0.84f to Color.Black,
                        1f to Color.Transparent,
                    ),
                blendMode = BlendMode.DstIn,
            )
        }

        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .focusProperties { if (!live) canFocus = false }
                    .focusRequester(wheelFocus)
                    .onFocusChanged { wheelFocused = it.isFocused }
                    .onKeyEvent { e ->
                        val last = songs.lastIndex
                        when (e.key) {
                            Key.DirectionDown, Key.DirectionUp -> {
                                val next = target + if (e.key == Key.DirectionDown) 1 else -1
                                if (next !in 0..last) {
                                    false
                                } else {
                                    if (e.type == KeyEventType.KeyDown) {
                                        target = next
                                        scope.launch { state.animateScrollToItem(next) }
                                    }
                                    true
                                }
                            }

                            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                                if (e.type == KeyEventType.KeyDown) {
                                    if (e.nativeKeyEvent.isLongPress) {
                                        enterLong = true
                                        songs.getOrNull(target)?.let(onTrackMenu)
                                    }
                                } else if (e.type == KeyEventType.KeyUp) {
                                    if (!enterLong) songs.getOrNull(target)?.let(onTrackClick)
                                    enterLong = false
                                }
                                true
                            }

                            Key.Menu -> {
                                if (e.type == KeyEventType.KeyDown) songs.getOrNull(target)?.let(onTrackMenu)
                                true
                            }

                            else -> {
                                false
                            }
                        }
                    }.focusable(),
            state = state,
            flingBehavior = rememberSnapFlingBehavior(state),
            contentPadding = PaddingValues(vertical = pad),
        ) {
            items(count = songs.size, key = { it }) { index ->
                val song = songs[index]
                WheelRow(
                    song = song,
                    index = index,
                    itemHeight = itemHeight,
                    rowWidth = rowWidth,
                    rMid = rMid,
                    state = state,
                    active = index == activeIndex,
                    offline = downloadStates[song.isrc] == DownloadState.Done,
                    downloadState = downloadStates[song.isrc],
                    playing = index == playingIndex,
                    accent = accent,
                    onClick = { onTrackClick(song) },
                    onLongClick = { onTrackMenu(song) },
                    onCancelDownload = { onCancelDownload(song) },
                    onArtist = { name -> onTrackArtist(song.copy(artist = name)) },
                )
            }
        }

        AnimatedVisibility(
            visible = showJump,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
            enter = fadeIn() + slideInVertically { -it / 2 },
            exit = fadeOut() + slideOutVertically { -it / 2 },
        ) {
            Row(
                Modifier
                    .pressScale(onClick = { scope.launch { state.animateScrollToItem(playingIndex) } })
                    .glass(RoundedCornerShape(50))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NowPlayingBars(color = ink())
                Text(
                    "Now playing",
                    style = MaterialTheme.typography.labelLarge,
                    color = ink(),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WheelRow(
    song: Track,
    index: Int,
    itemHeight: Dp,
    rowWidth: Dp,
    rMid: Dp,
    state: LazyListState,
    active: Boolean,
    offline: Boolean,
    downloadState: DownloadState?,
    playing: Boolean,
    accent: Color,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onCancelDownload: () -> Unit,
    onArtist: (String) -> Unit,
) {
    val coverSize = itemHeight - 32.dp
    val downloading = downloadState != null && downloadState != DownloadState.Done && downloadState !is DownloadState.Failed

    val interaction = remember { MutableInteractionSource() }
    val pulsed by interaction.collectIsPulsedAsState()
    val pressScale by animateFloatAsState(if (pulsed && active) 0.97f else 1f, label = "wheelRowPress")

    Box(
        Modifier
            .fillMaxWidth()
            .height(itemHeight)
            .wheelTransform(state, index, rMid)
            .focusProperties { canFocus = false },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .width(rowWidth)
                .height(itemHeight)
                .graphicsLayer {
                    scaleX = pressScale
                    scaleY = pressScale
                }
                // Inert unless this is the lit row: no click handler at all, so a touch on any
                // other row falls through to the list and becomes a scroll.
                .then(
                    if (active) {
                        Modifier.combinedClickable(
                            interactionSource = interaction,
                            indication = null,
                            onClick = onClick,
                            onLongClick = onLongClick,
                        )
                    } else {
                        Modifier
                    },
                ).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BadgedCover(local = song.isLocal, tagSize = 22.dp) {
                CoverImage(song.albumArtUrl.orEmpty(), Modifier.size(coverSize).softCover(RoundedCornerShape(14.dp)))
            }
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ExplicitMark(song.isExplicit, ink(0.6f), size = 14.dp)
                    Text(
                        song.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (playing) accent else ink(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (offline) {
                        Icon(
                            PhosphorIcons.Fill.CheckCircle,
                            "Available offline",
                            tint = ink(0.5f),
                            modifier = Modifier.padding(start = 6.dp).size(13.dp),
                        )
                    }
                }
                ArtistLine(song.artist, song.album, onArtist, stackSize = 18.dp) { m ->
                    Text(
                        song.artist,
                        style = MaterialTheme.typography.bodyMedium,
                        color = ink(0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = m,
                    )
                }
                if (downloading && !playing) {
                    DownloadProgressLine(downloadState, Modifier.padding(top = 5.dp))
                }
            }
            if (downloading) {
                TrackDownloadButton(
                    downloadState,
                    onDownload = {},
                    onCancel = { if (active) onCancelDownload() },
                    onDone = {},
                    glyph = 20.dp,
                )
            }
            // The play mark only shows on the lit row, which is also the only one that can play.
            Box(
                Modifier
                    .size(PlayButtonSize)
                    .graphicsLayer { alpha = ((state.focusOf(index) - 0.5f) * 2f).coerceIn(0f, 1f) }
                    .clip(CircleShape)
                    .background(if (playing) accent.copy(alpha = 0.18f) else ink(0.95f)),
                contentAlignment = Alignment.Center,
            ) {
                if (playing) {
                    NowPlayingBars(color = accent)
                } else {
                    Icon(PhosphorIcons.Fill.Play, "Play", tint = LocalCoverPalette.current.base, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

private fun LazyListState.centerKey(): Int? {
    val info = layoutInfo
    val c = (info.viewportStartOffset + info.viewportEndOffset) / 2f
    return info.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2f - c) }?.key as? Int
}

private fun LazyListState.distanceFromCenter(key: Int): Float? {
    val info = layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.key == key } ?: return null
    val center = (info.viewportStartOffset + info.viewportEndOffset) / 2f
    return (item.offset + item.size / 2f - center) / item.size
}

private fun LazyListState.focusOf(key: Int): Float = (1f - abs(distanceFromCenter(key) ?: 9f)).coerceIn(0f, 1f)

/**
 * Swings a row around the disc. The row's centre moves from its place in the straight list onto the
 * circle of radius [rMid] and the row turns to lie along the radius there, so its ends stay on the
 * two arcs. It is never scaled (that would pull the ends off the arcs); distance only fades it.
 */
private fun Modifier.wheelTransform(
    state: LazyListState,
    key: Int,
    rMid: Dp,
): Modifier =
    graphicsLayer {
        val d = state.distanceFromCenter(key) ?: return@graphicsLayer
        val y = d * size.height
        val r = rMid.toPx()
        val phi = (y / r).coerceIn(-0.9f, 0.9f)
        translationX = r * (1f - cos(phi))
        translationY = r * sin(phi) - y
        rotationZ = -Math.toDegrees(phi.toDouble()).toFloat()
        alpha = (1f - abs(d) / 2.7f).coerceIn(0f, 1f).pow(0.9f)
    }