package com.gamut.feature.playerui

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Bold
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.bold.DotsThreeVertical
import com.adamglin.phosphoricons.regular.Heart
import com.adamglin.phosphoricons.regular.ListPlus
import com.adamglin.phosphoricons.regular.Queue
import com.adamglin.phosphoricons.regular.Repeat
import com.adamglin.phosphoricons.regular.RepeatOnce
import com.adamglin.phosphoricons.regular.ShareNetwork
import com.adamglin.phosphoricons.regular.Shuffle
import com.adamglin.phosphoricons.regular.SkipBack
import com.adamglin.phosphoricons.regular.SkipForward
import com.adamglin.phosphoricons.regular.Sparkle
import com.gamut.core.designsystem.AcrylicHost
import com.gamut.core.designsystem.AdaptiveLayout
import com.gamut.core.designsystem.ArtistLine
import com.gamut.core.designsystem.ArtistPickerSheet
import com.gamut.core.designsystem.CoverPalette
import com.gamut.core.designsystem.ExplicitMark
import com.gamut.core.designsystem.FocusLive
import com.gamut.core.designsystem.GamutMotion
import com.gamut.core.designsystem.LocalAppearance
import com.gamut.core.designsystem.LocalCoverPalette
import com.gamut.core.designsystem.PlayerStyle
import com.gamut.core.designsystem.ShareSheet
import com.gamut.core.designsystem.acrylicSource
import com.gamut.core.designsystem.adaptiveGutter
import com.gamut.core.designsystem.isWideLayout
import com.gamut.core.designsystem.rememberArtistTap
import com.gamut.core.designsystem.splitArtists
import com.gamut.core.model.PlaybackState
import com.gamut.core.model.Track
import com.gamut.core.player.EqualizerController
import com.gamut.core.player.ModController
import com.gamut.core.player.ModPreset
import com.gamut.core.player.MusicPlayer
import com.gamut.core.player.VideoStreamProvider
import com.gamut.feature.playerui.components.ArtworkWithRing
import com.gamut.feature.playerui.components.BouncingText
import com.gamut.feature.playerui.components.DepthIcon
import com.gamut.feature.playerui.components.TimeRow
import com.gamut.feature.playerui.components.VolumeHud
import com.gamut.feature.playerui.components.VolumeHudOverlay
import com.gamut.feature.playerui.components.WaveformSource
import com.gamut.feature.playerui.components.backdropArtUrl
import com.gamut.feature.playerui.components.bounceTap
import com.gamut.feature.playerui.components.rememberCoverAccent
import com.gamut.feature.playerui.components.rememberCoverSubAccent
import com.gamut.feature.playerui.components.rememberCoverTint
import com.gamut.feature.playerui.components.rememberWaveform
import com.gamut.feature.playerui.components.swipeVolumeGesture
import com.gamut.feature.playerui.components.tapTarget
import com.gamut.feature.playerui.components.volumeHudAnchor
import com.gamut.feature.playerui.components.volumeHudHeaderAnchor
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlin.math.abs
import com.adamglin.phosphoricons.fill.Heart as HeartFilled
import com.gamut.core.model.RepeatMode as PlaybackRepeatMode

private val FavoriteColor = Color(0xFFFF4D6D)
private const val PrefsName = "player_ui"
private const val PrefLyricsOn = "lyrics_on"
private const val PrefVideoOn = "video_on"

/** How far the content below the hero slides up over the video's faded bottom edge. */
/** Clear space above the title when the music video is the backdrop. */
private val VideoTopSpace = 200.dp

@Composable
fun PlayerScreen(
    player: MusicPlayer,
    onBack: () -> Unit = {},
    favoriteIsrcs: Set<String> = emptySet(),
    onToggleFavorite: (Track) -> Unit = {},
    playlists: List<PlaylistPickerItem> = emptyList(),
    onAddToPlaylist: (Track, String) -> Unit = { _, _ -> },
    playlistIdsWithTrack: Set<String> = emptySet(),
    onRemoveFromPlaylist: (Track, String) -> Unit = { _, _ -> },
    onGoToArtist: (Track) -> Unit = {},
    active: Boolean = true,
    videoProvider: VideoStreamProvider? = null,
) {
    val state by player.playbackState.collectAsState()
    val liveTrack by remember { derivedStateOf { (state as? PlaybackState.Playing)?.track ?: (state as? PlaybackState.Paused)?.track } }
    var lastTrack by remember { mutableStateOf<Track?>(null) }
    LaunchedEffect(liveTrack) { liveTrack?.let { lastTrack = it } }
    val track: Track? = liveTrack ?: lastTrack

    val isPlaying by remember { derivedStateOf { state is PlaybackState.Playing } }
    var shownPlaying by remember { mutableStateOf(isPlaying) }
    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            shownPlaying = true
        } else {
            delay(700)
            shownPlaying = false
        }
    }
    val showLoading by player.loadingTrack.collectAsState()
    val loadProgressState = player.loadProgress.collectAsState()
    val awaiting by player.awaitingAudio.collectAsState()
    var bufferingShown by remember { mutableStateOf(false) }
    LaunchedEffect(awaiting) {
        if (awaiting) {
            delay(500)
            bufferingShown = true
        } else {
            bufferingShown = false
        }
    }
    val spinnerOn = showLoading || bufferingShown

    val streamDuration by player.streamDurationMs.collectAsState()
    val catalogDuration = track?.durationMs ?: 0L
    val streamPlausible =
        streamDuration > 0L &&
            (catalogDuration <= 0L || streamDuration in (catalogDuration / 2)..(catalogDuration * 2))
    val effectiveDurationMs = if (streamPlausible) streamDuration else catalogDuration
    val duration = effectiveDurationMs.coerceAtLeast(1L)
    val shuffleOn by player.shuffleEnabled.collectAsState()
    val repeatMode by player.repeatMode.collectAsState()
    val queueState by player.queue.collectAsState()
    var showQueue by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val eq = remember { EqualizerController.get(context) }
    val eqState by eq.state.collectAsState()
    var showEq by remember { mutableStateOf(false) }
    var showStats by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }
    var showShare by remember { mutableStateOf(false) }
    var showAddToPlaylist by remember { mutableStateOf(false) }
    val playlistChanges = remember { androidx.compose.runtime.mutableStateMapOf<String, Map<String, Boolean>>() }
    val modState by ModController.state.collectAsState()
    var showMod by remember { mutableStateOf(false) }
    val sleepEndsAt by SleepTimer.endsAt.collectAsState()
    val haptic = LocalHapticFeedback.current
    val volumeHud = remember { VolumeHud(context.getSystemService(AudioManager::class.java), haptic) }

    val prefs = remember { context.getSharedPreferences(PrefsName, Context.MODE_PRIVATE) }
    var lyricsOn by remember { mutableStateOf(prefs.getBoolean(PrefLyricsOn, true)) }
    var lyricsPanelOpen by remember { mutableStateOf(false) }
    var videoOn by remember { mutableStateOf(prefs.getBoolean(PrefVideoOn, true)) }

    val leave: () -> Unit = { if (active) onBack() }
    BackHandler(enabled = active && !lyricsPanelOpen) { leave() }
    BackHandler(enabled = active && lyricsPanelOpen) { lyricsPanelOpen = false }
    LaunchedEffect(active) {
        if (!active) {
            // Past the whole spring: flipping this mid-flight would switch the artwork's flight on halfway.
            delay(850L)
            lyricsPanelOpen = false
        }
    }
    val clock = remember { PlayerClock() }
    LaunchedEffect(modState.settings.speed) { clock.setSpeed(modState.settings.speed) }
    LaunchedEffect(player) { clock.follow(player) }
    LaunchedEffect(player, clock.live) { if (clock.live) clock.sample(player) }
    LaunchedEffect(clock.live, duration, active) { if (clock.live && active) clock.tick(duration) }

    var scrubFraction by remember { mutableStateOf<Float?>(null) }
    val durationKnown = effectiveDurationMs > 0L
    val currentKey = track?.let { it.isrc.orEmpty() + "|" + it.title }
    val progressProvider =
        remember(duration, durationKnown, currentKey, showLoading) {
            {
                val clockPosition = clock.positionMs
                val clockKey = clock.anchoredKey
                scrubFraction ?: when {
                    showLoading -> loadProgressState.value.coerceIn(0f, 1f)
                    durationKnown && clockKey == currentKey -> (clockPosition / duration).coerceIn(0f, 1f)
                    else -> 0f
                }
            }
        }
    val positionProvider = remember(progressProvider, duration) { { (progressProvider() * duration).toLong() } }
    val lyricsPosition = remember(clock) { { clock.positionMs.toLong() + LyricsSyncOffsetMs } }

    val seekToFraction: (Float) -> Unit = { fraction ->
        val target = (fraction * duration).toLong()
        clock.seeked(target)
        player.seekTo(target)
    }
    val seekToMs: (Long) -> Unit = { ms ->
        val target = (ms - LyricsSyncOffsetMs).coerceAtLeast(0L)
        clock.seeked(target)
        player.seekTo(target)
    }
    val togglePlay: () -> Unit = {
        if (shownPlaying) player.pause() else player.resume()
        shownPlaying = !shownPlaying
    }
    val coverFocus = remember { FocusRequester() }
    val inputMode = LocalInputModeManager.current
    LaunchedEffect(active) {
        if (active && inputMode.inputMode == InputMode.Keyboard) {
            delay(420L)
            runCatching { coverFocus.requestFocus() }
        }
    }

    val lyricsKey = track?.let { it.isrc.orEmpty().ifBlank { "${it.title}|${it.artist}" } }
    var lyricsState by remember(lyricsKey) {
        mutableStateOf<LyricsState>(track?.let { LyricsRepository.cached(it) } ?: LyricsState.Loading)
    }
    var lyricsRetry by remember(lyricsKey) { mutableIntStateOf(0) }
    var lyricsTriedSeconds by remember(lyricsKey) { mutableIntStateOf(-1) }
    val lyricsSeconds = (effectiveDurationMs / 1000).toInt()
    LaunchedEffect(lyricsKey, lyricsRetry, lyricsOn, lyricsSeconds, active) {
        val t = track ?: return@LaunchedEffect
        if (!lyricsOn || !active) return@LaunchedEffect
        val shown = lyricsState
        if (shown is LyricsState.Loaded && (shown.isSynced || lyricsTriedSeconds == lyricsSeconds)) return@LaunchedEffect
        if (shown !is LyricsState.Loaded) lyricsState = LyricsState.Loading
        lyricsTriedSeconds = lyricsSeconds
        var attempt = 0
        while (true) {
            val result = LyricsRepository.fetchLyrics(t, effectiveDurationMs)
            if (result is LyricsState.Loaded || shown !is LyricsState.Loaded) lyricsState = result
            if (result !is LyricsState.Error || ++attempt >= 20) break
            delay((2_000L * attempt).coerceAtMost(15_000L))
        }
    }
    val activeLine =
        remember(lyricsState, clock) {
            derivedStateOf {
                val s = lyricsState
                if (s is LyricsState.Loaded && s.isSynced) activeLineIndex(s.lines, lyricsPosition()) else -1
            }
        }
    val activeIndex: () -> Int = remember(activeLine) { { activeLine.value } }

    var artistInfo by remember(lyricsKey) { mutableStateOf(track?.let { ArtistInfoRepository.cached(it) }) }
    var artistLoading by remember(lyricsKey) { mutableStateOf(artistInfo == null) }
    LaunchedEffect(lyricsKey, active) {
        val t = track ?: return@LaunchedEffect
        if (!active) return@LaunchedEffect
        if (artistInfo != null) {
            artistLoading = false
            return@LaunchedEffect
        }
        var attempt = 0
        while (artistInfo == null && attempt < 3) {
            artistInfo = ArtistInfoRepository.fetch(t).takeIf { it.imageUrl != null || it.credits.size > 1 }
            if (artistInfo == null) {
                attempt++
                if (attempt < 3) delay(10_000L * attempt)
            }
        }
        artistLoading = false
    }

    val rawArt = track?.albumArtUrl
    val artUrl = remember(rawArt) { backdropArtUrl(rawArt) }
    val accent = rememberCoverAccent(artUrl)
    val lyricHighlight = rememberCoverSubAccent(artUrl, fallback = accent)
    val tint = rememberCoverTint(artUrl)
    SideEffect {
        WaveformSource.init(context)
        WaveformSource.provider = { t -> player.sourceUrlFor(t.isrc) }
    }
    val waveform = rememberWaveform(track.takeIf { active })
    val waveformDone = waveform.isNotEmpty() && waveform.indexOfLast { it > 0f } >= waveform.size * 9 / 10
    val shownLyrics =
        remember(lyricsState, waveformDone, effectiveDurationMs) {
            val s = lyricsState
            if (s is LyricsState.Loaded && s.isSynced && waveformDone) {
                LyricsState.Loaded(LyricsAudioAligner.refine(s.lines, waveform, effectiveDurationMs), true)
            } else {
                s
            }
        }

    val artistTap =
        rememberArtistTap(track?.artist.orEmpty(), track?.album) { name ->
            track?.let { t -> onGoToArtist(t.copy(artist = name)) }
        }
    val goToArtist: () -> Unit = artistTap.onClick

    val gutter = adaptiveGutter(AdaptiveLayout.PlayerMaxWidth)

    // Music video takes over the first page; it brings its own layout, so it overrides the cover/mesh styles.
    val videoSource = rememberVideoSource(track, videoProvider, enabled = videoOn, active = active)
    val heroStream = videoSource.stream.value
    val videoMode = heroStream != null

    val meshStyle = LocalAppearance.current.playerStyle == PlayerStyle.Mesh && !videoMode
    val artworkMesh = if (meshStyle) rememberArtworkMesh(artUrl) else null
    val meshSeam = remember { MeshSeam() }
    val meshSeamLift = animateFloatAsState(if (lyricsPanelOpen) 0f else 1f, tween(400), label = "mesh-seam-lift")

    CompositionLocalProvider(LocalCoverPalette provides CoverPalette.Neutral) {
        AcrylicHost(
            Modifier
                .fillMaxSize()
                .background(CoverPalette.Neutral.base)
                .then(if (meshStyle) meshSeam.onHost else Modifier)
                .then(if (lyricsPanelOpen) Modifier else Modifier.swipeVolumeGesture(volumeHud)),
        ) {
            val coverStyle = LocalAppearance.current.playerStyle == PlayerStyle.Cover && !videoMode
            val exactLyrics = (shownLyrics as? LyricsState.Loaded)?.lines?.any { it.exact } == true
            val playhead = remember(clock, exactLyrics) { FlowPlayhead(clock, if (exactLyrics) 0L else LyricsSyncOffsetMs) }
            val lyricsLoaded = shownLyrics as? LyricsState.Loaded
            val flowLines = remember(lyricsLoaded) { lyricsLoaded?.lines?.toFlowLines(lyricsLoaded.isSynced) }
            val stripKey = lyricsKey.orEmpty()
            val extrasContent: @Composable () -> Unit = {
                if (lyricsOn) {
                    LyricsPreviewCard(
                        lyricsState = lyricsState,
                        activeIndex = activeIndex,
                        tint = tint,
                        onShowLyrics = { lyricsPanelOpen = true },
                        onRetry = { lyricsRetry++ },
                    )
                    Spacer(Modifier.height(24.dp))
                }
                if (track != null) {
                    ArtistAboutSection(
                        artistName = track.artist,
                        info = artistInfo,
                        loading = artistLoading,
                        fallbackArtUrl = rawArt,
                        onArtistClick = goToArtist,
                    )
                }
            }

            if (coverStyle) {
                StyledPlayerLayer(
                    style = PlayerStyle.Cover,
                    track = track,
                    rawArt = rawArt,
                    blurArt = artUrl,
                    active = active,
                    isPlaying = shownPlaying,
                    live = clock.live,
                    loading = spinnerOn,
                    progress = progressProvider,
                    positionMs = positionProvider,
                    durationMs = effectiveDurationMs,
                    onScrub = { scrubFraction = it },
                    onSeekFraction = seekToFraction,
                    onTogglePlay = togglePlay,
                    onPrevious = player::skipPrevious,
                    onNext = player::skipNext,
                    isFavorite = track?.isrc?.let { it in favoriteIsrcs } == true,
                    onToggleFavorite = { track?.let(onToggleFavorite) },
                    onArtist = { name -> track?.let { t -> onGoToArtist(t.copy(artist = name)) } },
                    lyricsOn = lyricsOn,
                    lyricsState = shownLyrics,
                    playhead = playhead,
                    lyricsOpen = lyricsPanelOpen,
                    onLyricsOpenChange = { lyricsPanelOpen = it },
                    onSeekToMs = seekToMs,
                    onRetryLyrics = { lyricsRetry++ },
                    shuffleOn = shuffleOn,
                    repeatMode = repeatMode,
                    accent = accent,
                    modActive = modState.preset != ModPreset.ORIGINAL,
                    onShuffle = { player.setShuffle(!shuffleOn) },
                    onQueue = { showQueue = true },
                    onAddToPlaylist = { showAddToPlaylist = true },
                    onRepeat = {
                        player.setRepeatMode(
                            when (repeatMode) {
                                PlaybackRepeatMode.OFF -> PlaybackRepeatMode.ALL
                                PlaybackRepeatMode.ALL -> PlaybackRepeatMode.ONE
                                PlaybackRepeatMode.ONE -> PlaybackRepeatMode.OFF
                            },
                        )
                    },
                    onMod = { showMod = true },
                    onMore = { showMore = true },
                    onBack = leave,
                    volumeHud = volumeHud,
                    coverFocus = coverFocus,
                    extras = extrasContent,
                    highlight = lyricHighlight,
                )
                VolumeHudOverlay(volumeHud)
            } else {
                if (meshStyle) {
                    ArtworkMeshBackdrop(artworkMesh, animate = active, seam = { meshSeam.px * meshSeamLift.value })
                } else if (artUrl != null) {
                    // Same pre-blurred backdrop the styled player uses behind its lyrics.
                    val classicBackdrop = rememberFullArtworkBlurImage(artUrl, prepare = active)
                    // Hold the last picture while the next cover prepares, so a track change
                    // does not dip to the dark floor.
                    var heldBackdrop by remember { mutableStateOf(classicBackdrop) }
                    LaunchedEffect(classicBackdrop) { if (classicBackdrop != null) heldBackdrop = classicBackdrop }
                    FullArtworkBlurBackdrop(image = classicBackdrop ?: heldBackdrop)
                }

                // The music video is the page's backdrop: full screen, behind everything below.
                if (heroStream != null) {
                    VideoHero(
                        stream = heroStream,
                        player = player,
                        // The lyrics panel covers the page; no point decoding behind it.
                        active = active && !lyricsPanelOpen,
                        speed = modState.settings.speed,
                        onFailed = videoSource.refresh,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                val wide = isWideLayout()
                val edge = if (wide) 0.dp else gutter

                val trackHeader: @Composable () -> Unit = {
                    Box(Modifier.volumeHudHeaderAnchor(volumeHud)) {
                        TrackHeader(
                            title = track?.title.orEmpty(),
                            artist = track?.artist.orEmpty(),
                            albumTitle = track?.album,
                            explicit = track?.isExplicit,
                            onArtist = { name -> track?.let { t -> onGoToArtist(t.copy(artist = name)) } },
                            isFavorite = track?.isrc?.let { it in favoriteIsrcs } == true,
                            onToggleFavorite = { track?.let(onToggleFavorite) },
                        )
                    }
                }

                val controlsGrid: @Composable () -> Unit = {
                    ControlsGrid(
                        shuffleOn = shuffleOn,
                        repeatMode = repeatMode,
                        accent = accent,
                        modActive = modState.preset != ModPreset.ORIGINAL,
                        onShuffle = { player.setShuffle(!shuffleOn) },
                        onQueue = { showQueue = true },
                        onAddToPlaylist = { showAddToPlaylist = true },
                        onRepeat = {
                            player.setRepeatMode(
                                when (repeatMode) {
                                    PlaybackRepeatMode.OFF -> PlaybackRepeatMode.ALL
                                    PlaybackRepeatMode.ALL -> PlaybackRepeatMode.ONE
                                    PlaybackRepeatMode.ONE -> PlaybackRepeatMode.OFF
                                },
                            )
                        },
                        onShare = { showShare = true },
                        onMod = { showMod = true },
                    )
                }

                val seekStep = if (duration > 0) (10_000f / duration).coerceIn(0.005f, 0.1f) else 0.04f

                val playerContent: @Composable (Dp) -> Unit = { coverMax ->
                    if (heroStream != null || meshStyle) {
                        if (heroStream != null) {
                            // The picture is the backdrop; leave it room at the top before the title.
                            Spacer(Modifier.height(VideoTopSpace).volumeHudAnchor(volumeHud))
                        } else {
                            Spacer(Modifier.height(10.dp))
                            Box(Modifier.fillMaxWidth().volumeHudAnchor(volumeHud), contentAlignment = Alignment.Center) {
                                MeshSleeve(
                                    url = rawArt,
                                    isPlaying = shownPlaying,
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .widthIn(max = minOf(coverMax, MeshSleeveMaxWidth))
                                            .then(meshSeam.onSleeve),
                                    dockEnabled = !lyricsPanelOpen,
                                )
                            }
                            Spacer(Modifier.height(28.dp))
                        }
                        trackHeader()
                        Spacer(Modifier.height(6.dp))
                        LyricStripBlock(
                            lyricsOn = lyricsOn,
                            lyricsState = shownLyrics,
                            flowLines = flowLines,
                            trackKey = stripKey,
                            playhead = playhead,
                            isPlaying = clock.live,
                            durationMs = effectiveDurationMs,
                            onOpen = { lyricsPanelOpen = true },
                            highlight = lyricHighlight,
                        )
                        Spacer(Modifier.height(6.dp))
                        MeshScrubber(
                            progress = progressProvider,
                            onScrub = { scrubFraction = it },
                            onSeekFraction = seekToFraction,
                            seekStep = seekStep,
                        )
                        TimeRow(positionProvider, effectiveDurationMs)
                        Spacer(Modifier.height(6.dp))
                        MeshTransport(
                            isPlaying = shownPlaying,
                            loading = spinnerOn,
                            onPrevious = player::skipPrevious,
                            onTogglePlay = togglePlay,
                            onNext = player::skipNext,
                            playModifier = Modifier.focusRequester(coverFocus),
                        )
                        Spacer(Modifier.height(4.dp))
                        controlsGrid()
                    } else {
                        Spacer(Modifier.height(10.dp))
                        trackHeader()
                        Spacer(Modifier.height(36.dp))

                        Row(
                            Modifier
                                .fillMaxWidth()
                                .layout { measurable, constraints ->
                                    val extra = ArtworkExtraWidth.roundToPx()
                                    val w = constraints.maxWidth + extra * 2
                                    val placeable = measurable.measure(Constraints(minWidth = w, maxWidth = w))
                                    layout(constraints.maxWidth, placeable.height) { placeable.place(-extra, 0) }
                                },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            DepthIcon(
                                PhosphorIcons.Regular.SkipBack,
                                "Previous",
                                Modifier.bounceTap(onClick = player::skipPrevious).padding(SideButtonPadding),
                                size = SideButtonSize,
                            )
                            Box(Modifier.weight(1f).volumeHudAnchor(volumeHud), contentAlignment = Alignment.Center) {
                                ArtworkWithRing(
                                    modifier = Modifier.fillMaxWidth().widthIn(max = coverMax).focusRequester(coverFocus),
                                    url = rawArt,
                                    progress = progressProvider,
                                    onScrub = { scrubFraction = it },
                                    isPlaying = shownPlaying,
                                    enabled = true,
                                    loading = spinnerOn,
                                    waveform = waveform,
                                    onTogglePlay = togglePlay,
                                    onSeekFraction = seekToFraction,
                                    seekStep = seekStep,
                                    dockEnabled = !lyricsPanelOpen,
                                )
                            }
                            DepthIcon(
                                PhosphorIcons.Regular.SkipForward,
                                "Next",
                                Modifier.bounceTap(onClick = player::skipNext).padding(SideButtonPadding),
                                size = SideButtonSize,
                            )
                        }

                        Spacer(Modifier.height(10.dp))
                        TimeRow(positionProvider, effectiveDurationMs)

                        LyricStripBlock(
                            lyricsOn = lyricsOn,
                            lyricsState = shownLyrics,
                            flowLines = flowLines,
                            trackKey = stripKey,
                            playhead = playhead,
                            isPlaying = clock.live,
                            durationMs = effectiveDurationMs,
                            onOpen = { lyricsPanelOpen = true },
                            highlight = lyricHighlight,
                            modifier = Modifier.padding(top = 4.dp),
                        )

                        Spacer(Modifier.height(4.dp))
                        controlsGrid()
                    }
                }

                Column(Modifier.fillMaxSize().statusBarsPadding()) {
                    FocusLive(!lyricsPanelOpen) {
                        PlayerTopBar(
                            leave,
                            onMore = { showMore = true },
                            modifier =
                                Modifier.fillMaxWidth().padding(
                                    start = 24.dp + edge,
                                    end =
                                        24.dp + edge,
                                ),
                        )

                        if (wide) {
                            Row(Modifier.weight(1f).fillMaxWidth()) {
                                BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
                                    val coverMax = (maxHeight - 360.dp).coerceIn(220.dp, 560.dp)
                                    Column(
                                        Modifier
                                            .fillMaxSize()
                                            .verticalScroll(rememberScrollState())
                                            .padding(start = 32.dp, end = 16.dp),
                                    ) {
                                        playerContent(coverMax)
                                        Spacer(Modifier.height(32.dp).navigationBarsPadding())
                                    }
                                }
                                Column(
                                    Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .verticalScroll(rememberScrollState())
                                        .padding(start = 16.dp, end = 32.dp),
                                ) {
                                    Spacer(Modifier.height(10.dp))
                                    extrasContent()
                                    Spacer(Modifier.height(32.dp).navigationBarsPadding())
                                }
                            }
                        } else {
                            Column(
                                Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .verticalScroll(rememberScrollState())
                                    .padding(horizontal = 24.dp + gutter),
                            ) {
                                playerContent(Dp.Infinity)
                                Spacer(Modifier.height(28.dp))
                                extrasContent()
                                Spacer(Modifier.height(32.dp).navigationBarsPadding())
                            }
                        }
                    }
                }

                VolumeHudOverlay(volumeHud)

                AnimatedVisibility(
                    visible = lyricsPanelOpen && track != null,
                    enter =
                        slideInVertically(tween(GamutMotion.PLAYER_IN_MS, easing = GamutMotion.EmphasizedDecelerate)) { it } +
                            fadeIn(tween(200)),
                    exit =
                        slideOutVertically(tween(GamutMotion.PLAYER_OUT_MS, easing = GamutMotion.EmphasizedAccelerate)) { it } +
                            fadeOut(tween(200)),
                ) {
                    FullLyricsPanel(
                        title = track?.title.orEmpty(),
                        artist = track?.artist.orEmpty(),
                        lyricsState = shownLyrics,
                        flowLines = flowLines,
                        trackKey = stripKey,
                        playhead = playhead,
                        tint = tint,
                        isPlaying = clock.live,
                        isLoading = spinnerOn,
                        progress = progressProvider,
                        labelPositionMs = positionProvider,
                        durationMs = effectiveDurationMs,
                        onScrub = { scrubFraction = it },
                        onSeekFraction = seekToFraction,
                        onSeekToMs = seekToMs,
                        onClose = { lyricsPanelOpen = false },
                        onMore = { showMore = true },
                        onTogglePlay = togglePlay,
                        onPrevious = player::skipPrevious,
                        onNext = player::skipNext,
                        onRetry = { lyricsRetry++ },
                    )
                }
            }
        }
    }

    if (showMore) {
        PlayerOptionsSheet(
            isFavorite = track?.isrc?.let { it in favoriteIsrcs } == true,
            lyricsOn = lyricsOn,
            eqActive = eqState.enabled,
            sleepActive = sleepEndsAt != null,
            onShare = {
                showMore = false
                showShare = true
            },
            onToggleLyrics = {
                lyricsOn = !lyricsOn
                prefs.edit().putBoolean(PrefLyricsOn, lyricsOn).apply()
                if (!lyricsOn) lyricsPanelOpen = false
            },
            videoSupported = videoProvider != null,
            videoOn = videoOn,
            onToggleVideo = {
                videoOn = !videoOn
                prefs.edit().putBoolean(PrefVideoOn, videoOn).apply()
            },
            onToggleFavorite = { track?.let(onToggleFavorite) },
            onAddToPlaylist = {
                showMore = false
                showAddToPlaylist = true
            },
            onGoToArtist = {
                showMore = false
                goToArtist()
            },
            onEqualizer = {
                showMore = false
                showEq = true
            },
            onSleepTimer = {
                showMore = false
                showSleep = true
            },
            onStats = {
                showMore = false
                showStats = true
            },
            onDismiss = { showMore = false },
        )
    }
    artistTap.Sheet()
    if (showShare) {
        track?.let { t ->
            ShareSheet(title = t.title, artist = t.artist, artUrl = t.albumArtUrl, onDismiss = { showShare = false })
        }
    }
    if (showAddToPlaylist) {
        AddToPlaylistSheet(
            playlists = playlists,
            alreadyIn =
                run {
                    val delta = track?.isrc?.let { playlistChanges[it] }.orEmpty()
                    playlistIdsWithTrack.filter { delta[it] != false }.toSet() + delta.filterValues { it }.keys
                },
            onPick = { picked ->
                track?.let {
                    onAddToPlaylist(it, picked.id)
                    playlistChanges[it.isrc] = (playlistChanges[it.isrc] ?: emptyMap()) + (picked.id to true)
                }
            },
            onRemove = { picked ->
                track?.let {
                    onRemoveFromPlaylist(it, picked.id)
                    playlistChanges[it.isrc] = (playlistChanges[it.isrc] ?: emptyMap()) + (picked.id to false)
                }
            },
            onDismiss = { showAddToPlaylist = false },
        )
    }
    if (showEq) EqualizerSheet(eq, accent, onDismiss = { showEq = false })
    if (showStats) StatsForNerdsSheet(accent, onDismiss = { showStats = false })
    if (showMod) ModSheet(accent, onDismiss = { showMod = false })
    if (showSleep) {
        SleepTimerSheet(
            onPick = { minutes ->
                SleepTimer.start(minutes) { player.pause() }
                showSleep = false
            },
            onTurnOff = {
                SleepTimer.cancel()
                showSleep = false
            },
            onDismiss = { showSleep = false },
        )
    }
    if (showQueue) {
        QueueSheet(
            queue = queueState,
            accent = accent,
            onDismiss = { showQueue = false },
            onPlay = player::skipTo,
            onRemove = player::removeFromQueue,
        )
    }
}

@Composable
internal fun PlayerTopBar(
    onBack: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The same pill as the Cover style, in the same place; "More" sits over its
    // drag zone, not under it.
    Box(modifier.height(54.dp)) {
        PlayerPill(onDismiss = onBack, modifier = Modifier.fillMaxWidth(), height = 54.dp)
        DepthIcon(
            PhosphorIcons.Bold.DotsThreeVertical,
            "More",
            Modifier.align(Alignment.TopEnd).tapTarget(onClick = onMore).padding(8.dp),
        )
    }
}

@Composable
internal fun TrackHeader(
    title: String,
    artist: String,
    albumTitle: String?,
    onArtist: (String) -> Unit,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onMore: (() -> Unit)? = null,
    explicit: Boolean? = null,
    titleStyle: TextStyle = MaterialTheme.typography.headlineLarge.copy(letterSpacing = (-0.3).sp),
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ExplicitMark(explicit, Color.White.copy(alpha = 0.65f), size = 18.dp)
                BouncingText(
                    text = title,
                    style = titleStyle,
                    color = Color.White,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(2.dp))
            ArtistLine(artist, albumTitle, onArtist, stackSize = 22.dp) { m ->
                Box(m) {
                    BouncingText(
                        text = artist,
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.65f),
                    )
                }
            }
            QualityTagRow(Modifier.padding(top = 6.dp))
        }
        DepthIcon(
            if (isFavorite) PhosphorIcons.Fill.HeartFilled else PhosphorIcons.Regular.Heart,
            if (isFavorite) "Remove from favorites" else "Add to favorites",
            Modifier.bounceTap(onClick = onToggleFavorite).padding(4.dp),
            tint = if (isFavorite) FavoriteColor else Color.White,
            size = 26.dp,
        )
        if (onMore != null) {
            DepthIcon(PhosphorIcons.Bold.DotsThreeVertical, "More", Modifier.bounceTap(onClick = onMore).padding(4.dp), size = 26.dp)
        }
    }
}

@Composable
private fun ControlsGrid(
    shuffleOn: Boolean,
    repeatMode: PlaybackRepeatMode,
    accent: Color,
    modActive: Boolean,
    onShuffle: () -> Unit,
    onQueue: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onRepeat: () -> Unit,
    onShare: () -> Unit,
    onMod: () -> Unit,
) {
    val idle = Color.White.copy(alpha = 0.85f)
    val iconSize = 24.dp
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            GridCell {
                DepthIcon(
                    PhosphorIcons.Regular.Shuffle,
                    if (shuffleOn) "Shuffle on" else "Shuffle off",
                    Modifier.bounceTap(onClick = onShuffle).padding(8.dp),
                    tint = if (shuffleOn) accent else idle,
                    size = iconSize,
                )
            }
            GridCell {
                DepthIcon(
                    PhosphorIcons.Regular.Queue,
                    "Queue",
                    Modifier.bounceTap(onClick = onQueue).padding(8.dp),
                    tint = idle,
                    size = iconSize,
                )
            }
            GridCell {
                DepthIcon(
                    PhosphorIcons.Regular.ListPlus,
                    "Add to playlist",
                    Modifier.bounceTap(onClick = onAddToPlaylist).padding(8.dp),
                    tint = idle,
                    size = iconSize,
                )
            }
            GridCell {
                DepthIcon(
                    if (repeatMode == PlaybackRepeatMode.ONE) PhosphorIcons.Regular.RepeatOnce else PhosphorIcons.Regular.Repeat,
                    when (repeatMode) {
                        PlaybackRepeatMode.OFF -> "Repeat off"
                        PlaybackRepeatMode.ALL -> "Repeat all"
                        PlaybackRepeatMode.ONE -> "Repeat one"
                    },
                    Modifier.bounceTap(onClick = onRepeat).padding(8.dp),
                    tint = if (repeatMode != PlaybackRepeatMode.OFF) accent else idle,
                    size = iconSize,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            GridCell {}
            GridCell {
                DepthIcon(
                    PhosphorIcons.Regular.ShareNetwork,
                    "Share",
                    Modifier.bounceTap(onClick = onShare).padding(8.dp),
                    tint = idle,
                    size = iconSize,
                )
            }
            GridCell {
                DepthIcon(
                    PhosphorIcons.Regular.Sparkle,
                    if (modActive) "Mod on" else "Mod",
                    Modifier.bounceTap(onClick = onMod).padding(8.dp),
                    tint = if (modActive) accent else idle,
                    size = iconSize,
                )
            }
            GridCell {}
        }
    }
}

@Composable
private fun RowScope.GridCell(content: @Composable () -> Unit) {
    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { content() }
}

internal const val LyricsSyncOffsetMs = 250L

private val ArtworkExtraWidth = 14.dp

private val SideButtonSize = 30.dp
private val SideButtonPadding = 4.dp

private const val RESYNC_THRESHOLD_MS = 400L

@Stable
internal class PlayerClock {
    private var basePosition = 0L
    private var baseTimestamp = SystemClock.elapsedRealtime()
    private var speed = 1f

    var anchoredKey by mutableStateOf<String?>(null)
        private set

    var live by mutableStateOf(false)
        private set

    var positionMs by mutableFloatStateOf(0f)
        private set

    var readingMs by mutableLongStateOf(0L)
        private set
    var readingAtNanos by mutableLongStateOf(0L)
        private set
    var seeks by mutableIntStateOf(0)
        private set

    private fun reading(ms: Long) {
        readingMs = ms
        readingAtNanos = System.nanoTime()
    }

    fun seeked(positionMs: Long) {
        seeks++
        reading(positionMs)
        anchor(positionMs)
    }

    suspend fun sample(player: MusicPlayer) {
        while (true) {
            player.currentPositionMs().takeIf { it >= 0L }?.let { reading(it) }
            delay(250)
        }
    }

    fun anchor(positionMs: Long) {
        basePosition = positionMs
        baseTimestamp = SystemClock.elapsedRealtime()
        this.positionMs = positionMs.toFloat()
    }

    fun setSpeed(value: Float) {
        if (value == speed) return
        anchor(positionMs.toLong())
        speed = value
    }

    suspend fun follow(player: MusicPlayer) {
        var lastTrackKey: String? = null
        var wasLive = false
        combine(player.playbackState, player.awaitingAudio) { s, awaiting -> s to awaiting }.collect { (s, awaiting) ->
            val reported: Long
            val track: Track
            when (s) {
                is PlaybackState.Playing -> {
                    reported = player.currentPositionMs().takeIf { it >= 0L } ?: s.positionMs
                    track = s.track
                }

                is PlaybackState.Paused -> {
                    reported = player.currentPositionMs().takeIf { it >= 0L } ?: s.positionMs
                    track = s.track
                }

                else -> {
                    live = false
                    wasLive = false
                    lastTrackKey = null
                    anchoredKey = null
                    anchor(0L)
                    return@collect
                }
            }
            val key = track.isrc.orEmpty() + "|" + track.title
            val nowLive = s is PlaybackState.Playing && !awaiting
            if (key != lastTrackKey) anchoredKey = null
            val now = SystemClock.elapsedRealtime()
            val estimate = if (wasLive) basePosition + ((now - baseTimestamp) * speed).toLong() else basePosition
            if (nowLive && wasLive && key == lastTrackKey && abs(reported - estimate) > RESYNC_THRESHOLD_MS) seeks++
            reading(reported)
            if (!nowLive || key != lastTrackKey || nowLive != wasLive || abs(reported - estimate) > RESYNC_THRESHOLD_MS) {
                anchoredKey = key
                anchor(reported)
            }
            anchoredKey = key
            lastTrackKey = key
            wasLive = nowLive
            live = nowLive
        }
    }

    suspend fun tick(durationMs: Long) {
        while (true) {
            withFrameNanos {}
            val elapsed = (SystemClock.elapsedRealtime() - baseTimestamp) * speed
            positionMs = (basePosition + elapsed).toFloat().coerceAtMost(durationMs.toFloat())
        }
    }
}

private val BackgroundBlur = 24.dp

@Composable
internal fun PlayerBackdrop(
    artUrl: String,
    animateChanges: Boolean = true,
) {
    Box(Modifier.fillMaxSize().acrylicSource()) {
        Box(Modifier.fillMaxSize().clipToBounds()) {
            AsyncImage(
                model = rememberFadeRequest(artUrl, durationMs = if (animateChanges) 600 else 0),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = 1.3f
                            scaleY = 1.3f
                        }.then(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Modifier.blur(BackgroundBlur) else Modifier),
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(
                        CoverPalette.Neutral.base.copy(alpha = 0.50f),
                        CoverPalette.Neutral.base.copy(alpha = 0.78f),
                    ),
                ),
            ),
        )
        Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.05f)))
        Box(
            Modifier.fillMaxSize().background(
                Brush.linearGradient(
                    colors = listOf(Color.White.copy(alpha = 0.13f), Color.White.copy(alpha = 0.02f), Color.White.copy(alpha = 0.06f)),
                    start = Offset(0f, 0f),
                    end = Offset(1000f, 1700f),
                ),
            ),
        )
    }
}

@Composable
private fun rememberFadeRequest(
    url: String?,
    durationMs: Int = 600,
): ImageRequest {
    val context = LocalContext.current
    val firstUrl = remember { url }
    return remember(url, durationMs == 0) {
        ImageRequest
            .Builder(context)
            .data(url)
            .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
            .crossfade(if (url == firstUrl || durationMs == 0) 0 else durationMs)
            .build()
    }
}