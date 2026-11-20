package com.gamut.feature.playerui

import com.gamut.core.designsystem.TablerIcons
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gamut.core.player.AudioPipelineInfo
import com.gamut.core.player.BitPerfectStatus
import com.gamut.core.player.PipelineDetails
import com.gamut.core.player.SignalPath

/**
 * How a badge looks: WHITE = premium (black with a gold outline) for what is special, GRAY for plain facts (a white
 * codec block with an outlined detail beside it), BLACK for how it is being delivered.
 */
internal enum class TagTone { WHITE, GRAY, BLACK }

internal data class QualityTag(
    val text: String,
    val tone: TagTone,
    val icon: ImageVector? = null,
    /** Second half of a split badge, e.g. "160 kbps" beside "OPUS". */
    val detail: String? = null,
)

/**
 * The tags for what is playing right now, read from the live signal path: Dolby Atmos, Hi-Res Lossless /
 * Lossless, High quality, the codec and its rate, and Bit-perfect. Empty until a track has opened.
 */
internal fun qualityTags(
    path: SignalPath,
    details: PipelineDetails,
): List<QualityTag> {
    if (!path.known) return emptyList()
    val tags = mutableListOf<QualityTag>()
    val hiRes = path.lossless && (path.bitDepth >= 24 || path.sampleRateHz > 48_000)
    when {
        details.atmos -> tags += QualityTag("Dolby Atmos", TagTone.WHITE, TablerIcons.Box)
        hiRes -> tags += QualityTag("Hi-Res Lossless", TagTone.WHITE, TablerIcons.Waveform)
        path.lossless -> tags += QualityTag("Lossless", TagTone.WHITE, TablerIcons.Waveform)
        path.bitrateKbps >= 224 -> tags += QualityTag("High quality", TagTone.GRAY, TablerIcons.Headphones)
    }
    val detail =
        if (path.lossless) {
            listOfNotNull(
                path.bitDepth.takeIf { it > 0 }?.let { "$it-bit" },
                path.sampleRateHz.takeIf { it > 0 }?.let { khz(it) },
            ).joinToString(" / ")
        } else {
            path.bitrateKbps.takeIf { it > 0 }?.let { "$it kbps" }.orEmpty()
        }
    val codec = path.codec.uppercase()
    if (codec.isNotEmpty()) {
        tags += QualityTag(codec, TagTone.GRAY, detail = detail.ifEmpty { null })
    } else if (detail.isNotEmpty()) {
        tags += QualityTag(detail, TagTone.GRAY)
    }
    if (path.bitPerfect == BitPerfectStatus.ACTIVE) tags += QualityTag("Bit-perfect", TagTone.BLACK, TablerIcons.Vinyl)
    return tags
}

private fun khz(hz: Int): String = java.lang.String.format(java.util.Locale.US, "%.1f kHz", hz / 1000f).replace(".0 kHz", " kHz")

/** A single row of small tag boxes, scrollable sideways when there are too many for the width. */
@Composable
internal fun QualityTagRow(modifier: Modifier = Modifier) {
    val path by AudioPipelineInfo.state.collectAsState()
    val details by AudioPipelineInfo.details.collectAsState()
    val tags = remember(path, details) { qualityTags(path, details) }
    if (tags.isEmpty()) return
    Row(
        modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tags.forEach { QualityTagBox(it) }
    }
}

private val GoldLight = Color(0xFFF3D98B)
private val GoldDark = Color(0xFFB8862B)
private val GoldText = Color(0xFFE9C46A)
private val BadgeShape = RoundedCornerShape(6.dp)
private val BadgeHeight = 22.dp

@Composable
private fun QualityTagBox(tag: QualityTag) {
    when {
        tag.tone == TagTone.WHITE -> PremiumBadge(tag)
        tag.detail != null -> SplitBadge(tag)
        else -> OutlinedBadge(tag)
    }
}

/** Lossless, Hi-Res, Atmos: a black plate with a gold outline and gold type. */
@Composable
private fun PremiumBadge(tag: QualityTag) {
    val gold = Brush.linearGradient(listOf(GoldLight, GoldDark, GoldLight))
    Row(
        Modifier
            .height(BadgeHeight)
            .clip(BadgeShape)
            .background(Brush.verticalGradient(listOf(Color(0xFF1C1C1C), Color(0xFF050505))))
            .border(BorderStroke(1.dp, gold), BadgeShape)
            .padding(horizontal = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (tag.icon != null) {
            Icon(tag.icon, null, tint = GoldText, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(tag.text, color = GoldText, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp, maxLines = 1, softWrap = false)
    }
}

/** "OPUS | 160 kbps": a solid white block for the codec, the detail outlined beside it. */
@Composable
private fun SplitBadge(tag: QualityTag) {
    Row(
        Modifier
            .height(BadgeHeight)
            .clip(BadgeShape)
            .border(1.dp, Color.White.copy(alpha = 0.6f), BadgeShape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.fillMaxHeight().background(Color.White.copy(alpha = 0.92f)).padding(horizontal = 7.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(tag.text, color = Color(0xFF111111), fontSize = 10.5.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.6.sp, maxLines = 1, softWrap = false)
        }
        Box(
            Modifier.fillMaxHeight().background(Color.Black.copy(alpha = 0.35f)).padding(horizontal = 7.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(tag.detail.orEmpty(), color = Color.White.copy(alpha = 0.92f), fontSize = 10.5.sp, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false)
        }
    }
}

/** Everything else (High quality, Bit-perfect): a dark glass plate with a thin white outline. */
@Composable
private fun OutlinedBadge(tag: QualityTag) {
    val dark = tag.tone == TagTone.BLACK
    Row(
        Modifier
            .height(BadgeHeight)
            .clip(BadgeShape)
            .background(if (dark) Color.Black.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.12f))
            .border(1.dp, Color.White.copy(alpha = if (dark) 0.4f else 0.5f), BadgeShape)
            .padding(horizontal = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (tag.icon != null) {
            Icon(tag.icon, null, tint = Color.White, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(tag.text, color = Color.White.copy(alpha = 0.95f), fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.3.sp, maxLines = 1, softWrap = false)
    }
}