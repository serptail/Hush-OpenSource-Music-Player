package com.hush.core.designsystem

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

/**
 * The now-playing strip that docks onto [HushBottomBar] (pass it as the bar's `nowPlaying` slot). It is part of
 * the bar, not a separate floating card: same ink tint underneath, no shadow, flat. A thin progress line runs
 * along its top edge in the cover's accent. Tap the strip to open the player. Icons are drawn here, so this
 * file needs nothing but Coil. Must be inside [CoverTheme] (uses [ink] and the accent).
 */
@Composable
fun MiniPlayerStrip(
    title: String,
    artist: String,
    artUrl: String?,
    isPlaying: Boolean,
    progress: Float,
    onOpen: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onArtistClick: () -> Unit = {},
    modifier: Modifier = Modifier,
    /** True when the strip sits at the very bottom of a screen without [HushBottomBar]: it then also fills the system navigation area. */
    dockedToEdge: Boolean = false,
) {
    val accent = MaterialTheme.colorScheme.primary
    Column(
        modifier
            .fillMaxWidth()
            .background(ink(0.05f))
            .then(if (dockedToEdge) Modifier.navigationBarsPadding() else Modifier)
            .pressScale(onClick = onOpen, scaleDown = 0.99f),
    ) {
        // Progress line on the top edge: faint track, accent fill.
        Box(Modifier.fillMaxWidth().height(2.dp).background(ink(0.08f))) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).background(accent))
        }
        Row(
            Modifier.fillMaxWidth().height(60.dp).padding(start = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val artShape = RoundedCornerShape(8.dp)
            Box(Modifier.size(42.dp).clip(artShape).background(ink(0.08f))) {
                if (artUrl != null) {
                    AsyncImage(
                        model = artUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxHeight().fillMaxWidth(),
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = ink(), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    artist,
                    color = ink(0.6f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.pressScale(onClick = onArtistClick, scaleDown = 0.98f),
                )
            }
            Box(Modifier.size(48.dp).pressScale(onClick = onTogglePlay, scaleDown = 0.88f), contentAlignment = Alignment.Center) {
                Crossfade(isPlaying, animationSpec = tween(160), label = "mini-play") { playing ->
                    if (playing) GlyphPause(ink(), Modifier.size(24.dp)) else GlyphPlay(ink(), Modifier.size(24.dp))
                }
            }
            Box(Modifier.size(48.dp).pressScale(onClick = onNext, scaleDown = 0.88f), contentAlignment = Alignment.Center) {
                GlyphNext(ink(0.9f), Modifier.size(24.dp))
            }
        }
    }
}

@Composable
private fun GlyphPlay(color: Color, modifier: Modifier) = Canvas(modifier) {
    val w = size.width; val h = size.height
    val p = Path().apply {
        moveTo(w * 0.28f, h * 0.16f); lineTo(w * 0.84f, h * 0.5f); lineTo(w * 0.28f, h * 0.84f); close()
    }
    drawPath(p, color)
    drawPath(p, color, style = Stroke(w * 0.10f, join = StrokeJoin.Round)) // softens the corners
}

@Composable
private fun GlyphPause(color: Color, modifier: Modifier) = Canvas(modifier) {
    val w = size.width; val h = size.height
    val bar = Size(w * 0.17f, h * 0.64f)
    val r = CornerRadius(w * 0.06f)
    drawRoundRect(color, Offset(w * 0.26f, h * 0.18f), bar, r)
    drawRoundRect(color, Offset(w * 0.57f, h * 0.18f), bar, r)
}

@Composable
private fun GlyphNext(color: Color, modifier: Modifier) = Canvas(modifier) {
    val w = size.width; val h = size.height
    val p = Path().apply {
        moveTo(w * 0.20f, h * 0.20f); lineTo(w * 0.70f, h * 0.5f); lineTo(w * 0.20f, h * 0.80f); close()
    }
    drawPath(p, color)
    drawPath(p, color, style = Stroke(w * 0.08f, join = StrokeJoin.Round))
    drawRoundRect(color, Offset(w * 0.74f, h * 0.20f), Size(w * 0.10f, h * 0.60f), CornerRadius(w * 0.04f))
}