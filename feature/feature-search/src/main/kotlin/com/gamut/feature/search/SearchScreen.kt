package com.gamut.feature.search

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import com.gamut.core.designsystem.AcrylicHost
import com.gamut.core.designsystem.LocalAcrylicState
import dev.chrisbanes.haze.HazeState
import com.gamut.core.designsystem.acrylicSource
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.font.FontWeight
import com.adamglin.phosphoricons.light.ArrowRight
import com.adamglin.phosphoricons.light.Play
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.graphicsLayer
import com.gamut.core.designsystem.TrackRowSkeleton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamut.core.designsystem.ArtImage
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.saveable.rememberSaveable
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Light
import com.adamglin.phosphoricons.light.MagnifyingGlass
import com.adamglin.phosphoricons.light.MusicNote
import com.adamglin.phosphoricons.light.User
import com.adamglin.phosphoricons.light.VinylRecord
import com.adamglin.phosphoricons.light.Waveform
import com.adamglin.phosphoricons.light.X
import com.gamut.core.designsystem.BottomBarItem
import com.gamut.core.designsystem.CoverTheme
import com.gamut.core.designsystem.GamutBottomBar
import com.gamut.core.designsystem.GamutBottomBarTabsHeight
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import com.gamut.core.designsystem.LocalCoverPalette
import com.gamut.core.designsystem.TransparentStatusBar
import com.gamut.core.designsystem.glass
import com.gamut.core.designsystem.ink
import com.gamut.core.designsystem.pressScale
import com.gamut.core.designsystem.squareArtUrl
import com.gamut.core.model.Track
import com.gamut.core.model.TrackSource

// The only expressive element is the flowing background, which takes its colors from the cover of
// the track that is playing. Everything on top of it is a neutral "ink" (white on dark covers, near-black
// on light ones, supplied by CoverTheme) plus that cover's accent in a few small places (cursor, focus
// ring, spinners). Surfaces are flat, translucent ink.

private val TextPrimary: Color
    @Composable @ReadOnlyComposable get() = ink()
private val TextSecondary: Color
    @Composable @ReadOnlyComposable get() = ink(0.6f)
private val TextTertiary: Color
    @Composable @ReadOnlyComposable get() = ink(0.4f)

private val PanelShape = RoundedCornerShape(12.dp)
private val TagShape = RoundedCornerShape(4.dp)
private val ArtShape = RoundedCornerShape(8.dp)

@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    resolvingIsrc: String?,
    /** The picked track plus the list it was picked from (the queue); empty = just this track. */
    onTrackSelected: (Track, List<Track>) -> Unit,
    onArtistSelected: (Long) -> Unit,
    onAlbumSelected: (DiscoverItem) -> Unit,
    /** The round "what's this song?" button next to the profile icon. */
    onShazam: () -> Unit,
    /** Three dots on a Discover song row. Defaults to a no-op until the actions sheet is wired in MainActivity. */
    onTrackMore: (Track) -> Unit = {},
    /** Height of the shared bottom bar + mini player strip (owned by HomeTabs in MainActivity). */
    barHeight: androidx.compose.ui.unit.Dp,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val discover by viewModel.discover.collectAsStateWithLifecycle()
    val artistHit by viewModel.artistHit.collectAsStateWithLifecycle()

    // The flowing background is drawn once, behind the NavHost (see MainActivity); this is only its palette.
    val palette = LocalCoverPalette.current
    // Status bar icons are set by HomeTabs (only while this layer is visible).

    CoverTheme(palette) {
        val accent = MaterialTheme.colorScheme.primary
        val density = LocalDensity.current
        val showingDiscover = state.tracks.isEmpty() && state.query.isBlank()
        // ONE list for both modes (see the comment where it is built).
        val listState = rememberLazyListState()
        var discoverTab by rememberSaveable { mutableIntStateOf(0) }
        // Title + search scroll with the content as the first list item (they are not pinned): scroll down and they
        // leave the screen, scroll back up to reach them. Only the bottom bar stays.
        val header: @Composable (Modifier) -> Unit = { m ->
            Column(m.fillMaxWidth().statusBarsPadding()) {
                    Row(Modifier.fillMaxWidth().padding(top = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Discover",
                            style = MaterialTheme.typography.displayLarge,
                            color = TextPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        Box(Modifier.size(44.dp).pressScale(onClick = onShazam).glass(CircleShape), contentAlignment = Alignment.Center) {
                            Icon(
    painterResource(com.gamut.core.designsystem.R.drawable.ic_shazam),
    "Identify a song",
    tint = ink(0.9f),
    modifier = Modifier.size(22.dp),
)
                        }
                        Spacer(Modifier.width(8.dp))
                        Box(Modifier.size(44.dp).pressScale(onClick = {}), contentAlignment = Alignment.Center) {
                            Icon(PhosphorIcons.Light.User, "Profile", tint = ink(0.85f), modifier = Modifier.size(24.dp))
                        }
                    }

                    Spacer(Modifier.height(22.dp))
                    SearchField(query = state.query, onQueryChange = viewModel::updateQuery, accent = accent)
                    Spacer(Modifier.height(14.dp))

                    state.error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp))
                    }
                    Spacer(Modifier.height(6.dp))
            }
        }
        // Lists run to the bottom of the screen and scroll under the bar; the mini player strip frosts what passes behind it.
        // Saved across navigation, so returning to this screen lays out the list correctly on the FIRST frame
        // (starting from 0 made the list re-layout once the bar had been measured: a visible pop).
        // The tab row is transparent, so nothing may scroll behind it: the list ends where the tabs begin. Only the
        // mini player strip (frosted) overlaps the list, so the list just needs room for the strip at its end.
        val tabsHeight = GamutBottomBarTabsHeight + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val stripHeight = maxOf(0.dp, barHeight - tabsHeight)

        // Reuse the app-wide acrylic state when there is one (MainActivity marks the flowing background as a source
        // on it), so the mini player frosts the very same adaptive background instead of a copy of it.
        AcrylicHost(Modifier.fillMaxSize(), state = LocalAcrylicState.current ?: remember { HazeState() }) {
            // ONE list for Discover and for results. The title + search field is its first item (key "header") in both modes,
            // so it stays composed when the query goes from empty to "a": the field keeps its focus, keyboard and cursor.
            // (Two different lists meant the field was rebuilt after the first letter and dropped focus.) Every item adds its
            // own 24dp side padding, so Discover's card rows can still run edge to edge.
            Box(Modifier.fillMaxSize().padding(bottom = tabsHeight).acrylicSource()) {
                val resultsAlpha by animateFloatAsState(if (state.isLoading) 0.45f else 1f, tween(150), label = "results-alpha")
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = stripHeight + 24.dp),
                ) {
                    item(key = "header") { header(Modifier.padding(horizontal = 24.dp)) }
                    if (showingDiscover) {
                        discoverItems(
                            state = discover,
                            selectedTab = discoverTab,
                            onSelectTab = { discoverTab = it },
                            onTrackClick = onTrackSelected,
                            onMoreClick = onTrackMore,
                            onArtistClick = onArtistSelected,
                            onAlbumClick = onAlbumSelected,
                        )
                    } else {
                        artistHit?.let { hit ->
                            item(key = "artist-hit") {
                                // Eases in: the card arrives after the songs (it waits for its verification), so it must not jolt the list.
                                val visible = remember { MutableTransitionState(false).apply { targetState = true } }
                                AnimatedVisibility(
                                    visibleState = visible,
                                    enter = fadeIn(tween(240)) + expandVertically(tween(240)),
                                ) {
                                    Box(Modifier.padding(horizontal = 16.dp)) { ArtistHit(hit, onArtistSelected) }
                                }
                            }
                        }
                        item(key = "results-title") {
                            Text(
                                "Results",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = TextPrimary.copy(alpha = 0.9f),
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                            )
                        }
                        if (state.isLoading && state.tracks.isEmpty()) {
                            // Same shimmering placeholders as the album and artist screens.
                            items(8, key = { "skeleton-$it" }) { TrackRowSkeleton(Modifier.padding(horizontal = 24.dp)) }
                        } else {
                            itemsIndexed(state.tracks, key = { i, t -> "${t.isrc}#$i" }) { _, track ->
                                // While a refined search is loading, the old results dim instead of vanishing.
                                Box(Modifier.padding(horizontal = 16.dp).graphicsLayer { alpha = resultsAlpha }) {
                                    TrackResultRow(track, resolvingIsrc == track.isrc, accent) { picked -> onTrackSelected(picked, state.tracks) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Flat translucent field, no edge at rest. While focused it gets a hairline ring in the cover's accent. */
@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit, accent: Color) {
    var focused by remember { mutableStateOf(false) }
    val focus by animateFloatAsState(if (focused) 1f else 0f, tween(200), label = "search-focus")
    Row(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .glass(PanelShape, fill = 0.07f, borderColor = accent.copy(alpha = 0.55f * focus))
            .padding(start = 18.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(PhosphorIcons.Light.MagnifyingGlass, null, tint = ink(0.55f), modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f).onFocusChanged { focused = it.isFocused },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = TextPrimary),
            cursorBrush = SolidColor(accent),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text("Artists, songs, albums", style = MaterialTheme.typography.bodyLarge, color = TextTertiary)
                    }
                    inner()
                }
            },
        )
        if (query.isNotEmpty()) {
            IconButton(onClick = { onQueryChange("") }) {
                Icon(PhosphorIcons.Light.X, "Clear", tint = ink(0.6f), modifier = Modifier.size(20.dp))
            }
        }
    }
}

private val CardShapeLarge = RoundedCornerShape(22.dp)

/**
 * YouTube mark (32x32 viewBox). Drawn black and always shown through `Icon(tint = ink())`, so it is dark on light
 * covers and white on dark ones: one glyph instead of a black and a white copy.
 */
private val YouTubeGlyph: ImageVector by lazy {
    ImageVector.Builder(
        name = "YouTube",
        defaultWidth = 32.dp,
        defaultHeight = 32.dp,
        viewportWidth = 32f,
        viewportHeight = 32f,
    ).addPath(
        pathData = PathParser().parsePathString(
            "M24.325 8.309s-2.655-.334-8.357-.334c-5.517 0-8.294.334-8.294.334A2.675 2.675 0 0 0 5 10.984v10.034" +
                "a2.675 2.675 0 0 0 2.674 2.676s2.582.332 8.294.332c5.709 0 8.357-.332 8.357-.332A2.673 2.673 0 0 0 27 21.018" +
                "V10.982a2.673 2.673 0 0 0-2.675-2.673zM13.061 19.975V12.03L20.195 16l-7.134 3.975z",
        ).toNodes(),
        fill = SolidColor(Color.Black),
    ).build()
}
private val RowShape = RoundedCornerShape(16.dp)
private val ThumbShape = RoundedCornerShape(10.dp)

/**
 * Shared touch behaviour for result rows and the artist card: a soft ink tint fades in on hover (mouse, stylus,
 * ChromeOS) or press, and a press squeezes the surface then springs it back with a small overshoot (the bounce).
 * [content] receives `active` (hovered or pressed) so children can react too (play glyph, arrow slide).
 */
@Composable
private fun InteractiveSurface(
    shape: Shape,
    pressedScale: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable RowScope.(active: Boolean) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "surface-scale",
    )
    val tint by animateColorAsState(
        targetValue = ink(if (pressed) 0.10f else if (hovered) 0.06f else 0f),
        animationSpec = tween(140),
        label = "surface-tint",
    )
    Row(
        // Scale is outermost so anything [modifier] draws (the glass card) squeezes with the content.
        Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .then(modifier)
            .clip(shape)
            .background(tint)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) { content(this, hovered || pressed) }
}

/**
 * The searched artist: round portrait, name in the display face, tap opens their page. No container (no glass, no
 * border): it sits on the background like the rows below it, only larger. The hover/press tint is the one moment a
 * shape appears, as touch feedback. The 16dp list gutter plus 8dp inner padding keeps the portrait aligned with the rows.
 */
@Composable
private fun ArtistHit(item: DiscoverItem, onClick: (Long) -> Unit) {
    InteractiveSurface(
        shape = CardShapeLarge,
        pressedScale = 0.97f,
        onClick = { item.artistId?.let(onClick) },
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
    ) { active ->
        ArtImage(
            url = item.imageUrl,
            modifier = Modifier
                .size(76.dp)
                .shadow(10.dp, CircleShape, clip = false, ambientColor = Color.Black.copy(alpha = 0.25f), spotColor = Color.Black.copy(alpha = 0.35f))
                .clip(CircleShape),
            contentDescription = item.title,
            fallback = { ArtworkFallbackIcon() },
        )
        Column(Modifier.weight(1f).padding(start = 18.dp, end = 8.dp)) {
            Text(item.title, style = MaterialTheme.typography.headlineMedium, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Artist", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        }
        // The arrow nudges forward on hover/press: a quiet "this opens".
        val slide by animateDpAsState(
            targetValue = if (active) 4.dp else 0.dp,
            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
            label = "artist-arrow",
        )
        Icon(
            PhosphorIcons.Light.ArrowRight,
            "Open artist",
            tint = ink(if (active) 0.9f else 0.5f),
            modifier = Modifier.offset(x = slide).size(22.dp),
        )
    }
}

@Composable
private fun TrackResultRow(track: Track, isResolving: Boolean, accent: Color, onTrackSelected: (Track) -> Unit) {
    InteractiveSurface(
        shape = RowShape,
        pressedScale = 0.97f,
        onClick = { onTrackSelected(track) },
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
    ) { active ->
        // YouTube thumbnails keep their own 16:9 shape (bar-free frame), everything else is square.
        val isYoutubeThumb = track.albumArtUrl?.let { "ytimg.com" in it || "img.youtube.com" in it } == true
        ArtImage(
            url = remember(track.albumArtUrl) { squareArtUrl(track.albumArtUrl, 256) },
            modifier = Modifier
                .size(width = if (isYoutubeThumb) 100.dp else 56.dp, height = 56.dp)
                .shadow(6.dp, ThumbShape, clip = false, ambientColor = Color.Black.copy(alpha = 0.20f), spotColor = Color.Black.copy(alpha = 0.30f))
                .clip(ThumbShape),
            fallback = { ArtworkFallbackIcon() },
        )
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(
                track.title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium),
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    track.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            if (isResolving) {
                Text("Checking a verified source", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
            }
        }
        if (isResolving) {
            CircularProgressIndicator(Modifier.size(16.dp).padding(end = 0.dp), color = accent, strokeWidth = 1.5.dp)
        } else {
            // A play glyph fades in on hover/press, so a row reads as tappable before it is tapped.
            val playAlpha by animateFloatAsState(if (active) 0.9f else 0f, tween(140), label = "row-play")
            Icon(
                PhosphorIcons.Light.Play,
                null,
                tint = ink(),
                modifier = Modifier.padding(end = 4.dp).size(18.dp).graphicsLayer { alpha = playAlpha },
            )
        }
        // Off-catalog finds (fan edits, bootlegs) carry the YouTube mark at the far right instead of a text tag.
        if (track.source == TrackSource.YOUTUBE) {
            Icon(
                YouTubeGlyph,
                "From YouTube",
                tint = ink(0.7f),
                modifier = Modifier.padding(start = 6.dp, end = 2.dp).size(26.dp),
            )
        }
    }
}

@Composable
private fun ArtworkFallbackIcon() {
    Icon(PhosphorIcons.Light.MusicNote, null, tint = ink(0.45f), modifier = Modifier.size(22.dp))
}