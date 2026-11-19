package com.gamut.feature.playerui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ArrowSquareUp
import com.adamglin.phosphoricons.regular.Cube
import com.adamglin.phosphoricons.regular.Disc
import com.adamglin.phosphoricons.regular.Headphones
import com.adamglin.phosphoricons.regular.Waveform
import com.gamut.core.player.AudioPipelineInfo
import com.gamut.core.player.BitPerfectStatus
import com.gamut.core.player.PipelineDetails
import com.gamut.core.player.SignalPath

/** How loud a tag is: white for what is special, gray for plain facts, black for how it is being delivered. */
internal enum class TagTone { WHITE, GRAY, BLACK }

internal data class QualityTag(
    val text: String,
    val tone: TagTone,
    val icon: ImageVector? = null,
)

/**
 * The tags for what is playing right now, read from the live signal path: Dolby Atmos, Hi-Res Lossless /
 * Lossless, High quality, the codec and its rate, Bit-perfect, and Upgraded when a better stream replaced
 * the first one. Empty until a track has opened.
 */
internal fun qualityTags(
    path: SignalPath,
    details: PipelineDetails,
): List<QualityTag> {
    if (!path.known) return emptyList()
    val tags = mutableListOf<QualityTag>()
    val hiRes = path.lossless && (path.bitDepth >= 24 || path.sampleRateHz > 48_000)
    when {
        details.atmos -> tags += QualityTag("Dolby Atmos", TagTone.WHITE, PhosphorIcons.Regular.Cube)
        hiRes -> tags += QualityTag("Hi-Res Lossless", TagTone.WHITE, PhosphorIcons.Regular.Waveform)
        path.lossless -> tags += QualityTag("Lossless", TagTone.WHITE, PhosphorIcons.Regular.Waveform)
        path.bitrateKbps >= 224 -> tags += QualityTag("High quality", TagTone.GRAY, PhosphorIcons.Regular.Headphones)
    }
    tags += QualityTag(path.codec.uppercase(), TagTone.GRAY)
    val detail =
        if (path.lossless) {
            listOfNotNull(
                path.bitDepth.takeIf { it > 0 }?.let { "$it-bit" },
                path.sampleRateHz.takeIf { it > 0 }?.let { khz(it) },
            ).joinToString(" / ")
        } else {
            path.bitrateKbps.takeIf { it > 0 }?.let { "$it kbps" }.orEmpty()
        }
    if (detail.isNotEmpty()) tags += QualityTag(detail, TagTone.GRAY)
    if (path.bitPerfect == BitPerfectStatus.ACTIVE) tags += QualityTag("Bit-perfect", TagTone.BLACK, PhosphorIcons.Regular.Disc)
    if (details.upgradedFrom.isNotEmpty()) {
        tags += QualityTag("Upgraded from ${details.upgradedFrom}", TagTone.BLACK, PhosphorIcons.Regular.ArrowSquareUp)
    }
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

@Composable
private fun QualityTagBox(tag: QualityTag) {
    val (fill, content, border) =
        when (tag.tone) {
            TagTone.WHITE -> Triple(Color.White.copy(alpha = 0.92f), Color(0xFF111111), null)
            TagTone.GRAY -> Triple(Color.White.copy(alpha = 0.16f), Color.White.copy(alpha = 0.85f), null)
            TagTone.BLACK -> Triple(Color.Black.copy(alpha = 0.55f), Color.White, BorderStroke(0.5.dp, Color.White.copy(alpha = 0.35f)))
        }
    val shape = RoundedCornerShape(5.dp)
    Row(
        Modifier
            .height(20.dp)
            .clip(shape)
            .background(fill)
            .then(if (border != null) Modifier.border(border, shape) else Modifier)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (tag.icon != null) {
            Icon(tag.icon, null, tint = content, modifier = Modifier.size(11.dp))
            Spacer(Modifier.width(3.dp))
        }
        Text(tag.text, color = content, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
    }
}
