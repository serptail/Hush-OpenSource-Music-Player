package com.gamut.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager

/**
 * D-pad / remote counterpart of `clearAndSetSemantics` + `consumeTouches()` for the app's persistent layers
 * (Discover/Library tabs, HomeTabs + bottom bar, Settings and its sub-pages, the player, the NavHost).
 *
 * Those layers stay composed while parked off-screen or covered, and touch is blocked for them, but D-pad focus was
 * not: Down / Enter acted on something you cannot see. While [live] is false this makes the whole subtree
 * unfocusable (skipped by focus search) and, if focus is currently inside it, drops that focus so the next key press
 * starts in the layer that is really on screen.
 *
 * Do NOT put focusGroup() on a layer root: a nested focus target stops canFocus = false from reaching its children.
 */
@Composable
fun Modifier.focusLayer(live: Boolean): Modifier {
    val focusManager = LocalFocusManager.current
    var hasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(live) { if (!live && hasFocus) focusManager.clearFocus(force = true) }
    return this
        .onFocusChanged { hasFocus = it.hasFocus }
        // Only ever sets false, never true, so nested layers cannot re-enable what an outer one disabled.
        .focusProperties { if (!live) canFocus = false }
}

/**
 * False inside a layer that is parked, covered or otherwise not the one the user is looking at. Every focusable the
 * app owns (pressScale, focusRing, the cover, the search field) reads it and refuses D-pad focus when it is false.
 * This is checked on each item itself, so it does not matter how many focus groups or clickables sit in between.
 */
val LocalFocusLive = compositionLocalOf { true }

/** Marks everything inside as focusable only while [live] (and every enclosing layer is live too). */
@Composable
fun FocusLive(live: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalFocusLive provides (LocalFocusLive.current && live), content = content)
}