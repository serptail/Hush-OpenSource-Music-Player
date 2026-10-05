package com.gamut.feature.playerui

import android.os.Build
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Light
import com.adamglin.phosphoricons.light.CaretDown
import com.adamglin.phosphoricons.light.DotsThreeVertical
import com.adamglin.phosphoricons.light.Equalizer
import com.adamglin.phosphoricons.light.Heart
import com.adamglin.phosphoricons.light.Queue
import com.adamglin.phosphoricons.light.Repeat
import com.adamglin.phosphoricons.light.RepeatOnce
import com.adamglin.phosphoricons.light.Shuffle
import com.adamglin.phosphoricons.light.SkipBack
import com.adamglin.phosphoricons.light.SkipForward
import com.adamglin.phosphoricons.light.SpeakerHigh
import com.gamut.core.designsystem.AcrylicHost
import com.gamut.core.designsystem.CoverPalette
import com.gamut.core.designsystem.LocalCoverPalette
import com.gamut.core.designsystem.acrylicSource
import com.gamut.core.model.PlaybackState
import com.gamut.core.model.RepeatMode as PlaybackRepeatMode
import com.gamut.core.model.Track
import com.gamut.core.player.MusicPlayer
import com.gamut.feature.playerui.components.ArtworkWithRing
import com.gamut.feature.playerui.components.BouncingText
import com.gamut.feature.playerui.components.DepthIcon
import com.gamut.feature.playerui.components.LoadingVinyl
import com.gamut.feature.playerui.components.TimeRow
import com.gamut.feature.playerui.components.backdropArtUrl
import com.gamut.feature.playerui.components.bounceTap
import com.gamut.feature.playerui.components.rememberCoverAccent
import com.gamut.feature.playerui.components.rememberWaveform
import com.gamut.feature.playerui.components.tapTarget
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine

private val FavoriteColor = Color(0xFFFF4D6D)   // the heart when the track is a favorite

@Composable
fun PlayerScreen(
    player: MusicPlayer,
    onBack: () -> Unit = {},
    /** ISRCs of the favorited tracks; the heart is filled in when the current track is in this set. */
    favoriteIsrcs: Set<String> = emptySet(),
    onToggleFavorite: (Track) -> Unit = {},
    /** The "more" (three dots) button: e.g. open the add-to-playlist sheet for this track. */
    onMore: (Track) -> Unit = {},
) {
    val state by player.playbackState.collectAsState()
    // `state` changes every position tick. It is only read inside derivedStateOf, so this screen recomposes when
    // the track / play state really changes, not twice a second.
    val trackState by remember { derivedStateOf { (state as? PlaybackState.Playing)?.track ?: (state as? PlaybackState.Paused)?.track } }
    val track: Track? = trackState
    val isPlaying by remember { derivedStateOf { state is PlaybackState.Playing } }
    // What the cover shows as "playing". A seek (dragging the progress line) makes ExoPlayer report "not playing" for
    // a moment while it re-buffers, then "playing" again. Shown as is, that flickered the pause/play icon and made the
    // cover shrink and spring back. So a drop to "paused" only counts if it lasts; "playing" shows at once, and a
    // tap on the cover flips it immediately (see onTogglePlay).
    var shownPlaying by remember { mutableStateOf(isPlaying) }
    LaunchedEffect(isPlaying) {
        if (isPlaying) shownPlaying = true else { delay(700); shownPlaying = false }
    }
    val isLoadingState by remember { derivedStateOf { state is PlaybackState.Loading } }
    val loadProgress by player.loadProgress.collectAsState()
    val duration = (track?.durationMs ?: 0L).coerceAtLeast(1L)
    val shuffleOn by player.shuffleEnabled.collectAsState()
    val repeatMode by player.repeatMode.collectAsState()
    val queueState by player.queue.collectAsState()
    var showQueue by remember { mutableStateOf(false) }

    // Smooth progress clock (see PlayerClock). Everything below only reads it inside lambdas, so the per-frame
    // position invalidates the small pieces that draw it instead of recomposing the whole screen.
    val clock = remember { PlayerClock() }
    LaunchedEffect(player) { clock.follow(player) }
    LaunchedEffect(clock.live, duration) { if (clock.live) clock.tick(duration) }

    // While the progress line is dragged, the line and time label follow the finger; the real seek happens on release.
    var scrubFraction by remember { mutableStateOf<Float?>(null) }
    val progressProvider = remember(duration) { { scrubFraction ?: (clock.positionMs / duration).coerceIn(0f, 1f) } }
    val positionProvider = remember(duration) { { (progressProvider() * duration).toLong() } }
    val lyricsPosition = remember(clock) { { clock.positionMs.toLong() + LyricsSyncOffsetMs } }

    // Lyrics. Keyed on the track (ISRC if there is one, else title + artist: lyrics only need those), so a track
    // without an ISRC still gets lyrics, and the previous song's lines never show for a frame on a track change.
    val lyricsKey = track?.let { it.isrc.orEmpty().ifBlank { "${it.title}|${it.artist}" } }
    var lyricsState by remember(lyricsKey) {
        mutableStateOf<LyricsState>(track?.let { LyricsRepository.cached(it) } ?: LyricsState.Loading)
    }
    LaunchedEffect(lyricsKey) {
        val t = track ?: return@LaunchedEffect
        if (lyricsState is LyricsState.Loading) lyricsState = LyricsRepository.fetchLyrics(t)
    }
    var lyricsExpanded by remember { mutableStateOf(true) }

    val rawArt = track?.albumArtUrl
    val artUrl = remember(rawArt) { backdropArtUrl(rawArt) }   // blurred backdrop + accent
    val accent = rememberCoverAccent(artUrl)
    val waveform = rememberWaveform(track)

    // Base = the same flat fill as Discover / Library; with a track loaded, the acrylic backdrop goes on top.
    // The glass (lyrics card, sheets) takes its opaque backing color from LocalCoverPalette. That is the APP's cover
    // palette, which animates for 1.4 s on every track change and can be near-white. If the blur has no source for a
    // frame (back press, transition, re-record), the glass shows just that backing color: the flash. The player has
    // its own dark backdrop, so its glass is pinned to the neutral palette: the same color as the screen, and still.
    CompositionLocalProvider(LocalCoverPalette provides CoverPalette.Neutral) {
    AcrylicHost(Modifier.fillMaxSize().background(CoverPalette.Neutral.base)) {
        if (artUrl != null) PlayerBackdrop(artUrl)

        // Nothing playing (or still loading): just a black record turning in the middle of the flat background.
        if (track == null) {
            LoadingVinyl(if (isLoadingState) loadProgress else null, Modifier.align(Alignment.Center))
            return@AcrylicHost
        }

        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp)) {
            PlayerTopBar(onBack, onMore = { onMore(track) }, modifier = Modifier.fillMaxWidth())

            Spacer(Modifier.height(20.dp))
            TrackHeader(track, isFavorite = track.isrc in favoriteIsrcs, onToggleFavorite = { onToggleFavorite(track) })

            // Gap between the title row and the cover: bigger = cover + controls sit lower / more central.
            Spacer(Modifier.height(40.dp))

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                DepthIcon(PhosphorIcons.Light.SkipBack, "Previous", Modifier.bounceTap(onClick = player::skipPrevious).padding(8.dp), size = 28.dp)
                ArtworkWithRing(
                    modifier = Modifier.weight(1f),
                    url = rawArt,
                    progress = progressProvider,
                    onScrub = { scrubFraction = it },
                    isPlaying = shownPlaying,
                    enabled = true,
                    waveform = waveform,
                    onTogglePlay = {
                        if (shownPlaying) player.pause() else player.resume()
                        shownPlaying = !shownPlaying
                    },
                    onSeekFraction = { fraction ->
                        // Move the line right away instead of waiting for the player's next position tick.
                        val target = (fraction * duration).toLong()
                        clock.anchor(target)
                        player.seekTo(target)
                    },
                )
                DepthIcon(PhosphorIcons.Light.SkipForward, "Next", Modifier.bounceTap(onClick = player::skipNext).padding(8.dp), size = 28.dp)
            }

            Spacer(Modifier.height(14.dp))
            TimeRow(positionProvider, track.durationMs)

            Spacer(Modifier.height(10.dp))
            ModeRow(
                shuffleOn = shuffleOn,
                repeatMode = repeatMode,
                accent = accent,
                onShuffle = { player.setShuffle(!shuffleOn) },
                onRepeat = {
                    player.setRepeatMode(
                        when (repeatMode) {
                            PlaybackRepeatMode.OFF -> PlaybackRepeatMode.ALL
                            PlaybackRepeatMode.ALL -> PlaybackRepeatMode.ONE
                            PlaybackRepeatMode.ONE -> PlaybackRepeatMode.OFF
                        },
                    )
                },
                onQueue = { showQueue = true },
            )

            Spacer(Modifier.height(16.dp))

            // Lyrics: a collapsible card that takes whatever space is left below the controls.
            LyricsSection(
                lyricsState = lyricsState,
                positionMs = lyricsPosition,
                accent = accent,
                expanded = lyricsExpanded,
                onToggle = { lyricsExpanded = !lyricsExpanded },
                modifier = Modifier.fillMaxWidth().weight(1f).padding(bottom = 16.dp),
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
    }
}

@Composable
private fun PlayerTopBar(onBack: () -> Unit, onMore: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        DepthIcon(PhosphorIcons.Light.CaretDown, "Collapse", Modifier.tapTarget(onClick = onBack).padding(8.dp))
        Spacer(Modifier.weight(1f))
        DepthIcon(PhosphorIcons.Light.SpeakerHigh, "Volume", Modifier.tapTarget(onClick = {}).padding(8.dp))
        DepthIcon(PhosphorIcons.Light.Equalizer, "Equalizer", Modifier.tapTarget(onClick = {}).padding(8.dp))
        DepthIcon(PhosphorIcons.Light.DotsThreeVertical, "More", Modifier.tapTarget(onClick = onMore).padding(8.dp))
    }
}

@Composable
private fun TrackHeader(track: Track, isFavorite: Boolean, onToggleFavorite: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            BouncingText(
                text = track.title,
                style = MaterialTheme.typography.headlineLarge.copy(letterSpacing = (-0.3).sp),
                color = Color.White,
            )
            Spacer(Modifier.height(2.dp))
            BouncingText(
                text = track.artist,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.65f),
            )
        }
        DepthIcon(
            PhosphorIcons.Light.Heart,
            if (isFavorite) "Remove from favorites" else "Add to favorites",
            Modifier.bounceTap(onClick = onToggleFavorite).padding(4.dp),
            tint = if (isFavorite) FavoriteColor else Color.White,
            size = 26.dp,
        )
    }
}

@Composable
private fun ModeRow(
    shuffleOn: Boolean,
    repeatMode: PlaybackRepeatMode,
    accent: Color,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    onQueue: () -> Unit,
) {
    val idle = Color.White.copy(alpha = 0.85f)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        DepthIcon(
            PhosphorIcons.Light.Shuffle,
            if (shuffleOn) "Shuffle on" else "Shuffle off",
            Modifier.bounceTap(onClick = onShuffle).padding(8.dp),
            tint = if (shuffleOn) accent else idle,
            size = 22.dp,
        )
        DepthIcon(
            if (repeatMode == PlaybackRepeatMode.ONE) PhosphorIcons.Light.RepeatOnce else PhosphorIcons.Light.Repeat,
            when (repeatMode) {
                PlaybackRepeatMode.OFF -> "Repeat off"
                PlaybackRepeatMode.ALL -> "Repeat all"
                PlaybackRepeatMode.ONE -> "Repeat one"
            },
            Modifier.bounceTap(onClick = onRepeat).padding(8.dp),
            tint = if (repeatMode != PlaybackRepeatMode.OFF) accent else idle,
            size = 22.dp,
        )
        DepthIcon(PhosphorIcons.Light.Queue, "Queue", Modifier.bounceTap(onClick = onQueue).padding(8.dp), tint = idle, size = 22.dp)
    }
}


/**
 * How far the lyrics highlight runs ahead of the player's reported position. The position the player reports is
 * "what was handed to the audio pipeline", so what you hear trails it a little (output latency); a small lead
 * makes the words light up as they are sung instead of just after. Tune it (0 = off, try 150-400) if it still
 * feels early or late on your device.
 */
internal const val LyricsSyncOffsetMs = 250L

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

    /** Listens to the player's own position reports. Never returns; cancel it by leaving the composition. */
    suspend fun follow(player: MusicPlayer) {
        var lastTrackKey: String? = null
        var wasLive = false
        combine(player.playbackState, player.awaitingAudio) { s, awaiting -> s to awaiting }.collect { (s, awaiting) ->
            val reported: Long
            val track: Track
            when (s) {
                is PlaybackState.Playing -> { reported = s.positionMs; track = s.track }
                is PlaybackState.Paused -> { reported = s.positionMs; track = s.track }
                else -> return@collect
            }
            val key = track.isrc.orEmpty() + "|" + track.title
            val nowLive = s is PlaybackState.Playing && !awaiting
            val now = SystemClock.elapsedRealtime()
            val estimate = if (wasLive) basePosition + (now - baseTimestamp) else basePosition
            if (!nowLive || key != lastTrackKey || nowLive != wasLive || abs(reported - estimate) > RESYNC_THRESHOLD_MS) {
                anchor(reported)
            }
            lastTrackKey = key
            wasLive = nowLive
            live = nowLive
        }
    }

    /** Advances [positionMs] every frame. Only run it while [live]. */
    suspend fun tick(durationMs: Long) {
        while (true) {
            withFrameNanos {}
            val elapsed = SystemClock.elapsedRealtime() - baseTimestamp
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
internal fun PlayerBackdrop(artUrl: String) {
    Box(Modifier.fillMaxSize().acrylicSource()) {
        AsyncImage(
            model = rememberFadeRequest(artUrl),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { scaleX = 1.3f; scaleY = 1.3f }   // overscan, so the blur never shows an edge
                .then(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Modifier.blur(BackgroundBlur) else Modifier),
        )
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
    return remember(url) {
        ImageRequest.Builder(context).data(url).crossfade(if (url == firstUrl) 0 else durationMs).build()
    }
}