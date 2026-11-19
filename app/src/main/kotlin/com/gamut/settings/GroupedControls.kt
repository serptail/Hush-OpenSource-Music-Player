package com.gamut.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gamut.core.designsystem.ink
import com.gamut.core.designsystem.softResize

/*
 * Inline controls that sit inside a SettingsBlock and match GroupedRow: a small
 * icon, text starting at the same inset, and the control tucked under or beside it.
 */

private val TextInset = GroupedRowInset + GroupedIconSize + GroupedIconGap

@Composable
private fun GroupedLead(
    title: String,
    icon: ImageVector,
    subtitle: String?,
    modifier: Modifier = Modifier,
    value: String? = null,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = ink(0.9f), modifier = Modifier.size(GroupedIconSize))
        Spacer(Modifier.width(GroupedIconGap))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = ink(), maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    }
}

/** A grouped row with a pill switch on the right. Dimmed and inert when [enabled] is false. */
@Composable
fun GroupedSwitchRow(
    title: String,
    icon: ImageVector,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    GroupedRow(
        title = title,
        icon = icon,
        subtitle = subtitle,
        modifier = Modifier.alpha(if (enabled) 1f else 0.4f),
        onClick = if (enabled) ({ onChange(!checked) }) else null,
        trailing = {
            Spacer(Modifier.width(12.dp))
            PillSwitch(checked)
        },
    )
}

/** Icon, title and live value on one line, with the slider underneath, lined up with the text. */
@Composable
fun GroupedSliderRow(
    title: String,
    icon: ImageVector,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    subtitle: String? = null,
    enabled: Boolean = true,
    steps: Int = 0,
    centered: Boolean = false,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.4f)
            .padding(horizontal = GroupedRowInset)
            .padding(top = 13.dp, bottom = 8.dp),
    ) {
        GroupedLead(title, icon, subtitle, value = valueLabel)
        PillSlider(
            value = value,
            onChange = onChange,
            range = range,
            modifier = Modifier.padding(start = GroupedIconSize + GroupedIconGap - 10.dp),
            enabled = enabled,
            steps = steps,
            centered = centered,
        )
    }
}

/** Icon and title on one line, with a full-width segmented control underneath and an optional hint. */
@Composable
fun <T> GroupedSegmentedRow(
    title: String,
    icon: ImageVector,
    options: List<Choice<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    subtitle: String? = null,
    hint: String? = null,
) {
    Column(
        Modifier
            .softResize()
            .fillMaxWidth()
            .padding(horizontal = GroupedRowInset)
            .padding(top = 13.dp, bottom = 14.dp),
    ) {
        GroupedLead(title, icon, subtitle)
        SegmentedControl(options, selected, onSelect, Modifier.padding(top = 12.dp))
        if (hint != null) GroupedNote(hint, Modifier.padding(top = 2.dp), start = 4.dp, bottom = 0.dp)
    }
}

/** A small muted line inside a group, for status text that belongs to the row above it. */
@Composable
fun GroupedNote(
    text: String,
    modifier: Modifier = Modifier,
    start: Dp = TextInset,
    bottom: Dp = 12.dp,
) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = ink(0.55f),
        modifier = modifier.fillMaxWidth().padding(start = start, end = GroupedRowInset, top = 4.dp, bottom = bottom),
    )
}


/** One option of [GroupedChoiceRows]: its own icon, a name, and one line saying what it does. */
data class ChoiceRow<T>(
    val value: T,
    val label: String,
    val hint: String?,
    val icon: ImageVector,
)

/**
 * A pick-one list where every option is a full row with its own icon and description and a radio dot on
 * the right. Used where the options are too long for a segmented control, such as the bit-perfect modes.
 */
@Composable
fun <T> GroupedChoiceRows(
    options: List<ChoiceRow<T>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    options.forEachIndexed { i, option ->
        if (i > 0) GroupedDivider()
        GroupedRow(
            title = option.label,
            icon = option.icon,
            subtitle = option.hint,
            chevron = false,
            onClick = { onSelect(option.value) },
            trailing = {
                Spacer(Modifier.width(12.dp))
                RadioDot(option.value == selected)
            },
        )
    }
}

@Composable
private fun RadioDot(selected: Boolean) {
    Box(
        Modifier
            .size(20.dp)
            .clip(CircleShape)
            .then(if (selected) Modifier.background(ink(0.9f)) else Modifier.border(1.5.dp, ink(0.35f), CircleShape)),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Box(Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.background))
    }
}
