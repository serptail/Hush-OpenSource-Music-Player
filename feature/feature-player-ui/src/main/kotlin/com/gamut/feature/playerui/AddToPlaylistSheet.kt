package com.gamut.feature.playerui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.gamut.core.designsystem.TablerIcons
import com.gamut.core.designsystem.GamutSheet
import com.gamut.core.designsystem.bounceClickable
import com.gamut.core.designsystem.focusRing
import com.gamut.core.designsystem.ink
import com.gamut.core.designsystem.thumbnailBorder

/** "Create playlist" that expands into a name field in place; [onCreate] gets the trimmed name. */
@Composable
private fun CreatePlaylistRow(onCreate: (String) -> Unit) {
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
                Modifier
                    .fillMaxWidth()
                    .focusRing(RoundedCornerShape(12.dp))
                    .bounceClickable { creating = true }
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(ink(0.07f)).thumbnailBorder(RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(TablerIcons.ListPlus, contentDescription = null, tint = ink(0.7f), modifier = Modifier.size(24.dp))
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

data class PlaylistPickerItem(
    val id: String,
    val name: String,
    val coverUrl: String?,
    val trackCount: Int? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddToPlaylistSheet(
    playlists: List<PlaylistPickerItem>,
    alreadyIn: Set<String>,
    onPick: (PlaylistPickerItem) -> Unit,
    onRemove: (PlaylistPickerItem) -> Unit,
    onDismiss: () -> Unit,
    /** Creates a playlist with this name and adds the current track; null hides the option. */
    onCreate: ((String) -> Unit)? = null,
) {
    GamutSheet(onDismiss = onDismiss) {
        Text(
            "Add to playlist",
            style = MaterialTheme.typography.titleLarge,
            color = ink(),
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )

        if (playlists.isEmpty()) {
            Text(
                "You don't have any playlists yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = ink(0.6f),
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 12.dp),
            )
            if (onCreate != null) {
                Box(Modifier.imePadding().padding(bottom = 24.dp)) { CreatePlaylistRow(onCreate) }
            }
            return@GamutSheet
        }

        LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
            items(playlists, key = { it.id }) { playlist ->
                val added = playlist.id in alreadyIn
                Row(
                    Modifier
                        .fillMaxWidth()
                        .focusRing(RoundedCornerShape(12.dp))
                        .bounceClickable { if (added) onRemove(playlist) else onPick(playlist) }
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(ink(0.07f)).thumbnailBorder(RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(TablerIcons.MusicNote, contentDescription = null, tint = ink(0.4f), modifier = Modifier.size(20.dp))
                        if (!playlist.coverUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = playlist.coverUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(48.dp),
                            )
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            playlist.name,
                            style = MaterialTheme.typography.bodyLarge,
                            color = ink(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        playlist.trackCount?.let { n ->
                            Text(
                                if (n == 1) "1 song" else "$n songs",
                                style = MaterialTheme.typography.bodyMedium,
                                color = ink(0.6f),
                            )
                        }
                    }
                    AnimatedVisibility(added, enter = fadeIn(tween(160)) + scaleIn(tween(220), initialScale = 0.5f)) {
                        Icon(
                            TablerIcons.Check,
                            contentDescription = "In this playlist (tap to remove)",
                            tint = ink(0.85f),
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
        }
    }
}
