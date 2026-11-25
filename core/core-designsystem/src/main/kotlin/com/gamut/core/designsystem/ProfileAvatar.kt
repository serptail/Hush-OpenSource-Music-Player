package com.gamut.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

@Composable
fun ProfileAvatar(
    name: String?,
    seed: String?,
    size: Dp,
    guestIcon: ImageVector,
    modifier: Modifier = Modifier,
    image: Any? = null,
    shape: Shape = CircleShape,
    softGuest: Boolean = false,
) {
    if (image != null) {
        AsyncImage(
            model = image,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.size(size).clip(shape).background(ink(0.1f)),
        )
        return
    }
    if (seed == null && softGuest) {
        // Just the outline icon, like the Shazam and heart buttons beside it: no circle behind it.
        Box(modifier.size(size), contentAlignment = Alignment.Center) {
            Icon(guestIcon, null, tint = ink(0.9f), modifier = Modifier.size(26.dp))
        }
        return
    }
    if (seed == null) {
        Box(modifier.size(size).clip(shape).background(ink(0.1f)), contentAlignment = Alignment.Center) {
            Icon(guestIcon, null, tint = ink(0.85f), modifier = Modifier.size(size * 0.55f))
        }
        return
    }
    val color = remember(seed) { Color.hsv(Math.floorMod(seed.hashCode(), 360).toFloat(), 0.45f, 0.80f) }
    val initial =
        name
            ?.trim()
            ?.firstOrNull { it.isLetterOrDigit() }
            ?.uppercaseChar()
            ?.toString() ?: "?"
    Box(modifier.size(size).clip(shape).background(color), contentAlignment = Alignment.Center) {
        Text(
            initial,
            style = MaterialTheme.typography.titleMedium.copy(fontSize = (size.value * 0.42f).sp),
            color = Color(0xFF111111),
        )
    }
}
