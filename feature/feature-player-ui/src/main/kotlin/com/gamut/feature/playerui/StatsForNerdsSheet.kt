package com.gamut.feature.playerui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gamut.core.designsystem.GamutSheet
import com.gamut.core.designsystem.bounceClickable
import com.gamut.core.designsystem.ink
import com.gamut.core.player.AudioPipelineInfo
import com.gamut.core.player.AudioStatsReport
import com.gamut.core.player.StatRow
import com.gamut.core.player.StatState
import com.gamut.core.player.StatsReport

private val Amber = Color(0xFFFFB74D)

/**
 * Everything the player knows about what it is playing and how it reaches the speaker: the stream, the
 * decoder, each sound stage, the output route, and every place the pipeline fell back from what was asked.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StatsForNerdsSheet(
    accent: Color,
    onDismiss: () -> Unit,
) {
    val path by AudioPipelineInfo.state.collectAsState()
    val details by AudioPipelineInfo.details.collectAsState()
    val report = remember(path, details) { AudioStatsReport.build(path, details) }
    val clipboard = LocalClipboardManager.current

    DisposableEffect(Unit) {
        AudioPipelineInfo.watching = true
        onDispose { AudioPipelineInfo.watching = false }
    }

    GamutSheet(onDismiss = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Stats for nerds",
                    style = MaterialTheme.typography.titleLarge,
                    color = ink(),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "Copy",
                    style = MaterialTheme.typography.labelLarge,
                    color = accent,
                    modifier =
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .bounceClickable { clipboard.setText(AnnotatedString(report.asText())) }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            if (report.headline.isBlank()) {
                Text(
                    "Play a track to see its signal path.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ink(0.6f),
                )
                report.sections.forEach { Section(it.title, it.rows, accent) }
            } else {
                Text(
                    report.headline,
                    style = MaterialTheme.typography.titleMedium,
                    color = accent,
                    fontWeight = FontWeight.SemiBold,
                )
                Chain(report)
                if (report.fallbacks.isNotEmpty()) Fallbacks(report.fallbacks)
                report.sections.forEach { Section(it.title, it.rows, accent) }
            }
        }
    }
}

@Composable
private fun Chain(report: StatsReport) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(ink(0.06f))
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text("Signal path", style = MaterialTheme.typography.labelMedium, color = ink(0.5f))
        Spacer(Modifier.height(6.dp))
        report.chain.forEachIndexed { i, stage ->
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    if (i == 0) "●" else "↓",
                    fontSize = 11.sp,
                    color = ink(0.4f),
                    modifier = Modifier.width(18.dp).padding(top = 2.dp),
                    textAlign = TextAlign.Center,
                )
                Text(
                    stage,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    color = ink(0.9f),
                    modifier = Modifier.padding(start = 8.dp, bottom = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun Fallbacks(items: List<String>) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Amber.copy(alpha = 0.14f))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Fallbacks and notes", style = MaterialTheme.typography.labelMedium, color = Amber, fontWeight = FontWeight.SemiBold)
        items.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = ink(0.9f)) }
    }
}

@Composable
private fun Section(
    title: String,
    rows: List<StatRow>,
    accent: Color,
) {
    Column {
        Text(title, style = MaterialTheme.typography.labelLarge, color = ink(0.5f), modifier = Modifier.padding(bottom = 4.dp))
        rows.forEach { StatLine(it, accent) }
    }
}

@Composable
private fun StatLine(
    row: StatRow,
    accent: Color,
) {
    val dot =
        when (row.state) {
            StatState.ON -> accent
            StatState.WARN -> Amber
            StatState.OFF -> ink(0.25f)
            StatState.INFO -> Color.Transparent
        }
    val valueColor =
        when (row.state) {
            StatState.WARN -> Amber
            StatState.OFF -> ink(0.45f)
            else -> ink(0.9f)
        }
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                .padding(top = 6.dp)
                .size(6.dp)
                .clip(CircleShape)
                .background(dot),
        )
        Text(
            row.label,
            style = MaterialTheme.typography.bodyMedium,
            color = ink(0.6f),
            modifier = Modifier.padding(start = 10.dp).weight(0.42f),
        )
        Text(
            row.value,
            style = MaterialTheme.typography.bodyMedium,
            color = valueColor,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(0.58f),
        )
    }
}
