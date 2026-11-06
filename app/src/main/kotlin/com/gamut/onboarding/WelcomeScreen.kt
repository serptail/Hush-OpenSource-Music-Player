package com.gamut.onboarding

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import com.gamut.core.designsystem.focusRing
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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

/** Width of the text + button column on wide windows, so it stays tidy on tablets, TVs and unfolded foldables. */
private val ActionsWidth = 440.dp

/** Widest the wide (two-pane) layout gets; beyond it the content is centered with empty gutters. */
private val WideContentMax = 1120.dp

/** Largest the logo gets on a wide window / on a phone. */
private val HeroMaxWide = 380.dp
private val HeroMaxCompact = 240.dp
private val HeroMin = 104.dp

/** Windows shorter than this (landscape phones, split screen) drop the secondary copy so the buttons stay in view. */
private val ShortWindow = 520.dp

/**
 * Welcome: a calm, single-colour screen. Theme background, the logo, one headline, one line of copy and two buttons.
 * No gradients, no chips, no accent colours: the only colour on screen is the Google button's own icon.
 *
 * Three layouts, all from the window size (so rotation, split screen, foldables and TVs just work):
 *  - phone (narrower than [WideBreakpoint]): everything centered, logo floating in the free space, buttons at the bottom.
 *  - wide and tall (tablet, TV, unfolded fold): logo on the left, copy + buttons on the right, centered with a max width.
 *  - wide and short (landscape phone): same two panes, but the logo shrinks to the available height and the secondary
 *    copy is dropped so nothing needs scrolling.
 * It scrolls as a last resort (huge font size, tiny window). With a remote, the Google button starts focused.
 */
@Composable
fun WelcomeScreen(
    vm: WelcomeViewModel,
    // The stacked brand logo, in the variant that reads on the current background (dark text on light, light text on dark).
    // It fills the box the layout gives it (Fit), so the screen decides how big it is.
    logo: @Composable () -> Unit = {
        val res = if (LocalCoverPalette.current.isLight) R.drawable.ic_gamut_stacked_black
        else R.drawable.ic_gamut_stacked_white
        Image(
            painterResource(res),
            contentDescription = "Gamut",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
        )
    },
) {
    val ui by vm.ui.collectAsState()
    val context = LocalContext.current
    val palette = LocalCoverPalette.current

    GamutBackdrop {
        // Flat theme background. The logo and the type do all the work.
        BoxWithConstraints(Modifier.fillMaxSize().background(palette.base)) {
            BoxWithConstraints(Modifier.fillMaxSize().systemBarsPadding()) {
                val wide = maxWidth >= WideBreakpoint
                val windowWidth: Dp = maxWidth
                val windowHeight: Dp = maxHeight
                val short = windowHeight < ShortWindow

                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (wide) {
                        val sidePadding = 40.dp
                        val gap = 56.dp
                        val paneWidth = (minOf(windowWidth, WideContentMax) - sidePadding * 2 - gap) / 2f
                        val heroSize = maxOf(minOf(HeroMaxWide, windowHeight - 96.dp, paneWidth), HeroMin)
                        Row(
                            Modifier
                                .widthIn(max = WideContentMax)
                                .fillMaxWidth()
                                .heightIn(min = windowHeight)
                                .padding(horizontal = sidePadding, vertical = if (short) 16.dp else 32.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(gap),
                        ) {
                            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { Hero(heroSize, logo) }
                            Column(Modifier.weight(1f).widthIn(max = ActionsWidth)) {
                                WelcomeCopy(
                                    headlineSize = when {
                                        short -> 32.sp
                                        windowWidth >= 1000.dp -> 54.sp
                                        else -> 46.sp
                                    },
                                    centered = false,
                                    compactCopy = short,
                                )
                                Spacer(Modifier.height(if (short) 20.dp else 32.dp))
                                WelcomeActions(vm, ui, context, centered = false, showFootnote = !short)
                            }
                        }
                    } else {
                        val heroSize = maxOf(minOf(windowHeight * 0.27f, windowWidth * 0.62f, HeroMaxCompact), HeroMin)
                        Column(
                            Modifier
                                .widthIn(max = 480.dp)
                                .fillMaxWidth()
                                .heightIn(min = windowHeight)
                                .padding(start = 28.dp, end = 28.dp, top = 24.dp, bottom = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Spacer(Modifier.height(1.dp))
                            Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) { Hero(heroSize, logo) }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                WelcomeCopy(
                                    headlineSize = if (windowWidth < 360.dp || short) 34.sp else 40.sp,
                                    centered = true,
                                    compactCopy = short,
                                )
                                Spacer(Modifier.height(if (short) 20.dp else 28.dp))
                                WelcomeActions(vm, ui, context, centered = true, showFootnote = true)
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

/** The logo in a square of [size] (the layout picks it from the window), easing in with a slight zoom. */
@Composable
private fun Hero(size: Dp, logo: @Composable () -> Unit) {
    val reveal = rememberReveal(0)
    Box(
        Modifier
            .size(size)
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
private fun WelcomeCopy(headlineSize: TextUnit, centered: Boolean, compactCopy: Boolean) {
    val headline = rememberReveal(150)
    val body = rememberReveal(260)
    val align = if (centered) TextAlign.Center else TextAlign.Start

    Column(horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start) {
        // One headline, one colour, one weight. (Was a faded first line + a bold second line.)
        Text(
            "Music without limits.",
            style = MaterialTheme.typography.displaySmall.copy(
                fontFamily = GamutDisplayFont,
                fontSize = headlineSize,
                lineHeight = headlineSize * 1.06f,
                letterSpacing = (headlineSize.value * -0.035f).sp,
                fontWeight = FontWeight.ExtraBold,
                textAlign = align,
            ),
            color = ink(),
            modifier = Modifier.slideUpReveal(headline),
        )
        if (!compactCopy) {
            Text(
                "Stream, download and sync across all your devices. Free and open source.",
                style = MaterialTheme.typography.bodyLarge,
                color = ink(0.6f),
                textAlign = align,
                modifier = Modifier.padding(top = 14.dp).slideUpReveal(body),
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Actions
// ---------------------------------------------------------------------------------------------------------------------

@Composable
private fun WelcomeActions(
    vm: WelcomeViewModel,
    ui: WelcomeUi,
    context: android.content.Context,
    centered: Boolean,
    showFootnote: Boolean,
) {
    val reveal = rememberReveal(470)
    val align = if (centered) TextAlign.Center else TextAlign.Start

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

    // TV remote: the primary button starts focused once the entrance has played, so OK works straight away.
    val remote = LocalInputModeManager.current.inputMode == InputMode.Keyboard
    val primaryFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (remote) {
            delay(900)
            runCatching { primaryFocus.requestFocus() }
        }
    }

    Column(Modifier.slideUpReveal(reveal)) {
        // Google's button stays white in both modes. No glow: the focus ring already handles remote / keyboard focus.
        Button(
            onClick = { vm.signIn(context) },
            enabled = !ui.busy,
            modifier = Modifier.fillMaxWidth().height(56.dp).focusRing(CircleShape).focusRequester(primaryFocus),
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
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = vm::continueLocally,
            enabled = !ui.busy,
            modifier = Modifier.fillMaxWidth().height(56.dp).focusRing(CircleShape),
            shape = CircleShape,
            border = BorderStroke(1.dp, ink(0.25f)),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = ink()),
        ) { Text("Continue locally", fontWeight = FontWeight.Medium) }

        if (showFootnote) {
            Text(
                "Local mode stays on this device and backs up daily.",
                style = MaterialTheme.typography.bodySmall,
                color = ink(0.45f),
                textAlign = align,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            )
        }

        // Keep the last message while the box collapses, so the text does not blank out mid-animation.
        var lastError by remember { mutableStateOf<String?>(null) }
        ui.error?.let { lastError = it }
        AnimatedVisibility(visible = ui.error != null, enter = GamutMotion.expandIn, exit = GamutMotion.collapseOut) {
            Text(
                lastError.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = align,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
        }
    }
}