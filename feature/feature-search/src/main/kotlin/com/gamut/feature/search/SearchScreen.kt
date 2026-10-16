package com.gamut.feature.search

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.Bold
import com.adamglin.phosphoricons.fill.CheckCircle
import com.adamglin.phosphoricons.bold.ArrowRight
import com.adamglin.phosphoricons.bold.ClockCounterClockwise
import com.adamglin.phosphoricons.bold.DotsThreeVertical
import com.adamglin.phosphoricons.bold.MagnifyingGlass
import com.adamglin.phosphoricons.bold.MusicNote
import com.adamglin.phosphoricons.bold.Play
import com.adamglin.phosphoricons.bold.PlusCircle
import com.adamglin.phosphoricons.bold.User
import com.adamglin.phosphoricons.bold.X
import com.gamut.core.designsystem.adaptiveGutter
import com.gamut.core.designsystem.AcrylicHost
import com.gamut.core.designsystem.ArtImage
import com.gamut.core.designsystem.CoverTheme
import com.gamut.core.designsystem.GamutBottomBarTabsHeight
import com.gamut.core.designsystem.LocalAcrylicState
import com.gamut.core.designsystem.LocalCoverPalette
import com.gamut.core.designsystem.NowPlayingBars
import com.gamut.core.designsystem.ProfileAvatar
import com.gamut.core.designsystem.ShareSheet
import com.gamut.core.designsystem.ArtistLine
import com.gamut.core.designsystem.ArtistPickerSheet
import com.gamut.core.designsystem.rememberArtistTap
import com.gamut.core.designsystem.splitArtists
import com.gamut.core.designsystem.TrackRowSkeleton
import com.gamut.core.designsystem.acrylicSource
import com.gamut.core.designsystem.glass
import com.gamut.core.designsystem.ink
import com.gamut.core.designsystem.pressScale
import com.gamut.core.designsystem.squareArtUrl
import com.gamut.core.model.Track
import com.gamut.core.model.TrackSource
import dev.chrisbanes.haze.HazeState
import java.util.Locale
import kotlinx.coroutines.delay

// The only expressive element is the flowing background, which takes its colors from the cover of
// the track that is playing. Everything on top of it is a neutral "ink" (white on dark covers, near-black
// on light ones, supplied by CoverTheme) plus that cover's accent in a few small places (cursor, focus
// ring, spinners, the playing row). Surfaces are flat, translucent ink.
//
// Rhythm used everywhere: 24dp page gutter, 16dp row gutter (+8dp inner padding = 24dp aligned text edge),
// 28dp between sections, 12dp between a section title and its content.

internal val TextPrimary: Color
    @Composable @ReadOnlyComposable get() = ink()
internal val TextSecondary: Color
    @Composable @ReadOnlyComposable get() = ink(0.6f)
internal val TextTertiary: Color
    @Composable @ReadOnlyComposable get() = ink(0.4f)

private val PanelShape = RoundedCornerShape(16.dp)
internal val CardShapeLarge = RoundedCornerShape(22.dp)
internal val RowShape = RoundedCornerShape(16.dp)
internal val ThumbShape = RoundedCornerShape(10.dp)

private const val FILTER_ALL = 0
private const val FILTER_CATALOG = 1
private const val FILTER_YOUTUBE = 2

@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    resolvingIsrc: String?,
    /** The picked track plus the list it was picked from (the queue); empty = just this track. */
    onTrackSelected: (Track, List<Track>) -> Unit,
    onArtistSelected: (Long) -> Unit,
    onAlbumSelected: (DiscoverItem) -> Unit,
    /** The round "what's this song?" button next to the profile icon (same 34dp circle as the profile avatar). */
    onShazam: () -> Unit,
    /** Profile icon: opens the account / sync / backup screen. */
    onProfile: () -> Unit = {},
    /** Feeds the generated profile circle: name = its letter, seed = auth user id (null = signed out). */
    profileName: String? = null,
    profileSeed: String? = null,
    /** Current avatar image URL from the account ViewModel. */
    profileImage: Any? = null,
    /** Extra modifier for the profile button (the app module passes the support heart badge, which lives outside this module). */
    profileModifier: Modifier = Modifier,
    /** Three dots on a song row. Defaults to a no-op until the actions sheet is wired in MainActivity. */
    onTrackMore: (Track) -> Unit = {},
    /** ISRC of the playing track: its row gets the accent title + animated bars. */
    nowPlayingIsrc: String? = null,
    /** Opens the artist page of a track (by name). */
    onTrackArtist: (Track) -> Unit = {},
    /** ISRCs of the user's favorites (the + turns into a check). */
    favoriteIsrcs: Set<String> = emptySet(),
    /** Adds the track to favorites, or removes it when it is already there. */
    onToggleFavorite: (Track) -> Unit = {},
    /** The user's playlists, for the "Saved in" sheet and "Add to playlist". */
    playlists: List<TrackPlaylistOption> = emptyList(),
    /** Adds [Track] to the playlist with this id. */
    onAddToPlaylist: (Track, String) -> Unit = { _, _ -> },
    /** Height of the shared bottom bar + mini player strip (owned by HomeTabs in MainActivity). */
    barHeight: Dp,
    /** Bumped when something asks for search mode (empty-state "Find ..." buttons): scroll to the field, focus it, show the keyboard. */
    focusSearchTick: Int = 0,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val discover by viewModel.discover.collectAsStateWithLifecycle()
    val artistHit by viewModel.artistHit.collectAsStateWithLifecycle()

    // The flowing background is drawn once, behind the NavHost (see MainActivity); this is only its palette.
    val palette = LocalCoverPalette.current

    CoverTheme(palette) {
        val accent = MaterialTheme.colorScheme.primary
        val showingDiscover = state.tracks.isEmpty() && state.query.isBlank()
        val listState = rememberLazyListState()
        var discoverTab by rememberSaveable { mutableIntStateOf(0) }
        var chartExpanded by rememberSaveable { mutableStateOf(false) }
        var filter by rememberSaveable { mutableIntStateOf(FILTER_ALL) }
        val searchFocus = remember { FocusRequester() }
        var actionsTrack by remember { mutableStateOf<Track?>(null) }
        var savedTrack by remember { mutableStateOf<Track?>(null) }
        var shareTrack by remember { mutableStateOf<Track?>(null) }
        var pickerTrack by remember { mutableStateOf<Track?>(null) }
        var artistPickTrack by remember { mutableStateOf<Track?>(null) }
        val keyboard = LocalSoftwareKeyboardController.current
        val haptic = LocalHapticFeedback.current
        val context = LocalContext.current
        val recents = remember { RecentSearchesStore(context.applicationContext) }
        var recentList by remember { mutableStateOf(recents.all()) }
        var fieldFocused by remember { mutableStateOf(false) }
        val saveRecent: (String) -> Unit = { q -> recents.add(q); recentList = recents.all() }
        val showingRecents = showingDiscover && fieldFocused && recentList.isNotEmpty()

        LaunchedEffect(focusSearchTick) {
            if (focusSearchTick > 0) {
                // The tab slide (HomeTabs, ~240 ms) is still running: focusing a field that is not on screen yet can fail.
                delay(280)
                listState.scrollToItem(0)
                runCatching { searchFocus.requestFocus() }
                keyboard?.show()
            }
        }
        // Scrolling the list tucks the keyboard away (focus stays, so the cursor is still there when you tap back up).
        LaunchedEffect(listState) {
            snapshotFlow { listState.isScrollInProgress }.collect { if (it) keyboard?.hide() }
        }
        // A new search starts on "All"; chips never carry over from the previous query.
        LaunchedEffect(state.query) { filter = FILTER_ALL }

        // "No results" must not flash in the gap between typing and the request starting, so it waits until the
        // state has stayed settled (not loading, still empty) for a moment.
        var settled by remember { mutableStateOf(false) }
        LaunchedEffect(state.query, state.isLoading, state.tracks.isEmpty()) {
            settled = false
            if (!state.isLoading) {
                delay(400)
                settled = true
            }
        }

        val hasYoutube = state.tracks.any { it.source == TrackSource.YOUTUBE }
        val activeFilter = if (hasYoutube) filter else FILTER_ALL
        val visibleTracks = remember(state.tracks, activeFilter) {
            when (activeFilter) {
                FILTER_CATALOG -> state.tracks.filter { it.source != TrackSource.YOUTUBE }
                FILTER_YOUTUBE -> state.tracks.filter { it.source == TrackSource.YOUTUBE }
                else -> state.tracks
            }
        }

        val gutter = adaptiveGutter()   // 0.dp on phones; centers the content column on wide windows
        val tabsHeight = GamutBottomBarTabsHeight + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val stripHeight = maxOf(0.dp, barHeight - tabsHeight)

        // Title + search scroll with the content as the first list item (key "header"): the field stays composed when
        // the query goes from empty to "a", so it keeps focus, keyboard and cursor.
        val header: @Composable (Modifier) -> Unit = { m ->
            Column(m.fillMaxWidth().statusBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(top = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Discover",
                        style = MaterialTheme.typography.displayLarge,
                        color = TextPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    Box(Modifier.size(44.dp).pressScale(onClick = onShazam), contentAlignment = Alignment.Center) {
                        Icon(
                            painterResource(com.gamut.core.designsystem.R.drawable.ic_shazam),
                            "Identify a song",
                            tint = ink(0.9f),
                            modifier = Modifier.size(34.dp),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.size(44.dp).then(profileModifier).pressScale(onClick = onProfile).semantics { contentDescription = "Profile" }, contentAlignment = Alignment.Center) {
                        ProfileAvatar(
                            name = profileName,
                            seed = profileSeed,
                            size = 34.dp,
                            guestIcon = PhosphorIcons.Bold.User,
                            image = profileImage,
                        )
                    }
                }
                Spacer(Modifier.height(20.dp))
                SearchField(
                    query = state.query,
                    onQueryChange = viewModel::updateQuery,
                    accent = accent,
                    focusRequester = searchFocus,
                    onFocusChange = { fieldFocused = it },
                    onSubmit = { saveRecent(state.query) },
                )
                // A hairline progress bar replaces the old dimming-only feedback while a refined search is in flight.
                Box(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 12.dp).height(2.dp)) {
                    if (state.isLoading && state.tracks.isNotEmpty()) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxSize().clip(CircleShape),
                            color = accent,
                            trackColor = Color.Transparent,
                        )
                    }
                }
                state.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 8.dp))
                }
            }
        }

        // Pull down at the top of Discover: slingshot stretch + refresh button, reloads Discover from scratch.
        val refresh = rememberSlingshotRefresh(enabled = showingDiscover && !showingRecents, onRefresh = { viewModel.refreshDiscover() })

        AcrylicHost(Modifier.fillMaxSize(), state = LocalAcrylicState.current ?: remember { HazeState() }) {
            Box(Modifier.fillMaxSize().padding(bottom = tabsHeight).acrylicSource().nestedScroll(refresh.connection)) {
                val resultsAlpha by animateFloatAsState(if (state.isLoading) 0.55f else 1f, tween(150), label = "results-alpha")
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().offset { IntOffset(0, refresh.pull.coerceAtLeast(0f).roundToInt()) },
                    contentPadding = PaddingValues(start = gutter, end = gutter, bottom = stripHeight + 32.dp),
                ) {
                    item(key = "header") { header(Modifier.padding(horizontal = 24.dp)) }
                    if (showingRecents) {
                        item(key = "recents-head") {
                            Row(
                                Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "Recent",
                                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                                    color = TextPrimary,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    "Clear",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = TextSecondary,
                                    modifier = Modifier.pressScale(onClick = { recents.clear(); recentList = emptyList() }, scaleDown = 0.92f).padding(4.dp),
                                )
                            }
                        }
                        items(recentList, key = { "recent-$it" }) { q ->
                            Box(Modifier.padding(horizontal = 16.dp)) {
                                RecentRow(q, onClick = { viewModel.updateQuery(q) }, onRemove = { recents.remove(q); recentList = recents.all() })
                            }
                        }
                    } else if (showingDiscover) {
                        discoverItems(
                            state = discover,
                            selectedTab = discoverTab,
                            onSelectTab = { discoverTab = it; chartExpanded = false },
                            onTrackClick = onTrackSelected,
                            onMoreClick = { actionsTrack = it },
                            nowPlayingIsrc = nowPlayingIsrc,
                            onArtistClick = onArtistSelected,
                            onAlbumClick = onAlbumSelected,
                            chartExpanded = chartExpanded,
                            onToggleChartExpanded = { chartExpanded = !chartExpanded },
                            onRetry = { viewModel.refreshDiscover() },
                        )
                    } else {
                        val loadingEmpty = state.tracks.isEmpty() && (state.isLoading || !settled)
                        val noResults = state.tracks.isEmpty() && !loadingEmpty

                        artistHit?.let { hit ->
                            item(key = "artist-hit") {
                                // Eases in: the card arrives after the songs (it waits for its verification), so it must not jolt the list.
                                val visible = remember { MutableTransitionState(false).apply { targetState = true } }
                                AnimatedVisibility(
                                    visibleState = visible,
                                    enter = fadeIn(tween(240)) + expandVertically(tween(240)),
                                ) {
                                    Column(Modifier.padding(horizontal = 16.dp)) {
                                        SectionLabel("Top result", Modifier.padding(horizontal = 8.dp))
                                        ArtistHit(hit) { id -> saveRecent(state.query); onArtistSelected(id) }
                                    }
                                }
                            }
                        }

                        if (hasYoutube) {
                            item(key = "filters") {
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Pill("All", activeFilter == FILTER_ALL) { filter = FILTER_ALL }
                                    Pill("Catalog", activeFilter == FILTER_CATALOG) { filter = FILTER_CATALOG }
                                    Pill("YouTube", activeFilter == FILTER_YOUTUBE) { filter = FILTER_YOUTUBE }
                                }
                            }
                        }

                        if (state.tracks.isNotEmpty()) {
                            item(key = "results-title") {
                                Row(
                                    Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 14.dp, bottom = 6.dp),
                                    verticalAlignment = Alignment.Bottom,
                                ) {
                                    Text(
                                        "Songs",
                                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                                        color = TextPrimary,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        visibleTracks.size.toString(),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = TextTertiary,
                                        modifier = Modifier.padding(bottom = 2.dp),
                                    )
                                }
                            }
                        }

                        when {
                            loadingEmpty -> items(8, key = { "skeleton-$it" }) {
                                TrackRowSkeleton(Modifier.padding(horizontal = 24.dp))
                            }
                            noResults -> item(key = "empty") { NoResults(state.query) }
                            else -> itemsIndexed(visibleTracks, key = { i, t -> "${t.isrc}#$i" }) { _, track ->
                                // While a refined search is loading, the old results dim instead of vanishing.
                                Box(Modifier.padding(horizontal = 16.dp).graphicsLayer { alpha = resultsAlpha }) {
                                    val isFav = track.isrc in favoriteIsrcs
                                    TrackResultRow(
                                        track = track,
                                        query = state.query,
                                        isResolving = resolvingIsrc == track.isrc,
                                        isPlaying = track.isrc == nowPlayingIsrc,
                                        isFavorite = isFav,
                                        accent = accent,
                                        onMore = { actionsTrack = track },
                                        onLongPress = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            actionsTrack = track
                                        },
                                        onAdd = {
                                            // The + saves straight to favorites, then offers the playlists too.
                                            // Tapping it again (check showing) undoes the save.
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            onToggleFavorite(track)
                                            if (!isFav) savedTrack = track
                                        },
                                        onTrackSelected = { picked -> saveRecent(state.query); onTrackSelected(picked, visibleTracks) },
                                    )
                                }
                            }
                        }
                    }
                }
                SlingshotIndicator(refresh)
            }
        }

        actionsTrack?.let { t ->
            TrackActionsSheet(
                track = t,
                isFavorite = t.isrc in favoriteIsrcs,
                onDismiss = { actionsTrack = null },
                onShare = { actionsTrack = null; shareTrack = t },
                onToggleFavorite = { onToggleFavorite(t) },
                onAddToPlaylist = { actionsTrack = null; pickerTrack = t },
                onGoToArtist = {
                    actionsTrack = null
                    if (splitArtists(t.artist).size > 1) artistPickTrack = t else onTrackArtist(t)
                },
            )
        }
        savedTrack?.let { t ->
            SavedInSheet(
                track = t,
                playlists = playlists,
                accent = accent,
                showFavorites = true,
                onAddToPlaylist = { id -> onAddToPlaylist(t, id) },
                onDismiss = { savedTrack = null },
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
        artistPickTrack?.let { t ->
            ArtistPickerSheet(
                names = splitArtists(t.artist),
                albumTitle = t.album,
                onPick = { name -> onTrackArtist(t.copy(artist = name)) },
                onDismiss = { artistPickTrack = null },
            )
        }
        shareTrack?.let { t -> ShareSheet(title = t.title, artist = t.artist, artUrl = t.albumArtUrl, onDismiss = { shareTrack = null }) }
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Search field
// ---------------------------------------------------------------------------------------------------------------------

/**
 * Flat translucent field, no edge at rest. While focused it gets a hairline ring in the cover's accent and a
 * "Cancel" slides in beside it (clears the query and drops focus). The keyboard's action key is "Search" and
 * simply tucks the keyboard away: results already update as you type.
 */
@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    accent: Color,
    focusRequester: FocusRequester,
    onFocusChange: (Boolean) -> Unit,
    onSubmit: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val focus by animateFloatAsState(if (focused) 1f else 0f, tween(200), label = "search-focus")
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier
                .weight(1f)
                .height(52.dp)
                .glass(PanelShape, fill = 0.07f + 0.03f * focus, borderColor = accent.copy(alpha = 0.55f * focus))
                .padding(start = 16.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                PhosphorIcons.Bold.MagnifyingGlass,
                null,
                tint = ink(0.45f + 0.35f * focus),
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f).focusRequester(focusRequester).onFocusChanged { focused = it.isFocused; onFocusChange(it.isFocused) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = TextPrimary),
                cursorBrush = SolidColor(accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); onSubmit() }),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) {
                            Text("Artists, songs, albums", style = MaterialTheme.typography.bodyLarge, color = TextTertiary)
                        }
                        inner()
                    }
                },
            )
            AnimatedVisibility(visible = query.isNotEmpty(), enter = fadeIn(tween(120)), exit = fadeOut(tween(120))) {
                Box(
                    Modifier.size(40.dp).pressScale(onClick = { onQueryChange("") }, scaleDown = 0.88f),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(22.dp).background(ink(0.14f), CircleShape), contentAlignment = Alignment.Center) {
                        Icon(PhosphorIcons.Bold.X, "Clear", tint = ink(0.8f), modifier = Modifier.size(13.dp))
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = focused,
            enter = fadeIn(tween(180)) + expandHorizontally(tween(220)),
            exit = fadeOut(tween(120)) + shrinkHorizontally(tween(180)),
        ) {
            Text(
                "Cancel",
                style = MaterialTheme.typography.bodyLarge,
                color = ink(0.85f),
                maxLines = 1,
                softWrap = false,
                modifier = Modifier
                    .padding(start = 16.dp)
                    .pressScale(onClick = { onQueryChange(""); focusManager.clearFocus() }, scaleDown = 0.94f),
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Small shared pieces (also used by DiscoverSection.kt)
// ---------------------------------------------------------------------------------------------------------------------

/** Tiny uppercase-free label above a block ("Top result"). */
@Composable
internal fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
        color = TextTertiary,
        modifier = modifier.padding(top = 6.dp, bottom = 4.dp),
    )
}

/** Quiet pill used for result filters and Discover's chart tabs. Selected = filled ink tint, others = faint. */
@Composable
internal fun Pill(label: String, selected: Boolean, onClick: () -> Unit) {
    val fill by animateColorAsState(ink(if (selected) 0.16f else 0.06f), tween(200), label = "pill-fill")
    val text by animateColorAsState(ink(if (selected) 1f else 0.6f), tween(200), label = "pill-text")
    Box(
        Modifier
            .pressScale(onClick = onClick, scaleDown = 0.94f)
            .clip(CircleShape)
            .background(fill)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium),
            color = text,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** One recent query: clock, text, and an x to forget it. Tapping runs the search again. */
@Composable
private fun RecentRow(query: String, onClick: () -> Unit, onRemove: () -> Unit) {
    InteractiveSurface(
        shape = RowShape,
        pressedScale = 0.98f,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
    ) { _ ->
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            Icon(PhosphorIcons.Bold.ClockCounterClockwise, null, tint = ink(0.45f), modifier = Modifier.size(20.dp))
        }
        Text(
            query,
            style = MaterialTheme.typography.bodyLarge,
            color = ink(0.9f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
        )
        RowIconButton(PhosphorIcons.Bold.X, "Remove from recent searches", ink(0.45f), onRemove)
    }
}

@Composable
private fun NoResults(query: String) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(PhosphorIcons.Bold.MagnifyingGlass, null, tint = ink(0.3f), modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(4.dp))
        Text(
            "No results for \u201C${query.trim()}\u201D",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = TextPrimary,
            textAlign = TextAlign.Center,
        )
        Text(
            "Check the spelling, or try the artist or song title on its own.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

internal fun formatDuration(ms: Long): String {
    if (ms <= 0L) return ""
    val total = ms / 1_000
    return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
}

internal fun formatCount(n: Long): String = when {
    n >= 1_000_000 -> String.format(Locale.ROOT, "%.1fM", n / 1_000_000.0).replace(".0M", "M")
    n >= 1_000 -> "${n / 1_000}K"
    else -> n.toString()
}

/** Bolds the parts of [text] that the query matched, so you can see why a row is here. */
private fun highlight(text: String, query: String, match: Color): AnnotatedString {
    val tokens = query.lowercase().split(' ').filter { it.isNotBlank() }
    val lower = text.lowercase()
    if (tokens.isEmpty() || lower.length != text.length) return AnnotatedString(text)
    val hit = BooleanArray(text.length)
    tokens.forEach { t ->
        var i = lower.indexOf(t)
        while (i >= 0) {
            for (k in i until i + t.length) hit[k] = true
            i = lower.indexOf(t, i + t.length)
        }
    }
    return buildAnnotatedString {
        append(text)
        var k = 0
        while (k < text.length) {
            if (hit[k]) {
                val start = k
                while (k < text.length && hit[k]) k++
                addStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = match), start, k)
            } else k++
        }
    }
}

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

/**
 * Shared touch behaviour for rows and cards: a soft ink tint fades in on hover (mouse, stylus, ChromeOS) or press,
 * and a press squeezes the surface then springs it back with a small overshoot. Long press is optional (opens the
 * actions sheet on song rows). [content] receives `active` (hovered or pressed) so children can react too.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun InteractiveSurface(
    shape: Shape,
    pressedScale: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    onLongClick: (() -> Unit)? = null,
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
        // Scale is outermost so anything [modifier] draws squeezes with the content.
        Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .then(modifier)
            .clip(shape)
            .background(tint)
            .hoverable(interaction)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) { content(this, hovered || pressed) }
}

// ---------------------------------------------------------------------------------------------------------------------
// Artist card + song row
// ---------------------------------------------------------------------------------------------------------------------

/**
 * The searched artist: round portrait, name in the display face, fan count, tap opens their page. No container: it
 * sits on the background like the rows below it, only larger. The hover/press tint is the one moment a shape appears.
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.headlineMedium,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                // Big enough artists get the seal-check badge (follower count decides, see VerifiedBadge.kt).
                if (isVerifiedArtist(item.followers)) VerifiedBadge(Modifier.padding(start = 8.dp), size = 22.dp)
            }
            Text("Artist", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        }
        // The arrow nudges forward on hover/press: a quiet "this opens".
        val slide by animateDpAsState(
            targetValue = if (active) 4.dp else 0.dp,
            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
            label = "artist-arrow",
        )
        Icon(
            PhosphorIcons.Bold.ArrowRight,
            "Open artist",
            tint = ink(if (active) 0.9f else 0.5f),
            modifier = Modifier.offset(x = slide).size(22.dp),
        )
    }
}

@Composable
private fun TrackResultRow(
    track: Track,
    query: String,
    isResolving: Boolean,
    isPlaying: Boolean,
    isFavorite: Boolean,
    accent: Color,
    onMore: () -> Unit,
    onLongPress: () -> Unit,
    onAdd: () -> Unit,
    onTrackSelected: (Track) -> Unit,
) {
    InteractiveSurface(
        shape = RowShape,
        pressedScale = 0.97f,
        onClick = { onTrackSelected(track) },
        onLongClick = onLongPress,
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isPlaying) NowPlayingBars(color = accent, modifier = Modifier.padding(end = 8.dp))
                val titleColor = if (isPlaying) accent else ink(0.85f)
                Text(
                    text = highlight(track.title, query, if (isPlaying) accent else TextPrimary),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = if (isPlaying) FontWeight.SemiBold else FontWeight.Normal),
                    color = titleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Off-catalog finds (fan edits, bootlegs) carry the YouTube mark inline instead of a text tag.
                if (track.source == TrackSource.YOUTUBE) {
                    Icon(YouTubeGlyph, "From YouTube", tint = ink(0.6f), modifier = Modifier.padding(end = 4.dp).size(18.dp))
                }
                val subtitle = if (isResolving) "Checking a verified source\u2026"
                else listOfNotNull(track.artist, track.album?.takeIf { it.isNotBlank() }).joinToString(" \u00B7 ")
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
        }
        if (isResolving) {
            CircularProgressIndicator(Modifier.size(16.dp), color = accent, strokeWidth = 1.5.dp)
            Spacer(Modifier.width(8.dp))
        } else {
            // Duration at rest; a play glyph replaces it on hover/press, so a row reads as tappable before it is tapped.
            val playAlpha by animateFloatAsState(if (active) 0.9f else 0f, tween(140), label = "row-play")
            val duration = formatDuration(track.durationMs)
            Box(Modifier.padding(horizontal = 6.dp).width(36.dp), contentAlignment = Alignment.CenterEnd) {
                if (duration.isNotEmpty()) {
                    Text(
                        duration,
                        style = MaterialTheme.typography.labelMedium,
                        color = TextTertiary,
                        modifier = Modifier.graphicsLayer { alpha = 1f - playAlpha },
                    )
                }
                Icon(
                    PhosphorIcons.Bold.Play,
                    null,
                    tint = ink(),
                    modifier = Modifier.size(18.dp).graphicsLayer { alpha = playAlpha },
                )
            }
        }
        RowIconButton(
            icon = if (isFavorite) PhosphorIcons.Fill.CheckCircle else PhosphorIcons.Bold.PlusCircle,
            description = if (isFavorite) "Saved" else "Add to favorites",
            tint = if (isFavorite) accent else ink(0.7f),
            onClick = onAdd,
        )
        RowIconButton(PhosphorIcons.Bold.DotsThreeVertical, "More options", ink(0.6f), onMore)
    }
}

@Composable
internal fun RowIconButton(icon: ImageVector, description: String, tint: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(role = androidx.compose.ui.semantics.Role.Button, onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, description, tint = tint, modifier = Modifier.size(24.dp)) }
}

@Composable
internal fun ArtworkFallbackIcon() {
    Icon(PhosphorIcons.Bold.MusicNote, null, tint = ink(0.45f), modifier = Modifier.size(22.dp))
}