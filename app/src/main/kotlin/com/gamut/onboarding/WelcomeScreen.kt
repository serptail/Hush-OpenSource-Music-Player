package com.gamut.onboarding

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.gamut.R
import com.gamut.core.designsystem.AdaptiveLayout
import com.gamut.core.designsystem.CoverTheme
import com.gamut.core.designsystem.GamutBackdrop
import com.gamut.core.designsystem.GamutDisplayFont
import com.gamut.core.designsystem.GamutMotion
import com.gamut.core.designsystem.GamutTheme
import com.gamut.core.designsystem.GradientBlob
import com.gamut.core.designsystem.PulsingBorderFrame
import com.gamut.core.designsystem.LocalAppearance
import com.gamut.core.designsystem.LocalCoverPalette
import com.gamut.core.designsystem.TransparentStatusBar
import com.gamut.core.designsystem.appNeutralPalette
import com.gamut.core.designsystem.ink
import com.gamut.settings.AppearanceViewModel
import kotlinx.coroutines.delay

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
    // Welcome -> loading -> app used to be hard cuts. Now each swap is a crossfade.
    Crossfade(targetState = gate, animationSpec = tween(GamutMotion.CROSSFADE_MS, easing = GamutMotion.Standard), label = "onboarding-gate") { g ->
        when (g) {
            Gate.Ready -> app()
            Gate.Welcome -> AppearanceRoot { WelcomeScreen(vm) }
            // Same colour as the window background (the saved light/dark look), so there is no visible flash.
            Gate.Loading -> AppearanceRoot { Box(Modifier.fillMaxSize().background(LocalCoverPalette.current.base)) }
        }
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

/** Width at which the screen switches from the phone layout to logo-left / copy-and-buttons-right. */
private val WideBreakpoint = AdaptiveLayout.WideBreakpoint

/** Width of the text + button column on wide windows, so it stays tidy on tablets and unfolded foldables. */
private val ActionsWidth = 420.dp

/** Largest the logo gets, so it never dominates tablets. */
private val HeroMaxSize = 320.dp

/** How far the blob's hot spot sits above the screen top, as a fraction of its radius. Raise it to calm the buttons area. */
private const val BlobSink = 0.35f
private const val BlobIntensityDark = 0.9f
private const val BlobIntensityLight = 0.6f

/**
 * Welcome: theme background with a gradient blob pouring down from the top. The logo, headline, copy and buttons
 * stagger in from below.
 * Wide windows (tablet, unfolded fold, landscape): artwork on the left, copy and buttons on the right.
 * Scrolls when the window is short.
 */
@Composable
fun WelcomeScreen(
    vm: WelcomeViewModel,
    // The stacked brand logo, in the variant that reads on the current background (dark text on light, light text on dark).
    logo: @Composable () -> Unit = {
        val res = if (LocalCoverPalette.current.isLight) R.drawable.ic_gamut_stacked_black
        else R.drawable.ic_gamut_stacked_white
        Image(
            painterResource(res),
            contentDescription = "Gamut",
            modifier = Modifier.height(130.dp),
            contentScale = ContentScale.Fit,
        )
    },
) {
    val ui by vm.ui.collectAsState()
    val context = LocalContext.current
    val palette = LocalCoverPalette.current

    GamutBackdrop {
        // Outer box: base colour + the gradient blob pouring down from the top edge (it bleeds under the status bar).
        BoxWithConstraints(Modifier.fillMaxSize().background(palette.base)) {
            val blobReveal = rememberReveal(120)
            // Spans the full width (a little past it so the edges stay lit). Wide screens get an ellipse.
            val blobWidth = maxWidth * 1.25f
            val blobHeight = if (maxWidth >= WideBreakpoint) minOf(maxHeight * 0.95f, maxWidth * 0.9f) else maxWidth * 1.35f
            GradientBlob(
                Modifier
                    .align(Alignment.TopCenter)
                    .requiredWidth(blobWidth)
                    .requiredHeight(blobHeight)
                    // Push the hot spot just above the screen edge so the light seems to pour in from it.
                    .offset(y = -(blobHeight / 2f * BlobSink))
                    .graphicsLayer {
                        alpha = blobReveal.value
                        translationY = -(1f - blobReveal.value) * 48.dp.toPx()
                    },
                intensity = if (palette.isLight) BlobIntensityLight else BlobIntensityDark,
            )
            BoxWithConstraints(Modifier.fillMaxSize().systemBarsPadding()) {
                val wide = maxWidth >= WideBreakpoint
                val windowHeight: Dp = maxHeight

                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (wide) {
                        Row(
                            Modifier
                                .widthIn(max = AdaptiveLayout.ContentMaxWidth + 240.dp)
                                .fillMaxWidth()
                                .heightIn(min = windowHeight)
                                .padding(horizontal = 40.dp, vertical = 32.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(48.dp),
                        ) {
                            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { Hero(logo) }
                            Column(Modifier.weight(1f).widthIn(max = ActionsWidth)) {
                                WelcomeCopy()
                                Spacer(Modifier.height(32.dp))
                                WelcomeActions(vm, ui, context)
                            }
                        }
                    } else {
                        Column(
                            Modifier
                                .widthIn(max = 480.dp)
                                .fillMaxWidth()
                                .heightIn(min = windowHeight)
                                .padding(start = 28.dp, end = 28.dp, top = 24.dp, bottom = 24.dp),
                            verticalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Spacer(Modifier.height(1.dp))
                            Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) { Hero(logo) }
                            Column {
                                WelcomeCopy()
                                Spacer(Modifier.height(32.dp))
                                WelcomeActions(vm, ui, context)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Entrance animation helpers
// ---------------------------------------------------------------------------------------------------------------------

/** 0 -> 1 after [delayMs]. Read it inside a graphicsLayer block so the animation never recomposes anything. */
@Composable
private fun rememberReveal(delayMs: Int): Animatable<Float, *> {
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(delayMs.toLong())
        a.animateTo(1f, tween(550, easing = GamutMotion.Standard))
    }
    return a
}

/** Fade in while sliding up from below. */
private fun Modifier.slideUpReveal(p: Animatable<Float, *>): Modifier = graphicsLayer {
    alpha = p.value
    translationY = (1f - p.value) * 28.dp.toPx()
}

// ---------------------------------------------------------------------------------------------------------------------
// Hero: the logo
// ---------------------------------------------------------------------------------------------------------------------

@Composable
private fun Hero(logo: @Composable () -> Unit) {
    val reveal = rememberReveal(0)
    Box(
        Modifier
            .widthIn(max = HeroMaxSize)
            .fillMaxWidth()
            .aspectRatio(1f)
            .graphicsLayer {
                alpha = reveal.value
                val s = 0.88f + 0.12f * reveal.value
                scaleX = s
                scaleY = s
            },
        contentAlignment = Alignment.Center,
    ) { logo() }
}

// ---------------------------------------------------------------------------------------------------------------------
// Copy
// ---------------------------------------------------------------------------------------------------------------------

@Composable
private fun WelcomeCopy() {
    val headline = rememberReveal(150)
    val body = rememberReveal(260)

    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = ink(0.55f))) { append("Listen freely,\n") }
            append("Music without limits.")
        },
        style = MaterialTheme.typography.displaySmall.copy(
            fontFamily = GamutDisplayFont,
            fontSize = 42.sp,
            lineHeight = 44.sp,
            letterSpacing = (-1.5).sp,
            fontWeight = FontWeight.ExtraBold,
        ),
        color = ink(),
        modifier = Modifier.slideUpReveal(headline),
    )
    Text(
        "Stream, download, and sync your favorite music across all your devices \u2014 free forever and open source.",
        style = MaterialTheme.typography.bodyLarge,
        color = ink(0.6f),
        modifier = Modifier.padding(top = 14.dp).slideUpReveal(body),
    )
}

// ---------------------------------------------------------------------------------------------------------------------
// Actions
// ---------------------------------------------------------------------------------------------------------------------

@Composable
private fun WelcomeActions(vm: WelcomeViewModel, ui: WelcomeUi, context: android.content.Context) {
    val reveal = rememberReveal(470)

    // Only show the spinner if the work lasts a moment. A sign-in that returns almost instantly used to flash it.
    var showSpinner by remember { mutableStateOf(false) }
    LaunchedEffect(ui.busy) {
        if (ui.busy) {
            delay(180)
            showSpinner = true
        } else {
            showSpinner = false
        }
    }

    Column(Modifier.slideUpReveal(reveal)) {
        // Google's button stays white in both modes; the pulsing Google-colour border replaces the old hairline.
        PulsingBorderFrame(
            modifier = Modifier.fillMaxWidth().height(56.dp),
            cornerRadius = 28.dp,
            active = !ui.busy,
        ) {
            Button(
                onClick = { vm.signIn(context) },
                enabled = !ui.busy,
                modifier = Modifier.fillMaxSize(),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = Color(0xFF1F1F1F),
                    // Stay fully white while busy: the old 60% fade read as a flash right as the spinner appeared.
                    disabledContainerColor = Color.White,
                    disabledContentColor = Color(0xFF1F1F1F),
                ),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Fixed 20dp slot: the spinner replaces the Google icon in place, so nothing overlaps or shifts.
                    Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                        Crossfade(targetState = showSpinner, animationSpec = tween(GamutMotion.CONTENT_MS), label = "google-busy") { spinning ->
                            if (spinning) {
                                CircularProgressIndicator(Modifier.fillMaxSize(), strokeWidth = 2.dp, color = Color(0xFF1F1F1F))
                            } else {
                                // Image, not Icon: Icon would tint the four Google colours into one.
                                Image(painterResource(R.drawable.ic_google), contentDescription = null, modifier = Modifier.fillMaxSize())
                            }
                        }
                    }
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

        Text(
            "Sign in to sync across devices. Local mode stays on this device and backs up daily.",
            style = MaterialTheme.typography.bodySmall,
            color = ink(0.5f),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        )

        // Keep the last message while the box collapses, so the text does not blank out mid-animation.
        var lastError by remember { mutableStateOf<String?>(null) }
        ui.error?.let { lastError = it }
        AnimatedVisibility(visible = ui.error != null, enter = GamutMotion.expandIn, exit = GamutMotion.collapseOut) {
            Text(
                lastError.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
        }
    }
}