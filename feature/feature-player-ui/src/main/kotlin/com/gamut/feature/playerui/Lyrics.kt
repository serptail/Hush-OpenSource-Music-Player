package com.gamut.feature.playerui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.res.ResourcesCompat
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Light
import com.adamglin.phosphoricons.light.CaretDown
import com.adamglin.phosphoricons.light.MusicNote
import com.gamut.core.designsystem.AcrylicMaterials
import com.gamut.core.designsystem.AcrylicSurface
import com.gamut.core.model.Track
import com.gamut.feature.playerui.components.PlayerSpinner
import com.gamut.feature.playerui.components.fadeOutBetween
import com.gamut.feature.playerui.components.fadingEdges
import com.gamut.feature.playerui.components.tapTarget
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Collections
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Represents the state of lyrics fetching. */
sealed interface LyricsState {
    @Immutable
    object Loading : LyricsState

    @Immutable
    data class Loaded(val lines: List<LyricsLine>, val isSynced: Boolean = true) : LyricsState

    @Immutable
    data class NotAvailable(val reason: String) : LyricsState

    @Immutable
    data class Error(val message: String) : LyricsState
}

/** One word (or syllable) of a line, with the time window in which it is sung. */
@Immutable
data class LyricsWord(
    val startMs: Long,
    val endMs: Long,
    /** Includes its trailing space, so words can be concatenated as-is. */
    val text: String,
)

/** A single line of lyrics with its timestamp and per-word timing. */
@Immutable
data class LyricsLine(
    val timestampMs: Long,
    val text: String,
    val endMs: Long = timestampMs,
    val words: List<LyricsWord> = emptyList(),
)


private class FetchedLyrics(val lines: List<LyricsLine>, val isSynced: Boolean)

/** Lyrics from LRCLIB (https://lrclib.net), the open synced-lyrics database. Results are cached in memory. */
object LyricsRepository {
    private const val BASE_URL = "https://lrclib.net/api"
    private const val USER_AGENT = "Gamut/1.0 (Android)"

    /** A synced result whose length differs from the track by more than this is probably another version (live, remix). */
    private const val MAX_DURATION_DIFF_S = 10.0

    private val lineStampsRegex = Regex("""^(?:\s*\[\d{1,3}:\d{2}(?:[.:]\d{1,3})?\])+""")
    private val stampRegex = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?\]""")
    private val wordStampRegex = Regex("""<(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?>""")
    private val whitespaceRegex = Regex("\\s+")

    // "(feat. X)", "[Official Video]", "- Remastered 2011", ... only get in the way of matching.
    private val bracketNoise = Regex(
        """\s*[(\[][^)\]]*\b(feat\.?|ft\.?|featuring|with|official|video|audio|lyrics?|visuali[sz]er|remaster(?:ed)?|live|explicit)\b[^)\]]*[)\]]""",
        RegexOption.IGNORE_CASE,
    )
    private val dashNoise = Regex("""\s+-\s+(?:\d{4}\s+)?(?:remaster(?:ed)?|live|single version|radio edit)\b.*$""", RegexOption.IGNORE_CASE)
    private val featuringNoise = Regex("""\s+(?:feat\.?|ft\.?|featuring)\s+.*$""", RegexOption.IGNORE_CASE)

    private val cache = Collections.synchronizedMap(
        object : LinkedHashMap<String, LyricsState.Loaded>(32, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LyricsState.Loaded>?) = size > 40
        },
    )

    private fun cacheKey(track: Track) = track.isrc.orEmpty().ifBlank { "${track.title}|${track.artist}" }

    /** Lyrics already fetched for [track] this session, or null. Lets the player skip the spinner on re-open. */
    fun cached(track: Track): LyricsState.Loaded? = cache[cacheKey(track)]

    suspend fun fetchLyrics(track: Track): LyricsState {
        cached(track)?.let { return it }
        return try {
            val found = withContext(Dispatchers.IO) { lookup(track) }
            if (found == null || found.lines.isEmpty()) {
                LyricsState.NotAvailable("No lyrics found for this track")
            } else {
                LyricsState.Loaded(found.lines, found.isSynced).also { cache[cacheKey(track)] = it }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            LyricsState.Error("Couldn't reach the lyrics service")
        } catch (e: Exception) {
            LyricsState.Error(e.message ?: "Failed to fetch lyrics")
        }
    }

    private fun lookup(track: Track): FetchedLyrics? {
        val title = cleanTitle(track.title)
        val artist = track.artist.replace(featuringNoise, "").trim().ifBlank { track.artist }
        val seconds = track.durationMs / 1000.0

        // 1. Exact match by title + artist + length. One small request, and the right version of the song.
        if (seconds > 0) {
            val exact = httpGet("$BASE_URL/get?track_name=${enc(title)}&artist_name=${enc(artist)}&duration=${seconds.roundToInt()}")
            if (exact != null) toLyrics(JSONObject(exact))?.let { return it }
        }

        // 2. Search, then pick the result whose length is closest to the track's.
        for (query in listOf("track_name=${enc(title)}&artist_name=${enc(artist)}", "q=${enc("$title $artist")}")) {
            val body = httpGet("$BASE_URL/search?$query") ?: continue
            val results = JSONArray(body)
            val items = List(results.length()) { results.getJSONObject(it) }
            pickBest(items, seconds)?.let { return it }
        }
        return null
    }

    private fun pickBest(items: List<JSONObject>, seconds: Double): FetchedLyrics? {
        fun diff(o: JSONObject) = if (seconds > 0 && o.has("duration") && !o.isNull("duration")) abs(o.optDouble("duration") - seconds) else 0.0
        val synced = items.filter { hasText(it, "syncedLyrics") }.minByOrNull(::diff)
        if (synced != null && diff(synced) <= MAX_DURATION_DIFF_S) return toLyrics(synced)
        // No well-matching synced version: plain text beats highlighting against the wrong timing.
        val plain = items.filter { hasText(it, "plainLyrics") }.minByOrNull(::diff)
        if (plain != null) return toLyrics(plain, preferPlain = true)
        return synced?.let { toLyrics(it) }
    }

    private fun hasText(o: JSONObject, key: String) = o.optString(key, "").let { it.isNotBlank() && it != "null" }

    private fun toLyrics(o: JSONObject, preferPlain: Boolean = false): FetchedLyrics? {
        val synced = o.optString("syncedLyrics", "").takeIf { it.isNotBlank() && it != "null" }
        val plain = o.optString("plainLyrics", "").takeIf { it.isNotBlank() && it != "null" }
        if (synced != null && !preferPlain) {
            val lines = parseLrc(synced)
            if (lines.isNotEmpty()) return FetchedLyrics(lines, isSynced = true)
        }
        return plain?.let { FetchedLyrics(parsePlainLyrics(it), isSynced = false) }
    }

    private fun cleanTitle(title: String) = title.replace(bracketNoise, "").replace(dashNoise, "").trim().ifBlank { title }

    private fun enc(v: String) = URLEncoder.encode(v, "UTF-8")

    /** GET [url]; the body on 200, null on 404 (no such lyrics), an [IOException] on anything else. */
    private fun httpGet(url: String): String? {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.connectTimeout = 6000
            connection.readTimeout = 8000
            return when (val code = connection.responseCode) {
                200 -> connection.inputStream.bufferedReader().use { it.readText() }
                404 -> null
                else -> throw IOException("HTTP $code")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun toMs(min: String, sec: String, frac: String): Long {
        val fracMs = frac.padEnd(3, '0').take(3).toLong()
        return (min.toLong() * 60 + sec.toLong()) * 1000 + fracMs
    }

    /**
     * Parses LRC. Works with plain line-synced LRC (`[mm:ss.xx] text`, including several stamps on one
     * line for a repeated chorus) and with "enhanced" LRC that also carries word stamps (`<mm:ss.xx> word`).
     */
    private fun parseLrc(lrcText: String): List<LyricsLine> {
        val raw = mutableListOf<Pair<Long, String>>()
        lrcText.lines().forEach { line ->
            val lead = lineStampsRegex.find(line) ?: return@forEach
            val body = line.substring(lead.range.last + 1).trim()
            if (body.replace(wordStampRegex, "").isBlank()) return@forEach
            stampRegex.findAll(lead.value).forEach { m ->
                raw.add(toMs(m.groupValues[1], m.groupValues[2], m.groupValues[3]) to body)
            }
        }
        raw.sortBy { it.first }

        return raw.mapIndexed { i, (ts, body) ->
            val text = body.replace(wordStampRegex, "").replace(whitespaceRegex, " ").trim()
            val end = lineEnd(ts, text, raw.getOrNull(i + 1)?.first)
            val words = parseEnhancedWords(body, end) ?: estimateWords(text, ts, end)
            LyricsLine(timestampMs = ts, text = text, endMs = end, words = words)
        }
    }

    /**
     * When the line ends. Right up to the next line if that is close; otherwise a length-based
     * estimate, so a long instrumental gap doesn't stretch the last words of a line across it.
     */
    private fun lineEnd(start: Long, text: String, nextStart: Long?): Long {
        val estimate = 400L + text.length * 85L
        val gap = nextStart?.let { it - start }
        val duration = when {
            gap == null -> estimate
            gap <= estimate * 3 / 2 -> gap
            else -> estimate
        }
        return start + duration.coerceAtLeast(300L)
    }

    /** Exact word timings from enhanced-LRC stamps, or null when the line has none. */
    private fun parseEnhancedWords(body: String, lineEnd: Long): List<LyricsWord>? {
        val stamps = wordStampRegex.findAll(body).toList()
        if (stamps.isEmpty()) return null
        val words = mutableListOf<LyricsWord>()
        stamps.forEachIndexed { i, m ->
            val start = toMs(m.groupValues[1], m.groupValues[2], m.groupValues[3])
            val next = stamps.getOrNull(i + 1)
            val end = next?.let { toMs(it.groupValues[1], it.groupValues[2], it.groupValues[3]) } ?: lineEnd
            val segment = body.substring(m.range.last + 1, next?.range?.first ?: body.length)
            when {
                segment.isBlank() -> {
                    // A bare space between two stamps belongs to the previous word.
                    if (words.isNotEmpty() && segment.isNotEmpty()) {
                        val last = words.removeAt(words.lastIndex)
                        words.add(last.copy(text = last.text.trimEnd() + " "))
                    }
                }
                else -> words.add(LyricsWord(start, end.coerceAtLeast(start + 1), segment.trimStart()))
            }
        }
        return words.ifEmpty { null }
    }

    /**
     * Fallback when the source only has line timestamps: spread the line's duration over its
     * words in proportion to their length. Not exact, but it tracks singing closely enough.
     */
    private fun estimateWords(text: String, start: Long, end: Long): List<LyricsWord> {
        val parts = text.split(whitespaceRegex).filter { it.isNotEmpty() }
        if (parts.isEmpty()) return emptyList()
        val weights = parts.map { maxOf(it.length, 2) }
        val totalWeight = weights.sum().toFloat()
        val span = (end - start).coerceAtLeast(1L)
        var cursor = start.toFloat()
        return parts.mapIndexed { i, part ->
            val wordStart = cursor.toLong()
            cursor += span * weights[i] / totalWeight
            val wordEnd = if (i == parts.lastIndex) end else cursor.toLong()
            LyricsWord(wordStart, wordEnd, if (i == parts.lastIndex) part else "$part ")
        }
    }

    /** Parses plain lyrics without timestamps. */
    private fun parsePlainLyrics(plainText: String): List<LyricsLine> =
        plainText.lines()
            .filter { it.isNotBlank() }
            .map { LyricsLine(timestampMs = 0L, text = it.trim()) }
}


private val LyricsTitleHeight = 36.dp        // the "Lyrics" title row that sits above the card
private val LyricsCollapsedHeight = 92.dp    // card height when folded: 14 + 28 + 8 + 28 + 14 (two lines)
private val PreviewPaddingV = 14.dp          // top/bottom padding of the folded snippet
private val PreviewLineGap = 8.dp            // gap between the two folded lines
private const val PreviewFadeStart = 0.3f    // fade begins at this fraction of line 2's height (top of the letters' body)
private const val PreviewFadeEnd = 0.75f     // fully invisible from here down (~ the baseline, so the bottom half of the letters is truly gone)
private val LyricsCardShape = RoundedCornerShape(14.dp)
private val AcrylicBlur = 72.dp              // how hard the art behind the lyrics card is blurred

/**
 * Collapsible lyrics card with its "Lyrics" title outside, on top. Folded, the card shows a snippet
 * of the first lines fading out toward the bottom; expanded, it fills the space it is given and
 * shows the auto-scrolling, word-highlighted lyrics.
 *
 * [positionMs] is a lambda so only the parts that need the frame-by-frame position read it.
 */
@Composable
fun LyricsSection(
    lyricsState: LyricsState,
    positionMs: () -> Long,
    accent: Color,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier) {
        val fullHeight = maxHeight
        val expandedCardHeight = (fullHeight - LyricsTitleHeight).coerceAtLeast(0.dp)
        val collapsedCardHeight = minOf(LyricsCollapsedHeight, expandedCardHeight)
        val cardHeight by animateDpAsState(
            targetValue = if (expanded) expandedCardHeight else collapsedCardHeight,
            animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
            label = "lyrics-height",
        )
        val chevron by animateFloatAsState(if (expanded) 0f else 180f, tween(250), label = "lyrics-chevron")

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            // Title, outside (above) the card.
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(LyricsTitleHeight)
                    .tapTarget(onToggle)
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Lyrics", color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.weight(1f))
                Icon(
                    PhosphorIcons.Light.CaretDown,
                    contentDescription = if (expanded) "Hide lyrics" else "Show lyrics",
                    tint = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = chevron },
                )
            }

            // Real frosted glass: blurs the screen's cover backdrop (see acrylicSource above) behind the card.
            AcrylicSurface(
                Modifier
                    .fillMaxWidth()
                    .height(cardHeight)
                    .shadow(
                        elevation = 14.dp,
                        shape = LyricsCardShape,
                        ambientColor = Color.Black.copy(alpha = 0.35f),
                        spotColor = Color.Black.copy(alpha = 0.45f),
                        clip = false,
                    )
                    // Folded, tapping anywhere on the snippet opens it.
                    .tapTarget { if (!expanded) onToggle() },
                shape = LyricsCardShape,
                material = AcrylicMaterials.UltraThin,
            ) {
                Crossfade(targetState = expanded, animationSpec = tween(250), label = "lyrics-content") { isExpanded ->
                    if (!isExpanded) {
                        LyricsPreview(lyricsState)
                    } else {
                        Box(Modifier.fillMaxSize()) {
                            when (lyricsState) {
                                is LyricsState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    PlayerSpinner(Modifier.size(26.dp), Color.White.copy(alpha = 0.75f))
                                }
                                is LyricsState.Loaded -> {
                                    if (lyricsState.lines.isEmpty()) {
                                        LyricsEmptyState("No timed lyrics available")
                                    } else {
                                        LyricsList(
                                            lines = lyricsState.lines,
                                            isSynced = lyricsState.isSynced,
                                            positionMs = positionMs,
                                            accent = accent,
                                            viewport = fullHeight - LyricsTitleHeight,
                                        )
                                    }
                                }
                                is LyricsState.NotAvailable -> LyricsEmptyState(lyricsState.reason)
                                is LyricsState.Error -> LyricsEmptyState("Error: ${lyricsState.message}")
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Folded-card content: the first lyric up top, the lines under it fading out toward the bottom. */
@Composable
private fun LyricsPreview(lyricsState: LyricsState) {
    val font = rememberLyricsFont()
    when (lyricsState) {
        is LyricsState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            PlayerSpinner(Modifier.size(22.dp), Color.White.copy(alpha = 0.75f))
        }
        is LyricsState.Loaded -> {
            if (lyricsState.lines.isEmpty()) {
                LyricsPreviewMessage("No timed lyrics available")
            } else {
                val style = TextStyle(
                    fontFamily = font,
                    fontWeight = FontWeight.Normal,
                    fontSize = 20.sp,
                    lineHeight = 28.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                // Measured heights of the two lines, so the fade can be aimed at the second line
                // exactly (its own vertical middle), whatever the font size / wrapping is.
                val density = LocalDensity.current
                var firstLinePx by remember { mutableIntStateOf(0) }
                var secondLinePx by remember { mutableIntStateOf(0) }
                val secondTop = {
                    with(density) { (PreviewPaddingV + PreviewLineGap).toPx() } + firstLinePx
                }
                Box(
                    Modifier
                        .fillMaxSize()
                        .fadeOutBetween(
                            // Fully visible down to the top of line 2's letters...
                            start = { secondTop() + secondLinePx * PreviewFadeStart },
                            // ...and completely invisible from its baseline down.
                            end = { secondTop() + secondLinePx * PreviewFadeEnd },
                            hasSecondLine = { secondLinePx > 0 },
                        ),
                ) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .padding(horizontal = 20.dp, vertical = PreviewPaddingV),
                        verticalArrangement = Arrangement.spacedBy(PreviewLineGap),
                    ) {
                        lyricsState.lines.take(2).forEachIndexed { i, line ->
                            Text(
                                text = line.text,
                                color = Color.White.copy(alpha = 0.6f), // unhighlighted lyric color (same as unsung words in the expanded view); same on both lines: the fade alone does the cutting
                                style = style,
                                maxLines = 2,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .onSizeChanged { if (i == 0) firstLinePx = it.height else secondLinePx = it.height },
                            )
                        }
                    }
                }
            }
        }
        is LyricsState.NotAvailable -> LyricsPreviewMessage(lyricsState.reason)
        is LyricsState.Error -> LyricsPreviewMessage("Error: ${lyricsState.message}")
    }
}

@Composable
private fun LyricsPreviewMessage(message: String) {
    Box(Modifier.fillMaxSize().padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
        Text(
            message,
            color = Color.White.copy(alpha = 0.6f),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

/** Empty state for lyrics. */
@Composable
private fun LyricsEmptyState(message: String) {
    Box(
        Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                PhosphorIcons.Light.MusicNote,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.4f),
                modifier = Modifier.size(40.dp),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                message,
                color = Color.White.copy(alpha = 0.6f),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

/**
 * Loads `res/font/lyrics.ttf` (it lives in core-designsystem). It is looked up by name at runtime,
 * so this module doesn't need a dependency on that module's R class. Falls back to the system
 * sans if the font can't be found.
 */
@Composable
internal fun rememberLyricsFont(): FontFamily {
    val context = LocalContext.current
    return remember {
        runCatching {
            val id = context.resources.getIdentifier("lyrics", "font", context.packageName)
            FontFamily(ResourcesCompat.getFont(context, id)!!)
        }.getOrDefault(FontFamily.SansSerif)
    }
}

private const val HighlightStepMs = 40L   // the word colors are refreshed 25x a second: smooth to the eye, 2.5x cheaper than every frame

/** Auto-scrolling list. The active line sits in the upper-middle of the card. */
@Composable
internal fun LyricsList(
    lines: List<LyricsLine>,
    isSynced: Boolean,
    positionMs: () -> Long,
    accent: Color,
    viewport: Dp,
) {
    val font = rememberLyricsFont()
    val listState = rememberLazyListState()

    // One stable reader for every line, so lines whose state didn't change skip recomposition
    // even though the caller's lambda instance may change.
    val latestPosition by rememberUpdatedState(positionMs)
    val readPosition = remember { { latestPosition() } }

    val activeIndex by remember(lines, isSynced) {
        derivedStateOf { if (isSynced) activeLineIndex(lines, readPosition()) else -1 }
    }
    // Only a finger on the list pauses the auto-scroll. (isScrollInProgress would also be true while our own
    // animation is still running, which made the list skip lines when they changed quickly.)
    val dragged by listState.interactionSource.collectIsDraggedAsState()

    LaunchedEffect(lines) { listState.scrollToItem(activeIndex.coerceAtLeast(0)) }
    LaunchedEffect(activeIndex) {
        if (activeIndex >= 0 && !dragged) listState.animateScrollToItem(activeIndex)
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().fadingEdges(),
        contentPadding = PaddingValues(
            start = 22.dp,
            end = 22.dp,
            top = if (isSynced) viewport * 0.28f else 8.dp,
            bottom = if (isSynced) viewport * 0.60f else 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        itemsIndexed(lines) { index, line ->
            LyricLine(
                line = line,
                isSynced = isSynced,
                isActive = index == activeIndex,
                isPast = index < activeIndex,
                readPosition = readPosition,
                accent = accent,
                font = font,
            )
        }
    }
}

/**
 * One line. Words already sung are the accent color, the word being sung fades into it as it is
 * sung, and words still to come stay dim. Only the active line reads the position, so only it
 * recomposes while the song plays.
 */
@Composable
private fun LyricLine(
    line: LyricsLine,
    isSynced: Boolean,
    isActive: Boolean,
    isPast: Boolean,
    readPosition: () -> Long,
    accent: Color,
    font: FontFamily,
) {
    val style = TextStyle(
        fontFamily = font,
        fontWeight = FontWeight.Normal, // the bundled file is already SemiBold; don't let Compose fake-bold it
        fontSize = 22.sp,
        lineHeight = 30.sp,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )

    if (!isSynced || line.words.isEmpty()) {
        Text(
            text = line.text,
            color = Color.White.copy(alpha = 0.85f),
            style = style,
            modifier = Modifier.fillMaxWidth(),
        )
        return
    }

    // The line being sung is full size and full brightness; lines already sung recede the most.
    val lineAlpha by animateFloatAsState(
        targetValue = when {
            isActive -> 1f
            isPast -> 0.4f
            else -> 0.6f
        },
        animationSpec = tween(300),
        label = "lyric-line-alpha",
    )
    val lineScale by animateFloatAsState(
        targetValue = if (isActive) 1f else 0.96f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessLow),
        label = "lyric-line-scale",
    )
    val unsung = Color.White.copy(alpha = 0.6f)
    // Quantized position: this line recomposes only when the step changes, not on every frame.
    val step by remember(isActive, readPosition) {
        derivedStateOf { if (isActive) readPosition() / HighlightStepMs else 0L }
    }
    val now = when {
        isActive -> step * HighlightStepMs
        isPast -> Long.MAX_VALUE / 2 // every word done
        else -> -1_000_000L          // no word started
    }
    val text = buildAnnotatedString {
        line.words.forEach { word ->
            val span = (word.endMs - word.startMs).coerceAtLeast(1L)
            val p = ((now - word.startMs).toFloat() / span).coerceIn(0f, 1f)
            withStyle(SpanStyle(color = lerp(unsung, accent, p))) { append(word.text) }
        }
    }
    Text(
        text = text,
        style = style,
        modifier = Modifier.fillMaxWidth().graphicsLayer { alpha = lineAlpha; scaleX = lineScale; scaleY = lineScale },
    )
}

/** Index of the last line that has started at [positionMs], or -1 before the first line. */
private fun activeLineIndex(lines: List<LyricsLine>, positionMs: Long): Int {
    var lo = 0
    var hi = lines.lastIndex
    var answer = -1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (lines[mid].timestampMs <= positionMs) {
            answer = mid
            lo = mid + 1
        } else {
            hi = mid - 1
        }
    }
    return answer
}
