package com.gamut.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Light
import com.adamglin.phosphoricons.light.MusicNote
import com.gamut.core.designsystem.ink

/** Corner of every small cover in the library screens (same as Discover's song rows). */
internal val RowArtShape = RoundedCornerShape(14.dp)

/** A remote cover that fills its modifier, with a quiet placeholder while loading or on error. */
@Composable
internal fun CoverImage(url: String, modifier: Modifier = Modifier) {
    SubcomposeAsyncImage(
        model = url,
        contentDescription = null,
        modifier = modifier,
        contentScale = ContentScale.Crop,
        loading = { CoverPlaceholder() },
        error = { CoverPlaceholder() },
    )
}

@Composable
private fun CoverPlaceholder() {
    Box(Modifier.fillMaxSize().background(ink(0.08f)), contentAlignment = Alignment.Center) {
        Icon(PhosphorIcons.Light.MusicNote, null, tint = ink(0.45f), modifier = Modifier.size(22.dp))
    }
}

/** A cover with the soft drop shadow used on all small covers. */
internal fun Modifier.softCover(shape: androidx.compose.ui.graphics.Shape = RowArtShape): Modifier = this
    .shadow(4.dp, shape, clip = false, ambientColor = Color.Black, spotColor = Color.Black)
    .clip(shape)