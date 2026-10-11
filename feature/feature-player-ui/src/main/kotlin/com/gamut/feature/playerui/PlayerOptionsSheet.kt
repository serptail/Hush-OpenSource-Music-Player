package com.gamut.feature.playerui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import com.gamut.core.designsystem.GamutMotion
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Light
import com.adamglin.phosphoricons.light.Equalizer
import com.adamglin.phosphoricons.light.Heart
import com.adamglin.phosphoricons.light.ListPlus
import com.adamglin.phosphoricons.light.MicrophoneStage
import com.adamglin.phosphoricons.light.ShareNetwork
import com.adamglin.phosphoricons.light.Timer
import com.adamglin.phosphoricons.light.UserCircle
import com.gamut.core.designsystem.GamutSheet
import com.gamut.core.designsystem.ink

private val FavoriteTint = Color(0xFFFF4D6D)

/**
 * The three-dots menu on the player. Rows that open another sheet / screen close this one first (the caller does
 * that in its lambda); the two toggles (lyrics, favorite) are meant to stay open so the new state is visible.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerOptionsSheet(
    accent: Color,
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
    onDismiss: () -> Unit,
) {
    GamutSheet(onDismiss = onDismiss) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 16.dp),
        ) {
            Text(
                "Options",
                style = MaterialTheme.typography.titleLarge,
                color = ink(),
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            OptionRow(PhosphorIcons.Light.ShareNetwork, "Share", null, false, accent, onShare)
            OptionRow(PhosphorIcons.Light.MicrophoneStage, "Lyrics", if (lyricsOn) "On" else "Off", lyricsOn, accent, onToggleLyrics)
            OptionRow(
                PhosphorIcons.Light.Heart,
                if (isFavorite) "Remove from favorites" else "Add to favorites",
                null, isFavorite, FavoriteTint, onToggleFavorite,
            )
            OptionRow(PhosphorIcons.Light.ListPlus, "Add to playlist", null, false, accent, onAddToPlaylist)
            OptionRow(PhosphorIcons.Light.UserCircle, "Go to artist", null, false, accent, onGoToArtist)
            OptionRow(PhosphorIcons.Light.Timer, "Sleep timer", if (sleepActive) "On" else "Off", sleepActive, accent, onSleepTimer)
            OptionRow(PhosphorIcons.Light.Equalizer, "Equalizer", if (eqActive) "On" else "Off", eqActive, accent, onEqualizer)
        }
    }
}

@Composable
private fun OptionRow(icon: ImageVector, label: String, status: String?, active: Boolean, accent: Color, onClick: () -> Unit) {
    // Toggles (lyrics, favorite, ...) stay open: their colour eases instead of snapping.
    val iconTint by animateColorAsState(if (active) accent else ink(0.85f), tween(GamutMotion.CONTENT_MS), label = "option-icon")
    val statusTint by animateColorAsState(if (active) accent else ink(0.5f), tween(GamutMotion.CONTENT_MS), label = "option-status")
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = ink(), modifier = Modifier.weight(1f))
        if (status != null) {
            Text(status, style = MaterialTheme.typography.bodyMedium, color = statusTint)
        }
    }
}