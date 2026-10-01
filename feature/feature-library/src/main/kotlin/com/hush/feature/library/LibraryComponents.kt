package com.hush.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.Light
import com.adamglin.phosphoricons.fill.Heart
import com.adamglin.phosphoricons.light.DotsThreeVertical
import com.adamglin.phosphoricons.light.MusicNote
import com.hush.core.designsystem.ink
import com.hush.core.designsystem.pressScale

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

/** One song: cover, title, artist, optional filled heart (liked), and the three-dot menu. */
@Composable
internal fun SongRow(
    song: LibSong,
    modifier: Modifier = Modifier,
    liked: Boolean = false,
    onClick: () -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth().pressScale(onClick = onClick, scaleDown = 0.98f).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverImage(song.imageUrl, Modifier.size(54.dp).softCover())
        Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            Text(
                song.title,
                style = MaterialTheme.typography.titleMedium,
                color = ink(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                song.artist,
                style = MaterialTheme.typography.bodyMedium,
                color = ink(0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (liked) {
            Icon(PhosphorIcons.Fill.Heart, "Liked", tint = ink(), modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
        }
        Icon(PhosphorIcons.Light.DotsThreeVertical, null, tint = ink(0.5f), modifier = Modifier.size(22.dp))
    }
}
