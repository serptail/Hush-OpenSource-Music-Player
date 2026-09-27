package com.hush.feature.search

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.SubcomposeAsyncImage
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Light
import com.adamglin.phosphoricons.light.VinylRecord
import com.adamglin.phosphoricons.light.MagnifyingGlass
import com.adamglin.phosphoricons.light.MusicNote
import com.adamglin.phosphoricons.light.User
import com.adamglin.phosphoricons.light.X
import com.hush.core.designsystem.ArtworkShape
import com.hush.core.designsystem.HushAccentA
import com.hush.core.designsystem.HushInk
import com.hush.core.designsystem.HushMist
import com.hush.core.designsystem.HushPaper
import com.hush.core.designsystem.PillShape
import com.hush.core.designsystem.acrylicFrame
import com.hush.core.designsystem.hairlineBorder
import com.hush.core.designsystem.pressScale
import com.hush.core.designsystem.thinCard
import com.hush.core.model.Track

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    resolvingIsrc: String?,
    resolutionMessage: String?,
    onTrackSelected: (Track) -> Unit,
    onLibrary: () -> Unit,
    onPlayer: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().background(HushPaper).padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 22.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Good evening", style = MaterialTheme.typography.bodyMedium, color = HushMist)
                Text("Find your quiet", style = MaterialTheme.typography.headlineLarge, color = HushInk, fontWeight = FontWeight.SemiBold)
            }
            IconButton(onClick = {}) { Icon(PhosphorIcons.Light.User, "Profile", tint = HushInk) }
        }
        Spacer(Modifier.height(18.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(PillShape)
                .background(Color.White, PillShape)
                .hairlineBorder(PillShape)
                .padding(horizontal = 16.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(PhosphorIcons.Light.MagnifyingGlass, null, tint = HushMist)
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::updateQuery,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Artists, songs, albums", color = HushMist) },
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = HushInk),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = Color.Transparent,
                    focusedBorderColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedContainerColor = Color.Transparent,
                    cursorColor = HushAccentA,
                ),
            )
            if (state.query.isNotEmpty()) {
                IconButton(onClick = { viewModel.updateQuery("") }) { Icon(PhosphorIcons.Light.X, "Clear", tint = HushMist) }
            }
        }
        Spacer(Modifier.height(20.dp))
        AnimatedVisibility(state.isLoading) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), color = HushAccentA, strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Tuning the room...", color = HushMist)
            }
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp)) }
        resolutionMessage?.let {
            Text(it, color = HushMist, modifier = Modifier.padding(bottom = 8.dp))
        }
        if (state.tracks.isEmpty() && state.query.isBlank()) {
            EmptySearchState()
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                item { Text("Results", style = MaterialTheme.typography.titleLarge, color = HushInk, modifier = Modifier.padding(vertical = 8.dp)) }
                items(state.tracks, key = { it.isrc }) { track ->
                    TrackResultRow(track, resolvingIsrc == track.isrc, onTrackSelected)
                }
            }
        }
        BottomNavigation(onLibrary = onLibrary, onPlayer = onPlayer)
    }
}

@Composable
private fun EmptySearchState() {
    Column(Modifier.fillMaxWidth().padding(top = 54.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(84.dp).clip(CircleShape).background(HushAccentA.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
            Icon(PhosphorIcons.Light.MusicNote, null, tint = HushAccentA, modifier = Modifier.size(36.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text("A softer soundtrack", style = MaterialTheme.typography.titleLarge, color = HushInk)
        Text("Search for something that feels like now.", color = HushMist)
    }
}

@Composable
private fun TrackResultRow(track: Track, isResolving: Boolean, onTrackSelected: (Track) -> Unit) {
    Row(
        Modifier.fillMaxWidth().pressScale(onClick = { onTrackSelected(track) }, scaleDown = 0.98f).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SubcomposeAsyncImage(
            model = track.albumArtUrl,
            contentDescription = null,
            modifier = Modifier.size(52.dp).acrylicFrame(ArtworkShape, strength = 0.4f),
            contentScale = ContentScale.Crop,
            loading = { Icon(PhosphorIcons.Light.MusicNote, null, tint = HushMist) },
            error = { Icon(PhosphorIcons.Light.MusicNote, null, tint = HushMist) },
        )
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(track.title, style = MaterialTheme.typography.titleMedium, color = HushInk, maxLines = 1)
            Text(track.artist, style = MaterialTheme.typography.bodyMedium, color = HushMist, maxLines = 1)
            if (isResolving) Text("Checking a verified source", style = MaterialTheme.typography.labelMedium, color = HushAccentA)
        }
        if (isResolving) CircularProgressIndicator(Modifier.size(16.dp), color = HushAccentA, strokeWidth = 2.dp)
    }
}

@Composable
private fun BottomNavigation(onLibrary: () -> Unit, onPlayer: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(PhosphorIcons.Light.MagnifyingGlass, null, tint = HushAccentA)
            Text("Discover", color = HushAccentA, style = MaterialTheme.typography.labelMedium)
        }
        Column(Modifier.pressScale(onClick = onLibrary), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(PhosphorIcons.Light.VinylRecord, null, tint = HushMist)
            Text("Library", color = HushMist, style = MaterialTheme.typography.labelMedium)
        }
        Column(Modifier.pressScale(onClick = onPlayer), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(PhosphorIcons.Light.MusicNote, null, tint = HushMist)
            Text("Now playing", color = HushMist, style = MaterialTheme.typography.labelMedium)
        }
    }
}