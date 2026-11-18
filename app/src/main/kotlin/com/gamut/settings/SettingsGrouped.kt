package com.gamut.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.CaretRight
import com.gamut.core.designsystem.ink
import com.gamut.core.designsystem.pressScale
import java.util.Locale

/*
 * Grouped settings: a single soft block holding every row, with small uppercase
 * headers and inset dividers between rows. Adapted from the layout in Auris, but
 * built from Hush's own parts (ink() tints, pressScale) so it follows the cover
 * palette and light/dark mode. No outline, and no chip behind the icons.
 */

private val BlockShape = RoundedCornerShape(22.dp)
internal val GroupedIconSize = 22.dp
internal val GroupedRowInset = 16.dp
internal val GroupedIconGap = 14.dp

/** Where a row's text starts. Dividers are inset to match, so they never cut under the icon. */
private val TextInset = GroupedRowInset + GroupedIconSize + GroupedIconGap

@Composable
fun SettingsBlock(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(top = 20.dp)
            .clip(BlockShape)
            .background(ink(0.06f)),
        content = content,
    )
}

@Composable
fun GroupedHeader(text: String) {
    Text(
        text.uppercase(Locale.ROOT),
        style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.8.sp),
        color = ink(0.5f),
        modifier = Modifier.fillMaxWidth().padding(start = GroupedRowInset, end = GroupedRowInset, top = 20.dp, bottom = 6.dp),
    )
}

/** Muted plain-language note at the foot of a group, for the ones whose effect isn't obvious from the rows. */
@Composable
fun GroupedFooter(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = ink(0.45f),
        modifier = Modifier.fillMaxWidth().padding(start = GroupedRowInset, end = GroupedRowInset, top = 8.dp, bottom = 2.dp),
    )
}

@Composable
fun GroupedDivider(start: Dp = TextInset) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = start)
            .height(0.5.dp)
            .background(ink(0.08f)),
    )
}

/** Rounds only the outer corners of a row inside a lazy list, so the rows read as one block. */
fun groupedItemShape(
    index: Int,
    count: Int,
): Shape {
    val r = 22.dp
    val top = if (index == 0) r else 0.dp
    val bottom = if (index == count - 1) r else 0.dp
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

@Composable
fun <T> GroupedSegmented(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(ink(0.06f))
            .padding(3.dp),
    ) {
        options.forEach { (value, label) ->
            val isSelected = value == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(11.dp))
                    .background(if (isSelected) ink(0.12f) else Color.Transparent)
                    .pressScale(onClick = { onSelect(value) }, scaleDown = 0.97f)
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = ink(if (isSelected) 1f else 0.55f),
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
fun GroupedRow(
    title: String,
    icon: ImageVector?,
    modifier: Modifier = Modifier,
    painter: Painter? = null,
    subtitle: String? = null,
    value: String? = null,
    destructive: Boolean = false,
    chevron: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val tone = if (destructive) GamutDestructive else ink()
    val tap = if (onClick != null) Modifier.pressScale(onClick = onClick, scaleDown = 0.985f) else Modifier
    Row(
        modifier
            .fillMaxWidth()
            .then(tap)
            .heightIn(min = 56.dp)
            .padding(horizontal = GroupedRowInset, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            painter != null -> Icon(painter, null, tint = Color.Unspecified, modifier = Modifier.size(GroupedIconSize))
            icon != null ->
                Icon(
                    icon,
                    null,
                    tint = if (destructive) GamutDestructive else ink(0.9f),
                    modifier = Modifier.size(GroupedIconSize),
                )
        }
        Spacer(Modifier.width(GroupedIconGap))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = tone, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = ink(0.55f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
        }
        if (value != null) {
            Spacer(Modifier.width(10.dp))
            Text(value, style = MaterialTheme.typography.bodyLarge, color = ink(0.5f), maxLines = 1)
        }
        if (trailing != null) {
            trailing()
        } else if (onClick != null && chevron && !destructive) {
            Spacer(Modifier.width(6.dp))
            Icon(PhosphorIcons.Regular.CaretRight, null, tint = ink(0.35f), modifier = Modifier.size(14.dp))
        }
    }
}