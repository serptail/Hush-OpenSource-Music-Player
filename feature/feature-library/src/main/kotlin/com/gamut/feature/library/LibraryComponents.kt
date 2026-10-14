package com.gamut.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Light
import com.adamglin.phosphoricons.light.MusicNote
import com.gamut.core.designsystem.LocalCoverPalette
import com.gamut.core.designsystem.ink

/** Corner of every small cover in the library screens (same as Discover's song rows). */
internal val RowArtShape = RoundedCornerShape(14.dp)

/**
 * A remote cover that fills its modifier. Uses plain AsyncImage (no subcomposition, which is costly in lists):
 * a flat, OPAQUE tint shows while loading, and a music-note icon appears when there is no art or loading fails.
 * The tint must be opaque: [softCover]'s drop shadow is drawn behind the cover, and a translucent fill would let
 * it show through as a dark box with a lighter square inside.
 * Coil sizes the decode to the layout bounds, and the fade-in is off so fast scrolling doesn't animate.
 */
@Composable
internal fun CoverImage(url: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val request = remember(url) {
        ImageRequest.Builder(context)
            .data(url.ifBlank { null })
            .crossfade(false)
            .build()
    }
    var failed by remember(url) { mutableStateOf(false) }
    val noArt = url.isBlank()
    val flat = flatCoverFill()
    Box(modifier.background(flat), contentAlignment = Alignment.Center) {
        if (!noArt) {
            AsyncImage(
                model = request,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                onError = { failed = true },
            )
        }
        if (noArt || failed) {
            Icon(PhosphorIcons.Light.MusicNote, null, tint = ink(0.45f), modifier = Modifier.size(22.dp))
        }
    }
}

/** ink(0.08f) flattened onto the cover background: the blank-cover look, but opaque so nothing shows through it. */
@Composable
internal fun flatCoverFill(): Color = ink(0.08f).compositeOver(LocalCoverPalette.current.base)

/** A cover with the soft drop shadow used on all small covers. */
internal fun Modifier.softCover(shape: androidx.compose.ui.graphics.Shape = RowArtShape): Modifier = this
    .shadow(4.dp, shape, clip = false, ambientColor = Color.Black, spotColor = Color.Black)
    .clip(shape)