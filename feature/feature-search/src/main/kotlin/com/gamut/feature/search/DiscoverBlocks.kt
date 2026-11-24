package com.gamut.feature.search

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gamut.core.designsystem.ArtImage
import com.gamut.core.designsystem.ExplicitMark
import com.gamut.core.designsystem.NowPlayingBars
import com.gamut.core.designsystem.TablerIcons
import com.gamut.core.designsystem.ink
import com.gamut.core.designsystem.pressScale
import com.gamut.core.designsystem.squareArtUrl
import com.gamut.core.model.Track
import kotlin.math.absoluteValue

/*
 * The blocks Discover is made of, top to bottom:
 *   1. "Made for you": station cards, mixes on their cover and radio on a colour with artist photos
 *   2. the listener's own shelves (see DiscoverSection.kt)
 *   3. a cover-led hero carousel, in the middle of the page: what is new for the listener, then the week's biggest songs
 *   4. "Find your mood": flat colour cards with a tilted cover
 *   5. the rest of the shelves, charts and artists, styled to match
 *
 * Nothing here animates on its own and no artwork is downloaded for a station, so the page costs
 * nothing while idle.
 */

/** The page's side margin. Every Discover row, title and card lines up on it. */
internal val DiscoverInset: Dp = 20.dp

private const val HERO_COUNT = 6
private const val HERO_ASPECT = 0.84f
private val HeroShape = RoundedCornerShape(28.dp)
private val HeroDot = Color(0xFFFF5C5C)
private val StationCardSize = 156.dp

// ---- Moods --------------------------------------------------------------------------------------------------------

private val MoodCardWidth = 168.dp
private val MoodCardHeight = 92.dp
private val MoodGap = 12.dp
private val TileShape = RoundedCornerShape(10.dp)

/** One flat colour per mood, the way a browse tile is: no gradient, no drawn symbol. */
private fun moodColor(mood: Mood): Color =
    Color(
        when (mood) {
            Mood.ENERGY -> 0xFFE8451E
            Mood.CHILL -> 0xFF2D7D9A
            Mood.FOCUS -> 0xFF6B3E75
            Mood.FEEL_GOOD -> 0xFFC77B0A
            Mood.LOVE -> 0xFFD6247F
            Mood.BLUE -> 0xFF1E3264
            Mood.PARTY -> 0xFF8D67AB
            Mood.WORKOUT -> 0xFF9E1B32
            Mood.LATE_NIGHT -> 0xFF23235C
            Mood.SLEEP -> 0xFF477D95
            Mood.COMMUTE -> 0xFF1E7B4F
        },
    )

/**
 * Every mood as a colour card with a tilted cover tucked into its corner, two rows that scroll sideways. The covers
 * are artwork the page already holds (this week's biggest songs), so a card costs no request of its own.
 */
internal fun LazyListScope.discoverMoods(
    state: DiscoverState,
    loadingId: String?,
    onStation: (DiscoverItem) -> Unit,
) {
    val section = state.feed.firstOrNull { it.kind == FeedKind.MOODS } ?: return
    val moods = section.items.mapNotNull { item -> Mood.bySlug(item.id.substringAfterLast(':'))?.let { item to it } }
    if (moods.isEmpty()) return
    val covers = state.tabs.firstOrNull()?.items.orEmpty().map { it.imageUrl }.filter { it.isNotBlank() }.distinct()
    item(key = "moods-title") { DiscoverSectionTitle(section.title, section.subtitle) }
    item(key = "moods-grid") {
        LazyHorizontalGrid(
            rows = GridCells.Fixed(2),
            modifier = Modifier.fillMaxWidth().height(MoodCardHeight * 2 + MoodGap),
            contentPadding = PaddingValues(horizontal = DiscoverInset),
            horizontalArrangement = Arrangement.spacedBy(MoodGap),
            verticalArrangement = Arrangement.spacedBy(MoodGap),
        ) {
            itemsIndexed(moods, key = { _, pair -> pair.first.id }) { index, (item, mood) ->
                MoodCard(
                    label = item.title,
                    color = moodColor(mood),
                    cover = covers.takeIf { it.isNotEmpty() }?.get((index * 3) % covers.size),
                    loading = item.id == loadingId,
                ) { onStation(item) }
            }
        }
    }
}

/** A flat colour card: the mood's name top left, a cover turned on its corner at the bottom right. */
@Composable
private fun MoodCard(
    label: String,
    color: Color,
    cover: String?,
    loading: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .pressScale(onClick = onClick, scaleDown = 0.96f)
            .width(MoodCardWidth)
            .height(MoodCardHeight)
            .clip(TileShape)
            .background(color)
            .semantics { contentDescription = "$label mood station" },
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold),
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 12.dp, top = 12.dp).width(96.dp),
        )
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .offset(x = 14.dp, y = 12.dp)
                .size(70.dp)
                .graphicsLayer {
                    rotationZ = 25f
                    shadowElevation = 8.dp.toPx()
                    shape = RoundedCornerShape(5.dp)
                    clip = true
                },
        ) {
            if (cover != null) {
                ArtImage(url = squareArtUrl(cover, 200), modifier = Modifier.fillMaxSize())
            } else {
                // No artwork yet: a plain record in a lighter tone of the card.
                Canvas(Modifier.fillMaxSize()) {
                    drawRect(Color.White.copy(alpha = 0.2f))
                    drawCircle(Color.Black.copy(alpha = 0.35f), radius = size.width * 0.36f)
                    drawCircle(color, radius = size.width * 0.1f)
                }
            }
        }
        if (loading) {
            CircularProgressIndicator(
                Modifier.align(Alignment.TopEnd).padding(10.dp).size(18.dp),
                color = Color.White,
                strokeWidth = 2.dp,
            )
        }
    }
}

// ---- Hero carousel ------------------------------------------------------------------------------------------------

private enum class HeroKind { RELEASE, STATION, TRACK }

private class HeroEntry(
    val item: DiscoverItem,
    val kind: HeroKind,
    val eyebrow: String,
)

/** The listener's newest release from a favourite artist. Artist radio is a station card, not part of the carousel. */
private fun personalLead(state: DiscoverState): List<DiscoverItem> =
    state.feed
        .filter { it.kind == FeedKind.TOP_PICKS }
        .flatMap { it.items }
        .filter { it.imageUrl.isNotBlank() && it.id.startsWith(FeedRepository.DEEZER_ALBUM_PREFIX) }
        .take(1)

/** "New from Dua Lipa" for a release (its subtitle reads "Dua Lipa · 3 days ago"); the card's own label otherwise. */
private fun heroEyebrow(entry: HeroEntry): String {
    if (entry.kind != HeroKind.RELEASE) return entry.eyebrow
    val artist = entry.item.subtitle.substringBefore(" \u00B7 ").trim()
    return if (artist.isEmpty()) "New release" else "New from $artist"
}

internal fun LazyListScope.discoverHero(
    state: DiscoverState,
    nowPlayingIsrc: String?,
    onStation: (DiscoverItem) -> Unit,
    onAlbum: (DiscoverItem) -> Unit,
    onTrack: (Track, List<Track>) -> Unit,
) {
    val trending = state.tabs.firstOrNull()?.items.orEmpty().filter { it.track != null }
    val personal = personalLead(state)
    val entries =
        personal.map { item ->
            HeroEntry(item, if (item.id.startsWith(FeedRepository.DEEZER_ALBUM_PREFIX)) HeroKind.RELEASE else HeroKind.STATION, item.meta.orEmpty())
        } +
            trending
                .filter { it.imageUrl.isNotBlank() }
                .take(HERO_COUNT - personal.size)
                .mapIndexed { index, item -> HeroEntry(item, HeroKind.TRACK, "#${index + 1} Trending") }
    if (entries.isEmpty()) return
    item(key = "hero-title") { DiscoverSectionTitle("New and trending") }
    item(key = "hero") {
        val queue = remember(trending) { trending.mapNotNull { it.track } }
        HeroCarousel(entries, nowPlayingIsrc) { entry ->
            when (entry.kind) {
                HeroKind.RELEASE -> onAlbum(entry.item)
                HeroKind.STATION -> onStation(entry.item)
                HeroKind.TRACK -> entry.item.track?.let { onTrack(it, queue) }
            }
        }
    }
}

@Composable
private fun HeroCarousel(
    entries: List<HeroEntry>,
    nowPlayingIsrc: String?,
    onClick: (HeroEntry) -> Unit,
) {
    val pager = rememberPagerState(pageCount = { entries.size })
    val current = pager.currentPage.coerceIn(0, entries.lastIndex)
    // One card per screen with a sliver of the next one showing, so it reads as swipeable.
    HorizontalPager(
        state = pager,
        contentPadding = PaddingValues(horizontal = DiscoverInset),
        pageSpacing = 12.dp,
        key = { entries[it].item.id },
        modifier = Modifier.padding(top = 4.dp),
    ) { page ->
        val entry = entries[page]
        val playing = entry.item.track?.isrc != null && entry.item.track?.isrc == nowPlayingIsrc
        HeroCard(
            item = entry.item,
            eyebrow = heroEyebrow(entry),
            cta = if (entry.kind == HeroKind.RELEASE) "Open album" else "Play",
            isPlaying = playing,
            onClick = { onClick(entry) },
            modifier =
                Modifier.graphicsLayer {
                    val distance = ((pager.currentPage - page) + pager.currentPageOffsetFraction).absoluteValue.coerceIn(0f, 1f)
                    val scale = 1f - 0.04f * distance
                    scaleX = scale
                    scaleY = scale
                    alpha = 1f - 0.35f * distance
                },
            indicator = { if (entries.size > 1) PagerDashes(entries.size, current) },
        )
    }
}

/**
 * The big cover card: artwork edge to edge, a scrim for the text, an eyebrow with a live dot, the title, who it is
 * by, then a white call-to-action pill and, when given, [indicator] on the right. The whole card is the tap target.
 */
@Composable
internal fun HeroCard(
    item: DiscoverItem,
    eyebrow: String,
    cta: String,
    isPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    aspect: Float = HERO_ASPECT,
    indicator: @Composable () -> Unit = {},
) {
    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(aspect)
            .pressScale(onClick = onClick, scaleDown = 0.985f)
            .clip(HeroShape)
            .background(ink(0.06f)),
    ) {
        ArtImage(
            url = remember(item.imageUrl) { squareArtUrl(item.imageUrl, 800) },
            modifier = Modifier.fillMaxSize(),
            contentDescription = item.title,
            fallback = { ArtworkFallbackIcon() },
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0.28f to Color.Transparent, 0.6f to Color.Black.copy(alpha = 0.38f), 1f to Color.Black.copy(alpha = 0.9f)),
            ),
        )
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(HeroDot))
                Spacer(Modifier.width(8.dp))
                Text(
                    eyebrow.uppercase(),
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp),
                    color = Color.White.copy(alpha = 0.82f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                ExplicitMark(item.track?.isExplicit, Color.White.copy(alpha = 0.78f), size = 18.dp)
                Text(
                    item.title,
                    style = MaterialTheme.typography.headlineLarge.copy(fontSize = 30.sp, lineHeight = 34.sp),
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                item.subtitle,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
                color = Color.White.copy(alpha = 0.78f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                HeroButton(cta, isPlaying)
                Spacer(Modifier.weight(1f))
                indicator()
            }
        }
    }
}

@Composable
private fun HeroButton(
    label: String,
    playing: Boolean,
) {
    Row(
        Modifier.height(44.dp).clip(CircleShape).background(Color.White).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (playing) {
            NowPlayingBars(color = Color.Black)
        } else {
            Icon(TablerIcons.Play, null, tint = Color.Black, modifier = Modifier.size(14.dp))
        }
        Spacer(Modifier.width(8.dp))
        Text(
            if (playing) "Playing" else label,
            style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
            color = Color.Black,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** The current page is a short bar, the others dots; the bar glides as the pager moves. */
@Composable
private fun PagerDashes(
    count: Int,
    current: Int,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(count) { i ->
            val width by animateDpAsState(if (i == current) 18.dp else 4.dp, tween(220), label = "hero-dash")
            Box(
                Modifier
                    .height(4.dp)
                    .width(width)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = if (i == current) 1f else 0.4f)),
            )
        }
    }
}

// ---- Made for you -------------------------------------------------------------------------------------------------

private val OnPastel = Color(0xFF121212)

/** Soft card colours for radio cards and unlabelled stations. */
private val PastelColors =
    listOf(0xFFF8A9BE, 0xFF8EE3CF, 0xFFFFD579, 0xFFAFC8FF, 0xFFC9B6F2, 0xFFFFB38A, 0xFFB5E48C, 0xFF9AD9F5, 0xFFF2A6E0, 0xFFE6CF8B).map { Color(it) }

/** Label colours for the cover cards: light, a little different from card to card. */
private val TagColors =
    listOf(0xFFF4F24B, 0xFFD9D4FF, 0xFFB8F2E6, 0xFFFFC9D9, 0xFFFFD7A8, 0xFFC4E4FF, 0xFFE2F5A3, 0xFFFFB3A7).map { Color(it) }

/** The shelf of station cards whose section id starts with [family]: "picks", "mixes" or "radio". */
private fun shelf(
    state: DiscoverState,
    family: String,
): Pair<FeedSection, List<DiscoverItem>>? {
    val section = state.feed.firstOrNull { it.kind == FeedKind.TOP_PICKS && it.id.substringBefore(':') == family } ?: return null
    // The latest release belongs to the carousel and the moods have their own cards.
    val rows = section.items.filter { !it.id.startsWith(FeedRepository.DEEZER_ALBUM_PREFIX) && StationId.kindOf(it.id) != StationId.MOOD }
    return if (rows.isEmpty()) null else section to rows
}

/**
 * One shelf of station cards: "Made for you" (picks), "Your top mixes" or "Popular radio". The first shelf on the page
 * sits close under the search field instead of leaving the usual gap for a title.
 */
internal fun LazyListScope.discoverStations(
    state: DiscoverState,
    loadingId: String?,
    onStation: (DiscoverItem) -> Unit,
    family: String,
) {
    val (section, rows) = shelf(state, family) ?: return
    val first = family == "picks" || (family == "mixes" && shelf(state, "picks") == null)
    val title = if (family == "picks") "Made for you" else section.title
    item(key = "$family-title") { DiscoverSectionTitle(title, section.subtitle, topPadding = if (first) 4.dp else 32.dp) }
    item(key = "$family-rows") {
        LazyRow(
            contentPadding = PaddingValues(horizontal = DiscoverInset, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            itemsIndexed(rows, key = { _, row -> row.id }) { index, row ->
                StationCard(row, index, loading = row.id == loadingId) { onStation(row) }
            }
        }
    }
}

private fun stationBadge(item: DiscoverItem): String =
    if (item.meta == "Mix") {
        "MIX"
    } else when (StationId.kindOf(item.id)) {
        StationId.MIX -> "MIX"
        StationId.DISCOVER -> "DISCOVERY"
        StationId.ARTIST -> "RADIO"
        StationId.CHART -> "CHARTS"
        else -> item.meta.orEmpty().uppercase()
    }

/**
 * A square station card with its names underneath. A mix wears its cover art with a colour label across the bottom;
 * a radio is a colour with the artists' photos in a row. Tapping anywhere plays it.
 */
@Composable
private fun StationCard(
    item: DiscoverItem,
    index: Int,
    loading: Boolean,
    onClick: () -> Unit,
) {
    val onCover = item.imageUrl.isNotBlank() && StationId.kindOf(item.id) != StationId.ARTIST
    Column(Modifier.width(StationCardSize).pressScale(onClick = onClick, scaleDown = 0.96f)) {
        Box(Modifier.size(StationCardSize).clip(TileShape)) {
            if (onCover) CoverFace(item, index) else ColorFace(item, index)
            if (loading) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            item.subtitle,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 18.sp),
            color = TextSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The small round mark in a card's corner, in the card's own accent. */
@Composable
private fun BoxScope.CornerMark(color: Color) {
    Box(
        Modifier.align(Alignment.TopStart).padding(10.dp).size(24.dp).clip(CircleShape).background(color),
        contentAlignment = Alignment.Center,
    ) {
        Icon(TablerIcons.Play, null, tint = OnPastel, modifier = Modifier.size(10.dp))
    }
}

/** Full-bleed artwork with a colour label across the bottom: a pale stripe on the left, then the name. */
@Composable
private fun BoxScope.CoverFace(
    item: DiscoverItem,
    index: Int,
) {
    val accent = TagColors[index % TagColors.size]
    ArtImage(url = squareArtUrl(item.imageUrl, 400), modifier = Modifier.fillMaxSize())
    CornerMark(accent)
    Row(Modifier.align(Alignment.BottomStart).padding(bottom = 14.dp).fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(Modifier.width(8.dp).fillMaxHeight().background(lerp(accent, Color.White, 0.55f)))
        Box(Modifier.weight(1f).background(accent).padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(
                item.title,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold),
                color = OnPastel,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A soft colour, the kind of station top right, artist photos in a row (the first, in the middle, largest), the name below. */
@Composable
private fun BoxScope.ColorFace(
    item: DiscoverItem,
    index: Int,
) {
    val color = PastelColors[index % PastelColors.size]
    val photos = item.images.ifEmpty { listOf(item.imageUrl).filter { it.isNotBlank() } }.take(3)
    Box(Modifier.fillMaxSize().background(color))
    CornerMark(Color.White)
    Text(
        stationBadge(item),
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp),
        color = OnPastel,
        maxLines = 1,
        modifier = Modifier.align(Alignment.TopEnd).padding(end = 12.dp, top = 14.dp),
    )
    photos.getOrNull(1)?.let { url ->
        ArtImage(
            url = squareArtUrl(url, 200),
            modifier = Modifier.align(Alignment.TopStart).offset(x = (-18).dp, y = 40.dp).size(56.dp).clip(CircleShape),
        )
    }
    photos.getOrNull(2)?.let { url ->
        ArtImage(
            url = squareArtUrl(url, 200),
            modifier = Modifier.align(Alignment.TopEnd).offset(x = 18.dp, y = 40.dp).size(56.dp).clip(CircleShape),
        )
    }
    photos.getOrNull(0)?.let { url ->
        ArtImage(
            url = squareArtUrl(url, 300),
            modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = 28.dp)
                    .size(78.dp)
                    .border(3.dp, Color.White.copy(alpha = 0.7f), CircleShape)
                    .clip(CircleShape),
        )
    }
    val withPhotos = photos.isNotEmpty()
    Text(
        item.title.removeSuffix(" Radio"),
        style =
            MaterialTheme.typography.titleLarge.copy(
                fontSize = if (withPhotos) 17.sp else 22.sp,
                lineHeight = if (withPhotos) 20.sp else 26.sp,
                fontWeight = FontWeight.ExtraBold,
            ),
        color = OnPastel,
        maxLines = if (withPhotos) 1 else 3,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
    )
}

/** Stands in for the station row while the page is built, at about its size, so nothing jumps when it arrives. */
@Composable
internal fun TopPicksSkeleton() {
    Row(
        Modifier.padding(horizontal = DiscoverInset, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        repeat(2) { Box(Modifier.size(StationCardSize).clip(TileShape).background(ink(0.06f))) }
    }
}
