package com.gamut.core.designsystem

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Height of the tab row (without the system navigation inset or any docked strip). Screens use it to end their list above the tabs. */
val GamutBottomBarTabsHeight = 54.dp

/** One destination in [GamutBottomBar]. A null [onClick] means "you are already here". */
data class BottomBarItem(
    val label: String,
    val icon: ImageVector,
    val selected: Boolean = false,
    val onClick: (() -> Unit)? = null,
)

/**
 * The app's bottom navigation. Not a floating element: it is a flat, full-width strip that grows out of the
 * bottom edge of the window (it also fills the system navigation area underneath). A
 * single hairline on top separate it from the page (transparent, no glass; only the docked [MiniPlayerStrip] is frosted); the current destination is marked by a short bar on
 * that hairline and full-strength ink. No pills, no shadows. Must be inside [CoverTheme] (uses [ink]).
 */
@Composable
fun GamutBottomBar(
    items: List<BottomBarItem>,
    modifier: Modifier = Modifier,
    /** Optional strip docked on top of the tabs (see [MiniPlayerStrip]); it shares the bar's tint and hairline. */
    nowPlaying: @Composable () -> Unit = {},
) {
    val hairline = ink(0.08f)
    // The tab strip is fully transparent (no fill, no glass): the flowing background shows through. The only
    // glass is the mini player strip docked above it.
    Column(modifier.fillMaxWidth()) {
        nowPlaying()
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
        ) {
            // The hairline spans the whole window; the tabs themselves stay grouped in the middle on wide windows.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(GamutBottomBarTabsHeight)
                    .drawBehind {
                        val y = 0.5.dp.toPx()
                        drawLine(hairline, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
                    },
                contentAlignment = Alignment.Center,
            ) {
                Row(Modifier.widthIn(max = AdaptiveLayout.BottomBarTabsMaxWidth).fillMaxWidth().fillMaxHeight()) {
                    items.forEach { item -> BottomBarTab(item, Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun BottomBarTab(item: BottomBarItem, modifier: Modifier) {
    val tint by animateColorAsState(if (item.selected) ink() else ink(0.45f), tween(200), label = "bar-tint")
    val marker by animateFloatAsState(if (item.selected) 1f else 0f, tween(200), label = "bar-marker")
    Box(
        modifier
            .fillMaxHeight()
            .then(if (item.onClick != null) Modifier.pressScale(onClick = item.onClick, scaleDown = 0.92f) else Modifier),
    ) {
        // Short marker sitting on the top hairline.
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .size(width = 22.dp, height = 2.dp)
                .background(ink(marker), RoundedCornerShape(bottomStart = 2.dp, bottomEnd = 2.dp)),
        )
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(item.icon, null, tint = tint, modifier = Modifier.size(21.dp))
            Spacer(Modifier.height(2.dp))
            Text(item.label, color = tint, style = MaterialTheme.typography.labelMedium, fontSize = 10.5.sp)
        }
    }
}