package com.gamut.feature.playerui

import com.gamut.core.designsystem.GamutSheet
import com.gamut.core.designsystem.LocalCoverPalette
import com.gamut.core.designsystem.ink
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Light
import com.adamglin.phosphoricons.light.Check
import com.adamglin.phosphoricons.light.MusicNote
import com.gamut.core.designsystem.CoverPalette
import kotlinx.coroutines.delay

/** What the picker needs to know about a playlist. [coverUrl] is its picked cover, or the art of its last song. */
data class PlaylistPickerItem(
    val id: String,
    val name: String,
    val coverUrl: String?,
    val trackCount: Int? = null,
)

/**
 * "Add to playlist" sheet. Shows the user's playlists with their covers; tapping one calls [onPick], swaps the
 * title for "Added to playlist", and closes the sheet a moment later.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddToPlaylistSheet(
    playlists: List<PlaylistPickerItem>,
    accent: Color,
    onPick: (PlaylistPickerItem) -> Unit,
    onDismiss: () -> Unit,
) {
    var addedId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(addedId) {
        if (addedId != null) {
            delay(900)
            onDismiss()
        }
    }

    GamutSheet(onDismiss = onDismiss) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (addedId != null) {
                Icon(PhosphorIcons.Light.Check, contentDescription = null, tint = accent, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text("Added to playlist", style = MaterialTheme.typography.titleLarge, color = accent)
            } else {
                Text("Add to playlist", style = MaterialTheme.typography.titleLarge, color = ink())
            }
        }

        if (playlists.isEmpty()) {
            Text(
                "You don't have any playlists yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = ink(0.6f),
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp),
            )
            return@GamutSheet
        }

        LazyColumn(contentPadding = PaddingValues(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            items(playlists, key = { it.id }) { playlist ->
                val picked = playlist.id == addedId
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = addedId == null) {
                            addedId = playlist.id
                            onPick(playlist)
                        }
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(ink(0.07f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (playlist.coverUrl != null) {
                            AsyncImage(
                                model = playlist.coverUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(48.dp),
                            )
                        } else {
                            Icon(PhosphorIcons.Light.MusicNote, contentDescription = null, tint = ink(0.5f), modifier = Modifier.size(22.dp))
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            playlist.name,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (picked) accent else ink(),
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
                    if (picked) {
                        Icon(PhosphorIcons.Light.Check, contentDescription = null, tint = accent, modifier = Modifier.size(22.dp))
                    }
                }
            }
        }
    }
}