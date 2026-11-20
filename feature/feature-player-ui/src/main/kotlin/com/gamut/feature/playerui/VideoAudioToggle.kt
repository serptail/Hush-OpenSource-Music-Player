package com.gamut.feature.playerui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Bold
import com.adamglin.phosphoricons.bold.MusicNotes
import com.adamglin.phosphoricons.bold.VideoCamera
import com.gamut.feature.playerui.components.bounceTap

private val TrackWidth = 66.dp
private val TrackHeight = 30.dp
private val TrackInset = 3.dp
private val ThumbWidth = (TrackWidth - TrackInset * 2) / 2
private val ThumbHeight = TrackHeight - TrackInset * 2
private val OnThumbInk = Color(0xFF111111)

/**
 * A small two-way switch between audio-only and the music video: a glass capsule with a thin white outline (the
 * same plate the quality badges use) and a white thumb that springs between a note and a camera.
 *
 * The tap area is larger than the capsule so it is easy to hit; the capsule itself stays small.
 */
@Composable
internal fun VideoAudioToggle(
    videoOn: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val thumbX by animateDpAsState(
        targetValue = if (videoOn) ThumbWidth else 0.dp,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow),
        label = "avThumb",
    )
    val audioInk by animateColorAsState(
        targetValue = if (videoOn) Color.White.copy(alpha = 0.85f) else OnThumbInk,
        animationSpec = tween(200),
        label = "avAudioInk",
    )
    val videoInk by animateColorAsState(
        targetValue = if (videoOn) OnThumbInk else Color.White.copy(alpha = 0.85f),
        animationSpec = tween(200),
        label = "avVideoInk",
    )

    Box(
        modifier
            .semantics(mergeDescendants = true) {
                role = Role.Switch
                contentDescription = if (videoOn) "Music video on. Switch to audio only" else "Audio only. Switch to music video"
            }.bounceTap(onClick = onToggle)
            .padding(horizontal = 8.dp, vertical = 5.dp),
    ) {
        Box(
            Modifier
                .size(width = TrackWidth, height = TrackHeight)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.38f))
                .border(1.dp, Color.White.copy(alpha = 0.4f), CircleShape)
                .padding(TrackInset),
        ) {
            Box(
                Modifier
                    .offset(x = thumbX)
                    .size(width = ThumbWidth, height = ThumbHeight)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.94f)),
            )
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    Icon(PhosphorIcons.Bold.MusicNotes, contentDescription = null, tint = audioInk, modifier = Modifier.size(15.dp))
                }
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    Icon(PhosphorIcons.Bold.VideoCamera, contentDescription = null, tint = videoInk, modifier = Modifier.size(15.dp))
                }
            }
        }
    }
}
