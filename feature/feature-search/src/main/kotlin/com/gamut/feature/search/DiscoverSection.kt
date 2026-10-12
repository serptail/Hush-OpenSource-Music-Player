package com.gamut.feature.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Light
import com.adamglin.phosphoricons.light.CaretRight
import com.adamglin.phosphoricons.light.DotsThreeVertical
import com.adamglin.phosphoricons.light.Play
import com.gamut.core.designsystem.ArtImage
import com.gamut.core.designsystem.NowPlayingBars
import com.gamut.core.designsystem.TrackRowSkeleton
import com.gamut.core.designsystem.ink
import com.gamut.core.designsystem.pressScale
import com.gamut.core.designsystem.squareArtUrl
import com.gamut.core.model.Track

// Discover, top to bottom:
//   1. Hero        the #1 song of the blended Trending chart, big, one tap to play
//   2. Artists     round portraits, horizontal
//   3. Albums      square covers, horizontal
//   4. Charts      pill tabs (Trending / Billboard / Latest / Albums) + a numbered list, 10 rows, "Show all" for more
//
// Chart rows are separate LazyColumn items (the old tab list was one eager item), so 50 rows cost nothing off screen.
// Every block adds its own 24dp side gutter; rows use 16dp + 8dp inner padding so their text edge also lands on 24dp.

/** Tabs whose order is a ranking: they get position numbers. Latest and Albums are not rankings. */
private val NUMBERED_TABS = setOf("Trending", "Billboard")
private const val COLLAPSED_ROWS = 10

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
) {
    // Nothing yet: placeholders with the final shape, so the page does not jump when data lands.
    if (state.tabs.isEmpty()) {
        if (state.error != null && !state.isLoading) {
            item(key = "discover-error") {
                Text(
                    state.error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 48.dp),
                )
            }
        } else {
            item(key = "discover-skeleton-hero") {
                Box(
                    Modifier
                        .padding(horizontal = 24.dp, vertical = 8.dp)
                        .fillMaxWidth()
                        .aspectRatio(1.35f)
                        .clip(CardShapeLarge)
                        .background(ink(0.06f)),
                )
            }
            items(6, key = { "discover-skeleton-$it" }) { TrackRowSkeleton(Modifier.padding(horizontal = 24.dp)) }
        }
        return
    }

    val trending = state.tabs.first()
    val heroItem = trending.items.firstOrNull { it.track != null }

    // 1. Hero ------------------------------------------------------------------------------------------------------
    if (heroItem != null) {
        item(key = "hero") {
            HeroCard(
                item = heroItem,
                isPlaying = heroItem.track?.isrc == nowPlayingIsrc,
                onClick = { heroItem.track?.let { onTrackClick(it, trending.items.mapNotNull { i -> i.track }) } },
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
        }
    }

    // 2. Artists ---------------------------------------------------------------------------------------------------
    if (state.artists.isNotEmpty()) {
        item(key = "artists-title") { DiscoverSectionTitle("Artists to know") }
        item(key = "artists-row") {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                items(state.artists, key = { it.id }) { artist -> ArtistBubble(artist) { artist.artistId?.let(onArtistClick) } }
            }
        }
    }

    // 3. Albums ----------------------------------------------------------------------------------------------------
    if (state.albums.isNotEmpty()) {
        item(key = "albums-title") { DiscoverSectionTitle("Albums on the rise") }
        item(key = "albums-row") {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(state.albums, key = { it.id }) { album -> AlbumCard(album) { onAlbumClick(album) } }
            }
        }
    }

    // 4. Charts ----------------------------------------------------------------------------------------------------
    val tabIndex = selectedTab.coerceIn(0, state.tabs.lastIndex)
    val tab = state.tabs[tabIndex]
    item(key = "charts-title") { DiscoverSectionTitle("Charts") }
    item(key = "charts-tabs") {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(state.tabs, key = { _, t -> t.label }) { i, t -> Pill(t.label, i == tabIndex) { onSelectTab(i) } }
        }
    }
    item(key = "charts-caption") {
        Text(
            tab.title,
            style = MaterialTheme.typography.labelLarge,
            color = TextTertiary,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 14.dp, bottom = 4.dp),
        )
    }

    val numbered = tab.label in NUMBERED_TABS
    val queue = tab.items.mapNotNull { it.track }
    val shown = if (chartExpanded) tab.items else tab.items.take(COLLAPSED_ROWS)
    itemsIndexed(shown, key = { i, it -> "chart-${tab.label}-$i-${it.id}" }) { index, item ->
        val track = item.track
        Box(Modifier.padding(horizontal = 16.dp)) {
            ChartRow(
                rank = if (numbered) index + 1 else null,
                item = item,
                isPlaying = track != null && track.isrc == nowPlayingIsrc,
                onClick = { if (track != null) onTrackClick(track, queue) else onAlbumClick(item) },
                onMore = track?.let { t -> { onMoreClick(t) } },
            )
        }
    }
    if (tab.items.size > COLLAPSED_ROWS) {
        item(key = "charts-more") {
            Box(Modifier.fillMaxWidth().padding(top = 12.dp), contentAlignment = Alignment.Center) {
                Pill(if (chartExpanded) "Show less" else "Show all ${tab.items.size}", false, onToggleChartExpanded)
            }
        }
    }
}

@Composable
private fun DiscoverSectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        color = TextPrimary,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 12.dp),
    )
}

/** The #1 song: full-width cover with a soft scrim, rank chip, title, and a round play button. */
@Composable
private fun HeroCard(item: DiscoverItem, isPlaying: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(1.35f)
            .shadow(18.dp, CardShapeLarge, clip = false, ambientColor = Color.Black.copy(alpha = 0.25f), spotColor = Color.Black.copy(alpha = 0.35f))
            .pressScale(onClick = onClick, scaleDown = 0.98f)
            .clip(CardShapeLarge),
    ) {
        ArtImage(
            url = remember(item.imageUrl) { squareArtUrl(item.imageUrl, 600) },
            modifier = Modifier.fillMaxSize(),
            contentDescription = item.title,
            fallback = { ArtworkFallbackIcon() },
        )
        // Scrim: only the bottom half darkens, so the cover keeps its colour where the text is not.
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.72f)),
            ),
        )
        Column(
            Modifier.align(Alignment.BottomStart).padding(start = 20.dp, end = 80.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Box(Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.2f)).padding(horizontal = 10.dp, vertical = 3.dp)) {
                Text("#1 Trending", style = MaterialTheme.typography.labelMedium, color = Color.White)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                item.title,
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                item.subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.78f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // Just the glyph on the scrim: no circle behind it.
        Box(Modifier.align(Alignment.BottomEnd).padding(18.dp).size(44.dp), contentAlignment = Alignment.Center) {
            if (isPlaying) NowPlayingBars(color = Color.White)
            else Icon(PhosphorIcons.Light.Play, "Play", tint = Color.White.copy(alpha = 0.95f), modifier = Modifier.size(34.dp))
        }
    }
}

@Composable
private fun ArtistBubble(item: DiscoverItem, onClick: () -> Unit) {
    Column(
        Modifier.width(88.dp).pressScale(onClick = onClick, scaleDown = 0.94f),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ArtImage(
            url = item.imageUrl,
            modifier = Modifier
                .size(88.dp)
                .shadow(8.dp, CircleShape, clip = false, ambientColor = Color.Black.copy(alpha = 0.22f), spotColor = Color.Black.copy(alpha = 0.3f))
                .clip(CircleShape),
            contentDescription = item.title,
            fallback = { ArtworkFallbackIcon() },
        )
        Spacer(Modifier.height(10.dp))
        Text(
            item.title,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
            color = TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        item.followers?.toLong()?.takeIf { it > 0 }?.let {
            Text("${formatCount(it)} fans", style = MaterialTheme.typography.labelMedium, color = TextTertiary, maxLines = 1)
        }
    }
}

@Composable
private fun AlbumCard(item: DiscoverItem, onClick: () -> Unit) {
    Column(Modifier.width(148.dp).pressScale(onClick = onClick, scaleDown = 0.95f)) {
        ArtImage(
            url = remember(item.imageUrl) { squareArtUrl(item.imageUrl, 400) },
            modifier = Modifier
                .size(148.dp)
                .shadow(10.dp, RoundedCornerShape(14.dp), clip = false, ambientColor = Color.Black.copy(alpha = 0.22f), spotColor = Color.Black.copy(alpha = 0.3f))
                .clip(RoundedCornerShape(14.dp)),
            contentDescription = item.title,
            fallback = { ArtworkFallbackIcon() },
        )
        Spacer(Modifier.height(10.dp))
        Text(
            item.title,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
            color = TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            item.subtitle,
            style = MaterialTheme.typography.labelMedium,
            color = TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * One chart line: position number with its weekly movement underneath (Billboard), cover, title + artist + a small
 * meta line (peak and weeks, or release age), then three dots for songs or a chevron for albums. A playing song swaps
 * the number for animated bars and takes the accent.
 */
@Composable
private fun ChartRow(rank: Int?, item: DiscoverItem, isPlaying: Boolean, onClick: () -> Unit, onMore: (() -> Unit)?) {
    val accent = MaterialTheme.colorScheme.primary
    InteractiveSurface(
        shape = RowShape,
        pressedScale = 0.97f,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
    ) { _ ->
        if (rank != null) {
            Column(Modifier.width(40.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                if (isPlaying) NowPlayingBars(color = accent)
                else Text(
                    rank.toString(),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = if (rank <= 3) FontWeight.SemiBold else FontWeight.Normal),
                    color = if (rank <= 3) TextPrimary else TextTertiary,
                )
                TrendMark(item.trend, item.trendDelta, accent)
            }
        }
        ArtImage(
            url = remember(item.imageUrl) { squareArtUrl(item.imageUrl, 256) },
            modifier = Modifier
                .size(52.dp)
                .shadow(6.dp, ThumbShape, clip = false, ambientColor = Color.Black.copy(alpha = 0.20f), spotColor = Color.Black.copy(alpha = 0.30f))
                .clip(ThumbShape),
            fallback = { ArtworkFallbackIcon() },
        )
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(
                item.title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = if (isPlaying) FontWeight.SemiBold else FontWeight.Normal),
                color = if (isPlaying) accent else ink(0.9f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                item.subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            item.meta?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, color = TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (onMore != null) RowIconButton(PhosphorIcons.Light.DotsThreeVertical, "More options", ink(0.6f), onMore)
        else Icon(PhosphorIcons.Light.CaretRight, null, tint = ink(0.4f), modifier = Modifier.padding(end = 8.dp).size(18.dp))
    }
}

/** Weekly movement: green up, red down, accent NEW, a dash when unchanged. Nothing for charts without movement. */
@Composable
private fun TrendMark(trend: Trend?, delta: Int, accent: Color) {
    val small = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
    when (trend) {
        null -> Unit
        Trend.NEW -> Text("NEW", style = small, color = accent)
        Trend.UP -> Text("\u25B2$delta", style = small, color = Color(0xFF3DDC84))
        Trend.DOWN -> Text("\u25BC$delta", style = small, color = Color(0xFFFF6B6B))
        Trend.SAME -> Text("\u2013", style = small, color = TextTertiary)
    }
}