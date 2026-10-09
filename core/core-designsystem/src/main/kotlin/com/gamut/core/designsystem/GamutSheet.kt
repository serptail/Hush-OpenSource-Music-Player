package com.gamut.core.designsystem

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Shape

/**
 * The app's bottom sheet. Its container and text follow the current app palette (light / dark / preset / cover),
 * so a sheet is never stuck in dark mode. The sheet opens in its own window, so its content (and drag handle) is
 * wrapped in [CoverTheme] again: ink() and the Material colors inside it match the rest of the app.
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
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = shape,
        containerColor = palette.base,
        contentColor = palette.onBase,
        dragHandle = {
            CoverTheme(palette) {
                if (dragHandle != null) dragHandle() else BottomSheetDefaults.DragHandle(color = palette.onBase.copy(alpha = 0.3f))
            }
        },
    ) {
        CoverTheme(palette) { content() }
    }
}
