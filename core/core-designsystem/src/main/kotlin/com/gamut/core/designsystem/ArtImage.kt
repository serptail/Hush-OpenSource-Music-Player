package com.gamut.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest

/**
 * The app's cover/portrait image for lists and cards. Plain [AsyncImage] on a flat tinted box: unlike
 * SubcomposeAsyncImage there is no subcomposition per image, which is what made fast scrolling through rows of
 * covers expensive. Coil decodes at the size the modifier lays out to. The tint shows while loading, and
 * [fallback] (e.g. a music-note icon) is drawn on top only if loading failed. Must be inside [CoverTheme] (uses [ink]).
 */
@Composable
fun ArtImage(
    url: String?,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    fallback: (@Composable () -> Unit)? = null,
) {
    val context = LocalContext.current
    val request = remember(url) {
        ImageRequest.Builder(context).data(url?.takeIf { it.isNotBlank() }).crossfade(false).build()
    }
    var failed by remember(url) { mutableStateOf(false) }
    Box(modifier.background(ink(0.08f)), contentAlignment = Alignment.Center) {
        AsyncImage(
            model = request,
            contentDescription = contentDescription,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            onError = { failed = true },
        )
        if (failed) fallback?.invoke()
    }
}
