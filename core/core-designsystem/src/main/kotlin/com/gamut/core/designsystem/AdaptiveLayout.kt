package com.gamut.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Widest a screen's content gets before it is centered with empty gutters on both sides (tablets, unfolded foldables). */
object AdaptiveLayout {
    val ContentMaxWidth: Dp = 720.dp
    val PlayerMaxWidth: Dp = 560.dp

    /** Window width at which screens switch from the phone layout to the wide (two-pane / multi-column) layout. Same as the welcome screen. */
    val WideBreakpoint: Dp = 720.dp

    /** The bottom bar's tabs stay grouped in the middle on wide windows instead of spreading across a TV. */
    val BottomBarTabsMaxWidth: Dp = 480.dp

    /** Width of the content inside a bottom sheet. The sheet itself spans the window. Raise this (e.g. 2000.dp) to stretch sheet content too. */
    val SheetContentMaxWidth: Dp = 720.dp
}

/**
 * Extra horizontal padding to ADD to a screen's normal padding so its content never gets wider than [maxWidth].
 * It is 0.dp on every window narrower than [maxWidth] (all phones), so phone layouts are untouched.
 */
@Composable
fun adaptiveGutter(maxWidth: Dp = AdaptiveLayout.ContentMaxWidth): Dp {
    val width = LocalConfiguration.current.screenWidthDp.dp
    return ((width - maxWidth) / 2).coerceAtLeast(0.dp)
}

/** True on tablets, unfolded foldables, TVs and landscape windows wide enough for the multi-pane layouts. */
@Composable
fun isWideLayout(): Boolean =
    LocalConfiguration.current.screenWidthDp.dp >= AdaptiveLayout.WideBreakpoint