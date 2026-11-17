package com.gamut.feature.playerui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Bold
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.bold.CaretDown
import com.adamglin.phosphoricons.bold.DotsThreeVertical
import com.adamglin.phosphoricons.bold.MusicNote
import com.adamglin.phosphoricons.regular.ListPlus
import com.adamglin.phosphoricons.regular.Queue
import com.adamglin.phosphoricons.regular.Repeat
import com.adamglin.phosphoricons.regular.RepeatOnce
import com.adamglin.phosphoricons.regular.Shuffle
import com.adamglin.phosphoricons.regular.Sparkle
import com.gamut.core.designsystem.AdaptiveLayout
import com.gamut.core.designsystem.PlayerStyle
import com.gamut.core.designsystem.adaptiveGutter
import com.gamut.core.designsystem.squareArtUrl
import com.gamut.core.model.Track
import com.gamut.feature.playerui.components.DepthIcon
import com.gamut.feature.playerui.components.PlayerSpinner
import com.gamut.feature.playerui.components.TimeRow
import com.gamut.feature.playerui.components.VolumeHud
import com.gamut.feature.playerui.components.bounceTap
import com.gamut.feature.playerui.components.volumeHudAnchor
import com.gamut.feature.playerui.components.volumeHudHeaderAnchor
import com.gamut.feature.playerui.components.youtubeThumbCandidates
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.gamut.core.model.RepeatMode as PlaybackRepeatMode

private const val COVER_FADE_FRACTION = 0.34f

private fun smootherStep(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    return x * x * x * (x * (x * 6f - 15f) + 10f)
}

private const val FADE_CURVE_STOPS = 16

private val LyricsMotion = tween<Float>(420, easing = FastOutSlowInEasing)

@Composable
internal fun StyledPlayerLayer(
    style: PlayerStyle,
    track: Track?,
    rawArt: String?,
    blurArt: String?,
    active: Boolean,
    isPlaying: Boolean,
    live: Boolean,
    loading: Boolean,
    progress: () -> Float,
    positionMs: () -> Long,
    durationMs: Long,
    onScrub: (Float?) -> Unit,
    onSeekFraction: (Float) -> Unit,
    onTogglePlay: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onArtist: (String) -> Unit,
    lyricsOn: Boolean,
    lyricsState: LyricsState,
    playhead: FlowPlayhead,
    lyricsOpen: Boolean,
    onLyricsOpenChange: (Boolean) -> Unit,
    onSeekToMs: (Long) -> Unit,
    onRetryLyrics: () -> Unit,
    shuffleOn: Boolean,
    repeatMode: PlaybackRepeatMode,
    accent: Color,
    modActive: Boolean,
    onShuffle: () -> Unit,
    onQueue: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onRepeat: () -> Unit,
    onMod: () -> Unit,
    onMore: () -> Unit,
    onBack: () -> Unit,
    volumeHud: VolumeHud,
    coverFocus: FocusRequester,
    extras: @Composable () -> Unit,
    highlight: Color,
) {
    val mesh = rememberArtworkMesh(blurArt)
    val seam = remember { MeshSeam() }
    val density = LocalDensity.current
    val gutter = adaptiveGutter(AdaptiveLayout.PlayerMaxWidth)
    val edge = 24.dp + gutter

    val lyricsT by animateFloatAsState(if (lyricsOpen) 1f else 0f, LyricsMotion, label = "styledLyricsT")

    val scroll = rememberScrollState()
    val pull = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(lyricsOpen) { if (lyricsOpen) scroll.animateScrollTo(0) }

    var lyricsBackdrop by remember { mutableStateOf(lyricsOpen) }
    LaunchedEffect(lyricsOpen) {
        if (lyricsOpen) {
            lyricsBackdrop = true
        } else {
            delay(460)
            lyricsBackdrop = false
        }
    }

    val trackKey = track?.let { it.isrc.orEmpty() + "|" + it.title }.orEmpty()
    val loaded = lyricsState as? LyricsState.Loaded
    val flowLines = remember(loaded) { loaded?.lines?.toFlowLines(loaded.isSynced) }

    BoxWithConstraints(Modifier.fillMaxSize().graphicsLayer { translationY = pull.floatValue }.then(seam.onHost)) {
        val width = maxWidth
        val height = maxHeight
        val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val bannerHeight = (width + statusTop + 40.dp).coerceAtMost(height * 0.55f)
        val bannerPx = with(density) { bannerHeight.toPx() }
        val cover = style == PlayerStyle.Cover

        Box(Modifier.fillMaxSize().graphicsLayer { alpha = 1f - lyricsT }) {
            ArtworkMeshBackdrop(mesh = mesh, seam = if (cover) ({ bannerPx - scroll.value }) else ({ seam.px - scroll.value }))
        }
        if (lyricsBackdrop && blurArt != null) {
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = lyricsT }) {
                com.gamut.feature.playerui
                    .PlayerBackdrop(blurArt, animateChanges = active)
            }
        }

        if (cover) {
            CoverBanner(
                url = rawArt,
                height = bannerHeight,
                modifier =
                    Modifier
                        .align(Alignment.TopCenter)
                        .volumeHudAnchor(volumeHud)
                        .graphicsLayer {
                            alpha = 1f - lyricsT
                            translationY = -lyricsT * 28.dp.toPx() - scroll.value
                        },
            )
        }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll, enabled = !lyricsOpen),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .height(height)
                    .statusBarsPadding()
                    .navigationBarsPadding(),
            ) {
                Crossfade(targetState = lyricsOpen, animationSpec = tween(300), label = "styledTopBar") { open ->
                    if (open) {
                        LyricsHeader(
                            artUrl = blurArt,
                            title = track?.title.orEmpty(),
                            artist = track?.artist.orEmpty(),
                            onClose = { onLyricsOpenChange(false) },
                            onMore = onMore,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp + gutter).padding(top = 8.dp),
                        )
                    } else {
                        PullPill(pull = pull, onDismiss = onBack, modifier = Modifier.fillMaxWidth())
                    }
                }

                Crossfade(
                    targetState = lyricsOpen,
                    animationSpec = tween(420, easing = FastOutSlowInEasing),
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    label = "styledMiddle",
                ) { open ->
                    if (open) {
                        LyricsWindow(
                            lyricsState = lyricsState,
                            flowLines = flowLines,
                            trackKey = trackKey,
                            playhead = playhead,
                            isPlaying = live,
                            edge = edge,
                            onSeekToMs = onSeekToMs,
                            onRetry = onRetryLyrics,
                            highlight = highlight,
                        )
                    } else if (cover) {
                        Column(
                            Modifier.fillMaxSize().padding(horizontal = edge),
                            verticalArrangement = Arrangement.Bottom,
                        ) {
                            Box(Modifier.volumeHudHeaderAnchor(volumeHud)) {
                                TrackHeader(
                                    title = track?.title.orEmpty(),
                                    artist = track?.artist.orEmpty(),
                                    albumTitle = track?.album,
                                    onArtist = onArtist,
                                    isFavorite = isFavorite,
                                    onToggleFavorite = onToggleFavorite,
                                    onMore = onMore,
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                        }
                    } else {
                        BoxWithConstraints(Modifier.fillMaxSize()) {
                            val credits = 96.dp
                            val side = minOf(maxWidth - edge * 2, MeshSleeveMaxWidth, maxHeight - credits).coerceAtLeast(120.dp)
                            Column(
                                Modifier.fillMaxSize(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                MeshSleeve(
                                    url = rawArt,
                                    isPlaying = isPlaying,
                                    modifier = Modifier.size(side).then(seam.onSleeve).volumeHudAnchor(volumeHud),
                                )
                                Spacer(Modifier.height(20.dp))
                                Box(Modifier.padding(horizontal = edge).volumeHudHeaderAnchor(volumeHud)) {
                                    TrackHeader(
                                        title = track?.title.orEmpty(),
                                        artist = track?.artist.orEmpty(),
                                        albumTitle = track?.album,
                                        onArtist = onArtist,
                                        isFavorite = isFavorite,
                                        onToggleFavorite = onToggleFavorite,
                                        onMore = onMore,
                                    )
                                }
                            }
                        }
                    }
                }

                Column(Modifier.fillMaxWidth().padding(horizontal = edge)) {
                    Box(
                        Modifier.fillMaxWidth().height(56.dp).graphicsLayer { alpha = 1f - lyricsT },
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (lyricsOn && !lyricsOpen) {
                            when {
                                flowLines != null && loaded?.isSynced == true -> {
                                    FlowLyricStrip(
                                        lines = flowLines,
                                        trackKey = trackKey,
                                        playhead = playhead,
                                        isPlaying = live,
                                        durationMs = durationMs,
                                        onClick = { onLyricsOpenChange(true) },
                                        maxLines = 2,
                                        highlight = highlight,
                                    )
                                }

                                flowLines != null -> {
                                    Text(
                                        text = "Lyrics",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = Color.White.copy(alpha = 0.8f),
                                        modifier =
                                            Modifier
                                                .clip(
                                                    RoundedCornerShape(8.dp),
                                                ).clickable { onLyricsOpenChange(true) }
                                                .padding(vertical = 4.dp),
                                    )
                                }
                            }
                        }
                    }
                    MeshScrubber(progress = progress, onScrub = onScrub, onSeekFraction = onSeekFraction)
                    Box(Modifier.padding(top = 2.dp)) { TimeRow(positionMs, durationMs) }
                    MeshTransport(
                        isPlaying = isPlaying,
                        loading = loading,
                        onPrevious = onPrevious,
                        onTogglePlay = onTogglePlay,
                        onNext = onNext,
                        modifier = Modifier.padding(top = 4.dp),
                        playModifier = Modifier.focusRequester(coverFocus),
                    )
                    val idle = Color.White.copy(alpha = 0.85f)
                    Row(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Cell {
                            DepthIcon(
                                PhosphorIcons.Regular.Shuffle,
                                if (shuffleOn) "Shuffle on" else "Shuffle off",
                                Modifier.bounceTap(onClick = onShuffle).padding(8.dp),
                                tint = if (shuffleOn) accent else idle,
                            )
                        }
                        Cell {
                            DepthIcon(
                                PhosphorIcons.Regular.Queue,
                                "Queue",
                                Modifier.bounceTap(onClick = onQueue).padding(8.dp),
                                tint = idle,
                            )
                        }
                        Cell {
                            DepthIcon(
                                PhosphorIcons.Regular.ListPlus,
                                "Add to playlist",
                                Modifier.bounceTap(onClick = onAddToPlaylist).padding(8.dp),
                                tint = idle,
                            )
                        }
                        Cell {
                            DepthIcon(
                                if (repeatMode ==
                                    PlaybackRepeatMode.ONE
                                ) {
                                    PhosphorIcons.Regular.RepeatOnce
                                } else {
                                    PhosphorIcons.Regular.Repeat
                                },
                                when (repeatMode) {
                                    PlaybackRepeatMode.OFF -> "Repeat off"
                                    PlaybackRepeatMode.ALL -> "Repeat all"
                                    PlaybackRepeatMode.ONE -> "Repeat one"
                                },
                                Modifier.bounceTap(onClick = onRepeat).padding(8.dp),
                                tint = if (repeatMode != PlaybackRepeatMode.OFF) accent else idle,
                            )
                        }
                        Cell {
                            DepthIcon(
                                PhosphorIcons.Regular.Sparkle,
                                if (modActive) "Mod on" else "Mod",
                                Modifier.bounceTap(onClick = onMod).padding(8.dp),
                                tint = if (modActive) accent else idle,
                            )
                        }
                    }
                }
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = edge)) {
                Spacer(Modifier.height(8.dp))
                extras()
                Spacer(Modifier.height(32.dp).navigationBarsPadding())
            }
        }
    }
}

@Composable
private fun PullPill(
    pull: MutableFloatState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dismiss by rememberUpdatedState(onDismiss)
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val threshold = with(density) { 96.dp.toPx() }
    val limit = with(density) { 260.dp.toPx() }
    var settle by remember { mutableStateOf<Job?>(null) }

    fun release(fast: Boolean) {
        settle?.cancel()
        settle =
            scope.launch {
                animate(
                    initialValue = pull.floatValue,
                    targetValue = 0f,
                    animationSpec = if (fast) tween(260) else spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMediumLow),
                ) { value, _ -> pull.floatValue = value }
            }
    }
    Box(
        modifier
            .height(84.dp)
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { settle?.cancel() },
                    onDragEnd = {
                        val leave = pull.floatValue >= threshold
                        if (leave) dismiss()
                        release(fast = leave)
                    },
                    onDragCancel = { release(fast = false) },
                    onVerticalDrag = { change, dy ->
                        change.consume()
                        val resistance = 1f - 0.65f * (pull.floatValue / limit).coerceIn(0f, 1f)
                        pull.floatValue = (pull.floatValue + dy * resistance).coerceIn(0f, limit)
                    },
                )
            }.clickable(onClick = { dismiss() }),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            Modifier
                .padding(top = 14.dp)
                .graphicsLayer {
                    val t = (pull.floatValue / threshold).coerceIn(0f, 1.2f)
                    scaleX = 1f + 0.5f * t
                    alpha = 0.75f + 0.25f * t.coerceAtMost(1f)
                }.size(width = 44.dp, height = 5.dp)
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}

@Composable
private fun RowScope.Cell(content: @Composable () -> Unit) {
    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun CoverBanner(
    url: String?,
    height: Dp,
    modifier: Modifier = Modifier,
) {
    val model =
        remember(url) {
            url?.let { youtubeThumbCandidates(it)?.firstOrNull() ?: squareArtUrl(it, 1200) ?: it }
        }
    val fade =
        remember {
            Array(FADE_CURVE_STOPS + 1) { i ->
                val t = i / FADE_CURVE_STOPS.toFloat()
                t to Color.Black.copy(alpha = 1f - smootherStep(t))
            }
        }
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(
                    brush =
                        Brush.verticalGradient(
                            colorStops = fade,
                            startY = size.height * (1f - COVER_FADE_FRACTION),
                            endY = size.height,
                        ),
                    blendMode = BlendMode.DstIn,
                )
            },
    ) {
        if (model != null) {
            AsyncImage(
                model = model,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.38f), Color.Transparent))),
            )
        }
    }
}

@Composable
private fun LyricsHeader(
    artUrl: String?,
    title: String,
    artist: String,
    onClose: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        DepthIcon(PhosphorIcons.Bold.CaretDown, "Close lyrics", Modifier.bounceTap(onClick = onClose).padding(8.dp))
        Spacer(Modifier.size(8.dp))
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.12f))) {
            if (artUrl != null) {
                AsyncImage(model = artUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = Color.White,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                artist,
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DepthIcon(PhosphorIcons.Bold.DotsThreeVertical, "More", Modifier.bounceTap(onClick = onMore).padding(8.dp))
    }
}

@Composable
private fun LyricsWindow(
    lyricsState: LyricsState,
    flowLines: List<FlowLine>?,
    trackKey: String,
    playhead: FlowPlayhead,
    isPlaying: Boolean,
    edge: Dp,
    onSeekToMs: (Long) -> Unit,
    onRetry: () -> Unit,
    highlight: Color,
) {
    Box(Modifier.fillMaxSize()) {
        when (lyricsState) {
            is LyricsState.Loading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    PlayerSpinner(Modifier.size(30.dp), Color.White.copy(alpha = 0.8f))
                }
            }

            is LyricsState.Loaded -> {
                if (flowLines != null) {
                    FlowLyricsPanel(
                        lines = flowLines,
                        isSynced = lyricsState.isSynced,
                        trackKey = trackKey,
                        playhead = playhead,
                        isPlaying = isPlaying,
                        onSeekToLine = onSeekToMs,
                        modifier = Modifier.fillMaxSize(),
                        topPadding = 24.dp,
                        gutter = edge,
                        highlight = highlight,
                    )
                }
            }

            is LyricsState.NotAvailable -> {
                LyricsEmptyState("${lyricsState.reason}\nTap to retry", onRetry)
            }

            is LyricsState.Error -> {
                LyricsEmptyState("${lyricsState.message}\nRetrying\u2026 tap to retry now", onRetry)
            }
        }
    }
}
