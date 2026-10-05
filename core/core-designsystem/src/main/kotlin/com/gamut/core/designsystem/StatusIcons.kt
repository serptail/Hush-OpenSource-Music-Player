package com.gamut.core.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * The status icons, from your SVGs. Each SVG came as a black and a white copy of the same path, so every icon here is
 * ONE vector tinted at the call site: [ink] gives white on dark covers and near-black on light ones, which is exactly
 * that pair.
 *
 *  - [SuccessTick]: check in a filled circle
 *  - [ErrorTick]:   cross in a filled circle   (errors / failures)
 *  - [AlertTick]:   "!" in a filled circle      (warnings / info)
 */
val SuccessTick: ImageVector by lazy {
    ImageVector.Builder("SuccessTick", 24.dp, 24.dp, 24f, 24f).addPath(
        pathData = PathParser().parsePathString(
            "M12,2A10,10,0,1,0,22,12,10,10,0,0,0,12,2Zm5.676,8.237-6,5.5a1,1,0,0,1-1.383-.03l-3-3a1,1,0,1,1,1.414-1.414l2.323,2.323,5.294-4.853a1,1,0,1,1,1.352,1.474Z",
        ).toNodes(),
        fill = SolidColor(Color.Black),
    ).build()
}

// The two below are 512 viewBox SVGs whose artwork sits in a group translated by (42.666667, 42.666667) and filled
// even-odd (that is what cuts the symbol out of the circle). Tiny "e-14" exponents are written as 0.
val ErrorTick: ImageVector by lazy {
    ImageVector.Builder("ErrorTick", 24.dp, 24.dp, 512f, 512f)
        .addGroup(translationX = 42.666667f, translationY = 42.666667f)
        .addPath(
            pathData = PathParser().parsePathString(
                "M213.333333,0 C331.136,0 426.666667,95.5306667 426.666667,213.333333 C426.666667,331.136 331.136,426.666667 213.333333,426.666667 C95.5306667,426.666667 0,331.136 0,213.333333 C0,95.5306667 95.5306667,0 213.333333,0 Z M262.250667,134.250667 L213.333333,183.168 L164.416,134.250667 L134.250667,164.416 L183.168,213.333333 L134.250667,262.250667 L164.416,292.416 L213.333333,243.498667 L262.250667,292.416 L292.416,262.250667 L243.498667,213.333333 L292.416,164.416 L262.250667,134.250667 Z",
            ).toNodes(),
            fill = SolidColor(Color.Black),
            pathFillType = PathFillType.EvenOdd,
        )
        .clearGroup()
        .build()
}

val AlertTick: ImageVector by lazy {
    ImageVector.Builder("AlertTick", 24.dp, 24.dp, 512f, 512f)
        .addGroup(translationX = 42.666667f, translationY = 42.666667f)
        .addPath(
            pathData = PathParser().parsePathString(
                "M213.333333,0 C330.943502,0 426.666667,95.7231591 426.666667,213.333333 C426.666667,330.943502 330.943502,426.666667 213.333333,426.666667 C95.7231591,426.666667 0,330.943502 0,213.333333 C0,95.7231591 95.7231591,0 213.333333,0 Z M213.333333,272.042667 C198.095238,272.042667 186.666667,283.306667 186.666667,298.325333 C186.666667,314.026667 197.748918,325.290667 213.333333,325.290667 C228.571429,325.290667 240,314.026667 240,298.666667 C240,283.306667 228.571429,272.042667 213.333333,272.042667 Z M234.666667,85.3333333 L192,85.3333333 L192,234.666667 L234.666667,234.666667 L234.666667,85.3333333 Z",
            ).toNodes(),
            fill = SolidColor(Color.Black),
            pathFillType = PathFillType.EvenOdd,
        )
        .clearGroup()
        .build()
}
