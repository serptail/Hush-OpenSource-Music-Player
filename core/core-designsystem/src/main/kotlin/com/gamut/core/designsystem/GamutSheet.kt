package com.gamut.core.designsystem

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The app's bottom sheet. Its container and text follow the current app palette (light / dark / preset / cover),
 * so a sheet is never stuck in dark mode. The sheet opens in its own window, so its content (and drag handle) is
 * wrapped in [CoverTheme] again: ink() and the Material colors inside it match the rest of the app.
 *
 * The sheet spans the whole window (Material's default stops it at 640.dp, which left a tablet's sheet narrower than
 * the app). On a wide window its CONTENT is centered at [AdaptiveLayout.SheetContentMaxWidth]; below that width
 * (phones) nothing is wrapped, so the content is laid out exactly as before.
 *
 * Use it instead of ModalBottomSheet, and use ink(...) instead of Color.White inside.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GamutSheet(
    onDismiss: () -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    shape: Shape = BottomSheetDefaults.ExpandedShape,
    dragHandle: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val palette = LocalCoverPalette.current
    val centerContent = adaptiveGutter(AdaptiveLayout.SheetContentMaxWidth) > 0.dp
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = shape,
        containerColor = palette.base,
        contentColor = palette.onBase,
        sheetMaxWidth = Dp.Unspecified,
        dragHandle = {
            CoverTheme(palette) {
                if (dragHandle != null) dragHandle() else BottomSheetDefaults.DragHandle(color = palette.onBase.copy(alpha = 0.3f))
            }
        },
    ) {
        CoverTheme(palette) {
            // The sheet is its own window and the only thing that takes input while it is open. It inherits composition locals
            // from wherever it was declared, which can be a layer that is covered (not live), so reset the focus gate here:
            // otherwise its rows would refuse D-pad focus and never show a highlight.
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