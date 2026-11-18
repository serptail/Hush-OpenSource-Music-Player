package com.gamut.settings

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * Icons Phosphor doesn't have. They size like the 22dp Phosphor icons and take `tint`
 * from [androidx.compose.material3.Icon]. Filled marks sit on their own square viewport;
 * line icons use Phosphor's 256 grid with a 16 stroke, round caps and joins, so they
 * read as the same family as the Regular set.
 */
object GamutIcons {
    /**
     * The Dolby double-D mark, same path as Auris's `ic_dolby_atmos.xml`.
     * It's Dolby's trademark, so use it only for a real Dolby Atmos setting
     * (E-AC-3 decoding), not for the simulated Spatial audio row.
     */
    val DolbyAtmos: ImageVector by lazy {
        ImageVector.Builder(
            name = "DolbyAtmos",
            defaultWidth = 22.dp,
            defaultHeight = 22.dp,
            viewportWidth = 108.95f,
            viewportHeight = 108.95f,
        ).apply {
            addPath(
                pathData = PathParser().parsePathString(
                    "M0,16.175 L0,92.774 L108.95,92.774 L108.95,16.175 L0,16.175 z " +
                        "M12.882,26.162 L25.264,26.162 C38.156,26.162 48.932,39.032 48.932,54.326 " +
                        "C48.935,70.058 38.156,82.787 25.264,82.787 L12.882,82.787 L12.882,26.162 z " +
                        "M83.688,26.162 L96.17,26.162 L96.17,82.787 L83.688,82.787 " +
                        "C70.79,82.787 60.017,70.058 60.017,54.326 C60.017,39.032 70.79,26.162 83.688,26.162 z",
                ).toNodes(),
                pathFillType = PathFillType.EvenOdd,
                fill = SolidColor(Color.Black),
            )
        }.build()
    }

    /** A rounded glass pane with two diagonal glints, for the frosted glass setting. */
    val FrostedGlass: ImageVector by lazy {
        ImageVector.Builder(
            name = "FrostedGlass",
            defaultWidth = 22.dp,
            defaultHeight = 22.dp,
            viewportWidth = 256f,
            viewportHeight = 256f,
        ).apply {
            addPath(
                pathData = PathParser().parsePathString(
                    "M72,40 L184,40 A32,32 0 0 1 216,72 L216,184 A32,32 0 0 1 184,216 " +
                        "L72,216 A32,32 0 0 1 40,184 L40,72 A32,32 0 0 1 72,40 Z " +
                        "M88,168 L168,88 M136,184 L184,136",
                ).toNodes(),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 16f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }
}