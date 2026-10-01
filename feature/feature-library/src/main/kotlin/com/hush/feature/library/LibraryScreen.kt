package com.hush.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.Light
import com.adamglin.phosphoricons.fill.Heart
import com.adamglin.phosphoricons.light.CaretRight
import com.adamglin.phosphoricons.light.MagnifyingGlass
import com.adamglin.phosphoricons.light.MusicNote
import com.adamglin.phosphoricons.light.Plus
import com.adamglin.phosphoricons.light.VinylRecord
import com.hush.core.designsystem.BottomBarItem
import com.hush.core.designsystem.CoverTheme
import com.hush.core.designsystem.HushBottomBar
import com.hush.core.designsystem.LocalCoverPalette
import com.hush.core.designsystem.TransparentStatusBar
import com.hush.core.designsystem.fadeEdges
import com.hush.core.designsystem.glass
import com.hush.core.designsystem.ink
import com.hush.core.designsystem.pressScale
import com.hush.core.model.Track

private val TextPrimary: Color
    @Composable @ReadOnlyComposable get() = ink()
private val TextSecondary: Color
    @Composable @ReadOnlyComposable get() = ink(0.6f)

private val PanelShape = RoundedCornerShape(12.dp)
private val ButtonShape = RoundedCornerShape(8.dp)

/**
 * Library: Liked songs, the artists you follow, and your playlists.
 * Tapping a playlist or artist calls [onOpenPlaylist] / [onOpenArtist] (navigation lives in the NavHost).
 */
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    /** The track picked in search that is waiting to be added to a playlist, or null. */
    selectedTrack: Track?,
    /** Back to Discover (the search screen). */
    onBack: () -> Unit,
    nowPlaying: @Composable () -> Unit = {},
    onOpenPlaylist: (String) -> Unit,
    onOpenArtist: (String) -> Unit,
) {
    val userPlaylists by viewModel.playlists.collectAsStateWithLifecycle()
    var showCreate by remember { mutableStateOf(false) }

    val palette = LocalCoverPalette.current
    TransparentStatusBar(darkIcons = palette.isLight)

    val liked = LibraryMock.playlists.firstOrNull { it.isLiked }
    val mockPlaylists = LibraryMock.playlists.filter { !it.isLiked }
    val followed = LibraryMock.followedIds.mapNotNull { LibraryMock.artist(it) }

    CoverTheme(palette) {
        val accent = MaterialTheme.colorScheme.primary
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp)) {
                    Row(Modifier.fillMaxWidth().padding(top = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Library",
                            style = MaterialTheme.typography.displayLarge,
                            color = TextPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        Box(Modifier.size(44.dp).pressScale(onClick = { showCreate = true }), contentAlignment = Alignment.Center) {
                            Icon(PhosphorIcons.Light.Plus, "Create playlist", tint = ink(0.85f), modifier = Modifier.size(24.dp))
                        }
                    }

                    LazyColumn(
                        modifier = Modifier.weight(1f).fadeEdges(),
                        contentPadding = PaddingValues(top = 22.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        if (liked != null) {
                            item(key = "liked") {
                                LibraryRow(
                                    title = liked.name,
                                    subtitle = "${liked.songs.size} songs",
                                    onClick = { onOpenPlaylist(liked.id) },
                                    cover = { TileCover(PhosphorIcons.Fill.Heart) },
                                )
                            }
                        }

                        if (followed.isNotEmpty()) {
                            item(key = "followed-header") { SectionTitle("Artists you follow") }
                            item(key = "followed-row") {
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                    items(followed, key = { it.id }) { artist ->
                                        Column(
                                            Modifier.width(76.dp).pressScale(onClick = { onOpenArtist(artist.id) }),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                        ) {
                                            CoverImage(artist.imageUrl, Modifier.size(68.dp).softCover(CircleShape))
                                            Text(
                                                artist.name,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = TextPrimary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                textAlign = TextAlign.Center,
                                                modifier = Modifier.padding(top = 8.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        item(key = "playlists-header") { SectionTitle("Your playlists") }
                        items(mockPlaylists, key = { "m-${it.id}" }) { playlist ->
                            LibraryRow(
                                title = playlist.name,
                                subtitle = "${playlist.songs.size} songs",
                                onClick = { onOpenPlaylist(playlist.id) },
                                cover = {
                                    val first = playlist.songs.firstOrNull()
                                    if (first != null) CoverImage(first.imageUrl, Modifier.size(58.dp).softCover())
                                    else TileCover(PhosphorIcons.Light.VinylRecord)
                                },
                            )
                        }
                        // Playlists created in-app (view model). They open as an empty playlist until real data exists.
                        items(userPlaylists, key = { "v-${it.id}" }) { playlist ->
                            LibraryRow(
                                title = playlist.name,
                                subtitle = "Your collection",
                                onClick = { onOpenPlaylist(playlist.id.toString()) },
                                cover = { TileCover(PhosphorIcons.Light.VinylRecord) },
                                trailing = {
                                    if (selectedTrack != null) {
                                        Text(
                                            "Add",
                                            style = MaterialTheme.typography.labelLarge,
                                            color = TextPrimary,
                                            modifier = Modifier
                                                .pressScale(onClick = { viewModel.addTrack(playlist.id, selectedTrack) })
                                                .glass(ButtonShape)
                                                .padding(horizontal = 16.dp, vertical = 8.dp),
                                        )
                                    } else {
                                        ChevronIcon()
                                    }
                                },
                            )
                        }
                    }

                    if (selectedTrack != null) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp)
                                .glass(PanelShape)
                                .padding(horizontal = 18.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(PhosphorIcons.Light.MusicNote, null, tint = ink(0.6f), modifier = Modifier.size(18.dp))
                            Text(
                                "Ready to add: ${selectedTrack.title}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 10.dp),
                            )
                        }
                    }
                }

                HushBottomBar(
                    listOf(
                        BottomBarItem("Discover", PhosphorIcons.Light.MagnifyingGlass, onClick = onBack),
                        BottomBarItem("Library", PhosphorIcons.Light.VinylRecord, selected = true),
                    ),
                    nowPlaying = nowPlaying,
                )
            }
        }
        if (showCreate) {
            CreatePlaylistDialog(
                accent = accent,
                isLight = palette.isLight,
                onDismiss = { showCreate = false },
                onCreate = { viewModel.createPlaylist(it); showCreate = false },
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        color = TextPrimary,
        modifier = Modifier.padding(top = 22.dp, bottom = 10.dp),
    )
}

@Composable
private fun ChevronIcon() {
    Icon(PhosphorIcons.Light.CaretRight, null, tint = ink(0.45f), modifier = Modifier.size(18.dp))
}

/** Flat tinted square with a centered icon, for collections that have no cover (Liked songs, empty playlists). */
@Composable
private fun TileCover(icon: ImageVector) {
    Box(
        Modifier.size(58.dp).softCover().background(ink(0.10f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = ink(0.7f), modifier = Modifier.size(24.dp))
    }
}

/** Flat list row: cover, title, quiet subtitle, trailing chevron (or a custom trailing action). */
@Composable
private fun LibraryRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    cover: @Composable () -> Unit,
    trailing: @Composable () -> Unit = { ChevronIcon() },
) {
    Row(
        Modifier.fillMaxWidth().pressScale(onClick = onClick, scaleDown = 0.98f).padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        cover()
        Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = TextSecondary, maxLines = 1)
        }
        trailing()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreatePlaylistDialog(accent: Color, isLight: Boolean, onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    // The dialog sits above the flow, so it gets a solid neutral surface that matches the cover's light/dark.
    val container = if (isLight) Color(0xFFF3F3F3) else Color(0xFF1C1C1C)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = container,
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
        shape = PanelShape,
        title = { Text("New playlist") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                shape = RoundedCornerShape(8.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    focusedBorderColor = accent.copy(alpha = 0.7f),
                    unfocusedBorderColor = ink(0.2f),
                    focusedLabelColor = TextSecondary,
                    unfocusedLabelColor = ink(0.4f),
                    cursorColor = accent,
                ),
            )
        },
        confirmButton = {
            Button(
                onClick = { onCreate(name) },
                enabled = name.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ink(),
                    contentColor = container,
                    disabledContainerColor = ink(0.12f),
                    disabledContentColor = ink(0.3f),
                ),
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = ink(0.7f)) } },
    )
}