package com.gamut.core.designsystem

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset

/**
 * The one motion spec for the whole app, so Discover <-> Library, detail screens, the player, settings panels,
 * cards that expand and content that swaps all share the same timing and easing.
 *
 *  - Tabs:      [TAB_MS] horizontal slide (HomeTabs).
 *  - Push/pop:  fade-through + shared axis X. The old screen fades out fast ([EXIT_MS]) and is then REMOVED
 *               (its slide lasts exactly as long as its fade, so it does not linger, invisible, in composition).
 *               The new screen starts fading in only after that ([ENTER_DELAY_MS] == [EXIT_MS]), so two transparent
 *               screens never overlap and the new screen's heavy first composition lands in the invisible window.
 *  - Player:    vertical sheet-like slide, emphasized easing.
 *  - In place:  [expandIn] / [collapseOut] / [softResize] / [CONTENT_MS] crossfades / [itemPlacement] for list rows.
 *
 * Rules of thumb that keep it smooth:
 *  - Never stack fades. A screen gets ONE fade (the NavHost's); inner content must not fade again.
 *  - Never fade a list item as it first appears (every item becomes an alpha layer). Only animate placement.
 *  - Keep exits short: AnimatedContent keeps the old screen alive until its LONGEST animation finishes.
 */
object GamutMotion {

    // ---- durations (ms) ----
    const val TAB_MS = 180
    const val EXIT_MS = 75
    const val ENTER_MS = 160

    /** Fade-through: the new screen waits for the old one to be gone. Was 0, which made the two screens overlap. */
    const val ENTER_DELAY_MS = EXIT_MS

    /** Slide of the INCOMING screen: spans the delay plus its fade, so it is still moving while it becomes visible. */
    const val SLIDE_MS = ENTER_DELAY_MS + ENTER_MS

    /** Slide of the OUTGOING screen: no longer than its fade, so the old screen leaves composition as early as possible. */
    const val POP_SLIDE_MS = EXIT_MS

    const val PLAYER_IN_MS = 260
    const val PLAYER_OUT_MS = 200

    /** The player is a persistent layer (see PlayerLayer in MainActivity): only a translationY runs, these are its timings. */
    const val PLAYER_LAYER_IN_MS = 240
    const val PLAYER_LAYER_OUT_MS = 180
    const val EXPAND_MS = 180
    const val CONTENT_MS = 150
    const val CROSSFADE_MS = 220

    /** A screen drifts 1/SLIDE_FRACTION of its width while it fades. */
    const val SLIDE_FRACTION = 10

    /** The home tabs layer drifts left by this fraction of its width while a detail screen covers it. */
    const val HOME_SHIFT = 0.08f

    // ---- easings ----
    val Standard = FastOutSlowInEasing
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    /**
     * Closing the player: it must start moving on the very first frame. EmphasizedAccelerate sits almost still for the first
     * ~40% of its duration, which on a 200 ms drop reads as lag followed by a "curtain" falling. This one leaves at once.
     */
    val PlayerClose = FastOutLinearInEasing

    // ---- NavHost: fade-through + shared axis X ----
    val pushEnter: EnterTransition =
        fadeIn(tween(ENTER_MS, delayMillis = ENTER_DELAY_MS, easing = Standard)) +
            slideInHorizontally(tween(SLIDE_MS, easing = Standard)) { it / SLIDE_FRACTION }
    val pushExit: ExitTransition =
        fadeOut(tween(EXIT_MS, easing = FastOutLinearInEasing)) +
            slideOutHorizontally(tween(POP_SLIDE_MS, easing = FastOutLinearInEasing)) { -it / SLIDE_FRACTION }
    val popEnter: EnterTransition =
        fadeIn(tween(ENTER_MS, delayMillis = ENTER_DELAY_MS, easing = Standard)) +
            slideInHorizontally(tween(SLIDE_MS, easing = Standard)) { -it / SLIDE_FRACTION }
    val popExit: ExitTransition =
        fadeOut(tween(EXIT_MS, easing = FastOutLinearInEasing)) +
            slideOutHorizontally(tween(POP_SLIDE_MS, easing = FastOutLinearInEasing)) { it / SLIDE_FRACTION }

    // ---- detail screens: ONE STRIP, exactly like the Discover <-> Library tabs ----
    // The old screen leaves by a full width while the new one enters by a full width, same duration and easing, so they
    // sit edge to edge and never overlap. There is NO alpha anywhere (no offscreen layers): the whole transition is a
    // translation, applied in the draw phase. HomeTabs (below the NavHost) follows the same curve, see GamutApp.
    const val NAV_MS = TAB_MS
    val navPushEnter: EnterTransition = slideInHorizontally(tween(NAV_MS, easing = Standard)) { it }
    val navPushExit: ExitTransition = slideOutHorizontally(tween(NAV_MS, easing = Standard)) { -it }
    val navPopEnter: EnterTransition = slideInHorizontally(tween(NAV_MS, easing = Standard)) { -it }
    val navPopExit: ExitTransition = slideOutHorizontally(tween(NAV_MS, easing = Standard)) { it }

    // ---- player (opaque, vertical) ----
    val playerEnter: EnterTransition =
        slideInVertically(tween(PLAYER_IN_MS, easing = EmphasizedDecelerate)) { it }
    val playerExit: ExitTransition =
        slideOutVertically(tween(PLAYER_OUT_MS, easing = EmphasizedAccelerate)) { it }

    // ---- in place: rows / options that appear inside a card ----
    val expandIn: EnterTransition =
        fadeIn(tween(EXPAND_MS, delayMillis = 20, easing = Standard)) +
            expandVertically(tween(EXPAND_MS, easing = Standard), expandFrom = Alignment.Top)
    val collapseOut: ExitTransition =
        fadeOut(tween(120)) +
            shrinkVertically(tween(EXPAND_MS, easing = Standard), shrinkTowards = Alignment.Top)

    // ---- lists ----
    /**
     * For `Modifier.animateItem(fadeInSpec = null, placementSpec = GamutMotion.itemPlacement, fadeOutSpec = null)`.
     * Rows only glide to their new position; they never fade, so a list that is being composed for the first time
     * does not create an alpha layer per row.
     */
    val itemPlacement: FiniteAnimationSpec<IntOffset> = tween(EXPAND_MS, easing = Standard)
}

/** Animates any change of this element's height (text appearing under a control, a hint changing, ...). */
fun Modifier.softResize(): Modifier = animateContentSize(tween(GamutMotion.EXPAND_MS, easing = GamutMotion.Standard))

/**
 * False while a NavHost screen is still sliding in, true once the slide has finished (provided by `Settled` in
 * MainActivity). Heavy screens show their skeleton while it is false and compose their real content only when it turns
 * true, so the first (expensive) composition never lands in the middle of the motion. Defaults to true, so a screen
 * used outside the NavHost behaves exactly as before.
 */
val LocalNavSettled = compositionLocalOf { true }