package com.gamut.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ArrowSquareUp
import com.adamglin.phosphoricons.regular.Check
import com.adamglin.phosphoricons.regular.Devices
import com.adamglin.phosphoricons.regular.Plugs
import com.adamglin.phosphoricons.regular.SpeakerHifi
import com.adamglin.phosphoricons.regular.Trash
import com.gamut.core.designsystem.ink
import com.gamut.core.designsystem.pressScale
import com.gamut.core.player.audio.BitPerfectMode

// ───────────────────────── shared popup building blocks ─────────────────────────

/** A selectable row for popups: optional icon, title, hint, and a check when selected. */
@Composable
internal fun PopupOption(
    title: String,
    subtitle: String?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) ink(0.10f) else Color.Transparent)
            .pressScale(onClick = onClick, scaleDown = 0.98f)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = ink(if (selected) 0.95f else 0.6f), modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(14.dp))
        }
        RowText(title, subtitle, Modifier.weight(1f))
        if (selected) {
            Spacer(Modifier.width(10.dp))
            Icon(PhosphorIcons.Regular.Check, "Selected", tint = ink(0.95f), modifier = Modifier.size(20.dp))
        }
    }
}

/** A tappable action row (reorder, remove, add). */
@Composable
internal fun PopupAction(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit,
    subtitle: String? = null,
    enabled: Boolean = true,
    destructive: Boolean = false,
) {
    val tint = if (destructive) MaterialTheme.colorScheme.error else ink()
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.35f)
            .then(if (enabled) Modifier.pressScale(onClick = onClick, scaleDown = 0.98f) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = tint)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = ink(0.55f), modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

/** A label and its value on one line, for read-only detail. */
@Composable
internal fun InfoLine(
    label: String,
    value: String,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.Top) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = ink(0.55f))
        Spacer(Modifier.width(16.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = ink(),
            textAlign = TextAlign.End,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Small explanatory text under a block inside a popup. */
@Composable
internal fun PopupNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = ink(0.55f),
        modifier = Modifier.padding(horizontal = 6.dp).padding(top = 10.dp),
    )
}

/** Pill shortcuts such as Off / 3 s / 6 s. */
@Composable
internal fun QuickPicks(
    options: List<Pair<String, Boolean>>,
    onPick: (Int) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { i, (label, selected) ->
            Box(
                Modifier
                    .weight(1f)
                    .height(36.dp)
                    .clip(CircleShape)
                    .background(if (selected) ink(0.95f) else ink(0.08f))
                    .pressScale(onClick = { onPick(i) }, scaleDown = 0.95f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) onInkColor() else ink(0.7f),
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun onInkColor(): Color =
    if (com.gamut.core.designsystem.LocalCoverPalette.current.isLight) Color.White else Color.Black

@Composable
private fun PopupScroll(content: @Composable () -> Unit) {
    Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) { content() }
}

// ───────────────────────── detail popups ─────────────────────────

@Composable
internal fun PreloadDialog(
    preload: Boolean,
    wifiOnly: Boolean,
    onPreload: (Boolean) -> Unit,
    onWifiOnly: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    GamutDialog(
        title = "Preloading",
        body = "Loads the next track while the current one plays, so skips are instant and tracks join without a gap.",
        confirmLabel = "Done",
        onConfirm = onDismiss,
        onDismiss = onDismiss,
        dismissLabel = null,
    ) {
        Column {
            SoftCard {
                SoftSwitchRow("Preload next track", preload, onPreload, subtitle = "Gapless transitions and instant skips")
                SoftDivider()
                SoftSwitchRow(
                    "Wi-Fi only",
                    wifiOnly,
                    onWifiOnly,
                    subtitle = "On mobile data, load it when needed",
                    enabled = preload,
                )
            }
            PopupNote("Preloading uses a little extra data. Wi-Fi only keeps it off your mobile plan.")
        }
    }
}

@Composable
internal fun CrossfadeDialog(
    seconds: Int,
    onChange: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    GamutDialog(
        title = "Crossfade",
        body = "Fades the end of a track into the start of the next one.",
        confirmLabel = "Done",
        onConfirm = onDismiss,
        onDismiss = onDismiss,
        dismissLabel = null,
    ) {
        Column {
            SoftCard {
                SoftSliderRow(
                    title = "Length",
                    valueLabel = if (seconds == 0) "Off" else "$seconds s",
                    value = seconds.toFloat(),
                    range = 0f..12f,
                    steps = 11,
                    onChange = { onChange(it.toInt()) },
                )
            }
            Spacer(Modifier.height(10.dp))
            val picks = listOf(0, 3, 6, 12)
            QuickPicks(picks.map { (if (it == 0) "Off" else "$it s") to (it == seconds) }) { onChange(picks[it]) }
            PopupNote("With crossfade off, songs stay gapless. Bit-perfect output ignores crossfade.")
        }
    }
}

@Composable
internal fun BitPerfectDialog(
    mode: BitPerfectMode,
    onMode: (BitPerfectMode) -> Unit,
    allowFixedVolume: Boolean,
    onAllowFixedVolume: (Boolean) -> Unit,
    convertForLimitedDac: Boolean,
    onConvertForLimitedDac: (Boolean) -> Unit,
    status: String?,
    onDismiss: () -> Unit,
) {
    GamutDialog(
        title = "Bit-perfect output",
        body = "Sends each track to your DAC unchanged, at its own sample rate and bit depth.",
        confirmLabel = "Done",
        onConfirm = onDismiss,
        onDismiss = onDismiss,
        dismissLabel = null,
    ) {
        PopupScroll {
            BitPerfectMode.entries.forEach { m ->
                PopupOption(
                    title = m.label,
                    subtitle = m.hint,
                    selected = m == mode,
                    icon = bitPerfectIcon(m),
                    onClick = { onMode(m) },
                )
            }
            if (mode != BitPerfectMode.OFF) {
                Spacer(Modifier.height(10.dp))
                SoftCard {
                    if (mode == BitPerfectMode.USB_DIRECT) {
                        SoftSwitchRow(
                            "Allow DACs without volume",
                            allowFixedVolume,
                            onAllowFixedVolume,
                            subtitle = "Volume is then set on the amplifier",
                        )
                        SoftDivider()
                    }
                    SoftSwitchRow(
                        "Convert for limited DACs",
                        convertForLimitedDac,
                        onConvertForLimitedDac,
                        subtitle = "Not bit-exact, but plays more tracks",
                    )
                }
                if (!status.isNullOrBlank()) PopupNote(status)
            }
            PopupNote(
                "Bit-perfect bypasses the equalizer, spatial audio, speed, crossfade and fades, and leaves volume to the DAC. " +
                    "Direct USB works on any Android version.",
            )
        }
    }
}

internal fun bitPerfectIcon(m: BitPerfectMode): ImageVector =
    when (m) {
        BitPerfectMode.OFF -> PhosphorIcons.Regular.SpeakerHifi
        BitPerfectMode.USB_DIRECT -> GamutIcons.DirectUsb
        BitPerfectMode.DAP_EXCLUSIVE -> GamutIcons.BuiltInDac
        BitPerfectMode.USB_ANDROID -> PhosphorIcons.Regular.Devices
    }

/** Read-only view of the live audio path (stream, output, bit-perfect state). */
@Composable
internal fun PipelineDialog(
    rows: List<Pair<String, String>>,
    onDismiss: () -> Unit,
) {
    GamutDialog(
        title = "Audio path",
        body = "What is playing right now, from the source to your output.",
        confirmLabel = "Done",
        onConfirm = onDismiss,
        onDismiss = onDismiss,
        dismissLabel = null,
    ) {
        PopupScroll {
            SoftCard {
                rows.forEachIndexed { i, (k, v) ->
                    if (i > 0) SoftDivider()
                    InfoLine(k, v)
                }
            }
        }
    }
}

@Composable
internal fun SourceDialog(
    name: String,
    kindLabel: String,
    url: String,
    position: Int,
    total: Int,
    onEarlier: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    GamutDialog(
        title = name,
        body = "Sources are asked in order before YouTube.",
        confirmLabel = "Done",
        onConfirm = onDismiss,
        onDismiss = onDismiss,
        dismissLabel = null,
    ) {
        PopupScroll {
            SoftCard {
                InfoLine("Type", kindLabel)
                SoftDivider()
                InfoLine("Address", url)
                SoftDivider()
                InfoLine("Order", "${position + 1} of $total")
            }
            Spacer(Modifier.height(10.dp))
            SoftCard {
                PopupAction(
                    "Try earlier",
                    PhosphorIcons.Regular.ArrowSquareUp,
                    onEarlier,
                    subtitle = if (position == 0) "Already asked first" else "Ask this source before the one above",
                    enabled = position > 0,
                )
                SoftDivider()
                PopupAction("Remove source", PhosphorIcons.Regular.Trash, onRemove, destructive = true)
            }
        }
    }
}

@Composable
internal fun AddSourceDialog(
    onAddAddon: (String) -> Boolean,
    onAddModuleIndex: (String) -> Boolean,
    onDismiss: () -> Unit,
) {
    var url by remember { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }

    fun attempt(add: (String) -> Boolean) {
        if (add(url)) onDismiss() else failed = true
    }

    GamutDialog(
        title = "Add a source",
        body = "Paste an address, then choose what it points to.",
        confirmLabel = "Cancel",
        onConfirm = onDismiss,
        onDismiss = onDismiss,
        dismissLabel = null,
    ) {
        Column {
            GamutField(url, { url = it; failed = false }, "https://\u2026")
            if (failed) {
                Text(
                    "That address could not be added. Check it starts with https:// and try again.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 4.dp, top = 8.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            SoftCard {
                PopupAction(
                    "Addon server",
                    GamutIcons.Addons,
                    { attempt(onAddAddon) },
                    subtitle = "Serves /manifest.json, /search and /stream over HTTP",
                    enabled = url.isNotBlank(),
                )
                SoftDivider()
                PopupAction(
                    "Module index",
                    GamutIcons.JavaScript,
                    { attempt(onAddModuleIndex) },
                    subtitle = "A JSON index of JS plugins, run in a sandbox",
                    enabled = url.isNotBlank(),
                )
            }
            PopupNote("Gamut ships no sources. You are responsible for what you add.")
        }
    }
}

/** Plugs icon is kept for callers that want it next to USB rows. */
internal val UsbPlugIcon: ImageVector get() = PhosphorIcons.Regular.Plugs