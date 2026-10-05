package com.gamut.feature.library

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.Light
import com.adamglin.phosphoricons.fill.Play
import com.adamglin.phosphoricons.light.ArrowDown
import com.adamglin.phosphoricons.light.ArrowLeft
import com.adamglin.phosphoricons.light.ArrowUp
import com.adamglin.phosphoricons.light.Check
import com.adamglin.phosphoricons.light.DotsThreeVertical
import com.adamglin.phosphoricons.light.PencilSimple
import com.adamglin.phosphoricons.light.Plus
import com.adamglin.phosphoricons.light.Shuffle
import com.adamglin.phosphoricons.light.Trash
import com.adamglin.phosphoricons.light.VinylRecord
import com.adamglin.phosphoricons.light.X
import com.gamut.core.designsystem.CoverTheme
import com.gamut.core.designsystem.LocalCoverPalette
import com.gamut.core.designsystem.TransparentStatusBar
import com.gamut.core.designsystem.ink
import com.gamut.core.designsystem.pressScale
import com.gamut.core.model.Track

private val HeaderCoverShape = RoundedCornerShape(20.dp)
private val PillShape = RoundedCornerShape(50)
private val PanelShape = RoundedCornerShape(16.dp)
private const val MAX_NAME_LENGTH = 60

/**
 * A playlist: a centered cover, the name, tags, a quiet count line and two flat pills (Play / Shuffle), then the songs.
 *
 * The pencil (top right) turns the screen into edit mode: change the name, tags and cover (a picked cover always
 * beats the art of the last song) and remove songs with the X on each row. Nothing is written until the check mark;
 * the back arrow / X / system back throws the edits away.
 *
 * Outside edit mode every row has a three-dots button on the far right; long-pressing a row opens the same panel
 * (Play, Move up, Move down, Remove).
 */
@Composable
fun PlaylistScreen(
    playlistId: String,
    /** Fallback name, used until the playlist itself has loaded. */
    title: String = "Playlist",
    onBack: () -> Unit,
    /** Play the whole list from the top, in order. */
    onPlay: (List<Track>) -> Unit = {},
    /** Play the whole list in random order. */
    onShuffle: (List<Track>) -> Unit = {},
    onTrackSelected: (Track) -> Unit = {},
    viewModel: PlaylistViewModel = viewModel(),
    nowPlaying: @Composable () -> Unit = {},
) {
    val songs by viewModel.tracks.collectAsStateWithLifecycle()
    val playlist by viewModel.playlist.collectAsStateWithLifecycle()
    val palette = LocalCoverPalette.current
    val haptic = LocalHapticFeedback.current
    TransparentStatusBar(darkIcons = palette.isLight)

    val name = playlist?.name ?: title
    var editing by remember { mutableStateOf(false) }
    var draftName by remember { mutableStateOf("") }
    var draftCover by remember { mutableStateOf<String?>(null) }
    val draftTags = remember { mutableStateListOf<String>() }
    val draftTracks = remember { mutableStateListOf<Track>() }
    var menuIndex by remember { mutableStateOf<Int?>(null) }
    val pickCover = rememberCoverPicker { draftCover = it }

    // In edit mode everything shown comes from the drafts, so Cancel really changes nothing.
    val shown: List<Track> = if (editing) draftTracks else songs
    val customCover = if (editing) draftCover else playlist?.coverUri
    val coverUrl = customCover ?: shown.lastOrNull()?.albumArtUrl

    fun startEdit() {
        draftName = name
        draftCover = playlist?.coverUri
        draftTags.clear(); draftTags.addAll(playlist?.tags.orEmpty())
        draftTracks.clear(); draftTracks.addAll(songs)
        editing = true
    }

    fun saveEdit() {
        if (draftName.isBlank()) return
        val list = draftTracks.toList()
        viewModel.save(draftName, draftTags.toList(), draftCover, if (list != songs) list else null)
        editing = false
    }

    fun moveBy(index: Int, delta: Int) {
        val to = index + delta
        if (to !in shown.indices) return
        if (editing) draftTracks.add(to, draftTracks.removeAt(index)) else viewModel.move(index, to)
    }

    fun removeAt(index: Int) {
        if (index !in shown.indices) return
        if (editing) draftTracks.removeAt(index) else viewModel.removeAt(index)
    }

    BackHandler(enabled = editing) { editing = false }

    CoverTheme(palette) {
        Column(Modifier.fillMaxSize().statusBarsPadding().then(if (editing) Modifier.imePadding() else Modifier)) {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                if (editing) TopIcon(PhosphorIcons.Light.X, "Cancel") { editing = false }
                else TopIcon(PhosphorIcons.Light.ArrowLeft, "Back", onClick = onBack)
                Spacer(Modifier.weight(1f))
                if (editing) TopIcon(PhosphorIcons.Light.Check, "Save", enabled = draftName.isNotBlank(), onClick = ::saveEdit)
                else if (playlist != null) TopIcon(PhosphorIcons.Light.PencilSimple, "Edit playlist", onClick = ::startEdit)
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 24.dp)) {
                item(key = "header") {
                    PlaylistHeader(
                        title = name,
                        coverUrl = coverUrl,
                        hasCustomCover = customCover != null,
                        tags = if (editing) draftTags else playlist?.tags.orEmpty(),
                        songCount = shown.size,
                        artistCount = shown.map { it.artist }.distinct().size,
                        editing = editing,
                        draftName = draftName,
                        onNameChange = { draftName = it },
                        onPickCover = pickCover,
                        onResetCover = { draftCover = null },
                        onTagsChange = { draftTags.clear(); draftTags.addAll(it) },
                        onPlay = { onPlay(songs) },
                        onShuffle = { onShuffle(songs) },
                    )
                }
                if (shown.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            "Nothing here yet. Add songs from Discover.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = ink(0.6f),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                        )
                    }
                }
                // Index in the key: the same song can sit in a playlist twice.
                itemsIndexed(shown, key = { i, s -> "${s.isrc}-$i" }) { index, song ->
                    PlaylistTrackRow(
                        track = song,
                        editing = editing,
                        onClick = { onTrackSelected(song) },
                        onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); menuIndex = index },
                        onMore = { menuIndex = index },
                        onRemove = { removeAt(index) },
                    )
                }
                item(key = "nav-space") { Box(Modifier.navigationBarsPadding()) }
            }
            nowPlaying()
        }

        val menuTrack = menuIndex?.let { shown.getOrNull(it) }
        if (menuIndex != null && menuTrack != null) {
            val i = menuIndex!!
            TrackOptionsPanel(
                track = menuTrack,
                showPlay = !editing,
                canMoveUp = i > 0,
                canMoveDown = i < shown.lastIndex,
                onPlay = { onTrackSelected(menuTrack) },
                onMoveUp = { moveBy(i, -1) },
                onMoveDown = { moveBy(i, +1) },
                onRemove = { removeAt(i) },
                onDismiss = { menuIndex = null },
            )
        }
    }
}

@Composable
private fun TopIcon(icon: ImageVector, description: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).then(if (enabled) Modifier.pressScale(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = ink(if (enabled) 0.85f else 0.3f), modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun PlaylistHeader(
    title: String,
    coverUrl: String?,
    hasCustomCover: Boolean,
    tags: List<String>,
    songCount: Int,
    artistCount: Int,
    editing: Boolean,
    draftName: String,
    onNameChange: (String) -> Unit,
    onPickCover: () -> Unit,
    onResetCover: () -> Unit,
    onTagsChange: (List<String>) -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        // Tapping the cover only does something in edit mode.
        val coverSize = Modifier.fillMaxWidth(0.62f).aspectRatio(1f).then(if (editing) Modifier.pressScale(onClick = onPickCover) else Modifier)
        Box(coverSize) {
            if (coverUrl.isNullOrBlank()) {
                // No song yet: a flat placeholder. No shadow here, a translucent fill over a shadow shows as a dark box.
                Box(Modifier.fillMaxSize().clip(HeaderCoverShape).background(ink(0.07f)), contentAlignment = Alignment.Center) {
                    Icon(if (editing) PhosphorIcons.Light.Plus else PhosphorIcons.Light.VinylRecord, if (editing) "Add cover" else null, tint = ink(0.6f), modifier = Modifier.size(56.dp))
                }
            } else {
                CoverImage(
                    remember(coverUrl) { resolveCoverUrl(coverUrl, 600) },
                    Modifier
                        .fillMaxSize()
                        .shadow(22.dp, HeaderCoverShape, clip = false, ambientColor = Color.Black.copy(alpha = 0.35f), spotColor = Color.Black.copy(alpha = 0.35f))
                        .clip(HeaderCoverShape),
                )
            }
            if (editing) {
                Box(
                    Modifier.align(Alignment.BottomEnd).padding(10.dp).size(38.dp).background(Color.Black.copy(alpha = 0.55f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(PhosphorIcons.Light.PencilSimple, "Change cover", tint = Color.White, modifier = Modifier.size(20.dp))
                }
            }
        }

        if (editing) {
            if (hasCustomCover) {
                Text(
                    "Use the last song's art instead",
                    style = MaterialTheme.typography.labelLarge,
                    color = ink(0.7f),
                    modifier = Modifier.padding(top = 12.dp).pressScale(onClick = onResetCover).padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            val style = MaterialTheme.typography.headlineLarge
            Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp).padding(top = 20.dp)) {
                BasicTextField(
                    value = draftName,
                    onValueChange = { if (it.length <= MAX_NAME_LENGTH) onNameChange(it) },
                    singleLine = true,
                    textStyle = style.copy(color = ink(), textAlign = TextAlign.Center),
                    cursorBrush = SolidColor(ink()),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner ->
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            if (draftName.isEmpty()) Text("Playlist name", style = style, color = ink(0.35f), textAlign = TextAlign.Center)
                            inner()
                        }
                    },
                )
                Spacer(Modifier.height(10.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(ink(0.25f)))
            }
            Text(
                "Tags",
                style = MaterialTheme.typography.titleLarge,
                color = ink(),
                modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 12.dp),
            )
            TagsEditor(tags = tags, onChange = onTagsChange)
            Text(
                if (songCount == 0) "Empty" else "${plural(songCount, "song")} \u00B7 tap X to remove",
                style = MaterialTheme.typography.bodyMedium,
                color = ink(0.6f),
                modifier = Modifier.padding(top = 20.dp),
            )
        } else {
            Text(
                title,
                style = MaterialTheme.typography.displayMedium,
                color = ink(),
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp).padding(top = 24.dp),
            )
            Text(
                if (songCount == 0) "Empty" else "${plural(songCount, "song")} \u00B7 ${plural(artistCount, "artist")}",
                style = MaterialTheme.typography.bodyMedium,
                color = ink(0.6f),
                modifier = Modifier.padding(top = 4.dp),
            )
            if (tags.isNotEmpty()) TagsRow(tags, Modifier.padding(horizontal = 24.dp).padding(top = 14.dp))
            if (songCount > 0) {
                Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ActionPill("Play", PhosphorIcons.Fill.Play, filled = true, onClick = onPlay)
                    ActionPill("Shuffle", PhosphorIcons.Light.Shuffle, filled = false, onClick = onShuffle)
                }
            }
        }
    }
}

/** Flat pill: the main action is solid ink, the other a quiet translucent ink. No blur, no glass. */
@Composable
private fun ActionPill(label: String, icon: ImageVector, filled: Boolean, onClick: () -> Unit) {
    val isLight = LocalCoverPalette.current.isLight
    val content = if (filled) (if (isLight) Color.White else Color.Black) else ink()
    Row(
        Modifier
            .pressScale(onClick = onClick)
            .background(if (filled) ink() else ink(0.10f), PillShape)
            .padding(horizontal = 22.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = content, modifier = Modifier.size(18.dp))
        Text(label, style = MaterialTheme.typography.titleMedium, color = content, modifier = Modifier.padding(start = 8.dp))
    }
}

/** Tap = play, long press = options panel (same press-scale feel as the other rows). */
@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.rowPress(enabled: Boolean, onClick: () -> Unit, onLongClick: () -> Unit): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && enabled) 0.98f else 1f, tween(90), label = "row-press")
    graphicsLayer { scaleX = scale; scaleY = scale }
        .combinedClickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick, onLongClick = onLongClick)
}

@Composable
private fun PlaylistTrackRow(
    track: Track,
    editing: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMore: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .rowPress(enabled = !editing, onClick = onClick, onLongClick = onLongClick)
            .padding(start = 24.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverImage(remember(track.albumArtUrl) { resolveCoverUrl(track.albumArtUrl, 160) }, Modifier.size(54.dp).softCover())
        Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            Text(track.title, style = MaterialTheme.typography.titleMedium, color = ink(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track.artist, style = MaterialTheme.typography.bodyMedium, color = ink(0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (editing) {
            Box(Modifier.size(40.dp).pressScale(onClick = onRemove), contentAlignment = Alignment.Center) {
                Icon(PhosphorIcons.Light.X, "Remove ${track.title}", tint = ink(0.75f), modifier = Modifier.size(20.dp))
            }
        } else {
            if (track.durationMs > 0) {
                Text(formatDuration(track.durationMs), style = MaterialTheme.typography.bodyMedium, color = ink(0.45f))
            }
            Box(Modifier.size(40.dp).pressScale(onClick = onMore), contentAlignment = Alignment.Center) {
                Icon(PhosphorIcons.Light.DotsThreeVertical, "More options", tint = ink(0.7f), modifier = Modifier.size(22.dp))
            }
        }
    }
}

/** The small options panel (opened by the three dots or a long press). Every action closes it. */
@Composable
private fun TrackOptionsPanel(
    track: Track,
    showPlay: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onPlay: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    val base = LocalCoverPalette.current.base
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(PanelShape)
                .background(base)
                .background(ink(0.08f))
                .padding(vertical = 8.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                CoverImage(resolveCoverUrl(track.albumArtUrl, 160), Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)))
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(track.title, style = MaterialTheme.typography.titleMedium, color = ink(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(track.artist, style = MaterialTheme.typography.bodyMedium, color = ink(0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(ink(0.10f)))
            if (showPlay) PanelAction(PhosphorIcons.Fill.Play, "Play", true) { onPlay(); onDismiss() }
            PanelAction(PhosphorIcons.Light.ArrowUp, "Move up", canMoveUp) { onMoveUp(); onDismiss() }
            PanelAction(PhosphorIcons.Light.ArrowDown, "Move down", canMoveDown) { onMoveDown(); onDismiss() }
            PanelAction(PhosphorIcons.Light.Trash, "Remove from playlist", true) { onRemove(); onDismiss() }
        }
    }
}

@Composable
private fun PanelAction(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    val alpha = if (enabled) 1f else 0.3f
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier.pressScale(onClick = onClick, scaleDown = 0.98f) else Modifier)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = ink(0.85f * alpha), modifier = Modifier.size(20.dp))
        Text(label, style = MaterialTheme.typography.titleMedium, color = ink(alpha), modifier = Modifier.padding(start = 16.dp))
    }
}

private fun plural(n: Int, word: String) = if (n == 1) "1 $word" else "$n ${word}s"

private fun formatDuration(ms: Long): String {
    val total = ms / 1000
    return "%d:%02d".format(total / 60, total % 60)
}