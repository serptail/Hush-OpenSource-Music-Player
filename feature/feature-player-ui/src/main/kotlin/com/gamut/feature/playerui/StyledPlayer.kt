package com.gamut.feature.playerui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.gamut.core.designsystem.TablerIcons
import com.gamut.core.designsystem.AdaptiveLayout
import com.gamut.core.designsystem.PlayerStyle
import com.gamut.core.designsystem.adaptiveGutter
import com.gamut.core.designsystem.bounceClickable
import com.gamut.core.designsystem.dockArtHide
import com.gamut.core.designsystem.dockArtReport
import com.gamut.core.designsystem.squareArtUrl
import com.gamut.core.designsystem.thumbnailBorder
import com.gamut.core.model.Track
import com.gamut.feature.playerui.components.DepthIcon
import com.gamut.feature.playerui.components.PlayerSpinner
import com.gamut.feature.playerui.components.TimeRow
import com.gamut.feature.playerui.components.VolumeHud
import com.gamut.feature.playerui.components.bounceTap
import com.gamut.feature.playerui.components.volumeHudAnchor
import com.gamut.feature.playerui.components.volumeHudHeaderAnchor
import com.gamut.feature.playerui.components.youtubeThumbCandidates
import kotlinx.coroutines.delay
import com.gamut.core.model.RepeatMode as PlaybackRepeatMode

// The cover art dissolves over this share of its height, ending where the title starts. The long
// run lets it melt through the blur levels in StyledBackdrop instead of ending at a hard edge.
private const val COVER_FADE_FRACTION = 0.42f
private val COVER_FADE_MIN = 96.dp

// Auris's playerInkGlow over white ink: a soft dark halo behind chrome text so it keeps an edge
// where it crosses a bright patch of the backdrop.
// 1f = original strength; lower = fainter / more transparent blurred background
private const val BACKDROP_STRENGTH = 1f

private val HeaderInkShadow = Shadow(color = Color.Black.copy(alpha = 0.30f), blurRadius = 9f)

private fun smootherStep(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    return x * x * x * (x * (x * 6f - 15f) + 10f)
}

private const val FADE_CURVE_STOPS = 16

private val StyledTitleStyle: TextStyle
    @Composable get() = MaterialTheme.typography.titleLarge.copy(letterSpacing = (-0.2).sp)

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
    videoSupported: Boolean,
    videoOn: Boolean,
    onToggleVideo: () -> Unit,
    onBack: () -> Unit,
    volumeHud: VolumeHud,
    coverFocus: FocusRequester,
    extras: @Composable () -> Unit,
    highlight: Color,
) {
    val bannerModel = remember(rawArt) { coverBannerModel(rawArt) }
    // The cover style blurs the exact image the banner shows so the two line up pixel for pixel.
    val backdropBlur = rememberArtworkBlurSet(if (style == PlayerStyle.Cover) bannerModel else blurArt, prepare = true)
    val lyricsBackdropImage = rememberFullArtworkBlurImage(blurArt, prepare = active)
    val density = LocalDensity.current
    val gutter = adaptiveGutter(AdaptiveLayout.PlayerMaxWidth)
    val edge = 24.dp + gutter

    val lyricsT by animateFloatAsState(if (lyricsOpen) 1f else 0f, LyricsMotion, label = "styledLyricsT")

    val scroll = rememberScrollState()
    var layerTop by remember { mutableFloatStateOf(0f) }
    var titleTop by remember { mutableFloatStateOf(0f) }
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

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { layerTop = it.positionInRoot().y },
    ) {
        val width = maxWidth
        val height = maxHeight
        val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val cover = style == PlayerStyle.Cover
        val fallbackBanner = (width + statusTop + 40.dp).coerceAtMost(height * 0.55f)
        val bannerHeight =
            if (cover && titleTop > 0f) {
                with(density) { titleTop.toDp() }.coerceIn(120.dp, height * 0.7f)
            } else {
                fallbackBanner
            }
        val bannerPx = with(density) { bannerHeight.toPx() }
        val coverFade = (bannerHeight * COVER_FADE_FRACTION).coerceAtLeast(COVER_FADE_MIN).coerceAtMost(bannerHeight / 2)
        val coverFadePx = with(density) { coverFade.toPx() }
        val screenPx = with(density) { height.toPx() }
        val scrollPx = remember(scroll) { { scroll.value.toFloat() } }

        Box(Modifier.fillMaxSize().graphicsLayer { alpha = (1f - lyricsT) * BACKDROP_STRENGTH }) {
            StyledBackdrop(
                blur = backdropBlur,
                coverGeometry = cover,
                artPx = if (cover) bannerPx else screenPx,
                fadePx = if (cover) coverFadePx else screenPx,
                scroll = scrollPx,
            )
        }
        if (lyricsBackdrop && blurArt != null) {
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = lyricsT }) {
                FullArtworkBlurBackdrop(image = lyricsBackdropImage)
            }
        }

        if (cover) {
            CoverBanner(
                url = rawArt,
                height = bannerHeight,
                fade = coverFade,
                dockEnabled = !lyricsOpen,
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
                        PlayerPill(onDismiss = onBack, modifier = Modifier.fillMaxWidth())
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
                            Box(
                                Modifier
                                    .volumeHudHeaderAnchor(volumeHud)
                                    .onGloballyPositioned {
                                        titleTop = it.positionInRoot().y - layerTop + scroll.value
                                    },
                            ) {
                                TrackHeader(
                                    title = track?.title.orEmpty(),
                                    artist = track?.artist.orEmpty(),
                                    albumTitle = track?.album,
                                    explicit = track?.isExplicit,
                                    onArtist = onArtist,
                                    isFavorite = isFavorite,
                                    onToggleFavorite = onToggleFavorite,
                                    onMore = onMore,
                                    titleStyle = StyledTitleStyle,
                                    videoSupported = videoSupported,
                                    videoOn = videoOn,
                                    onToggleVideo = onToggleVideo,
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
                                    modifier = Modifier.size(side).volumeHudAnchor(volumeHud),
                                )
                                Spacer(Modifier.height(20.dp))
                                Box(Modifier.padding(horizontal = edge).volumeHudHeaderAnchor(volumeHud)) {
                                    TrackHeader(
                                        title = track?.title.orEmpty(),
                                        artist = track?.artist.orEmpty(),
                                        albumTitle = track?.album,
                                        explicit = track?.isExplicit,
                                        onArtist = onArtist,
                                        isFavorite = isFavorite,
                                        onToggleFavorite = onToggleFavorite,
                                        onMore = onMore,
                                        titleStyle = StyledTitleStyle,
                                        videoSupported = videoSupported,
                                        videoOn = videoOn,
                                        onToggleVideo = onToggleVideo,
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
                                                ).bounceClickable { onLyricsOpenChange(true) }
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
                                TablerIcons.Shuffle,
                                if (shuffleOn) "Shuffle on" else "Shuffle off",
                                Modifier.bounceTap(onClick = onShuffle).padding(8.dp),
                                tint = if (shuffleOn) accent else idle,
                                size = ControlIconSize,
                            )
                        }
                        Cell {
                            DepthIcon(
                                TablerIcons.Playlist,
                                "Queue",
                                Modifier.bounceTap(onClick = onQueue).padding(8.dp),
                                tint = idle,
                                size = ControlIconSize,
                            )
                        }
                        Cell {
                            DepthIcon(
                                TablerIcons.ListPlus,
                                "Add to playlist",
                                Modifier.bounceTap(onClick = onAddToPlaylist).padding(8.dp),
                                tint = idle,
                                size = ControlIconSize,
                            )
                        }
                        Cell {
                            DepthIcon(
                                if (repeatMode ==
                                    PlaybackRepeatMode.ONE
                                ) {
                                    TablerIcons.RepeatOne
                                } else {
                                    TablerIcons.Repeat
                                },
                                when (repeatMode) {
                                    PlaybackRepeatMode.OFF -> "Repeat off"
                                    PlaybackRepeatMode.ALL -> "Repeat all"
                                    PlaybackRepeatMode.ONE -> "Repeat one"
                                },
                                Modifier.bounceTap(onClick = onRepeat).padding(8.dp),
                                tint = if (repeatMode != PlaybackRepeatMode.OFF) accent else idle,
                                size = ControlIconSize,
                            )
                        }
                        Cell {
                            DepthIcon(
                                TablerIcons.MagicWand,
                                if (modActive) "Mod on" else "Mod",
                                Modifier.bounceTap(onClick = onMod).padding(8.dp),
                                tint = if (modActive) accent else idle,
                                size = ControlIconSize,
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
private fun RowScope.Cell(content: @Composable () -> Unit) {
    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { content() }
}

private fun coverBannerModel(url: String?): String? =
    url?.let { youtubeThumbCandidates(it)?.firstOrNull() ?: squareArtUrl(it, 1200) ?: it }

@Composable
private fun CoverBanner(
    url: String?,
    height: Dp,
    fade: Dp,
    modifier: Modifier = Modifier,
    dockEnabled: Boolean = true,
) {
    val model = remember(url) { coverBannerModel(url) }
    val curve =
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
            .dockArtHide(dockEnabled)
            .dockArtReport(model = model, corner = 0.dp, enabled = dockEnabled, fadeFraction = (fade / height).coerceIn(0f, 1f), scrim = true)
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(
                    brush =
                        Brush.verticalGradient(
                            colorStops = curve,
                            startY = size.height - fade.toPx(),
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
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.26f), Color.Transparent))),
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
        DepthIcon(TablerIcons.ChevronDown, "Close lyrics", Modifier.bounceTap(onClick = onClose).padding(8.dp))
        Spacer(Modifier.size(8.dp))
        Box(
            Modifier
                .size(44.dp)
                .then(if (artUrl != null) Modifier.shadow(10.dp, RoundedCornerShape(8.dp), clip = false) else Modifier)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White.copy(alpha = 0.12f))
                .thumbnailBorder(RoundedCornerShape(8.dp)),
        ) {
            if (artUrl != null) {
                AsyncImage(model = artUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = Color.White,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, shadow = HeaderInkShadow),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                artist,
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodySmall.copy(shadow = HeaderInkShadow),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DepthIcon(TablerIcons.MoreVertical, "More", Modifier.bounceTap(onClick = onMore).padding(8.dp))
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