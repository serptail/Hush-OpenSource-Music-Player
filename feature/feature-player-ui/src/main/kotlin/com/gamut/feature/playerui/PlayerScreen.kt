package com.gamut.feature.playerui

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import com.gamut.core.designsystem.GamutMotion
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.Light
import com.adamglin.phosphoricons.fill.Heart as HeartFilled
import com.adamglin.phosphoricons.light.CaretDown
import com.adamglin.phosphoricons.light.DotsThreeVertical
import com.adamglin.phosphoricons.light.Heart
import com.adamglin.phosphoricons.light.ListPlus
import com.adamglin.phosphoricons.light.Queue
import com.adamglin.phosphoricons.light.Repeat
import com.adamglin.phosphoricons.light.RepeatOnce
import com.adamglin.phosphoricons.light.ShareNetwork
import com.adamglin.phosphoricons.light.Shuffle
import com.adamglin.phosphoricons.light.SkipBack
import com.adamglin.phosphoricons.light.SkipForward
import com.adamglin.phosphoricons.light.Sparkle
import com.gamut.core.designsystem.AcrylicHost
import com.gamut.core.designsystem.CoverPalette
import com.gamut.core.designsystem.LocalCoverPalette
import com.gamut.core.designsystem.acrylicSource
import com.gamut.core.model.PlaybackState
import com.gamut.core.model.RepeatMode as PlaybackRepeatMode
import com.gamut.core.model.Track
import com.gamut.core.player.EqualizerController
import com.gamut.core.player.ModController
import com.gamut.core.player.ModPreset
import com.gamut.core.player.MusicPlayer
import com.gamut.core.designsystem.ShareSheet
import com.gamut.core.designsystem.ArtistLine
import com.gamut.core.designsystem.ArtistPickerSheet
import com.gamut.core.designsystem.rememberArtistTap
import com.gamut.core.designsystem.splitArtists
import com.gamut.feature.playerui.components.ArtworkWithRing
import com.gamut.feature.playerui.components.BouncingText
import com.gamut.feature.playerui.components.DepthIcon
import com.gamut.feature.playerui.components.TimeRow
import com.gamut.feature.playerui.components.WaveformSource
import com.gamut.feature.playerui.components.VolumeHud
import com.gamut.feature.playerui.components.VolumeHudOverlay
import com.gamut.feature.playerui.components.backdropArtUrl
import com.gamut.feature.playerui.components.bounceTap
import com.gamut.feature.playerui.components.swipeVolumeGesture
import com.gamut.feature.playerui.components.rememberCoverAccent
import com.gamut.feature.playerui.components.rememberCoverSubAccent
import com.gamut.feature.playerui.components.rememberCoverTint
import com.gamut.feature.playerui.components.rememberWaveform
import com.gamut.feature.playerui.components.tapTarget
import com.gamut.feature.playerui.components.volumeHudAnchor
import com.gamut.feature.playerui.components.volumeHudHeaderAnchor
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine

private val FavoriteColor = Color(0xFFFF4D6D)   // the heart when the track is a favorite
private const val PrefsName = "player_ui"
private const val PrefLyricsOn = "lyrics_on"

@Composable
fun PlayerScreen(
    player: MusicPlayer,
    onBack: () -> Unit = {},
    /** ISRCs of the favorited tracks; the heart is filled in when the current track is in this set. */
    favoriteIsrcs: Set<String> = emptySet(),
    onToggleFavorite: (Track) -> Unit = {},
    /** The playlists shown in the list-plus sheet. */
    playlists: List<PlaylistPickerItem> = emptyList(),
    /** Called when the user taps a playlist in the list-plus sheet: add [Track] to the playlist with this id. */
    onAddToPlaylist: (Track, String) -> Unit = { _, _ -> },
    /** "Go to artist" (options sheet, and a tap on the artist photo / name). The CALLER must close the player layer. */
    onGoToArtist: (Track) -> Unit = {},
    /**
     * False while the screen is parked off-screen (the player lives as a persistent layer, see PlayerLayer in MainActivity) or
     * sliding away. Everything that costs something (lyrics / artist / waveform lookups, the per-frame clock) only runs while true.
     */
    active: Boolean = true,
) {
    val state by player.playbackState.collectAsState()
    // `state` changes every position tick. It is only read inside derivedStateOf, so this screen recomposes when
    // the track / play state really changes, not twice a second.
    val liveTrack by remember { derivedStateOf { (state as? PlaybackState.Playing)?.track ?: (state as? PlaybackState.Paused)?.track } }
    // No loading screen any more: while the next track resolves (state = Loading, no track) the player simply keeps
    // showing the last one, with the spinner on the cover and the real load progress on the ring. This also removes
    // the flash of an empty screen between two queue items.
    var lastTrack by remember { mutableStateOf<Track?>(null) }
    LaunchedEffect(liveTrack) { liveTrack?.let { lastTrack = it } }
    val track: Track? = liveTrack ?: lastTrack

    val isPlaying by remember { derivedStateOf { state is PlaybackState.Playing } }
    // What the cover shows as "playing". A seek makes ExoPlayer report "not playing" for a moment while it
    // re-buffers; a drop to "paused" only counts if it lasts, "playing" shows at once, and a tap flips it immediately.
    var shownPlaying by remember { mutableStateOf(isPlaying) }
    LaunchedEffect(isPlaying) {
        if (isPlaying) shownPlaying = true else { delay(700); shownPlaying = false }
    }
    // True while a track change is in progress (state Loading, or a queue skip that already shows the new track as
    // Playing while its URL resolves). Only then is loadProgress meaningful; afterwards it sits at 1.
    val showLoading by player.loadingTrack.collectAsState()
    val loadProgressState = player.loadProgress.collectAsState()
    // The spinner also covers a re-buffer in the middle of a song, but only once it lasts, so a seek doesn't flash it.
    val awaiting by player.awaitingAudio.collectAsState()
    var bufferingShown by remember { mutableStateOf(false) }
    LaunchedEffect(awaiting) { if (awaiting) { delay(500); bufferingShown = true } else bufferingShown = false }
    val spinnerOn = showLoading || bufferingShown

    // The stream's real length wins over the catalog's (another version of the song), but only when it is plausible:
    // right after a track change this value can still belong to the PREVIOUS song, which gave a full or empty bar.
    val streamDuration by player.streamDurationMs.collectAsState()
    val catalogDuration = track?.durationMs ?: 0L
    val streamPlausible = streamDuration > 0L &&
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
    var showSleep by remember { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }
    var showShare by remember { mutableStateOf(false) }
    var showAddToPlaylist by remember { mutableStateOf(false) }
    val modState by ModController.state.collectAsState()
    var showMod by remember { mutableStateOf(false) }
    val sleepEndsAt by SleepTimer.endsAt.collectAsState()
    val haptic = LocalHapticFeedback.current
    val volumeHud = remember { VolumeHud(context.getSystemService(AudioManager::class.java), haptic) }

    // "Lyrics: On / Off" from the options sheet. Remembered across launches.
    val prefs = remember { context.getSharedPreferences(PrefsName, Context.MODE_PRIVATE) }
    var lyricsOn by remember { mutableStateOf(prefs.getBoolean(PrefLyricsOn, true)) }
    var lyricsPanelOpen by remember { mutableStateOf(false) }

    // `active` goes false the moment the user leaves: from then on the screen is a still picture (see PlayerClock).
    // It is a persistent layer now, so it is never disposed: reset the lyrics panel once the slide-away is over.
    val leave: () -> Unit = { if (active) onBack() }
    BackHandler(enabled = active && !lyricsPanelOpen) { leave() }
    BackHandler(enabled = active && lyricsPanelOpen) { lyricsPanelOpen = false }   // declared last: wins while the panel is open
    LaunchedEffect(active) { if (!active) { delay(GamutMotion.PLAYER_LAYER_OUT_MS + 120L); lyricsPanelOpen = false } }
    val clock = remember { PlayerClock() }
    LaunchedEffect(modState.settings.speed) { clock.setSpeed(modState.settings.speed) }
    LaunchedEffect(player) { clock.follow(player) }
    // The per-frame tick only runs while the player is on screen (a parked layer must not keep the display refreshing).
    LaunchedEffect(clock.live, duration, active) { if (clock.live && active) clock.tick(duration) }

    // While the progress line is dragged, the line and time label follow the finger; the real seek happens on release.
    var scrubFraction by remember { mutableStateOf<Float?>(null) }
    val durationKnown = effectiveDurationMs > 0L
    val currentKey = track?.let { it.isrc.orEmpty() + "|" + it.title }
    val progressProvider = remember(duration, durationKnown, currentKey, showLoading) {
        {
            // Read the clock's state FIRST, unconditionally: the ring's draw scope and TimeRow's derivedStateOf only
            // subscribe to what they read. On a fresh open the clock isn't anchored yet, so the branch below used to
            // return 0f without touching positionMs, and nothing ever redrew once the clock started.
            val clockPosition = clock.positionMs
            val clockKey = clock.anchoredKey
            scrubFraction ?: when {
                // Loading the next song: the ring shows the real load progress (it replaces the old loading screen's bar).
                showLoading -> loadProgressState.value.coerceIn(0f, 1f)
                // Not anchored to THIS track yet: the clock still holds the previous song's position.
                durationKnown && clockKey == currentKey -> (clockPosition / duration).coerceIn(0f, 1f)
                else -> 0f
            }
        }
    }
    val positionProvider = remember(progressProvider, duration) { { (progressProvider() * duration).toLong() } }
    val lyricsPosition = remember(clock) { { clock.positionMs.toLong() + LyricsSyncOffsetMs } }

    val seekToFraction: (Float) -> Unit = { fraction ->
        val target = (fraction * duration).toLong()
        clock.anchor(target)   // move the line right away instead of waiting for the player's next tick
        player.seekTo(target)
    }
    val seekToMs: (Long) -> Unit = { ms ->
        val target = (ms - LyricsSyncOffsetMs).coerceAtLeast(0L)
        clock.anchor(target)
        player.seekTo(target)
    }
    val togglePlay: () -> Unit = {
        if (shownPlaying) player.pause() else player.resume()
        shownPlaying = !shownPlaying
    }

    // Lyrics. Keyed on the track so the previous song's lines never show for a frame on a track change.
    val lyricsKey = track?.let { it.isrc.orEmpty().ifBlank { "${it.title}|${it.artist}" } }
    var lyricsState by remember(lyricsKey) {
        mutableStateOf<LyricsState>(track?.let { LyricsRepository.cached(it) } ?: LyricsState.Loading)
    }
    var lyricsRetry by remember(lyricsKey) { mutableIntStateOf(0) }   // bumped by tapping the card after a failure
    var lyricsTriedSeconds by remember(lyricsKey) { mutableIntStateOf(-1) }
    // Lyrics are matched against the length of the audio that is really playing: once the stream's length is known
    // it replaces the catalog's. Synced lyrics are final; plain ones are looked up once more if the length changed.
    val lyricsSeconds = (effectiveDurationMs / 1000).toInt()
    LaunchedEffect(lyricsKey, lyricsRetry, lyricsOn, lyricsSeconds, active) {
        val t = track ?: return@LaunchedEffect
        if (!lyricsOn || !active) return@LaunchedEffect   // parked: no network for a screen nobody sees
        val shown = lyricsState
        if (shown is LyricsState.Loaded && (shown.isSynced || lyricsTriedSeconds == lyricsSeconds)) return@LaunchedEffect
        if (shown !is LyricsState.Loaded) lyricsState = LyricsState.Loading
        lyricsTriedSeconds = lyricsSeconds
        // A failed lookup is usually a slow service or no network, not "no lyrics": keep retrying with growing pauses.
        var attempt = 0
        while (true) {
            val result = LyricsRepository.fetchLyrics(t, effectiveDurationMs)
            // A failed re-check must not wipe plain lyrics that are already on screen.
            if (result is LyricsState.Loaded || shown !is LyricsState.Loaded) lyricsState = result
            if (result !is LyricsState.Error || ++attempt >= 20) break
            delay((2_000L * attempt).coerceAtMost(15_000L))
        }
    }
    // Index of the line being sung. A derived state: it only notifies when the line changes, so the one-line view,
    // the preview card and the panel recompose once per line, not once per frame.
    val activeLine = remember(lyricsState, clock) {
        derivedStateOf {
            val s = lyricsState
            if (s is LyricsState.Loaded && s.isSynced) activeLineIndex(s.lines, lyricsPosition()) else -1
        }
    }
    val activeIndex: () -> Int = remember(activeLine) { { activeLine.value } }

    // Artist photo + credits (Deezer / MusicBrainz, see ArtistInfoRepository). Nothing here blocks the player.
    var artistInfo by remember(lyricsKey) { mutableStateOf(track?.let { ArtistInfoRepository.cached(it) }) }
    var artistLoading by remember(lyricsKey) { mutableStateOf(artistInfo == null) }
    LaunchedEffect(lyricsKey, active) {
        val t = track ?: return@LaunchedEffect
        if (!active) return@LaunchedEffect
        if (artistInfo != null) { artistLoading = false; return@LaunchedEffect }
        var attempt = 0
        while (artistInfo == null && attempt < 3) {   // offline -> online recovers on its own
            artistInfo = ArtistInfoRepository.fetch(t).takeIf { it.imageUrl != null || it.credits.size > 1 }
            if (artistInfo == null) { attempt++; if (attempt < 3) delay(10_000L * attempt) }
        }
        artistLoading = false   // gave up: the section just shows the name + cover, no spinner
    }

    val rawArt = track?.albumArtUrl
    val artUrl = remember(rawArt) { backdropArtUrl(rawArt) }   // blurred backdrop + accent + gradient tint
    val accent = rememberCoverAccent(artUrl)
    val lyricHighlight = rememberCoverSubAccent(artUrl, fallback = accent)   // the cover's second colour for the sung words
    val tint = rememberCoverTint(artUrl)
    SideEffect {
        WaveformSource.init(context)
        WaveformSource.provider = { t -> player.sourceUrlFor(t.isrc) }
    }
    val waveform = rememberWaveform(track.takeIf { active })   // parked: no audio analysis
    // Word timing refined with the loudness envelope (skips real silences inside a line). The envelope fills in from
    // the start; refine only once nearly all of it is measured. Line timestamps are unchanged, so activeIndex still
    // uses lyricsState.
    val waveformDone = waveform.isNotEmpty() && waveform.indexOfLast { it > 0f } >= waveform.size * 9 / 10
    val shownLyrics = remember(lyricsState, waveformDone, effectiveDurationMs) {
        val s = lyricsState
        if (s is LyricsState.Loaded && s.isSynced && waveformDone)
            LyricsState.Loaded(LyricsAudioAligner.refine(s.lines, waveform, effectiveDurationMs), true)
        else s
    }

    // The player does NOT close itself here: the caller closes the layer and navigates, so the two slides run together.
    // One artist: straight to their page. Several: the picker sheet opens first (placed next to the other sheets below).
    // The picked name goes to the caller as track.copy(artist = name), so the existing by-name lookup just works.
    val artistTap = rememberArtistTap(track?.artist.orEmpty(), track?.album) { name ->
        track?.let { t -> onGoToArtist(t.copy(artist = name)) }
    }
    val goToArtist: () -> Unit = artistTap.onClick

    // The glass (sheets) takes its backing color from LocalCoverPalette; the player has its own dark backdrop, so
    // inside it the palette is pinned to the neutral one (the same color as the screen, and still).
    CompositionLocalProvider(LocalCoverPalette provides CoverPalette.Neutral) {
    AcrylicHost(
        Modifier
            .fillMaxSize()
            .background(CoverPalette.Neutral.base)
            // The sideways volume swipe on the cover is dead while the lyrics panel covers the screen.
            .then(if (lyricsPanelOpen) Modifier else Modifier.swipeVolumeGesture(volumeHud)),
    ) {
        if (artUrl != null) PlayerBackdrop(artUrl, animateChanges = active)

        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            PlayerTopBar(leave, onMore = { showMore = true }, modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 14.dp))

            // Everything below the top bar scrolls: controls first, then the lyrics card, then the artist.
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp),
            ) {
                Spacer(Modifier.height(10.dp))
                Box(Modifier.volumeHudHeaderAnchor(volumeHud)) {
                    TrackHeader(
                        title = track?.title.orEmpty(),
                        artist = track?.artist.orEmpty(),
                        albumTitle = track?.album,
                        onArtist = { name -> track?.let { t -> onGoToArtist(t.copy(artist = name)) } },
                        isFavorite = track?.isrc?.let { it in favoriteIsrcs } == true,
                        onToggleFavorite = { track?.let(onToggleFavorite) },
                    )
                }
                Spacer(Modifier.height(36.dp))   // the cover sits lower, closer to the middle of the screen

                // [previous] cover [next]. The row may reach ArtworkExtraWidth past the screen padding on each side, and
                // the cover takes whatever the two buttons leave. Play / pause is a tap on the cover itself.
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
                        PhosphorIcons.Light.SkipBack, "Previous",
                        Modifier.bounceTap(onClick = player::skipPrevious).padding(SideButtonPadding),
                        size = SideButtonSize,
                    )
                    Box(Modifier.weight(1f).volumeHudAnchor(volumeHud), contentAlignment = Alignment.Center) {
                        ArtworkWithRing(
                            modifier = Modifier.fillMaxWidth(),
                            url = rawArt,
                            progress = progressProvider,
                            onScrub = { scrubFraction = it },
                            isPlaying = shownPlaying,
                            enabled = true,
                            loading = spinnerOn,
                            waveform = waveform,
                            onTogglePlay = togglePlay,
                            onSeekFraction = seekToFraction,
                        )
                    }
                    DepthIcon(
                        PhosphorIcons.Light.SkipForward, "Next",
                        Modifier.bounceTap(onClick = player::skipNext).padding(SideButtonPadding),
                        size = SideButtonSize,
                    )
                }

                Spacer(Modifier.height(10.dp))
                TimeRow(positionProvider, effectiveDurationMs)

                // The line being sung: right under the cover and the time codes.
                if (lyricsOn) CurrentLyricLine(shownLyrics, activeIndex, lyricsPosition, tint, highlight = lyricHighlight)

                Spacer(Modifier.height(4.dp))
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

                Spacer(Modifier.height(28.dp))
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
                Spacer(Modifier.height(32.dp).navigationBarsPadding())
            }
        }

        // Volume pill: drawn above the page, takes no touches.
        VolumeHudOverlay(volumeHud)

        // The "opened mini player": every lyric on a gradient from the cover, with the controls at the bottom.
        AnimatedVisibility(
            visible = lyricsPanelOpen && track != null,
            enter = slideInVertically(tween(GamutMotion.PLAYER_IN_MS, easing = GamutMotion.EmphasizedDecelerate)) { it } + fadeIn(tween(200)),
            exit = slideOutVertically(tween(GamutMotion.PLAYER_OUT_MS, easing = GamutMotion.EmphasizedAccelerate)) { it } + fadeOut(tween(200)),
        ) {
            FullLyricsPanel(
                title = track?.title.orEmpty(),
                artist = track?.artist.orEmpty(),
                lyricsState = shownLyrics,
                positionMs = lyricsPosition,
                activeIndex = activeIndex,
                tint = tint,
                isPlaying = shownPlaying,
                isLoading = spinnerOn,
                progress = progressProvider,
                labelPositionMs = positionProvider,
                durationMs = effectiveDurationMs,
                onScrub = { scrubFraction = it },
                onSeekFraction = seekToFraction,
                onSeekToMs = seekToMs,
                onClose = { lyricsPanelOpen = false },
                onShare = { showShare = true },
                onMore = { showMore = true },
                onTogglePlay = togglePlay,
                onPrevious = player::skipPrevious,
                onNext = player::skipNext,
                onRetry = { lyricsRetry++ },
            )
        }
    }
    }

    // Sheets sit OUTSIDE the Neutral pin: a sheet is its own window and follows the app's light / dark look.
    if (showMore) {
        PlayerOptionsSheet(
            accent = accent,
            isFavorite = track?.isrc?.let { it in favoriteIsrcs } == true,
            lyricsOn = lyricsOn,
            eqActive = eqState.enabled,
            sleepActive = sleepEndsAt != null,
            onShare = { showMore = false; showShare = true },
            onToggleLyrics = {
                lyricsOn = !lyricsOn
                prefs.edit().putBoolean(PrefLyricsOn, lyricsOn).apply()
                if (!lyricsOn) lyricsPanelOpen = false
            },
            onToggleFavorite = { track?.let(onToggleFavorite) },
            onAddToPlaylist = { showMore = false; showAddToPlaylist = true },
            onGoToArtist = { showMore = false; goToArtist() },
            onEqualizer = { showMore = false; showEq = true },
            onSleepTimer = { showMore = false; showSleep = true },
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
            accent = accent,
            onPick = { picked -> track?.let { onAddToPlaylist(it, picked.id) } },
            onDismiss = { showAddToPlaylist = false },
        )
    }
    if (showEq) EqualizerSheet(eq, accent, onDismiss = { showEq = false })
    if (showMod) ModSheet(accent, onDismiss = { showMod = false })
    if (showSleep) {
        SleepTimerSheet(
            onPick = { minutes -> SleepTimer.start(minutes) { player.pause() }; showSleep = false },
            onTurnOff = { SleepTimer.cancel(); showSleep = false },
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
private fun PlayerTopBar(onBack: () -> Unit, onMore: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        DepthIcon(PhosphorIcons.Light.CaretDown, "Collapse", Modifier.tapTarget(onClick = onBack).padding(8.dp))
        Spacer(Modifier.weight(1f))
        DepthIcon(PhosphorIcons.Light.DotsThreeVertical, "More", Modifier.tapTarget(onClick = onMore).padding(8.dp))
    }
}

@Composable
private fun TrackHeader(
    title: String,
    artist: String,
    albumTitle: String?,
    onArtist: (String) -> Unit,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            BouncingText(
                text = title,
                style = MaterialTheme.typography.headlineLarge.copy(letterSpacing = (-0.3).sp),
                color = Color.White,
            )
            Spacer(Modifier.height(2.dp))
            // Several artists: a faded stack of portraits before the names; the line opens the artist / the picker.
            ArtistLine(artist, albumTitle, onArtist, stackSize = 22.dp) { m ->
                Box(m) {
                    BouncingText(
                        text = artist,
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.65f),
                    )
                }
            }
        }
        DepthIcon(
            if (isFavorite) PhosphorIcons.Fill.HeartFilled else PhosphorIcons.Light.Heart,
            if (isFavorite) "Remove from favorites" else "Add to favorites",
            Modifier.bounceTap(onClick = onToggleFavorite).padding(4.dp),
            tint = if (isFavorite) FavoriteColor else Color.White,
            size = 26.dp,
        )
    }
}

/**
 * Two rows on a four-column grid, so the second row sits centered under the first:
 *   Shuffle   Queue   Add to playlist   Repeat
 *             Share   Mod
 */
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
                    PhosphorIcons.Light.Shuffle,
                    if (shuffleOn) "Shuffle on" else "Shuffle off",
                    Modifier.bounceTap(onClick = onShuffle).padding(8.dp),
                    tint = if (shuffleOn) accent else idle,
                    size = iconSize,
                )
            }
            GridCell { DepthIcon(PhosphorIcons.Light.Queue, "Queue", Modifier.bounceTap(onClick = onQueue).padding(8.dp), tint = idle, size = iconSize) }
            GridCell { DepthIcon(PhosphorIcons.Light.ListPlus, "Add to playlist", Modifier.bounceTap(onClick = onAddToPlaylist).padding(8.dp), tint = idle, size = iconSize) }
            GridCell {
                DepthIcon(
                    if (repeatMode == PlaybackRepeatMode.ONE) PhosphorIcons.Light.RepeatOnce else PhosphorIcons.Light.Repeat,
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
            GridCell { DepthIcon(PhosphorIcons.Light.ShareNetwork, "Share", Modifier.bounceTap(onClick = onShare).padding(8.dp), tint = idle, size = iconSize) }
            GridCell {
                DepthIcon(
                    PhosphorIcons.Light.Sparkle,
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

/**
 * How far the lyrics highlight runs ahead of the player's reported position. The position the player reports is
 * "what was handed to the audio pipeline", so what you hear trails it a little (output latency); a small lead
 * makes the words light up as they are sung instead of just after. Tune it (0 = off, try 150-400) if it still
 * feels early or late on your device.
 */
internal const val LyricsSyncOffsetMs = 250L

/** How far (per side) the cover row reaches past the screen's horizontal padding. Bigger = bigger cover. */
private val ArtworkExtraWidth = 14.dp

/** The previous / next buttons beside the cover: glyph size and the touch padding around it. Smaller = bigger cover. */
private val SideButtonSize = 30.dp
private val SideButtonPadding = 4.dp

private const val RESYNC_THRESHOLD_MS = 400L   // ignore tick differences smaller than this

/**
 * Smooth, continuous playback position. The player only reports its position about twice a second, so between
 * reports it is advanced locally against a monotonic clock. The clock runs only while sound is really coming out
 * ([live]): not while the next track is still resolving / buffering. A report re-anchors it only when it differs
 * from our estimate by more than [RESYNC_THRESHOLD_MS], on a track change, or when [live] flips, so normal ticks
 * never make the line step backwards.
 */
@Stable
internal class PlayerClock {
    private var basePosition = 0L
    private var baseTimestamp = SystemClock.elapsedRealtime()
    private var speed = 1f   // playback speed: the position advances this many ms per wall-clock ms

    /** Key of the track the position currently belongs to (null = none yet). */
    var anchoredKey by mutableStateOf<String?>(null)
        private set

    /** True while audio is actually playing. */
    var live by mutableStateOf(false)
        private set

    /** Current position in ms. Read it inside lambdas / draw scopes only. */
    var positionMs by mutableFloatStateOf(0f)
        private set

    /** Jump to [positionMs] right now (used after a seek, so the UI doesn't wait for the next tick). */
    fun anchor(positionMs: Long) {
        basePosition = positionMs
        baseTimestamp = SystemClock.elapsedRealtime()
        this.positionMs = positionMs.toFloat()
    }

    /** A mod changed the playback speed: re-anchor at the current position, then run at the new rate. */
    fun setSpeed(value: Float) {
        if (value == speed) return
        anchor(positionMs.toLong())
        speed = value
    }

    /** Listens to the player's own position reports. Never returns; cancel it by leaving the composition. */
    suspend fun follow(player: MusicPlayer) {
        var lastTrackKey: String? = null
        var wasLive = false
        combine(player.playbackState, player.awaitingAudio) { s, awaiting -> s to awaiting }.collect { (s, awaiting) ->
            val reported: Long
            val track: Track
            when (s) {
                // The player's live position wins over the last published one, which can be stale (e.g. right after a
                // gapless hand-over to the next track); -1 = unknown right now, use the published value.
                is PlaybackState.Playing -> { reported = player.currentPositionMs().takeIf { it >= 0L } ?: s.positionMs; track = s.track }
                is PlaybackState.Paused -> { reported = player.currentPositionMs().takeIf { it >= 0L } ?: s.positionMs; track = s.track }
                else -> {
                    // Loading / idle / error: the next track is not playing yet. Without this the clock kept the
                    // PREVIOUS track's end position, so the bar showed full while the new song loaded.
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
            if (key != lastTrackKey) anchoredKey = null   // until re-anchored below: the old position is not this track's
            val now = SystemClock.elapsedRealtime()
            val estimate = if (wasLive) basePosition + ((now - baseTimestamp) * speed).toLong() else basePosition
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

    /** Advances [positionMs] every frame. Only run it while [live]. */
    suspend fun tick(durationMs: Long) {
        while (true) {
            withFrameNanos {}
            val elapsed = (SystemClock.elapsedRealtime() - baseTimestamp) * speed
            positionMs = (basePosition + elapsed).toFloat().coerceAtMost(durationMs.toFloat())
        }
    }
}


private val BackgroundBlur = 24.dp   // how hard the cover is blurred behind the screen (acrylic wants it soft, not recognisable)

/**
 * The acrylic source: the blurred cover + a frosted tint + a faint white veil + a diagonal sheen, all in one
 * layer, so the lyrics card frosts exactly what is behind it instead of the raw, un-tinted art.
 */
@Composable
internal fun PlayerBackdrop(artUrl: String, animateChanges: Boolean = true) {
    Box(Modifier.fillMaxSize().acrylicSource()) {
        // The 1.3x overscan must be clipped to the screen: unclipped it spills past the top edge, and while the
        // screen slides that spill shows as an untinted band of blurred cover above the player.
        Box(Modifier.fillMaxSize().clipToBounds()) {
            AsyncImage(
                model = rememberFadeRequest(artUrl, durationMs = if (animateChanges) 600 else 0),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { scaleX = 1.3f; scaleY = 1.3f }   // overscan, so the blur never shows an edge
                    .then(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Modifier.blur(BackgroundBlur) else Modifier),
            )
        }
        // Frosted tint: the app's base color, translucent, denser toward the bottom where the controls are.
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
        // Luminosity veil: the milky depth that makes it read as acrylic and not just dimmed.
        Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.05f)))
        // Diagonal sheen, brightest top-left where light would hit the slab.
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

/** Image request that fades in when the URL changes (a new track) instead of popping in. */
@Composable
private fun rememberFadeRequest(url: String?, durationMs: Int = 600): ImageRequest {
    val context = LocalContext.current
    // The first image (already loaded elsewhere, e.g. in search) must appear instantly; only later
    // track changes fade. A fade from the dark base on entry shows up as a flicker.
    val firstUrl = remember { url }
    return remember(url, durationMs == 0) {
        ImageRequest.Builder(context).data(url)
            .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
            .crossfade(if (url == firstUrl || durationMs == 0) 0 else durationMs).build()
    }
}