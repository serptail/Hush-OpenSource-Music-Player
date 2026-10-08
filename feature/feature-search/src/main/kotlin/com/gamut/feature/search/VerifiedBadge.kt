package com.gamut.feature.search

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.fill.SealCheck

/** An artist with at least this many followers (fans) gets the badge. Tune to taste. */
internal const val VERIFIED_MIN_FOLLOWERS = 500_000L

private val VerifiedBlue = Color(0xFF3B9EFF)

internal fun isVerifiedArtist(followers: Long?): Boolean = followers != null && followers >= VERIFIED_MIN_FOLLOWERS

/** Phosphor "seal-check" (fill): the scalloped seal with the tick cut out of it. */
@Composable
internal fun VerifiedBadge(modifier: Modifier = Modifier, size: Dp = 20.dp) {
    Icon(
        PhosphorIcons.Fill.SealCheck,
        contentDescription = "Verified artist",
        tint = VerifiedBlue,
        modifier = modifier.size(size),
    )
}
