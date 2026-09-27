package com.hush.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Light
import com.adamglin.phosphoricons.light.CaretLeft
import com.adamglin.phosphoricons.light.MusicNote
import com.adamglin.phosphoricons.light.Play
import com.adamglin.phosphoricons.light.Plus
import com.adamglin.phosphoricons.light.VinylRecord
import com.hush.core.designsystem.AcrylicGloss
import com.hush.core.designsystem.ArtworkShape
import com.hush.core.designsystem.CardShape
import com.hush.core.designsystem.HushAccentA
import com.hush.core.designsystem.HushAccentB
import com.hush.core.designsystem.HushAccentC
import com.hush.core.designsystem.HushInk
import com.hush.core.designsystem.HushMist
import com.hush.core.designsystem.HushPaper
import com.hush.core.designsystem.acrylicFrame
import com.hush.core.designsystem.pressScale
import com.hush.core.designsystem.rememberDynamicAccent
import com.hush.core.designsystem.thinCard
import com.hush.core.model.Track

@Composable
fun LibraryScreen(viewModel: LibraryViewModel, selectedTrack: Track?, onBack: () -> Unit, onPlayer: () -> Unit) {
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    var showCreate by remember { mutableStateOf(false) }
    // Reacts to whatever's selected/playing, same as the full player — keeps
    // this screen feeling part of the same living app rather than a static
    // list bolted on separately.
    val accent = rememberDynamicAccent(selectedTrack?.albumArtUrl)

    Column(Modifier.fillMaxSize().background(HushPaper).padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 22.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(PhosphorIcons.Light.CaretLeft, "Back", tint = HushInk, modifier = Modifier.pressScale(onClick = onBack).padding(8.dp))
            Text("Your library", style = MaterialTheme.typography.headlineLarge, color = HushInk, modifier = Modifier.weight(1f).padding(start = 4.dp))
            Icon(PhosphorIcons.Light.MusicNote, "Now playing", tint = accent.top, modifier = Modifier.pressScale(onClick = onPlayer).padding(8.dp))
        }

        // Gradient promo banner, tinted by whatever's currently selected —
        // a real "hero" moment instead of a flat white box.
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 16.dp)
                .clip(CardShape)
                .background(Brush.linearGradient(listOf(accent.top, accent.mid)))
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Make room for favorites", style = MaterialTheme.typography.titleLarge, color = Color.White)
                Text("Playlists that feel like you", color = Color.White.copy(alpha = 0.85f))
            }
            Icon(
                PhosphorIcons.Light.Plus,
                "Create playlist",
                tint = Color.White,
                modifier = Modifier
                    .pressScale(onClick = { showCreate = true })
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.22f))
                    .padding(10.dp),
            )
        }

        Text("Playlists", style = MaterialTheme.typography.titleLarge, color = HushInk, modifier = Modifier.padding(top = 26.dp, bottom = 10.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            itemsIndexed(playlists, key = { _, item -> item.id }) { index, playlist ->
                PlaylistRow(
                    name = playlist.name,
                    coverGradient = coverGradientFor(index),
                    showAdd = selectedTrack != null,
                    onAdd = { selectedTrack?.let { viewModel.addTrack(playlist.id, it) } },
                )
            }
        }

        if (selectedTrack != null) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 14.dp).thinCard(elevation = 3.dp).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(PhosphorIcons.Light.MusicNote, null, tint = accent.top, modifier = Modifier.size(18.dp))
                Text("Ready to add: ${selectedTrack.title}", color = HushMist, modifier = Modifier.padding(start = 10.dp))
            }
        }
    }
    if (showCreate) {
        CreatePlaylistDialog(accentColor = accent.top, onDismiss = { showCreate = false }, onCreate = { viewModel.createPlaylist(it); showCreate = false })
    }
}

/** A small rotation of pleasant cover gradients so the list doesn't look like N identical placeholder squares. */
private fun coverGradientFor(index: Int): Brush {
    val palettes = listOf(
        listOf(HushAccentA, HushAccentC),
        listOf(HushAccentB, HushAccentC),
        listOf(HushAccentA, HushAccentB),
    )
    return Brush.linearGradient(palettes[index % palettes.size])
}

@Composable
private fun PlaylistRow(name: String, coverGradient: Brush, showAdd: Boolean, onAdd: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().thinCard(elevation = 4.dp).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(56.dp).acrylicFrame(ArtworkShape, strength = 0.55f)) {
            Box(Modifier.fillMaxSize().clip(ArtworkShape).background(coverGradient), contentAlignment = Alignment.Center) {
                Icon(PhosphorIcons.Light.VinylRecord, null, tint = Color.White)
            }
            AcrylicGloss(shape = ArtworkShape, alpha = 0.22f)
        }
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(name, style = MaterialTheme.typography.titleMedium, color = HushInk)
            Text("Your collection", color = HushMist, style = MaterialTheme.typography.bodyMedium)
        }
        if (showAdd) {
            TextButton(onClick = onAdd) { Text("Add", color = HushAccentA) }
        } else {
            Icon(PhosphorIcons.Light.Play, "Play", tint = HushMist, modifier = Modifier.pressScale(onClick = {}))
        }
    }
}

@Composable
private fun CreatePlaylistDialog(accentColor: Color, onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New playlist") },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true) },
        confirmButton = {
            Button(onClick = { onCreate(name) }, enabled = name.isNotBlank(), colors = ButtonDefaults.buttonColors(containerColor = accentColor)) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}