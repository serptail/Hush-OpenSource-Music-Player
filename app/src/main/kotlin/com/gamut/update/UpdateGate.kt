package com.gamut.update

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.gamut.core.designsystem.CoverTheme
import com.gamut.core.designsystem.GamutTheme
import com.gamut.core.designsystem.LocalAppearance
import com.gamut.core.designsystem.appNeutralPalette
import com.gamut.core.designsystem.ink
import com.gamut.settings.GamutButton
import com.gamut.settings.GamutDestructive
import com.gamut.settings.GamutHeading
import kotlinx.coroutines.delay

/**
 * Wraps the whole app. Shows [content] only when this build is allowed to run; otherwise the update screen.
 * The check runs each time the app comes to the foreground (and is throttled inside the view model).
 */
@Composable
fun UpdateGate(vm: AppUpdateViewModel = hiltViewModel(), content: @Composable () -> Unit) {
    val ui by vm.state.collectAsState()

    val activity = LocalContext.current as? ComponentActivity
    DisposableEffect(activity) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_START) vm.onStart() }
        activity?.lifecycle?.addObserver(observer)   // replays the current state, so this also runs the first check
        onDispose { activity?.lifecycle?.removeObserver(observer) }
    }

    if (!ui.checking && ui.required == null) {
        content()
        return
    }

    val palette = appNeutralPalette(LocalAppearance.current)
    GamutTheme(dark = !palette.isLight) {
        CoverTheme(palette) {
            Box(Modifier.fillMaxSize().background(palette.base).systemBarsPadding(), contentAlignment = Alignment.Center) {
                val info = ui.required
                if (info == null) {
                    // Still checking. Only show a spinner if it takes a moment, so a fast check does not flash one.
                    var showSpinner by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) { delay(500); showSpinner = true }
                    if (showSpinner) CircularProgressIndicator(Modifier.size(28.dp), color = ink(0.6f), strokeWidth = 2.dp)
                } else {
                    UpdateRequired(ui, info, onUpdate = vm::startUpdate)
                }
            }
        }
    }
}

@Composable
private fun UpdateRequired(ui: UpdateUiState, info: UpdateInfo, onUpdate: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp)) {
        Text("Update required", style = GamutHeading, color = ink())
        Text(
            "Gamut ${info.versionName} is out. YouTube changes all the time, so every update has to be installed to keep playback working.",
            style = MaterialTheme.typography.bodyLarge, color = ink(0.7f), modifier = Modifier.padding(top = 12.dp),
        )
        if (info.notes.isNotBlank()) {
            Text(info.notes.take(400), style = MaterialTheme.typography.bodyMedium, color = ink(0.55f), modifier = Modifier.padding(top = 12.dp))
        }
        Spacer(Modifier.height(28.dp))

        when (ui.phase) {
            UpdatePhase.DOWNLOADING -> LinearProgressIndicator(
                progress = { ui.progress },
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                color = ink(0.95f),
                trackColor = ink(0.18f),
            )
            UpdatePhase.NEEDS_PERMISSION -> Text(
                "Android needs your OK before Gamut can install updates. Allow it on the next screen, then come back.",
                style = MaterialTheme.typography.bodyMedium, color = ink(0.7f), modifier = Modifier.padding(bottom = 16.dp),
            )
            else -> Unit
        }
        if (ui.error != null) {
            Text(ui.error, style = MaterialTheme.typography.bodyMedium, color = GamutDestructive, modifier = Modifier.padding(bottom = 16.dp))
        }

        GamutButton(
            label = when (ui.phase) {
                UpdatePhase.READY -> "Update now"
                UpdatePhase.NEEDS_PERMISSION -> "Allow installs"
                UpdatePhase.DOWNLOADING -> "Downloading ${(ui.progress * 100).toInt()}%"
                UpdatePhase.INSTALLING -> "Installing..."
                UpdatePhase.FAILED -> "Try again"
            },
            onClick = onUpdate,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
