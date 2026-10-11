package com.gamut.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Bold
import com.adamglin.phosphoricons.bold.MusicNotes

/**
 * The app's cover/portrait image for lists and cards. Plain [AsyncImage] on a flat tinted box: unlike
 * SubcomposeAsyncImage there is no subcomposition per image, which is what made fast scrolling through rows of
 * covers expensive. Coil decodes at the size the modifier lays out to. The tint shows while loading, and the app's one
 * [ArtPlaceholder] (bold music notes on a flat opaque tile) is drawn if loading failed or there is no url.
 * [fallback] is kept so existing callers still compile, but it is ignored: every list shows the same placeholder.
 * Must be inside [CoverTheme] (uses [ink]).
 */
@Composable
fun ArtImage(
    url: String?,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    @Suppress("UNUSED_PARAMETER") fallback: (@Composable () -> Unit)? = null,
) {
    val context = LocalContext.current
    val request = remember(url) {
        ImageRequest.Builder(context).data(url?.takeIf { it.isNotBlank() }).crossfade(false).build()
    }
    var failed by remember(url) { mutableStateOf(false) }
    // The tint must be OPAQUE. Callers put Modifier.shadow() before this box; a translucent fill let that shadow show
    // through as a darker/lighter square around the fallback icon (worst on the big album cards). Same look, no bleed.
    val tile = artTileColor()
    Box(modifier.background(tile), contentAlignment = Alignment.Center) {
        AsyncImage(
            model = request,
            contentDescription = contentDescription,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            onError = { failed = true },
        )
        if (failed) ArtPlaceholder(tile = tile)
    }
}
/**
 * The flat tile colour behind every cover: [ink] at 8% composited onto the app's base, so it is OPAQUE. Never use a
 * translucent fill under a `Modifier.shadow()`: the shadow shows through it as a hazy square.
 */
@Composable
fun artTileColor(): Color =
    ink(0.08f).compositeOver(if (ink().luminance() > 0.5f) Color(0xFF0E0E10) else Color(0xFFF4F4F6))

/**
 * The app's one cover placeholder: the bold Phosphor "music notes" glyph on a completely flat, opaque tile. No gradient,
 * no glow, no glass. The icon takes [iconFraction] of the tile's size, so the same call fits a 24 dp avatar and a
 * full-width album cover. Defaults use [ink] (inside CoverTheme); screens outside it (the player) pass [tile] and [tint].
 */
@Composable
fun ArtPlaceholder(
    modifier: Modifier = Modifier.fillMaxSize(),
    iconFraction: Float = 0.42f,
    tile: Color = artTileColor(),
    tint: Color = ink(0.45f),
    iconAlignment: Alignment = Alignment.Center,
) {
    Box(modifier.background(tile), contentAlignment = iconAlignment) {
        Icon(PhosphorIcons.Bold.MusicNotes, contentDescription = null, tint = tint, modifier = Modifier.fillMaxSize(iconFraction))
    }
}