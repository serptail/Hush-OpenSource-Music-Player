package com.gamut.feature.playerui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Light
import com.adamglin.phosphoricons.light.Equalizer
import com.adamglin.phosphoricons.light.Timer
import com.gamut.core.designsystem.CoverPalette

/**
 * The three-dots menu on the player. A list of options; tapping one closes this sheet and the caller opens
 * the matching one. Add more rows here later (the Mod panel, for instance) without touching the player.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerOptionsSheet(
    accent: Color,
    eqActive: Boolean,
    sleepActive: Boolean,
    onEqualizer: () -> Unit,
    onSleepTimer: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = CoverPalette.Neutral.base,
        contentColor = Color.White,
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(
                "Options",
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            OptionRow(PhosphorIcons.Light.Equalizer, "Equalizer", if (eqActive) "On" else "Off", eqActive, accent, onEqualizer)
            OptionRow(PhosphorIcons.Light.Timer, "Sleep timer", if (sleepActive) "On" else "Off", sleepActive, accent, onSleepTimer)
        }
    }
}

@Composable
private fun OptionRow(icon: ImageVector, label: String, status: String, active: Boolean, accent: Color, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = if (active) accent else Color.White.copy(alpha = 0.85f), modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = Color.White, modifier = Modifier.weight(1f))
        Text(status, style = MaterialTheme.typography.bodyMedium, color = if (active) accent else Color.White.copy(alpha = 0.5f))
    }
}
