package com.gamut.feature.playerui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gamut.core.designsystem.GamutMotion
import com.gamut.core.designsystem.GamutSheet
import com.gamut.core.designsystem.TablerIcons
import com.gamut.core.designsystem.bounceClickable
import com.gamut.core.designsystem.ink

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerOptionsSheet(
    isFavorite: Boolean,
    lyricsOn: Boolean,
    eqActive: Boolean,
    sleepActive: Boolean,
    onShare: () -> Unit,
    onToggleLyrics: () -> Unit,
    onToggleFavorite: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onGoToArtist: () -> Unit,
    onEqualizer: () -> Unit,
    onSleepTimer: () -> Unit,
    onStats: () -> Unit,
    onDismiss: () -> Unit,
    videoSupported: Boolean = false,
    videoOn: Boolean = false,
    onToggleVideo: () -> Unit = {},
    /** Null hides the row (nothing is playing). [upgradeStatus] is the row's right-hand text. */
    onUpgradeQuality: (() -> Unit)? = null,
    upgradeStatus: String? = null,
    /** No longer used: ON/OFF values are neutral so they stay legible on every theme. Kept so callers don't break. */
    @Suppress("UNUSED_PARAMETER") accent: Color = MaterialTheme.colorScheme.primary,
) {
    GamutSheet(onDismiss = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 16.dp),
        ) {
            Text(
                "Options",
                style = MaterialTheme.typography.titleLarge,
                color = ink(),
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            if (onUpgradeQuality != null) {
                OptionRow(TablerIcons.RippleUp, "Upgrade quality", upgradeStatus, onUpgradeQuality)
            }
            OptionRow(TablerIcons.Share, "Share", null, onShare)
            OptionRow(TablerIcons.Microphone, "Lyrics", null, onToggleLyrics, toggled = lyricsOn)
            if (videoSupported) {
                OptionRow(TablerIcons.Video, "Music video", null, onToggleVideo, toggled = videoOn)
            }
            OptionRow(
                TablerIcons.Heart,
                if (isFavorite) "Remove from favorites" else "Add to favorites",
                null,
                onToggleFavorite,
            )
            OptionRow(TablerIcons.ListPlus, "Add to playlist", null, onAddToPlaylist)
            OptionRow(TablerIcons.CircleUser, "Go to artist", null, onGoToArtist)
            OptionRow(TablerIcons.Timer, "Sleep timer", null, onSleepTimer, toggled = sleepActive)
            OptionRow(TablerIcons.Equalizer, "Equalizer", null, onEqualizer, toggled = eqActive)
            OptionRow(TablerIcons.Info, "Stats for nerds", null, onStats)
        }
    }
}

@Composable
private fun OptionRow(
    icon: ImageVector,
    label: String,
    status: String?,
    onClick: () -> Unit,
    /** Non-null shows an ON / OFF badge instead of [status]. */
    toggled: Boolean? = null,
) {
    Row(
        Modifier.fillMaxWidth().bounceClickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = ink(0.85f), modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = ink(), modifier = Modifier.weight(1f))
        if (toggled != null) {
            ToggleValue(on = toggled)
        } else if (status != null) {
            Text(status, style = MaterialTheme.typography.bodyMedium, color = ink(0.5f))
        }
    }
}

/**
 * The ON / OFF value of a toggle row. Deliberately neutral (theme ink only, no accent colour, no dot) so it stays
 * readable on every cover palette: ON is a bold text on a soft filled pill, OFF is dim text on a quiet outline.
 */
@Composable
internal fun ToggleValue(
    on: Boolean,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(50)
    val tween = tween<Color>(GamutMotion.CONTENT_MS)
    val tone by animateColorAsState(if (on) ink() else ink(0.45f), tween, label = "toggle-value-tone")
    val fill by animateColorAsState(if (on) ink(0.12f) else Color.Transparent, tween, label = "toggle-value-fill")
    val ring by animateColorAsState(if (on) ink(0.4f) else ink(0.16f), tween, label = "toggle-value-ring")
    Text(
        if (on) "ON" else "OFF",
        color = tone,
        fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
        fontSize = 12.sp,
        letterSpacing = 0.8.sp,
        modifier =
            modifier
                .clip(shape)
                .background(fill)
                .border(1.dp, ring, shape)
                .padding(horizontal = 12.dp, vertical = 5.dp),
    )
}
