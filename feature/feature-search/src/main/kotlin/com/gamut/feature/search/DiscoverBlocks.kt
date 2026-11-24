package com.gamut.feature.search

import androidx.compose.animation.Crossfade
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
import androidx.compose.foundation.lazy.LazyListScope
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
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
 *   1. a cover-led carousel: what is new for the listener, then the week's biggest songs
 *   2. "Made for you": station rows
 *   3. the shelves the page already had, restyled flat
 *   4. "Find your mood": dense shortcut chips, each with a tinted tile and an emblem
 *
 * Nothing here animates on its own and no artwork is downloaded for a station, so the page costs
 * nothing while idle.
 */

private const val HERO_COUNT = 6
private const val MOOD_COLLAPSED = 6

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
    val background = MaterialTheme.colorScheme.background
    Box(Modifier.fillMaxWidth()) {
        // The cover's colours, softened, bleeding up behind the carousel and out into the page.
        Crossfade(targetState = entries[current].item.imageUrl, label = "hero-tint") { url ->
            ArtImage(url = squareArtUrl(url, 160), modifier = Modifier.fillMaxWidth().height(460.dp).graphicsLayer { alpha = 0.55f }.blur(60.dp))
        }
        Box(Modifier.fillMaxWidth().height(460.dp).background(Brush.verticalGradient(0f to background.copy(alpha = 0.35f), 1f to background)))
        Column(Modifier.padding(top = 12.dp)) {
            HorizontalPager(
                state = pager,
                contentPadding = PaddingValues(horizontal = 34.dp, vertical = 12.dp),
                pageSpacing = 14.dp,
                key = { entries[it].item.id },
            ) { page ->
                val entry = entries[page]
                val playing = entry.item.track?.isrc != null && entry.item.track?.isrc == nowPlayingIsrc
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(0.8f)
                        .graphicsLayer {
                            val distance = ((pager.currentPage - page) + pager.currentPageOffsetFraction).absoluteValue.coerceIn(0f, 1f)
                            val scale = 1f - 0.07f * distance
                            scaleX = scale
                            scaleY = scale
                            alpha = 1f - 0.4f * distance
                        }.pressScale(onClick = { onClick(entry) }, scaleDown = 0.98f)
                        .clip(RoundedCornerShape(18.dp)),
                ) {
                    ArtImage(url = squareArtUrl(entry.item.imageUrl, 700), modifier = Modifier.fillMaxSize(), contentDescription = entry.item.title)
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.8f))))
                    Column(Modifier.align(Alignment.BottomStart).padding(start = 18.dp, end = 64.dp, bottom = 18.dp)) {
                        Text(
                            entry.eyebrow.uppercase(),
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp),
                            color = Color.White.copy(alpha = 0.72f),
                            maxLines = 1,
                        )
                        Text(
                            entry.item.title,
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 28.sp),
                            color = Color.White,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                        Text(
                            entry.item.subtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.8f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (entry.kind != HeroKind.RELEASE) {
                        Box(Modifier.align(Alignment.BottomEnd).padding(16.dp).size(40.dp), contentAlignment = Alignment.Center) {
                            if (playing) {
                                NowPlayingBars(color = Color.White)
                            } else {
                                Icon(PhosphorIcons.Fill.Play, "Play", tint = Color.White, modifier = Modifier.size(26.dp))
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.Center) {
                repeat(entries.size) { i ->
                    Box(
                        Modifier
                            .padding(horizontal = 3.dp)
                            .size(if (i == current) 7.dp else 5.dp)
                            .clip(CircleShape)
                            .background(if (i == current) TextPrimary else ink(0.25f)),
                    )
                }
            }
        }
    }
}

// ---- Made for you -------------------------------------------------------------------------------------------------

/** Station rows, leaving out what the carousel already shows and the moods, which have their own grid. */
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
        Column(Modifier.padding(horizontal = 24.dp)) {
            rows.forEach { row ->
                StationRow(row, loading = row.id == loadingId) { onStation(row) }
                Box(Modifier.fillMaxWidth().padding(start = 72.dp).height(0.5.dp).background(ink(0.08f)))
            }
        }
    }
}

@Composable
private fun StationRow(
    item: DiscoverItem,
    loading: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().pressScale(onClick = onClick, scaleDown = 0.985f).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StationTile(item, 56.dp)
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(
                item.title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(item.subtitle, style = MaterialTheme.typography.bodyMedium, color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.size(36.dp).border(1.dp, ink(0.25f), CircleShape), contentAlignment = Alignment.Center) {
            if (loading) {
                CircularProgressIndicator(Modifier.size(16.dp), color = TextPrimary, strokeWidth = 2.dp)
            } else {
                Icon(PhosphorIcons.Fill.Play, "Play", tint = TextPrimary, modifier = Modifier.size(14.dp))
            }
        }
    }
}

/** A station's square: its photo where it has one, otherwise a colour with a small drawn mark. */
@Composable
private fun StationTile(
    item: DiscoverItem,
    size: Dp,
) {
    val shape = RoundedCornerShape(8.dp)
    if (item.imageUrl.isNotBlank()) {
        ArtImage(url = squareArtUrl(item.imageUrl, 200), modifier = Modifier.size(size).clip(shape))
        return
    }
    val kind = StationId.kindOf(item.id)
    val hue = ((item.id.hashCode().toLong() and 0x7fffffffL) % 360L).toFloat()
    val top = if (kind == StationId.MIX) Color(0xFFFF4D6D) else if (kind == StationId.DISCOVER) Color(0xFF4F5BD5) else Color.hsv(hue, 0.55f, 0.6f)
    val bottom = if (kind == StationId.MIX) Color(0xFFFF8A4C) else if (kind == StationId.DISCOVER) Color(0xFF2FB8A6) else Color.hsv((hue + 40f) % 360f, 0.6f, 0.5f)
    Canvas(Modifier.size(size).clip(shape)) {
        drawRect(Brush.linearGradient(listOf(top, bottom), Offset.Zero, Offset(this.size.width, this.size.height)))
        val w = this.size.width
        val h = this.size.height
        val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val path = Path()
        when (kind) {
            StationId.MIX ->
                listOf(0.34f, 0.2f).forEach { inset ->
                    path.reset()
                    path.moveTo(w * inset, h * 0.26f)
                    path.lineTo(w * (inset + 0.3f), h * 0.5f)
                    path.lineTo(w * inset, h * 0.74f)
                    drawPath(path, Color.White.copy(alpha = 0.9f), style = stroke)
                }

            StationId.DISCOVER -> {
                val c = Offset(w / 2f, h / 2f)
                val r = w * 0.26f
                path.moveTo(c.x, c.y - r)
                path.quadraticTo(c.x, c.y, c.x + r, c.y)
                path.quadraticTo(c.x, c.y, c.x, c.y + r)
                path.quadraticTo(c.x, c.y, c.x - r, c.y)
                path.quadraticTo(c.x, c.y, c.x, c.y - r)
                path.close()
                drawPath(path, Color.White.copy(alpha = 0.9f), style = stroke)
            }

            else ->
                listOf(0.3f to 0.3f, 0.5f to 0.5f, 0.7f to 0.38f).forEach { (fx, fh) ->
                    drawLine(Color.White.copy(alpha = 0.9f), Offset(w * fx, h * 0.72f), Offset(w * fx, h * (0.72f - fh)), 3.dp.toPx(), StrokeCap.Round)
                }
        }
    }
}

// ---- Moods --------------------------------------------------------------------------------------------------------

@Composable
internal fun MoodGrid(
    items: List<DiscoverItem>,
    loadingId: String?,
    onClick: (DiscoverItem) -> Unit,
) {
    val moods = remember(items) { items.mapNotNull { item -> Mood.bySlug(item.id.substringAfterLast(':'))?.let { item to it } } }
    var expanded by remember { mutableStateOf(false) }
    val shown = if (expanded) moods else moods.take(MOOD_COLLAPSED)
    Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        shown.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { (item, mood) ->
                    MoodChip(item.title, mood, loading = item.id == loadingId, modifier = Modifier.weight(1f)) { onClick(item) }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        if (moods.size > MOOD_COLLAPSED) {
            Text(
                if (expanded) "Show fewer" else "All ${moods.size} moods",
                style = MaterialTheme.typography.labelLarge,
                color = TextSecondary,
                modifier =
                    Modifier
                        .align(Alignment.CenterHorizontally)
                        .pressScale(onClick = { expanded = !expanded }, scaleDown = 0.94f)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

/** A shortcut: a tile in the mood's colours with its emblem, on a chip washed with the same colour. */
@Composable
private fun MoodChip(
    label: String,
    mood: Mood,
    loading: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val from = Color(mood.from)
    val to = Color(mood.to)
    Row(
        modifier
            .height(56.dp)
            .pressScale(onClick = onClick, scaleDown = 0.97f)
            .clip(RoundedCornerShape(10.dp))
            .background(Brush.horizontalGradient(0f to from.copy(alpha = 0.30f), 0.7f to ink(0.06f)))
            .semantics { contentDescription = "$label mood station" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(56.dp)) {
            drawRect(Brush.linearGradient(listOf(from, to), Offset.Zero, Offset(size.width, size.height)))
            drawEmblem(mood, Offset(size.width / 2f, size.height / 2f), size.width * 0.5f, Color.White.copy(alpha = 0.92f), 1.8.dp.toPx())
        }
        Text(
            label,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = TextPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
        )
        if (loading) CircularProgressIndicator(Modifier.padding(end = 12.dp).size(16.dp), color = TextPrimary, strokeWidth = 2.dp)
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
    Box(Modifier.fillMaxWidth().padding(horizontal = 34.dp, vertical = 24.dp).aspectRatio(0.8f).clip(RoundedCornerShape(18.dp)).background(ink(0.06f)))
}
