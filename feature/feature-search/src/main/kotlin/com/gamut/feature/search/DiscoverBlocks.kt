package com.gamut.feature.search

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.fill.Play
import com.gamut.core.designsystem.ArtImage
import com.gamut.core.designsystem.ExplicitMark
import com.gamut.core.designsystem.NowPlayingBars
import com.gamut.core.designsystem.ink
import com.gamut.core.designsystem.pressScale
import com.gamut.core.designsystem.squareArtUrl
import com.gamut.core.model.Track
import kotlin.math.PI
import kotlin.math.absoluteValue
import kotlin.math.cos
import kotlin.math.sin

/*
 * The blocks Discover is made of, top to bottom:
 *   1. mood pills: one-tap stations, scrolling sideways under the search field
 *   2. a cover-led hero carousel: what is new for the listener, then the week's biggest songs
 *   3. "Made for you": station cards
 *   4. the shelves, charts and artists, styled to match (see DiscoverSection.kt)
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

// ---- Mood pills ---------------------------------------------------------------------------------------------------

/** The mood stations as a sideways row of pills, the ones that suit the hour first. */
internal fun LazyListScope.discoverMoodPills(
    state: DiscoverState,
    loadingId: String?,
    onStation: (DiscoverItem) -> Unit,
) {
    val section = state.feed.firstOrNull { it.kind == FeedKind.MOODS } ?: return
    val moods = section.items.mapNotNull { item -> Mood.bySlug(item.id.substringAfterLast(':'))?.let { item to it } }
    if (moods.isEmpty()) return
    item(key = "mood-pills") {
        LazyRow(
            contentPadding = PaddingValues(horizontal = DiscoverInset, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(moods, key = { it.first.id }) { (item, mood) ->
                MoodPill(item.title, mood, loading = item.id == loadingId) { onStation(item) }
            }
        }
    }
}

/** A small tinted dot with the mood's emblem, then its name, on a quiet pill. */
@Composable
private fun MoodPill(
    label: String,
    mood: Mood,
    loading: Boolean,
    onClick: () -> Unit,
) {
    val from = Color(mood.from)
    val to = Color(mood.to)
    Row(
        Modifier
            .pressScale(onClick = onClick, scaleDown = 0.95f)
            .height(40.dp)
            .clip(CircleShape)
            .background(ink(0.07f))
            .border(1.dp, ink(0.08f), CircleShape)
            .semantics { contentDescription = "$label mood station" }
            .padding(start = 8.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            if (loading) {
                CircularProgressIndicator(Modifier.size(18.dp), color = TextPrimary, strokeWidth = 2.dp)
            } else {
                Canvas(Modifier.size(24.dp).clip(CircleShape)) {
                    drawRect(Brush.linearGradient(listOf(from, to), Offset.Zero, Offset(size.width, size.height)))
                    drawEmblem(mood, Offset(size.width / 2f, size.height / 2f), size.width * 0.5f, Color.White.copy(alpha = 0.95f), 1.3.dp.toPx())
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = TextPrimary,
            maxLines = 1,
            softWrap = false,
        )
    }
}

// ---- Hero carousel ------------------------------------------------------------------------------------------------

private enum class HeroKind { RELEASE, STATION, TRACK }

private class HeroEntry(
    val item: DiscoverItem,
    val kind: HeroKind,
    val eyebrow: String,
)

/** The station cards that carry a picture of their own: the latest release and the artist's radio. */
private fun personalLead(state: DiscoverState): List<DiscoverItem> =
    state.feed
        .filter { it.kind == FeedKind.TOP_PICKS }
        .flatMap { it.items }
        .filter { it.imageUrl.isNotBlank() && (it.id.startsWith(FeedRepository.DEEZER_ALBUM_PREFIX) || StationId.kindOf(it.id) == StationId.ARTIST) }
        .take(2)

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
            Icon(PhosphorIcons.Fill.Play, null, tint = Color.Black, modifier = Modifier.size(14.dp))
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

/** Station cards, leaving out what the carousel already shows and the moods, which are the pills at the top. */
internal fun LazyListScope.discoverMadeForYou(
    state: DiscoverState,
    loadingId: String?,
    onStation: (DiscoverItem) -> Unit,
) {
    val section = state.feed.firstOrNull { it.kind == FeedKind.TOP_PICKS } ?: return
    val shown = personalLead(state).mapTo(HashSet()) { it.id }
    val rows =
        section.items.filter { item ->
            item.id !in shown &&
                !item.id.startsWith(FeedRepository.DEEZER_ALBUM_PREFIX) &&
                StationId.kindOf(item.id) != StationId.MOOD
        }
    if (rows.isEmpty()) return
    item(key = "made-title") { DiscoverSectionTitle("Made for you", section.subtitle) }
    item(key = "made-rows") {
        LazyRow(
            contentPadding = PaddingValues(horizontal = DiscoverInset, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(rows, key = { it.id }) { row -> StationCard(row, loading = row.id == loadingId) { onStation(row) } }
        }
    }
}

private fun stationBadge(item: DiscoverItem): String =
    when (StationId.kindOf(item.id)) {
        StationId.MIX -> "MIX"
        StationId.DISCOVER -> "DISCOVERY"
        StationId.ARTIST -> "RADIO"
        StationId.CHART -> "CHARTS"
        else -> item.meta.orEmpty().uppercase()
    }

/** A square station cover with its kind in the corner and a round play button, its name and blurb underneath. */
@Composable
private fun StationCard(
    item: DiscoverItem,
    loading: Boolean,
    onClick: () -> Unit,
) {
    val badge = stationBadge(item)
    Column(Modifier.width(StationCardSize).pressScale(onClick = onClick, scaleDown = 0.96f)) {
        Box(Modifier.size(StationCardSize).clip(RoundedCornerShape(18.dp))) {
            StationTile(item, Modifier.fillMaxSize())
            Box(Modifier.fillMaxWidth().height(56.dp).background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.32f), 1f to Color.Transparent)))
            if (badge.isNotEmpty()) {
                Text(
                    badge,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp),
                    color = Color.White,
                    maxLines = 1,
                    modifier = Modifier.align(Alignment.TopStart).padding(start = 12.dp, top = 12.dp),
                )
            }
            Box(
                Modifier.align(Alignment.BottomEnd).padding(10.dp).size(36.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center,
            ) {
                if (loading) {
                    CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                } else {
                    Icon(PhosphorIcons.Fill.Play, "Play", tint = Color.White, modifier = Modifier.size(14.dp))
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            item.title,
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
            color = TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            item.subtitle,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp, lineHeight = 16.sp),
            color = TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A station's square: its photo where it has one, otherwise a colour wash with soft shapes and a drawn mark. */
@Composable
private fun StationTile(
    item: DiscoverItem,
    modifier: Modifier,
) {
    if (item.imageUrl.isNotBlank()) {
        ArtImage(url = squareArtUrl(item.imageUrl, 400), modifier = modifier)
        return
    }
    val kind = StationId.kindOf(item.id)
    val hue = ((item.id.hashCode().toLong() and 0x7fffffffL) % 360L).toFloat()
    val top = if (kind == StationId.MIX) Color(0xFFFF4D6D) else if (kind == StationId.DISCOVER) Color(0xFF4F5BD5) else Color.hsv(hue, 0.55f, 0.6f)
    val bottom = if (kind == StationId.MIX) Color(0xFFFF8A4C) else if (kind == StationId.DISCOVER) Color(0xFF2FB8A6) else Color.hsv((hue + 40f) % 360f, 0.6f, 0.5f)
    Canvas(modifier) {
        drawRect(Brush.linearGradient(listOf(top, bottom), Offset.Zero, Offset(this.size.width, this.size.height)))
        // Two soft discs break up the flat gradient and give the tile some depth.
        drawCircle(Color.White.copy(alpha = 0.12f), radius = this.size.width * 0.55f, center = Offset(this.size.width * 0.9f, this.size.height * 0.05f))
        drawCircle(Color.Black.copy(alpha = 0.14f), radius = this.size.width * 0.45f, center = Offset(this.size.width * 0.05f, this.size.height * 1.0f))
        inset(this.size.width * 0.24f) {
            val w = size.width
            val h = size.height
            val stroke = Stroke(width = (w / 14f).coerceAtLeast(2.dp.toPx()), cap = StrokeCap.Round, join = StrokeJoin.Round)
            val mark = Color.White.copy(alpha = 0.92f)
            val path = Path()
            when (kind) {
                StationId.MIX ->
                    listOf(0.34f, 0.2f).forEach { at ->
                        path.reset()
                        path.moveTo(w * at, h * 0.26f)
                        path.lineTo(w * (at + 0.3f), h * 0.5f)
                        path.lineTo(w * at, h * 0.74f)
                        drawPath(path, mark, style = stroke)
                    }

                StationId.DISCOVER -> {
                    val c = Offset(w / 2f, h / 2f)
                    val r = w * 0.34f
                    path.moveTo(c.x, c.y - r)
                    path.quadraticTo(c.x, c.y, c.x + r, c.y)
                    path.quadraticTo(c.x, c.y, c.x, c.y + r)
                    path.quadraticTo(c.x, c.y, c.x - r, c.y)
                    path.quadraticTo(c.x, c.y, c.x, c.y - r)
                    path.close()
                    drawPath(path, mark, style = stroke)
                }

                else ->
                    listOf(0.3f to 0.3f, 0.5f to 0.5f, 0.7f to 0.38f).forEach { (fx, fh) ->
                        drawLine(mark, Offset(w * fx, h * 0.72f), Offset(w * fx, h * (0.72f - fh)), stroke.width, StrokeCap.Round)
                    }
            }
        }
    }
}

/** One outline emblem per mood, [side] pixels square around [center]: a few strokes, legible at chip size. */
private fun DrawScope.drawEmblem(
    mood: Mood,
    center: Offset,
    side: Float,
    color: Color,
    strokeWidth: Float,
) {
    val left = center.x - side / 2f
    val top = center.y - side / 2f

    fun x(f: Float) = left + side * f

    fun y(f: Float) = top + side * f

    fun at(
        fx: Float,
        fy: Float,
    ) = Offset(x(fx), y(fy))
    val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val path = Path()

    fun polygon(vararg points: Pair<Float, Float>) {
        path.reset()
        points.forEachIndexed { i, (fx, fy) -> if (i == 0) path.moveTo(x(fx), y(fy)) else path.lineTo(x(fx), y(fy)) }
        path.close()
        drawPath(path, color, style = stroke)
    }

    when (mood) {
        Mood.ENERGY -> polygon(0.60f to 0.0f, 0.18f to 0.56f, 0.46f to 0.56f, 0.38f to 1.0f, 0.82f to 0.40f, 0.54f to 0.40f)

        Mood.CHILL ->
            listOf(0.28f, 0.5f, 0.72f).forEach { fy ->
                path.reset()
                path.moveTo(x(0.05f), y(fy))
                path.cubicTo(x(0.25f), y(fy - 0.16f), x(0.40f), y(fy - 0.16f), x(0.5f), y(fy))
                path.cubicTo(x(0.60f), y(fy + 0.16f), x(0.75f), y(fy + 0.16f), x(0.95f), y(fy))
                drawPath(path, color, style = stroke)
            }

        Mood.FOCUS -> {
            listOf(0.5f, 0.34f, 0.18f).forEach { r -> drawCircle(color, radius = side * r, center = center, style = stroke) }
            drawCircle(color, radius = side * 0.04f, center = center)
        }

        Mood.FEEL_GOOD -> {
            drawCircle(color, radius = side * 0.22f, center = center, style = stroke)
            for (i in 0 until 8) {
                val a = PI * 2 * i / 8
                val from = Offset(center.x + (side * 0.32f * cos(a)).toFloat(), center.y + (side * 0.32f * sin(a)).toFloat())
                val to = Offset(center.x + (side * 0.46f * cos(a)).toFloat(), center.y + (side * 0.46f * sin(a)).toFloat())
                drawLine(color, from, to, strokeWidth, StrokeCap.Round)
            }
        }

        Mood.LOVE -> {
            path.reset()
            path.moveTo(x(0.5f), y(0.92f))
            path.cubicTo(x(-0.15f), y(0.45f), x(0.15f), y(-0.1f), x(0.5f), y(0.28f))
            path.cubicTo(x(0.85f), y(-0.1f), x(1.15f), y(0.45f), x(0.5f), y(0.92f))
            path.close()
            drawPath(path, color, style = stroke)
        }

        Mood.BLUE -> {
            path.reset()
            path.moveTo(x(0.5f), y(0.0f))
            path.cubicTo(x(0.62f), y(0.28f), x(0.84f), y(0.46f), x(0.84f), y(0.68f))
            path.cubicTo(x(0.84f), y(0.90f), x(0.68f), y(1.0f), x(0.5f), y(1.0f))
            path.cubicTo(x(0.32f), y(1.0f), x(0.16f), y(0.90f), x(0.16f), y(0.68f))
            path.cubicTo(x(0.16f), y(0.46f), x(0.38f), y(0.28f), x(0.5f), y(0.0f))
            path.close()
            drawPath(path, color, style = stroke)
        }

        Mood.PARTY -> {
            fun sparkle(
                c: Offset,
                r: Float,
            ) {
                path.reset()
                path.moveTo(c.x, c.y - r)
                path.quadraticTo(c.x, c.y, c.x + r, c.y)
                path.quadraticTo(c.x, c.y, c.x, c.y + r)
                path.quadraticTo(c.x, c.y, c.x - r, c.y)
                path.quadraticTo(c.x, c.y, c.x, c.y - r)
                path.close()
                drawPath(path, color, style = stroke)
            }
            sparkle(at(0.42f, 0.55f), side * 0.40f)
            sparkle(at(0.84f, 0.16f), side * 0.16f)
            sparkle(at(0.86f, 0.84f), side * 0.12f)
        }

        Mood.WORKOUT ->
            listOf(0.18f to 0.45f, 0.39f to 0.7f, 0.61f to 0.55f, 0.82f to 0.95f).forEach { (fx, h) ->
                drawLine(color, at(fx, 1f), at(fx, 1f - h), strokeWidth * 2.2f, StrokeCap.Round)
            }

        Mood.LATE_NIGHT -> {
            val moon = Path().apply { addOval(Rect(center, side * 0.4f)) }
            val bite = Path().apply { addOval(Rect(Offset(center.x + side * 0.2f, center.y - side * 0.12f), side * 0.34f)) }
            drawPath(Path.combine(PathOperation.Difference, moon, bite), color, style = stroke)
            drawCircle(color, radius = strokeWidth * 0.9f, center = at(0.8f, 0.2f))
            drawCircle(color, radius = strokeWidth * 0.7f, center = at(0.62f, 0.06f))
        }

        Mood.SLEEP ->
            listOf(Triple(0.08f, 0.62f, 0.26f), Triple(0.36f, 0.34f, 0.34f), Triple(0.66f, 0.04f, 0.30f)).forEach { (fx, fy, s) ->
                path.reset()
                path.moveTo(x(fx), y(fy))
                path.lineTo(x(fx + s), y(fy))
                path.lineTo(x(fx), y(fy + s))
                path.lineTo(x(fx + s), y(fy + s))
                drawPath(path, color, style = stroke)
            }

        Mood.COMMUTE -> {
            drawLine(color, at(0.08f, 1f), at(0.44f, 0.04f), strokeWidth, StrokeCap.Round)
            drawLine(color, at(0.92f, 1f), at(0.56f, 0.04f), strokeWidth, StrokeCap.Round)
            drawLine(color, at(0.5f, 1f), at(0.5f, 0.06f), strokeWidth, StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(side * 0.1f, side * 0.1f)))
        }
    }
}


/** Stands in for the carousel while the page is built, at about its size, so nothing jumps when it arrives. */
@Composable
internal fun TopPicksSkeleton() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = DiscoverInset, vertical = 4.dp)
            .aspectRatio(HERO_ASPECT)
            .clip(HeroShape)
            .background(ink(0.06f)),
    )
}
