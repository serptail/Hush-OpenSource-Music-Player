package com.gamut.onboarding

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.gamut.R
import com.gamut.core.designsystem.CoverTheme
import com.gamut.core.designsystem.FlowingBackground
import com.gamut.core.designsystem.GamutBackdrop
import com.gamut.core.designsystem.GamutTheme
import com.gamut.core.designsystem.LocalAppearance
import com.gamut.core.designsystem.LocalCoverPalette
import com.gamut.core.designsystem.TransparentStatusBar
import com.gamut.core.designsystem.appNeutralPalette
import com.gamut.core.designsystem.ink
import com.gamut.settings.AppearanceViewModel

/** Wrap the app with this: shows the welcome screen until the user signs in or chooses to continue locally. */
@Composable
fun OnboardingGate(vm: WelcomeViewModel = hiltViewModel(), app: @Composable () -> Unit) {
    val rawGate by vm.gate.collectAsState()
    // Once the app has been shown, a transient Loading (Supabase re-checking the session when you come back from
    // the file picker) must NOT remove it from composition: that threw away nav state, rememberSaveable state
    // (splash replayed, Profile left) and the export/import launchers, so the picked file never got written.
    var appShown by remember { mutableStateOf(false) }
    if (rawGate == Gate.Ready) appShown = true
    val gate = if (rawGate == Gate.Loading && appShown) Gate.Ready else rawGate
    when (gate) {
        Gate.Ready -> app()
        Gate.Welcome -> AppearanceRoot { WelcomeScreen(vm) }
        // Same colour as the window background (the saved light/dark look), so there is no visible flash.
        Gate.Loading -> AppearanceRoot { Box(Modifier.fillMaxSize().background(LocalCoverPalette.current.base)) }
    }
}

/**
 * Gives the pre-app screens the user's saved look: theme mode (light / dark / system), or the fixed colour preset.
 * Nothing is playing yet, so the palette is the solid "neutral" one from the Appearance settings.
 */
@Composable
private fun AppearanceRoot(content: @Composable () -> Unit) {
    val appearance by hiltViewModel<AppearanceViewModel>().settings.collectAsState()
    val palette = appNeutralPalette(appearance)
    TransparentStatusBar(darkIcons = palette.isLight)
    CompositionLocalProvider(LocalAppearance provides appearance) {
        GamutTheme { CoverTheme(palette) { content() } }
    }
}

@Composable
fun WelcomeScreen(
    vm: WelcomeViewModel,
    // Swap in your real logo here, e.g. { Image(painterResource(R.drawable.gamut_logo), null, Modifier.size(120.dp)) }
      logo: @Composable () -> Unit = {
      Image(painterResource(com.gamut.core.designsystem.R.drawable.gamut_logo), null, Modifier.size(120.dp))
      },
) {
    val ui by vm.ui.collectAsState()
    val context = LocalContext.current
    val palette = LocalCoverPalette.current

    GamutBackdrop {
        Box(Modifier.fillMaxSize()) {
            FlowingBackground(
                palette = palette,
                isPlaying = true,
                visible = true,
                modifier = Modifier.fillMaxSize(),
            )
            Column(
                Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.weight(1f))
                logo()
                Spacer(Modifier.height(12.dp))
                Text(
                    "Your library, on your terms.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = ink(0.7f),
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.weight(1.2f))

                Button(
                    onClick = { vm.signIn(context) },
                    enabled = !ui.busy,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = CircleShape,
                    // Google's button stays white in both modes; the hairline keeps it visible on a light background.
                    border = BorderStroke(1.dp, ink(0.12f)),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = Color(0xFF1F1F1F),
                        disabledContainerColor = Color.White.copy(alpha = 0.6f),
                        disabledContentColor = Color(0xFF1F1F1F),
                    ),
                ) {
                    if (ui.busy) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color(0xFF1F1F1F))
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Image, not Icon: Icon would tint the four Google colours into one.
                            Image(painterResource(R.drawable.ic_google), contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(12.dp))
                            Text("Continue with Google", fontWeight = FontWeight.Medium)
                        }
                    }
                }
                Text(
                    "Syncs your favorites and playlists across devices automatically.",
                    style = MaterialTheme.typography.bodySmall,
                    color = ink(0.55f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp, start = 8.dp, end = 8.dp),
                )

                Spacer(Modifier.height(20.dp))

                OutlinedButton(
                    onClick = vm::continueLocally,
                    enabled = !ui.busy,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = CircleShape,
                    border = BorderStroke(1.dp, ink(0.35f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = ink()),
                ) { Text("Continue locally", fontWeight = FontWeight.Medium) }
                Text(
                    "Everything stays on this device. Gamut saves a backup copy automatically every day.",
                    style = MaterialTheme.typography.bodySmall,
                    color = ink(0.55f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp, start = 8.dp, end = 8.dp),
                )

                ui.error?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
                Spacer(Modifier.height(28.dp))
            }
        }
    }
}

/** Placeholder until the real logo is plugged in. */
@Composable
private fun Wordmark() {
    Text(
        "Gamut",
        style = MaterialTheme.typography.displayMedium,
        fontWeight = FontWeight.Bold,
        color = ink(),
    )
}