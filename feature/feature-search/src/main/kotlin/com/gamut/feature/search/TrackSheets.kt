package com.gamut.feature.search

import com.gamut.core.designsystem.GamutSheet
import com.gamut.core.designsystem.LocalCoverPalette
import com.gamut.core.designsystem.ink
import androidx.compose.foundation.background
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.graphicsLayer
import com.gamut.core.designsystem.CoverPalette
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.Light
import com.adamglin.phosphoricons.fill.CheckCircle
import com.adamglin.phosphoricons.fill.Heart as HeartFill
import com.adamglin.phosphoricons.light.ListPlus
import com.adamglin.phosphoricons.light.UserCircle
import com.adamglin.phosphoricons.light.Heart
import com.adamglin.phosphoricons.light.MusicNote
import com.adamglin.phosphoricons.light.PlusCircle
import com.adamglin.phosphoricons.light.ShareNetwork
import com.gamut.core.designsystem.ArtImage
import com.gamut.core.designsystem.squareArtUrl
import com.gamut.core.model.Track

internal val SheetShape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)

/** A playlist as the "Saved in" sheet needs it. [contains] = the track is already in it. */
data class TrackPlaylistOption(
    val id: String,
    val name: String,
    val coverUrl: String?,
    val trackCount: Int? = null,
    val contains: Boolean = false,
)

/**
 * Same touch feel as the search result rows: a soft tint fades in on hover/press and a press squeezes the row to 97%
 * then springs back with a small overshoot. No ripple.
 */
internal fun Modifier.bounceClick(onClick: () -> Unit): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val hovered by source.collectIsHoveredAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "bounce",
    )
    val tint by animateColorAsState(
        targetValue = ink().copy(alpha = if (pressed) 0.10f else if (hovered) 0.06f else 0f),
        animationSpec = tween(140),
        label = "tint",
    )
    this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .clip(RoundedCornerShape(16.dp))
        .background(tint)
        .hoverable(source)
        .clickable(interactionSource = source, indication = null, onClick = onClick)
}

// ------------------------------------------------------------------ actions sheet

/** Replaces the small dropdown: Share, Add to favorites, Add to playlist, Go to artist. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TrackActionsSheet(
    track: Track,
    isFavorite: Boolean,
    onDismiss: () -> Unit,
    onShare: () -> Unit,
    onToggleFavorite: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onGoToArtist: (() -> Unit)?,
) {
    GamutSheet(onDismiss = onDismiss, shape = SheetShape) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            TrackHeader(track)
            HorizontalDivider(Modifier.padding(top = 14.dp, bottom = 6.dp), color = ink(0.1f))
            ActionRow(PhosphorIcons.Light.ShareNetwork, "Share") { onShare() }
            // Stays open: the icon fills and the label changes in place.
            ActionRow(
                if (isFavorite) PhosphorIcons.Fill.HeartFill else PhosphorIcons.Light.Heart,
                if (isFavorite) "Added to favorites" else "Add to favorites",
                tint = if (isFavorite) MaterialTheme.colorScheme.primary else ink(),
            ) { onToggleFavorite() }
            ActionRow(PhosphorIcons.Light.ListPlus, "Add to playlist") { onAddToPlaylist() }
            if (onGoToArtist != null) ActionRow(PhosphorIcons.Light.UserCircle, "Go to artist") { onGoToArtist() }
        }
    }
}

@Composable
private fun TrackHeader(track: Track) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
        ArtImage(
            url = remember(track.albumArtUrl) { squareArtUrl(track.albumArtUrl, 256) },
            modifier = Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)),
            fallback = { Icon(PhosphorIcons.Light.MusicNote, null, tint = ink(0.5f)) },
        )
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(track.title, style = MaterialTheme.typography.titleMedium, color = ink(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track.artist, style = MaterialTheme.typography.bodyMedium, color = ink(0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ActionRow(icon: ImageVector, label: String, tint: Color = ink(), onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().bounceClick(onClick).padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(20.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = tint)
    }
}

// ------------------------------------------------------------------ saved in / add to playlist

/**
 * "Saved in" sheet. With [showFavorites] (opened by the + button) the first row is Favorites, already checked, and the
 * playlists below can be ticked to add the song there too. Without it, it is the plain "Add to playlist" picker.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SavedInSheet(
    track: Track,
    playlists: List<TrackPlaylistOption>,
    accent: Color,
    showFavorites: Boolean,
    onAddToPlaylist: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // Local ticks so the check responds instantly; the real state is the caller's.
    val ticked = remember { mutableStateListOf<String>().apply { addAll(playlists.filter { it.contains }.map { it.id }) } }
    GamutSheet(onDismiss = onDismiss, shape = SheetShape) {
        Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (showFavorites) {
                Icon(PhosphorIcons.Fill.CheckCircle, null, tint = accent, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(10.dp))
            }
            Text(if (showFavorites) "Saved in" else "Add to playlist", style = MaterialTheme.typography.titleLarge, color = ink())
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
            if (showFavorites) {
                item {
                    PlaylistRow("Favorites", "Liked songs", null, checked = true, accent = accent, icon = PhosphorIcons.Fill.HeartFill, iconTint = accent, onClick = {})
                }
            }
            if (playlists.isEmpty()) {
                item {
                    Text(
                        "You don't have any playlists yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ink(0.6f),
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp),
                    )
                }
            }
            items(playlists, key = { it.id }) { p ->
                val checked = p.id in ticked
                PlaylistRow(
                    name = p.name,
                    sub = p.trackCount?.let { if (it == 1) "1 song" else "$it songs" },
                    coverUrl = p.coverUrl,
                    checked = checked,
                    accent = accent,
                    // Add-only: the library has no "remove from playlist" call here, so a ticked row stays ticked.
                    onClick = { if (!checked) { ticked.add(p.id); onAddToPlaylist(p.id) } },
                )
            }
        }
    }
}

@Composable
private fun PlaylistRow(
    name: String,
    sub: String?,
    coverUrl: String?,
    checked: Boolean,
    accent: Color,
    icon: ImageVector = PhosphorIcons.Light.MusicNote,
    iconTint: Color = ink(0.5f),
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().bounceClick(onClick).padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(ink(0.07f)),
            contentAlignment = Alignment.Center,
        ) {
            if (coverUrl != null) ArtImage(url = coverUrl, modifier = Modifier.size(48.dp), fallback = {})
            else Icon(icon, null, tint = iconTint, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, color = ink(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodyMedium, color = ink(0.6f))
        }
        // Plus circle that turns into a filled check circle once added.
        Crossfade(targetState = checked, label = "added") { on ->
            if (on) Icon(PhosphorIcons.Fill.CheckCircle, "Added", tint = accent, modifier = Modifier.size(28.dp))
            else Icon(PhosphorIcons.Light.PlusCircle, "Add", tint = ink(0.6f), modifier = Modifier.size(28.dp))
        }
    }
}