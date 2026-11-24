package com.gamut.feature.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Bold
import com.adamglin.phosphoricons.bold.CaretRight
import com.gamut.core.designsystem.ArtImage
import com.gamut.core.designsystem.ExplicitMark
import com.gamut.core.designsystem.NowPlayingBars
import com.gamut.core.designsystem.TrackRowSkeleton
import com.gamut.core.designsystem.TablerIcons
import com.gamut.core.designsystem.ink
import com.gamut.core.designsystem.pressScale
import com.gamut.core.designsystem.squareArtUrl
import com.gamut.core.designsystem.thumbnailBorder
import com.gamut.core.model.Track

private val NUMBERED_TABS = setOf("Trending", "Billboard")
private const val COLLAPSED_ROWS = 10

internal fun LazyListScope.discoverFeaturedItems(
    state: DiscoverState,
    onTrackClick: (Track, List<Track>) -> Unit,
    nowPlayingIsrc: String?,
    onArtistClick: (Long) -> Unit,
    onAlbumClick: (DiscoverItem) -> Unit,
    onRetry: () -> Unit = {},
    onMoreClick: (Track) -> Unit = {},
    onArtistItemClick: (DiscoverItem) -> Unit = {},
    onStationClick: (DiscoverItem) -> Unit = {},
    stationLoadingId: String? = null,
) {
    if (state.tabs.isEmpty()) {
        if (state.error != null && !state.isLoading) {
            item(key = "discover-error") {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        state.error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Try again",
                        style = MaterialTheme.typography.labelLarge,
                        color = TextPrimary,
                        modifier = Modifier.pressScale(onClick = onRetry, scaleDown = 0.92f).padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
        } else {
            item(key = "discover-skeleton-hero") { TopPicksSkeleton() }
            items(6, key = { "discover-skeleton-$it" }) { TrackRowSkeleton(Modifier.padding(horizontal = DiscoverInset)) }
        }
        return
    }

    // Top to bottom: the mood pills, the carousel, stations made for the listener, what they played, the shelves,
    // then the artists and albums of the week.
    discoverMoodPills(state, stationLoadingId, onStationClick)
    if (state.personalLoading && state.feed.none { it.kind == FeedKind.TOP_PICKS }) {
        item(key = "top-picks-skeleton") { TopPicksSkeleton() }
    }
    discoverHero(state, nowPlayingIsrc, onStationClick, onAlbumClick, onTrackClick)
    discoverMadeForYou(state, stationLoadingId, onStationClick)

    feedSectionItems(
        state.feed.filter { it.slot == FeedSlot.TOP },
        onTrackClick,
        onMoreClick,
        nowPlayingIsrc,
        onArtistItemClick,
        onAlbumClick,
        onStationClick,
        stationLoadingId,
    )
    feedSectionItems(
        state.feed.filter { it.slot == FeedSlot.MIDDLE },
        onTrackClick,
        onMoreClick,
        nowPlayingIsrc,
        onArtistItemClick,
        onAlbumClick,
        onStationClick,
        stationLoadingId,
    )

    if (state.artists.isNotEmpty()) {
        item(key = "artists-title") { DiscoverSectionTitle("Artists to know") }
        item(key = "artists-row") {
            LazyRow(
                contentPadding = PaddingValues(horizontal = DiscoverInset, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                items(state.artists, key = { it.id }) { artist -> ArtistBubble(artist) { artist.artistId?.let(onArtistClick) } }
            }
        }
    }

    if (state.albums.isNotEmpty()) {
        item(key = "albums-title") { DiscoverSectionTitle("Albums on the rise") }
        item(key = "albums-row") {
            LazyRow(
                contentPadding = PaddingValues(horizontal = DiscoverInset, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(state.albums, key = { it.id }) { album -> AlbumCard(album) { onAlbumClick(album) } }
            }
        }
    }
}

internal fun LazyListScope.discoverFeedItems(
    state: DiscoverState,
    onTrackClick: (Track, List<Track>) -> Unit,
    onMoreClick: (Track) -> Unit,
    nowPlayingIsrc: String?,
    onArtistItemClick: (DiscoverItem) -> Unit,
    onAlbumClick: (DiscoverItem) -> Unit,
    onLoadMore: () -> Unit,
) {
    if (state.tabs.isEmpty()) return
    val bottom = state.feed.filter { it.slot == FeedSlot.BOTTOM }
    feedSectionItems(bottom, onTrackClick, onMoreClick, nowPlayingIsrc, onArtistItemClick, onAlbumClick)
    if (state.feedEnabled) {
        item(key = "feed-footer") {
            FeedFooter(
                count = bottom.size,
                exhausted = state.feedExhausted,
                failed = state.feedFailed,
                onLoadMore = onLoadMore,
            )
        }
    }
}

internal fun LazyListScope.discoverChartItems(
    state: DiscoverState,
    selectedTab: Int,
    onSelectTab: (Int) -> Unit,
    onTrackClick: (Track, List<Track>) -> Unit,
    onMoreClick: (Track) -> Unit,
    nowPlayingIsrc: String?,
    onAlbumClick: (DiscoverItem) -> Unit,
    chartExpanded: Boolean = false,
    onToggleChartExpanded: () -> Unit = {},
) {
    if (state.tabs.isEmpty()) return
    val tabIndex = selectedTab.coerceIn(0, state.tabs.lastIndex)
    val tab = state.tabs[tabIndex]
    val canExpand = tab.items.size > COLLAPSED_ROWS
    item(key = "charts-title") {
        DiscoverSectionTitle(
            text = "Charts",
            subtitle = tab.title,
            actionLabel = if (canExpand) (if (chartExpanded) "Show less" else "See all") else null,
            onAction = onToggleChartExpanded,
        )
    }
    item(key = "charts-tabs") {
        LazyRow(
            contentPadding = PaddingValues(horizontal = DiscoverInset, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(state.tabs, key = { _, t -> t.label }) { i, t -> Pill(t.label, i == tabIndex) { onSelectTab(i) } }
        }
    }
    item(key = "charts-gap") { Spacer(Modifier.height(6.dp)) }

    val numbered = tab.label in NUMBERED_TABS
    val queue = tab.items.mapNotNull { it.track }
    val shown = if (chartExpanded) tab.items else tab.items.take(COLLAPSED_ROWS)
    itemsIndexed(shown, key = { i, it -> "chart-${tab.label}-$i-${it.id}" }) { index, item ->
        val track = item.track
        Column(Modifier.padding(horizontal = 12.dp)) {
            if (index > 0) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = if (numbered) 36.dp else 8.dp, end = 8.dp)
                        .height(0.5.dp)
                        .background(ink(0.07f)),
                )
            }
            ChartRow(
                rank = if (numbered) index + 1 else null,
                item = item,
                isPlaying = track != null && track.isrc == nowPlayingIsrc,
                onClick = { if (track != null) onTrackClick(track, queue) else onAlbumClick(item) },
                onMore = track?.let { t -> { onMoreClick(t) } },
            )
        }
    }
    // "See all" sits in the title; once the list is long, "Show less" is repeated at its foot.
    if (chartExpanded && canExpand) {
        item(key = "charts-more") {
            Box(Modifier.fillMaxWidth().padding(top = 12.dp), contentAlignment = Alignment.Center) {
                Pill("Show less", false, onToggleChartExpanded)
            }
        }
    }
}

internal fun LazyListScope.discoverItems(
    state: DiscoverState,
    selectedTab: Int,
    onSelectTab: (Int) -> Unit,
    onTrackClick: (Track, List<Track>) -> Unit,
    onMoreClick: (Track) -> Unit,
    nowPlayingIsrc: String?,
    onArtistClick: (Long) -> Unit,
    onAlbumClick: (DiscoverItem) -> Unit,
    chartExpanded: Boolean = false,
    onToggleChartExpanded: () -> Unit = {},
    onRetry: () -> Unit = {},
    onArtistItemClick: (DiscoverItem) -> Unit = {},
    onLoadMore: () -> Unit = {},
    onStationClick: (DiscoverItem) -> Unit = {},
    stationLoadingId: String? = null,
) {
    discoverFeaturedItems(
        state,
        onTrackClick,
        nowPlayingIsrc,
        onArtistClick,
        onAlbumClick,
        onRetry,
        onMoreClick,
        onArtistItemClick,
        onStationClick,
        stationLoadingId,
    )
    discoverChartItems(
        state,
        selectedTab,
        onSelectTab,
        onTrackClick,
        onMoreClick,
        nowPlayingIsrc,
        onAlbumClick,
        chartExpanded,
        onToggleChartExpanded,
    )
    discoverFeedItems(state, onTrackClick, onMoreClick, nowPlayingIsrc, onArtistItemClick, onAlbumClick, onLoadMore)
}

@Composable
internal fun DiscoverSectionTitle(
    text: String,
    subtitle: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = DiscoverInset, end = DiscoverInset, top = 32.dp, bottom = 10.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text,
                style = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp, fontWeight = FontWeight.Bold),
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 18.sp),
                    color = TextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (actionLabel != null && onAction != null) {
            Text(
                actionLabel,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = TextSecondary,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.pressScale(onClick = onAction, scaleDown = 0.94f).padding(start = 12.dp, top = 8.dp, bottom = 2.dp),
            )
        }
    }
}

private val CoverShape = RoundedCornerShape(14.dp)

@Composable
private fun ArtistBubble(
    item: DiscoverItem,
    onClick: () -> Unit,
) {
    Column(
        Modifier.width(92.dp).pressScale(onClick = onClick, scaleDown = 0.94f),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ArtImage(
            url = item.imageUrl,
            modifier =
                Modifier
                    .size(92.dp)
                    .clip(CircleShape)
                    .thumbnailBorder(CircleShape),
            contentDescription = item.title,
            fallback = { ArtworkFallbackIcon() },
        )
        Spacer(Modifier.height(10.dp))
        Text(
            item.title,
            style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
            color = TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun AlbumCard(
    item: DiscoverItem,
    isPlaying: Boolean = false,
    size: Dp = 144.dp,
    onClick: () -> Unit,
) {
    Column(Modifier.width(size).pressScale(onClick = onClick, scaleDown = 0.96f)) {
        ArtImage(
            url = remember(item.imageUrl) { squareArtUrl(item.imageUrl, 400) },
            modifier =
                Modifier
                    .size(size)
                    .clip(CoverShape)
                    .thumbnailBorder(CoverShape),
            contentDescription = item.title,
            fallback = { ArtworkFallbackIcon() },
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ExplicitMark(item.track?.isExplicit, TextSecondary, size = 12.dp)
            Text(
                item.title,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                color = if (isPlaying) MaterialTheme.colorScheme.primary else TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        Text(
            item.subtitle,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp, lineHeight = 16.sp),
            color = TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        item.meta?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp, lineHeight = 16.sp),
                color = TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ChartRow(
    rank: Int?,
    item: DiscoverItem,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onMore: (() -> Unit)?,
) {
    val accent = MaterialTheme.colorScheme.primary
    InteractiveSurface(
        shape = RowShape,
        pressedScale = 0.98f,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
    ) { _ ->
        if (rank != null) {
            Box(Modifier.width(28.dp), contentAlignment = Alignment.CenterStart) {
                if (isPlaying) {
                    NowPlayingBars(color = accent)
                } else {
                    Text(
                        rank.toString(),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium),
                        color = TextTertiary,
                    )
                }
            }
        }
        ArtImage(
            url = remember(item.imageUrl) { squareArtUrl(item.imageUrl, 256) },
            modifier =
                Modifier
                    .size(52.dp)
                    .clip(ThumbShape)
                    .thumbnailBorder(ThumbShape),
            fallback = { ArtworkFallbackIcon() },
        )
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ExplicitMark(item.track?.isExplicit, TextSecondary)
                Text(
                    item.title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = if (isPlaying) accent else TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            Text(
                item.subtitle,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 18.sp),
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            item.meta?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, color = TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (rank != null) TrendMark(item.trend, item.trendDelta, accent)
        if (onMore != null) {
            RowIconButton(TablerIcons.MoreVertical, "More options", ink(0.6f), onMore)
        } else {
            Icon(PhosphorIcons.Bold.CaretRight, null, tint = ink(0.4f), modifier = Modifier.padding(end = 8.dp).size(18.dp))
        }
    }
}

/** How a chart entry moved since last week, at the end of its row: green up, red down, a dash for no change. */
@Composable
private fun TrendMark(
    trend: Trend?,
    delta: Int,
    accent: Color,
) {
    val small = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    Box(Modifier.widthIn(min = 28.dp), contentAlignment = Alignment.CenterEnd) {
        when (trend) {
            null -> Unit
            Trend.NEW -> Text("NEW", style = small, color = accent)
            Trend.UP -> Text("\u25B2$delta", style = small, color = Color(0xFF3DDC84))
            Trend.DOWN -> Text("\u25BC$delta", style = small, color = Color(0xFFFF6B6B))
            Trend.SAME -> Text("\u2013", style = small, color = TextTertiary)
        }
    }
}

private fun LazyListScope.feedSectionItems(
    sections: List<FeedSection>,
    onTrackClick: (Track, List<Track>) -> Unit,
    onMoreClick: (Track) -> Unit,
    nowPlayingIsrc: String?,
    onArtistItemClick: (DiscoverItem) -> Unit,
    onAlbumClick: (DiscoverItem) -> Unit,
    onStationClick: (DiscoverItem) -> Unit = {},
    stationLoadingId: String? = null,
) {
    sections.forEach { section ->
        // Station cards are drawn by discoverMadeForYou and the mood pills by discoverMoodPills, beside the hero they belong with.
        if (section.items.isEmpty() || section.kind == FeedKind.TOP_PICKS || section.kind == FeedKind.MOODS) return@forEach
        val queue = section.items.mapNotNull { it.track }
        val play: (DiscoverItem) -> Unit = { item -> item.track?.let { onTrackClick(it, queue) } }
        val playing: (DiscoverItem) -> Boolean = { item -> nowPlayingIsrc != null && item.track?.isrc == nowPlayingIsrc }

        if (section.kind == FeedKind.SPOTLIGHT) {
            item(key = "feed-${section.id}") {
                val album = section.items.first()
                HeroCard(
                    item = album,
                    eyebrow = section.title,
                    cta = "Open album",
                    isPlaying = false,
                    onClick = { onAlbumClick(album) },
                    modifier = Modifier.padding(horizontal = DiscoverInset, vertical = 20.dp),
                    aspect = 1.2f,
                )
            }
            return@forEach
        }

        item(key = "feed-${section.id}-title") { DiscoverSectionTitle(section.title, section.subtitle) }
        item(key = "feed-${section.id}-body") {
            when (section.kind) {
                FeedKind.TOP_PICKS -> {
                    Unit
                }

                FeedKind.MOODS -> {
                    Unit
                }

                FeedKind.TRACK_RAILS -> {
                    if (section.id.startsWith("recent:")) {
                        // What you played last reads best as covers you can tap straight back into.
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = DiscoverInset, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            items(section.items, key = { it.id }) { song -> AlbumCard(song, isPlaying = playing(song)) { play(song) } }
                        }
                    } else {
                        TrackRails(section.items, playing, play, onMoreClick)
                    }
                }

                FeedKind.TRACK_CAROUSEL, FeedKind.QUICK_PICKS -> {
                    val cover = if (section.kind == FeedKind.QUICK_PICKS) 176.dp else 144.dp
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = DiscoverInset, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        items(section.items, key = { it.id }) { song -> AlbumCard(song, isPlaying = playing(song), size = cover) { play(song) } }
                    }
                }

                FeedKind.ALBUM_CAROUSEL -> {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = DiscoverInset, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        items(section.items, key = { it.id }) { album -> AlbumCard(album) { onAlbumClick(album) } }
                    }
                }

                FeedKind.ARTIST_CAROUSEL -> {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = DiscoverInset, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        items(section.items, key = { it.id }) { artist -> ArtistBubble(artist) { onArtistItemClick(artist) } }
                    }
                }

                FeedKind.SPOTLIGHT -> {
                    Unit
                }
            }
        }
    }
}

@Composable
private fun TrackRails(
    items: List<DiscoverItem>,
    isPlaying: (DiscoverItem) -> Boolean,
    onPlay: (DiscoverItem) -> Unit,
    onMore: (Track) -> Unit,
) {
    val pages = remember(items) { items.chunked(3) }
    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        itemsIndexed(pages, key = { index, _ -> index }) { _, page ->
            Column(Modifier.fillParentMaxWidth(0.9f)) {
                page.forEach { item ->
                    val track = item.track
                    ChartRow(
                        rank = null,
                        item = item,
                        isPlaying = isPlaying(item),
                        onClick = { onPlay(item) },
                        onMore = track?.let { t -> { onMore(t) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun FeedFooter(
    count: Int,
    exhausted: Boolean,
    failed: Boolean,
    onLoadMore: () -> Unit,
) {
    LaunchedEffect(count, exhausted, failed) { if (!exhausted && !failed) onLoadMore() }
    Box(Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 12.dp).height(56.dp), contentAlignment = Alignment.Center) {
        when {
            failed -> {
                Text(
                    "Couldn\u2019t load more. Tap to retry",
                    style = MaterialTheme.typography.labelLarge,
                    color = TextSecondary,
                    modifier = Modifier.pressScale(onClick = onLoadMore, scaleDown = 0.92f).padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            exhausted -> {
                Text(
                    "You\u2019re all caught up \u00B7 pull down for a fresh mix",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextTertiary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }

            else -> {
                CircularProgressIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.primary, strokeWidth = 2.dp)
            }
        }
    }
}