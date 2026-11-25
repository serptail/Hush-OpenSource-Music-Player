package com.gamut.core.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Breathing room kept between the top of an expanded sheet and the status bar / camera cutout. */
private val SheetTopGap = 8.dp

/**
 * What the sheet measured about its own window, so the content can be capped without guessing: the full window
 * height (the drag handle is the first thing the sheet lays out, and it is offered the whole window) and how much
 * of it the handle takes.
 */
private class SheetBounds {
    var windowHeight by mutableIntStateOf(0)
    var handleHeight by mutableIntStateOf(0)
}

private fun Modifier.probeSheetWindow(bounds: SheetBounds): Modifier =
    layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        if (constraints.hasBoundedHeight) bounds.windowHeight = constraints.maxHeight
        bounds.handleHeight = placeable.height
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }

/** Limits the content so handle + content never grow taller than the window minus [reservedTopPx]. */
private fun Modifier.capSheetHeight(
    bounds: SheetBounds,
    reservedTopPx: Int,
): Modifier =
    layout { measurable, constraints ->
        val window = if (bounds.windowHeight > 0) bounds.windowHeight else constraints.maxHeight
        val room = (window - bounds.handleHeight - reservedTopPx).coerceAtLeast(0)
        val maxHeight = if (constraints.hasBoundedHeight) minOf(constraints.maxHeight, room) else room
        val placeable = measurable.measure(constraints.copy(maxHeight = maxOf(maxHeight, constraints.minHeight)))
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }

/**
 * A [ModalBottomSheet] that can never grow above the status bar / display cutout (the camera lens area), however
 * much content it holds. Content taller than that is cut off at the limit, so it has to scroll: lists scroll on
 * their own (a `LazyColumn` or an inner `verticalScroll` simply gets the bounded height), and a plain column sets
 * [scrollable] so the whole content scrolls. Leave [scrollable] off when the content already scrolls, because a
 * vertical scroller inside another one is a crash.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CappedModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    sheetMaxWidth: Dp = BottomSheetDefaults.SheetMaxWidth,
    shape: Shape = BottomSheetDefaults.ExpandedShape,
    containerColor: Color = BottomSheetDefaults.ContainerColor,
    contentColor: Color = contentColorFor(containerColor),
    scrollable: Boolean = false,
    dragHandle: (@Composable () -> Unit)? = { BottomSheetDefaults.DragHandle() },
    content: @Composable ColumnScope.() -> Unit,
) {
    // Read from the host window, which is known to be edge-to-edge, rather than from inside the sheet's own window.
    val density = LocalDensity.current
    val reservedTopPx = WindowInsets.safeDrawing.getTop(density) + with(density) { SheetTopGap.roundToPx() }
    val bounds = remember { SheetBounds() }
    val handleSlot: (@Composable () -> Unit)? =
        if (dragHandle == null) {
            null
        } else {
            { Box(Modifier.probeSheetWindow(bounds)) { dragHandle() } }
        }
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
        sheetMaxWidth = sheetMaxWidth,
        shape = shape,
        containerColor = containerColor,
        contentColor = contentColor,
        dragHandle = handleSlot,
    ) {
        val capped = Modifier.fillMaxWidth().capSheetHeight(bounds, reservedTopPx)
        if (scrollable) {
            Column(capped.verticalScroll(rememberScrollState()), content = content)
        } else {
            Column(capped, content = content)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GamutSheet(
    onDismiss: () -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    shape: Shape = BottomSheetDefaults.ExpandedShape,
    dragHandle: (@Composable () -> Unit)? = null,
    scrollable: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val palette = LocalCoverPalette.current
    val centerContent = adaptiveGutter(AdaptiveLayout.SheetContentMaxWidth) > 0.dp
    CappedModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = shape,
        containerColor = palette.base,
        contentColor = palette.onBase,
        sheetMaxWidth = Dp.Unspecified,
        scrollable = scrollable,
        dragHandle = {
            CoverTheme(palette) {
                if (dragHandle != null) dragHandle() else BottomSheetDefaults.DragHandle(color = palette.onBase.copy(alpha = 0.3f))
            }
        },
    ) {
        CoverTheme(palette) {
            CompositionLocalProvider(LocalFocusLive provides true) {
                if (centerContent) {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Column(Modifier.widthIn(max = AdaptiveLayout.SheetContentMaxWidth).fillMaxWidth()) { content() }
                    }
                } else {
                    content()
                }
            }
        }
    }
}
