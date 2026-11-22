package com.gamut.feature.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Bold
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.bold.ArrowCircleDown
import com.adamglin.phosphoricons.fill.CheckCircle
import com.gamut.core.designsystem.collectIsPulsedAsState
import com.gamut.core.designsystem.ink
import com.gamut.core.designsystem.pressScale
import com.gamut.feature.resolver.DownloadStage
import com.gamut.feature.resolver.DownloadState
import com.gamut.feature.resolver.describe
import com.gamut.feature.resolver.formatSpeed

private val FailureColor = Color(0xFFFF6B6B)

enum class PlaylistDownloadStatus { Idle, Active, Done }

data class PlaylistDownloadUi(
    val status: PlaylistDownloadStatus = PlaylistDownloadStatus.Idle,
    val progress: Float = 0f,
    val done: Int = 0,
    val total: Int = 0,
    val failed: Int = 0,
    val waiting: Int = 0,
    val bytesPerSec: Long = 0L,
) {
    val percent: Int get() = (progress.coerceIn(0f, 1f) * 100).toInt()
}

fun playlistDownloadUi(
    isrcs: List<String>,
    states: Map<String, DownloadState>,
): PlaylistDownloadUi {
    if (isrcs.isEmpty()) return PlaylistDownloadUi()
    var done = 0
    var busy = false
    var fraction = 0f
    var failed = 0
    var waiting = 0
    var speed = 0L
    isrcs.forEach { isrc ->
        when (val state = states[isrc]) {
            DownloadState.Done -> {
                done++
                fraction += 1f
            }

            is DownloadState.Active -> {
                busy = true
                fraction += state.progress
                speed += state.bytesPerSec
            }

            DownloadState.Queued -> {
                busy = true
                waiting++
            }

            is DownloadState.Failed -> {
                failed++
            }

            else -> {
                Unit
            }
        }
    }
    val status =
        when {
            busy -> PlaylistDownloadStatus.Active
            done == isrcs.size -> PlaylistDownloadStatus.Done
            else -> PlaylistDownloadStatus.Idle
        }
    return PlaylistDownloadUi(status, fraction / isrcs.size, done, isrcs.size, failed, waiting, speed)
}

private val GlyphSize = 28.dp
private val ChipShape = RoundedCornerShape(11.dp)
private val PillShape = RoundedCornerShape(percent = 50)

/** Pill for a whole playlist: an icon when idle, a filling "12/40 \u00b7 30%" capsule while busy. */
@Composable
fun DownloadButton(
    ui: PlaylistDownloadUi,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    tint: Color = ink(0.85f),
    accent: Color = ink(),
    glyphSize: Dp = GlyphSize,
    idleIcon: ImageVector = PhosphorIcons.Bold.ArrowCircleDown,
    doneIcon: ImageVector = PhosphorIcons.Fill.CheckCircle,
) {
    val interaction = remember { MutableInteractionSource() }
    val pulsed by interaction.collectIsPulsedAsState()
    val press by animateFloatAsState(if (pulsed) 0.94f else 1f, tween(120), label = "downloadPress")
    var watched by remember { mutableStateOf(false) }
    LaunchedEffect(ui.status) { if (ui.status == PlaylistDownloadStatus.Active) watched = true }
    val active = ui.status == PlaylistDownloadStatus.Active
    val label =
        when (ui.status) {
            PlaylistDownloadStatus.Idle -> "Download playlist"
            PlaylistDownloadStatus.Active -> "Downloading ${ui.done} of ${ui.total}, ${ui.percent} percent. Tap to stop"
            PlaylistDownloadStatus.Done -> "Downloaded"
        }

    Box(
        modifier
            .animateContentSize(tween(220))
            .height(size)
            .widthIn(min = size)
            .graphicsLayer {
                scaleX = press
                scaleY = press
            }.clip(PillShape)
            .then(if (active) Modifier.background(tint.copy(alpha = 0.12f)) else Modifier)
            .semantics { contentDescription = label }
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClickLabel = label,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (active) {
            val shown by animateFloatAsState(ui.progress.coerceIn(0.03f, 1f), tween(300), label = "playlistFill")
            Box(Modifier.matchParentSize()) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(shown).background(accent.copy(alpha = 0.26f)))
            }
        }
        Crossfade(ui.status, animationSpec = tween(200), label = "downloadStatus") { status ->
            when (status) {
                PlaylistDownloadStatus.Idle -> {
                    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
                        Icon(
                            idleIcon,
                            "Download playlist",
                            tint = if (ui.failed > 0) FailureColor else tint,
                            modifier = Modifier.size(glyphSize),
                        )
                    }
                }

                PlaylistDownloadStatus.Active -> {
                    Row(
                        Modifier.padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            "${ui.done}/${ui.total}",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                            color = accent,
                            maxLines = 1,
                        )
                        Text(
                            "${ui.percent}%",
                            style = MaterialTheme.typography.labelMedium,
                            color = accent.copy(alpha = 0.7f),
                            maxLines = 1,
                        )
                    }
                }

                PlaylistDownloadStatus.Done -> {
                    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
                        CheckCirclePop(animate = watched, color = accent, size = glyphSize, icon = doneIcon)
                    }
                }
            }
        }
    }
}

private enum class Kind { Idle, Queued, Preparing, Downloading, Finishing, Done, Failed }

/**
 * Per-track control. The old thin ring is gone: while downloading, a rounded tile fills from
 * the bottom and shows the percentage as a number, so progress reads at a glance.
 */
@Composable
fun TrackDownloadButton(
    state: DownloadState?,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    glyph: Dp = 24.dp,
    tint: Color = ink(0.7f),
    accent: Color = ink(),
) {
    var watched by remember { mutableStateOf(false) }
    LaunchedEffect(state is DownloadState.Active) { if (state is DownloadState.Active) watched = true }
    val kind =
        when (state) {
            null -> Kind.Idle
            DownloadState.Queued -> Kind.Queued
            is DownloadState.Active ->
                when (state.stage) {
                    DownloadStage.Preparing -> Kind.Preparing
                    DownloadStage.Downloading -> Kind.Downloading
                    DownloadStage.Finishing -> Kind.Finishing
                }
            DownloadState.Done -> Kind.Done
            is DownloadState.Failed -> Kind.Failed
        }
    val percent = ((state as? DownloadState.Active)?.progress ?: 0f).coerceIn(0f, 1f)
    val chip = glyph + 12.dp
    val description =
        when (kind) {
            Kind.Idle -> "Download"
            Kind.Queued -> "Queued. Tap to cancel"
            Kind.Preparing -> "Preparing download. Tap to cancel"
            Kind.Downloading -> "Downloading, ${(percent * 100).toInt()} percent. Tap to cancel"
            Kind.Finishing -> "Saving. Tap to cancel"
            Kind.Done -> "Downloaded"
            Kind.Failed -> "Download failed. Tap to retry"
        }
    Box(
        modifier
            .size(size)
            .semantics { contentDescription = description }
            .pressScale(
                onClick =
                    when (kind) {
                        Kind.Idle, Kind.Failed -> onDownload
                        Kind.Queued, Kind.Preparing, Kind.Downloading, Kind.Finishing -> onCancel
                        Kind.Done -> onDone
                    },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(kind, modifier = Modifier.size(chip), animationSpec = tween(180), label = "trackDownload") { shown ->
            Box(Modifier.size(chip), contentAlignment = Alignment.Center) {
                when (shown) {
                    Kind.Idle -> {
                        Icon(PhosphorIcons.Bold.ArrowCircleDown, null, tint = tint, modifier = Modifier.size(glyph))
                    }

                    Kind.Failed -> {
                        Box(
                            Modifier.fillMaxSize().clip(ChipShape).border(1.5.dp, FailureColor, ChipShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(PhosphorIcons.Bold.ArrowCircleDown, null, tint = FailureColor, modifier = Modifier.size(glyph - 4.dp))
                        }
                    }

                    Kind.Queued -> {
                        Box(
                            Modifier.fillMaxSize().clip(ChipShape).border(1.dp, tint.copy(alpha = 0.4f), ChipShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            PulseDots(tint)
                        }
                    }

                    Kind.Preparing -> {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .clip(ChipShape)
                                .background(accent.copy(alpha = 0.08f))
                                .border(1.dp, accent.copy(alpha = 0.3f), ChipShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            PulseDots(accent)
                        }
                    }

                    Kind.Downloading, Kind.Finishing -> {
                        val fill by animateFloatAsState(percent, tween(250), label = "tileFill")
                        Box(
                            Modifier
                                .fillMaxSize()
                                .clip(ChipShape)
                                .background(tint.copy(alpha = 0.12f))
                                .border(1.dp, accent.copy(alpha = 0.35f), ChipShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                                Box(Modifier.fillMaxWidth().fillMaxHeight(fill).background(accent.copy(alpha = 0.3f)))
                            }
                            Text(
                                if (shown == Kind.Finishing) "\u2026" else "${(percent * 100).toInt()}",
                                style =
                                    MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                    ),
                                color = accent,
                                maxLines = 1,
                            )
                        }
                    }

                    Kind.Done -> {
                        CheckCirclePop(animate = watched, color = accent, size = glyph)
                    }
                }
            }
        }
    }
}

/**
 * Status strip for a track row: a bar plus a line saying what is happening
 * ("42% \u00b7 2.1 MB of 5.4 MB \u00b7 780 KB/s \u00b7 4s left"). It collapses away when there is nothing to show.
 */
@Composable
fun DownloadProgressLine(
    state: DownloadState?,
    modifier: Modifier = Modifier,
    color: Color = ink(),
) {
    val visible = state is DownloadState.Queued || state is DownloadState.Active || state is DownloadState.Failed
    var last by remember { mutableStateOf(state) }
    if (visible) last = state
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(160)) + expandVertically(tween(200)),
        exit = fadeOut(tween(220)) + shrinkVertically(tween(260)),
    ) {
        val shown = last
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val failed = shown is DownloadState.Failed
            val bar = if (failed) FailureColor else color
            when (shown) {
                is DownloadState.Active ->
                    if (shown.stage == DownloadStage.Downloading) {
                        TrackBar(shown.progress, bar)
                    } else {
                        IndeterminateBar(bar)
                    }

                is DownloadState.Failed -> TrackBar(1f, bar.copy(alpha = 0.5f))

                else -> IndeterminateBar(bar)
            }
            val text =
                when (shown) {
                    is DownloadState.Active ->
                        if (shown.stage == DownloadStage.Downloading) {
                            "${(shown.progress.coerceIn(0f, 1f) * 100).toInt()}% \u00b7 ${shown.describe()}"
                        } else {
                            shown.describe()
                        }

                    is DownloadState.Failed -> "Failed \u00b7 ${shown.message.take(48)} \u00b7 tap to retry"
                    else -> "Waiting in queue"
                }
            Text(
                text,
                style = MaterialTheme.typography.labelSmall,
                color = if (failed) FailureColor else ink(0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Header panel for a playlist-wide download: title, percent, bar and a speed/queue line. */
@Composable
fun PlaylistDownloadPanel(
    ui: PlaylistDownloadUi,
    modifier: Modifier = Modifier,
    color: Color = ink(),
) {
    val visible = ui.status == PlaylistDownloadStatus.Active || (ui.failed > 0 && ui.status != PlaylistDownloadStatus.Done)
    var last by remember { mutableStateOf(ui) }
    if (visible) last = ui
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(160)) + expandVertically(tween(220)),
        exit = fadeOut(tween(200)) + shrinkVertically(tween(240)),
    ) {
        val shown = last
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(color.copy(alpha = 0.08f))
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (shown.status == PlaylistDownloadStatus.Active) "Downloading ${minOf(shown.done + 1, shown.total)} of ${shown.total}" else "Download paused",
                    style = MaterialTheme.typography.titleSmall,
                    color = color,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text("${shown.percent}%", style = MaterialTheme.typography.titleSmall, color = color)
            }
            TrackBar(shown.progress, color, height = 6.dp)
            val line =
                buildList {
                    if (shown.bytesPerSec > 0) add(formatSpeed(shown.bytesPerSec))
                    if (shown.waiting > 0) add("${shown.waiting} waiting")
                    add("${shown.done} done")
                    if (shown.failed > 0) add("${shown.failed} failed")
                }.joinToString(" \u00b7 ")
            Text(
                line,
                style = MaterialTheme.typography.labelMedium,
                color = if (shown.failed > 0 && shown.status != PlaylistDownloadStatus.Active) FailureColor else ink(0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TrackBar(
    progress: Float,
    color: Color,
    height: Dp = 4.dp,
) {
    val shown by animateFloatAsState(progress.coerceIn(0f, 1f), tween(240, easing = LinearEasing), label = "barProgress")
    Box(
        Modifier
            .fillMaxWidth()
            .height(height)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.16f)),
    ) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(shown).clip(CircleShape).background(color))
    }
}

@Composable
private fun IndeterminateBar(
    color: Color,
    height: Dp = 4.dp,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(height)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.16f)),
    ) {
        IndeterminateSweep(color, Modifier.fillMaxSize())
    }
}

@Composable
private fun IndeterminateSweep(
    color: Color,
    modifier: Modifier,
) {
    val transition = rememberInfiniteTransition(label = "sweep")
    val phase by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1300, easing = LinearEasing)), label = "sweepPhase")
    Canvas(modifier) {
        val segment = size.width * 0.35f
        val x = -segment + phase * (size.width + segment)
        clipRect {
            drawRoundRect(color, Offset(x, 0f), androidx.compose.ui.geometry.Size(segment, size.height), CornerRadius(size.height / 2f))
        }
    }
}

@Composable
private fun PulseDots(color: Color) {
    val transition = rememberInfiniteTransition(label = "dots")
    val phase by transition.animateFloat(0f, 3f, infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "dotsPhase")
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            val behind = (phase - i + 3f) % 3f
            Box(
                Modifier
                    .size(4.dp)
                    .graphicsLayer { alpha = 1f - (behind / 3f) * 0.75f }
                    .clip(CircleShape)
                    .background(color),
            )
        }
    }
}

@Composable
private fun CheckCirclePop(
    animate: Boolean,
    color: Color,
    size: Dp,
    icon: ImageVector = PhosphorIcons.Fill.CheckCircle,
) {
    val scale = remember { Animatable(if (animate) 0.35f else 1f) }
    LaunchedEffect(animate) {
        if (animate) scale.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMedium))
    }
    Icon(
        icon,
        "Downloaded",
        tint = color,
        modifier =
            Modifier.size(size).graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            },
    )
}

@Composable
fun AvailableOfflineLine(
    modifier: Modifier = Modifier,
    color: Color = ink(0.6f),
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(PhosphorIcons.Fill.CheckCircle, null, tint = color, modifier = Modifier.size(13.dp))
        Text(
            "Available offline",
            style = MaterialTheme.typography.labelSmall,
            color = color,
            maxLines = 1,
            modifier = Modifier.padding(start = 5.dp),
        )
    }
}
