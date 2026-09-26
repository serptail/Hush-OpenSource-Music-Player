package com.hush.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBackIosNew
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hush.core.designsystem.ArtworkShape
import com.hush.core.designsystem.HushAccentA
import com.hush.core.designsystem.HushInk
import com.hush.core.designsystem.HushMist
import com.hush.core.designsystem.HushPaper
import com.hush.core.designsystem.thinCard
import com.hush.core.model.Track

@Composable
fun LibraryScreen(viewModel: LibraryViewModel, selectedTrack: Track?, onBack: () -> Unit, onPlayer: () -> Unit) {
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    var showCreate by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(HushPaper).padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 22.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBackIosNew, "Back", tint = HushInk) }
            Text("Your library", style = MaterialTheme.typography.headlineLarge, color = HushInk, modifier = Modifier.weight(1f).padding(start = 8.dp))
            IconButton(onClick = onPlayer) { Icon(Icons.Default.MusicNote, "Now playing", tint = HushAccentA) }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 16.dp).thinCard().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Make room for favorites", style = MaterialTheme.typography.titleLarge, color = HushInk)
                Text("Playlists that feel like you", color = HushMist)
            }
            IconButton(onClick = { showCreate = true }) { Icon(Icons.Default.Add, "Create playlist", tint = HushAccentA) }
        }
        Text("Playlists", style = MaterialTheme.typography.titleLarge, color = HushInk, modifier = Modifier.padding(top = 26.dp, bottom = 8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(playlists, key = { it.id }) { playlist ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(52.dp).clip(ArtworkShape).background(HushAccentA.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Default.LibraryMusic, null, tint = HushAccentA)
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                        Text(playlist.name, style = MaterialTheme.typography.titleMedium, color = HushInk)
                        Text("Your collection", color = HushMist, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (selectedTrack != null) {
                        TextButton(onClick = { viewModel.addTrack(playlist.id, selectedTrack) }) { Text("Add", color = HushAccentA) }
                    } else {
                        Icon(Icons.Default.PlayArrow, "Play", tint = HushMist)
                    }
                }
            }
        }
        if (selectedTrack != null) {
            Text("Ready to add: ${selectedTrack.title}", color = HushMist, modifier = Modifier.padding(vertical = 14.dp))
        }
    }
    if (showCreate) {
        CreatePlaylistDialog(onDismiss = { showCreate = false }, onCreate = { viewModel.createPlaylist(it); showCreate = false })
    }
}

@Composable
private fun CreatePlaylistDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New playlist") },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true) },
        confirmButton = {
            Button(onClick = { onCreate(name) }, enabled = name.isNotBlank(), colors = ButtonDefaults.buttonColors(containerColor = HushAccentA)) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}