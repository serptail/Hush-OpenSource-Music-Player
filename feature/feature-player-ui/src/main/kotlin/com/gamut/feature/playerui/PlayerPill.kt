package com.gamut.feature.playerui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gamut.core.designsystem.LocalPlayerMotion
import com.gamut.core.designsystem.bounceClickable

/**
 * The grabber at the top of every player style. Dragging it steers the player
 * itself — the same spring as a tap or a back press, carrying the finger's own
 * speed on release — and a tap collapses it. Without a host to steer (previews)
 * it is just a tap target.
 */
@Composable
internal fun PlayerPill(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 84.dp,
    pillTop: Dp = 14.dp,
) {
    val motion = LocalPlayerMotion.current
    val dismiss by rememberUpdatedState(onDismiss)
    val dragState = rememberDraggableState { delta -> motion?.dragBy(delta) }
    Box(
        modifier
            .height(height)
            .semantics { contentDescription = "Collapse player" }
            .then(
                if (motion != null) {
                    Modifier.draggable(
                        orientation = Orientation.Vertical,
                        state = dragState,
                        onDragStopped = { velocity -> motion.release(velocity) },
                    )
                } else {
                    Modifier
                },
            ).bounceClickable(onClick = { dismiss() }),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            Modifier
                .padding(top = pillTop)
                .graphicsLayer {
                    val pulled = motion?.pulled() ?: 0f
                    scaleX = 1f + 0.5f * pulled.coerceAtMost(1.2f)
                    alpha = 0.75f + 0.25f * pulled.coerceAtMost(1f)
                }.size(width = 44.dp, height = 5.dp)
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}
