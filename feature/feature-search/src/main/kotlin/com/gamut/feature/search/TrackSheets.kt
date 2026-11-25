package com.gamut.feature.search

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gamut.core.designsystem.ArtImage
import com.gamut.core.designsystem.CoverPalette
import com.gamut.core.designsystem.ExplicitMark
import com.gamut.core.designsystem.GamutSheet
import com.gamut.core.designsystem.LocalCoverPalette
import com.gamut.core.designsystem.TablerIcons
import com.gamut.core.designsystem.collectIsPulsedAsState
import com.gamut.core.designsystem.ink
import com.gamut.core.designsystem.squareArtUrl
import com.gamut.core.designsystem.thumbnailBorder
import com.gamut.core.model.Track

internal val SheetShape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)

data class TrackPlaylistOption(
    val id: String,
    val name: String,
    val coverUrl: String?,
    val trackCount: Int? = null,
    val contains: Boolean = false,
    val trackIsrcs: Set<String> = emptySet(),
)

val LocalPlaylistRemove = staticCompositionLocalOf<(Track, String) -> Unit> { { _, _ -> } }

/** Creates a playlist with the given name and puts the track in it. */
val LocalPlaylistCreate = staticCompositionLocalOf<(String, Track) -> Unit> { { _, _ -> } }

internal fun Modifier.bounceClick(onClick: () -> Unit): Modifier =
    composed {
        val source = remember { MutableInteractionSource() }
        val pressed by source.collectIsPressedAsState()
        val pulsed by source.collectIsPulsedAsState()
        val hovered by source.collectIsHoveredAsState()
        val scale by animateFloatAsState(
            targetValue = if (pulsed) 0.97f else 1f,
            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
            label = "bounce",
        )
        val tint by animateColorAsState(
            targetValue =
                ink().copy(
                    alpha =
                        if (pressed) {
                            0.10f
                        } else if (hovered) {
                            0.06f
                        } else {
                            0f
                        },
                ),
            animationSpec = tween(140),
            label = "tint",
        )
        this
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }.clip(RoundedCornerShape(16.dp))
            .background(tint)
            .hoverable(source)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
    }

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
    GamutSheet(onDismiss = onDismiss, shape = SheetShape, scrollable = true) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            TrackHeader(track)
            HorizontalDivider(Modifier.padding(top = 14.dp, bottom = 6.dp), color = ink(0.1f))
            ActionRow(TablerIcons.Share, "Share") { onShare() }
            ActionRow(
                if (isFavorite) TablerIcons.HeartFilled else TablerIcons.Heart,
                if (isFavorite) "Added to favorites" else "Add to favorites",
                tint = if (isFavorite) MaterialTheme.colorScheme.primary else ink(),
            ) { onToggleFavorite() }
            ActionRow(TablerIcons.ListPlus, "Add to playlist") { onAddToPlaylist() }
            if (onGoToArtist != null) ActionRow(TablerIcons.CircleUser, "Go to artist") { onGoToArtist() }
        }
    }
}

/** Options for a mix or radio card: play it, keep its songs in Favorites, or drop them into a playlist. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StationActionsSheet(
    item: DiscoverItem,
    badge: String,
    busy: Boolean,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    onAddToFavorites: () -> Unit,
    onAddToPlaylist: () -> Unit,
) {
    GamutSheet(onDismiss = onDismiss, shape = SheetShape, scrollable = true) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)).background(ink(0.07f)).thumbnailBorder(RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (item.imageUrl.isNotBlank()) {
                        ArtImage(url = remember(item.imageUrl) { squareArtUrl(item.imageUrl, 256) }, modifier = Modifier.size(52.dp), fallback = {})
                    } else {
                        Icon(TablerIcons.MusicNote, null, tint = ink(0.5f))
                    }
                }
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(
                        item.title.ifBlank { item.subtitle },
                        style = MaterialTheme.typography.titleMedium,
                        color = ink(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOf(badge.lowercase().replaceFirstChar { it.uppercase() }, item.subtitle.takeIf { it != item.title })
                            .filterNotNull()
                            .filter { it.isNotBlank() }
                            .joinToString(" \u00B7 "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = ink(0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            HorizontalDivider(Modifier.padding(top = 14.dp, bottom = 6.dp), color = ink(0.1f))
            ActionRow(TablerIcons.Play, "Play") { onPlay() }
            ActionRow(TablerIcons.Heart, if (busy) "Adding\u2026" else "Add songs to favorites") { if (!busy) onAddToFavorites() }
            ActionRow(TablerIcons.ListPlus, "Add songs to playlist") { if (!busy) onAddToPlaylist() }
        }
    }
}

/** Picks the playlist that receives every song of a station. [onPick] gets the playlist id. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StationPlaylistSheet(
    playlists: List<TrackPlaylistOption>,
    accent: Color,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    GamutSheet(onDismiss = onDismiss, shape = SheetShape) {
        Text(
            "Add to playlist",
            style = MaterialTheme.typography.titleLarge,
            color = ink(),
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        LazyColumn(modifier = Modifier.imePadding(), contentPadding = PaddingValues(bottom = 32.dp)) {
            if (playlists.isEmpty()) {
                item(key = "no-playlists") {
                    Text(
                        "You don't have any playlists yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ink(0.6f),
                        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 12.dp),
                    )
                }
            }
            items(playlists, key = { it.id }) { p ->
                PlaylistRow(
                    name = p.name,
                    sub = p.trackCount?.let { if (it == 1) "1 song" else "$it songs" },
                    coverUrl = p.coverUrl,
                    checked = false,
                    accent = accent,
                    onClick = { onPick(p.id) },
                )
            }
        }
    }
}

@Composable
private fun TrackHeader(track: Track) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
        ArtImage(
            url = remember(track.albumArtUrl) { squareArtUrl(track.albumArtUrl, 256) },
            modifier = Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)).thumbnailBorder(RoundedCornerShape(8.dp)),
            fallback = { Icon(TablerIcons.MusicNote, null, tint = ink(0.5f)) },
        )
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ExplicitMark(track.isExplicit, ink(0.6f))
                Text(
                    track.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = ink(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            Text(
                track.artist,
                style = MaterialTheme.typography.bodyMedium,
                color = ink(0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ActionRow(
    icon: ImageVector,
    label: String,
    tint: Color = ink(),
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().bounceClick(onClick).padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(20.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = tint)
    }
}

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
    val removeFromPlaylist = LocalPlaylistRemove.current
    val createPlaylist = LocalPlaylistCreate.current
    val ticked =
        remember {
            mutableStateListOf<String>().apply { addAll(playlists.filter { it.contains || track.isrc in it.trackIsrcs }.map { it.id }) }
        }
    GamutSheet(onDismiss = onDismiss, shape = SheetShape) {
        Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (showFavorites) {
                Icon(TablerIcons.CircleCheck, null, tint = accent, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(10.dp))
            }
            Text(if (showFavorites) "Saved in" else "Add to playlist", style = MaterialTheme.typography.titleLarge, color = ink())
        }
        LazyColumn(modifier = Modifier.imePadding(), contentPadding = PaddingValues(bottom = 32.dp)) {
            if (showFavorites) {
                item {
                    PlaylistRow("Favorites", "Liked songs", null, checked = true, accent = accent, icon = TablerIcons.HeartFilled, iconTint = accent, onClick = {
                    })
                }
            }
            if (playlists.isEmpty()) {
                item(key = "no-playlists") {
                    Text(
                        "You don't have any playlists yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ink(0.6f),
                        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 12.dp),
                    )
                }
                item(key = "create-playlist") {
                    CreatePlaylistRow(accent) { name ->
                        createPlaylist(name, track)
                        onDismiss()
                    }
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
                    onClick = {
                        if (checked) {
                            ticked.remove(p.id)
                            removeFromPlaylist(track, p.id)
                        } else {
                            ticked.add(p.id)
                            onAddToPlaylist(p.id)
                        }
                    },
                )
            }
        }
    }
}

/** "Create playlist" that expands into a name field in place; [onCreate] gets the trimmed name. */
@Composable
private fun CreatePlaylistRow(
    accent: Color,
    onCreate: (String) -> Unit,
) {
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val submit = { if (name.isNotBlank()) onCreate(name.trim()) }
    Crossfade(targetState = creating, label = "create-playlist") { isCreating ->
        if (isCreating) {
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .weight(1f)
                        .background(ink(0.1f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    if (name.isEmpty()) {
                        Text("Playlist name", color = ink(0.4f), style = MaterialTheme.typography.bodyLarge)
                    }
                    BasicTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.merge(TextStyle(color = ink())),
                        cursorBrush = SolidColor(ink()),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { submit() }),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { submit() }, enabled = name.isNotBlank()) { Text("Create") }
            }
        } else {
            Row(
                Modifier.fillMaxWidth().bounceClick { creating = true }.padding(horizontal = 24.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(accent.copy(alpha = 0.15f))
                        .thumbnailBorder(RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(TablerIcons.ListPlus, null, tint = accent, modifier = Modifier.size(24.dp))
                }
                Text(
                    "Create playlist",
                    style = MaterialTheme.typography.bodyLarge,
                    color = ink(),
                    modifier = Modifier.padding(start = 14.dp),
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
    icon: ImageVector = TablerIcons.MusicNote,
    iconTint: Color = ink(0.5f),
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().bounceClick(onClick).padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(ink(0.07f)).thumbnailBorder(RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (coverUrl != null) {
                ArtImage(url = coverUrl, modifier = Modifier.size(48.dp), fallback = {})
            } else {
                Icon(icon, null, tint = iconTint, modifier = Modifier.size(22.dp))
            }
        }
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, color = ink(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodyMedium, color = ink(0.6f))
        }
        Crossfade(targetState = checked, label = "added") { on ->
            if (on) {
                Icon(TablerIcons.CircleCheck, "Added", tint = accent, modifier = Modifier.size(28.dp))
            } else {
                Icon(TablerIcons.CirclePlus, "Add", tint = ink(0.6f), modifier = Modifier.size(28.dp))
            }
        }
    }
}