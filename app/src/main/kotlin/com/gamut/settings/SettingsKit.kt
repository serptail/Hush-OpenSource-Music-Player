package com.gamut.settings

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Light
import com.adamglin.phosphoricons.light.CaretLeft
import com.adamglin.phosphoricons.light.CaretRight
import com.adamglin.phosphoricons.light.Check
import com.gamut.core.designsystem.CoverTheme
import com.gamut.core.designsystem.LocalCoverPalette
import com.gamut.core.designsystem.TransparentStatusBar
import com.gamut.core.designsystem.ink
import com.gamut.core.designsystem.pressScale

/**
 * Minimal kit shared by Settings and Profile: no cards, no containers around rows. Type and spacing do the grouping.
 * The only filled shapes are [Tile]s (Profile) and fields/buttons. Everything reads colour through ink(), which comes
 * from CoverTheme, so it follows the current cover palette (light/dark) like every other screen.
 */
val GamutDestructive = Color(0xFFFF6B6B)

/** The ONE place that decides which style is a "heading". Point it at your CodeNext-ExtraBold style if the theme's isn't. */
val GamutHeading: TextStyle @Composable get() = MaterialTheme.typography.headlineMedium

/** Page: back button + title, scrolling body, the now-playing strip docked underneath. Themed from the ambient palette. */
@Composable
fun GamutPage(title: String, onBack: () -> Unit, nowPlaying: @Composable () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val palette = LocalCoverPalette.current
    TransparentStatusBar(darkIcons = palette.isLight)
    CoverTheme(palette) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 24.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).pressScale(onClick = onBack), contentAlignment = Alignment.Center) {
                    Icon(PhosphorIcons.Light.CaretLeft, "Back", tint = ink(0.85f), modifier = Modifier.size(24.dp))
                }
                if (title.isNotEmpty()) Text(title, style = MaterialTheme.typography.titleLarge, color = ink(), modifier = Modifier.padding(start = 4.dp))
            }
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
                content()
                Spacer(Modifier.height(32.dp))
            }
            nowPlaying()
        }
    }
}

/** Section heading: big, bold, no box. */
@Composable
fun GroupLabel(text: String) {
    Text(text, style = GamutHeading, color = ink(), modifier = Modifier.padding(top = 32.dp, bottom = 8.dp))
}

/** Kept for source compatibility: a plain column, no surface. */
@Composable
fun GroupCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth(), content = content)
}

/** Rows are flat now; spacing separates them. Kept so existing call sites compile. */
@Composable
fun CellDivider(inset: Boolean = true) = Unit

@Composable
fun Cell(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    subtitle: String? = null,
    subtitleColor: Color? = null,
    destructive: Boolean = false,
    chevron: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val tone = if (destructive) GamutDestructive else ink()
    val tap = if (onClick != null) Modifier.pressScale(onClick = onClick, scaleDown = 0.985f) else Modifier
    Row(modifier.fillMaxWidth().then(tap).padding(vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(icon, null, tint = if (destructive) GamutDestructive else ink(0.8f), modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = tone, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(
                    subtitle, style = MaterialTheme.typography.bodyMedium, color = subtitleColor ?: ink(0.55f),
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        if (trailing != null) trailing()
        else if (onClick != null && chevron && !destructive) Icon(PhosphorIcons.Light.CaretRight, null, tint = ink(0.45f), modifier = Modifier.size(18.dp))
    }
}

/** Square action tile (Profile). Pass it `Modifier.weight(1f)` inside a Row for a two-up grid. [badge] sits top-right. */
@Composable
fun Tile(
    label: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    destructive: Boolean = false,
    badge: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val tone = if (destructive) GamutDestructive else ink()
    Box(
        modifier.aspectRatio(1f).pressScale(onClick = onClick, scaleDown = 0.97f)
            .clip(RoundedCornerShape(26.dp)).background(ink(0.07f)).padding(18.dp),
    ) {
        Icon(icon, null, tint = if (destructive) GamutDestructive else ink(0.85f), modifier = Modifier.size(28.dp).align(Alignment.TopStart))
        if (badge != null) Box(Modifier.align(Alignment.TopEnd)) { badge() }
        Column(Modifier.align(Alignment.BottomStart)) {
            Text(label, style = MaterialTheme.typography.titleMedium, color = tone, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = ink(0.55f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun GamutButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, filled: Boolean = true) {
    val onInk = if (LocalCoverPalette.current.isLight) Color.White else Color.Black
    Box(
        modifier.pressScale(onClick = onClick).clip(RoundedCornerShape(16.dp))
            .background(if (filled) ink(0.95f) else ink(0.12f)).padding(horizontal = 20.dp, vertical = 15.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium, color = if (filled) onInk else ink(), maxLines = 1)
    }
}

@Composable
fun GamutSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    val onInk = if (LocalCoverPalette.current.isLight) Color.White else Color.Black
    val x by animateDpAsState(if (checked) 20.dp else 0.dp, label = "switch")
    Box(
        Modifier.pressScale(onClick = { onChange(!checked) }).size(width = 48.dp, height = 28.dp).clip(CircleShape)
            .background(if (checked) ink(0.95f) else ink(0.18f)).padding(3.dp),
    ) {
        Box(Modifier.offset(x = x).size(22.dp).background(if (checked) onInk else ink(0.7f), CircleShape))
    }
}

@Composable
fun GamutCheck(checked: Boolean, label: String, onChange: (Boolean) -> Unit, warn: Boolean = false) {
    val onInk = if (LocalCoverPalette.current.isLight) Color.White else Color.Black
    val shape = RoundedCornerShape(7.dp)
    Row(
        Modifier.fillMaxWidth().pressScale(onClick = { onChange(!checked) }, scaleDown = 0.98f).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(22.dp).clip(shape)
                .then(if (checked) Modifier.background(ink(0.95f), shape) else Modifier.border(1.5.dp, ink(0.3f), shape)),
            contentAlignment = Alignment.Center,
        ) { if (checked) Icon(PhosphorIcons.Light.Check, null, tint = onInk, modifier = Modifier.size(14.dp)) }
        Text(
            label, style = MaterialTheme.typography.bodyMedium, color = if (warn && checked) GamutDestructive else ink(0.85f),
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

/** Single-line text field in the app's look. */
@Composable
fun GamutField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(ink(0.07f)).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = ink(0.4f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicTextField(
                value = value, onValueChange = onChange, singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = ink()),
                cursorBrush = SolidColor(ink()),
                visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (trailing != null) trailing()
    }
}

/**
 * The one confirmation dialog. Uses the AMBIENT palette (captured before the Dialog window opens) instead of a fixed
 * Neutral one, so it follows light/dark with the rest of the app.
 */
@Composable
fun GamutDialog(
    title: String,
    onDismiss: () -> Unit,
    confirmLabel: String,
    onConfirm: () -> Unit,
    destructive: Boolean = false,
    body: String? = null,
    dismissLabel: String? = "Cancel",
    content: (@Composable ColumnScope.() -> Unit)? = null,   // keep LAST so `GamutDialog(...) { }` binds here
) {
    val palette = LocalCoverPalette.current
    Dialog(onDismissRequest = onDismiss) {
        CoverTheme(palette) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(palette.base).padding(24.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, color = ink())
                if (body != null) Text(body, style = MaterialTheme.typography.bodyMedium, color = ink(0.6f), modifier = Modifier.padding(top = 10.dp))
                if (content != null) Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(2.dp), content = content)
                Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.End) {
                    if (dismissLabel != null) Text(
                        dismissLabel, style = MaterialTheme.typography.titleMedium, color = ink(),
                        modifier = Modifier.pressScale(onClick = onDismiss).padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                    Text(
                        confirmLabel, style = MaterialTheme.typography.titleMedium, color = if (destructive) GamutDestructive else ink(),
                        modifier = Modifier.pressScale(onClick = onConfirm).padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}