package com.gamut.onboarding

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.os.Build
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.ColorFilter
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
import com.gamut.core.designsystem.GamutHeatBackground
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
    // The stacked brand logo, in the variant that reads on the current background (dark text on light, light text on dark).
    // [shadow] = true draws the same logo flat black, used for the drop shadow behind it.
    logo: @Composable (shadow: Boolean) -> Unit = { shadow ->
        val res = if (LocalCoverPalette.current.isLight) R.drawable.ic_gamut_stacked_black
        else R.drawable.ic_gamut_stacked_white
        Image(
            painterResource(res),
            contentDescription = if (shadow) null else "Gamut",
            modifier = Modifier.height(170.dp),
            contentScale = ContentScale.Fit,
            colorFilter = if (shadow) ColorFilter.tint(Color.Black) else null,
        )
    },
) {
    val ui by vm.ui.collectAsState()
    val context = LocalContext.current
    val palette = LocalCoverPalette.current

    // Three lines, the middle one softer: reads as one sentence, looks like a poster.
    val strong = ink()
    val soft = ink(0.38f)
    val headline = buildAnnotatedString {
        withStyle(SpanStyle(color = strong)) { append("Your library,\n") }
        withStyle(SpanStyle(color = soft)) { append("on your\n") }
        withStyle(SpanStyle(color = strong)) { append("terms.") }
    }

    GamutBackdrop {
        Box(Modifier.fillMaxSize()) {
            // Grain heatmap glow in the logo colours (magenta/purple left, cyan/teal right) over the calm base.
            GamutHeatBackground(base = palette.base, isLight = palette.isLight, modifier = Modifier.fillMaxSize())
            Column(
                Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 28.dp),
            ) {
                Spacer(Modifier.weight(1f))

                // Hero: the logo, drifting slowly up and down.
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Pop(delayMs = 100) {
                        Box(Modifier.floating()) {
                            // Drop shadow: a blurred, offset, semi-transparent black copy of the logo (blur needs Android 12+).
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                Box(Modifier.offset(y = 14.dp).blur(18.dp).graphicsLayer { alpha = if (palette.isLight) 0.28f else 0.5f }) {
                                    logo(true)
                                }
                            }
                            logo(false)
                        }
                    }
                }

                Spacer(Modifier.weight(1f))

                Reveal(delayMs = 250) {
                    Text(
                        headline,
                        style = MaterialTheme.typography.displayMedium.copy(
                            fontSize = 44.sp,
                            lineHeight = 48.sp,
                            letterSpacing = (-1).sp,
                            fontWeight = FontWeight.Bold,
                        ),
                    )
                }
                Reveal(delayMs = 400) {
                    Text(
                        "Stream, download and sync the music you love, or keep it all on this device.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = ink(0.62f),
                        modifier = Modifier.padding(top = 14.dp, end = 24.dp),
                    )
                }

                Spacer(Modifier.height(32.dp))

                Reveal(delayMs = 550, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth()) {
                        Button(
                            onClick = { vm.signIn(context) },
                            enabled = !ui.busy,
                            modifier = Modifier.fillMaxWidth().height(56.dp),
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
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = vm::continueLocally,
                            enabled = !ui.busy,
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            shape = CircleShape,
                            border = BorderStroke(1.dp, ink(0.3f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = ink()),
                        ) { Text("Continue locally", fontWeight = FontWeight.Medium) }
                    }
                }

                Reveal(delayMs = 700, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Sign in to sync your favorites and playlists across devices. Local mode keeps your library on this device and backs it up daily.",
                        style = MaterialTheme.typography.bodySmall,
                        color = ink(0.5f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp, start = 8.dp, end = 8.dp),
                    )
                }

                ui.error?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    )
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** Fades in and rises a little after [delayMs]: the screen builds up from top to bottom instead of appearing at once. */
@Composable
private fun Reveal(delayMs: Int, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(delayMs.toLong())
        shown = true
    }
    AnimatedVisibility(
        visible = shown,
        modifier = modifier,
        enter = fadeIn(tween(550)) + slideInVertically(tween(550, easing = FastOutSlowInEasing)) { it / 6 },
    ) { content() }
}

/** Pops in: scales up from small with a springy overshoot while fading in. */
@Composable
private fun Pop(delayMs: Int, content: @Composable () -> Unit) {
    val scale = remember { Animatable(0.55f) }
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(delayMs.toLong())
        launch { alpha.animateTo(1f, tween(250)) }
        scale.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessLow))
    }
    Box(Modifier.graphicsLayer { scaleX = scale.value; scaleY = scale.value; this.alpha = alpha.value }) { content() }
}

/** A slow, small up-and-down drift. */
@Composable
private fun Modifier.floating(): Modifier {
    val offset by rememberInfiniteTransition(label = "welcome-float").animateFloat(
        initialValue = 0f,
        targetValue = -14f,
        animationSpec = infiniteRepeatable(tween(3200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "welcome-float-y",
    )
    return graphicsLayer { translationY = offset }
}