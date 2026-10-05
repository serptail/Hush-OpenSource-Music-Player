package com.gamut.feature.library

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.gamut.core.designsystem.BottomBarItem
import com.gamut.core.designsystem.CoverTheme
import com.gamut.core.designsystem.GamutBottomBar
import com.gamut.core.designsystem.LocalCoverPalette
import com.gamut.core.designsystem.LocalNotifier
import com.gamut.core.designsystem.TransparentStatusBar
import com.gamut.core.designsystem.fadeEdges
import com.gamut.core.designsystem.ink
import com.gamut.core.designsystem.pressScale
import com.gamut.core.model.Track

private val TextPrimary: Color
    @Composable @ReadOnlyComposable get() = ink()
private val TextSecondary: Color
    @Composable @ReadOnlyComposable get() = ink(0.6f)

private val PanelShape = RoundedCornerShape(12.dp)
private val ButtonShape = RoundedCornerShape(8.dp)

/**
 * Library: Liked songs and your playlists.
 * Tapping one calls [onOpenPlaylist] (navigation lives in the NavHost); Liked songs passes the id "liked".
 */
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    /** The track picked in search that is waiting to be added to a playlist, or null. */
    selectedTrack: Track?,
    onOpenPlaylist: (String) -> Unit,
    /** The "+" button: opens the New playlist screen (navigation lives in the NavHost). */
    onCreatePlaylist: () -> Unit,
    /** Height of the shared bottom bar + mini player strip (owned by HomeTabs in MainActivity). */
    barHeight: androidx.compose.ui.unit.Dp,
) {
    val userPlaylists by viewModel.playlists.collectAsStateWithLifecycle()
    // Count only: collecting the full favorites list here mapped every liked track just to show a number.
    val favoriteCount by viewModel.favoriteCount.collectAsStateWithLifecycle()
    val playlistInfo by viewModel.playlistInfo.collectAsStateWithLifecycle()
    val notifier = LocalNotifier.current

    val palette = LocalCoverPalette.current
    // Status bar icons are set by HomeTabs (only while this layer is visible).

    CoverTheme(palette) {
        val accent = MaterialTheme.colorScheme.primary
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().statusBarsPadding().padding(bottom = barHeight)) {
                Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp)) {
                    Row(Modifier.fillMaxWidth().padding(top = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Library",
                            style = MaterialTheme.typography.displayLarge,
                            color = TextPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        Box(Modifier.size(44.dp).pressScale(onClick = onCreatePlaylist), contentAlignment = Alignment.Center) {
                            Icon(PhosphorIcons.Light.Plus, "Create playlist", tint = ink(0.85f), modifier = Modifier.size(24.dp))
                        }
                    }

                    LazyColumn(
                        modifier = Modifier.weight(1f).fadeEdges(),
                        contentPadding = PaddingValues(top = 22.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        item(key = "liked") {
                            LibraryRow(
                                title = "Liked songs",
                                subtitle = songCountLabel(favoriteCount),
                                onClick = { onOpenPlaylist("liked") },
                                cover = { RowCover(null, PhosphorIcons.Fill.Heart) },
                            )
                        }

                        item(key = "playlists-header") { SectionTitle("Your playlists") }
                        items(userPlaylists, key = { "v-${it.id}" }, contentType = { "playlist" }) { playlist ->
                            LibraryRow(
                                title = playlist.name,
                                subtitle = songCountLabel(playlistInfo[playlist.id]?.songCount ?: 0),
                                onClick = { onOpenPlaylist(playlist.id.toString()) },
                                // A cover the user picked always wins over the art of the last song.
                                cover = { RowCover(playlist.coverUri ?: playlistInfo[playlist.id]?.coverUrl, PhosphorIcons.Light.VinylRecord) },
                                trailing = {
                                    if (selectedTrack != null) {
                                        Text(
                                            "Add",
                                            style = MaterialTheme.typography.labelLarge,
                                            color = TextPrimary,
                                            modifier = Modifier
                                                .pressScale(onClick = { viewModel.addTrack(playlist.id, selectedTrack); notifier.success("Added to ${playlist.name}") })
                                                .background(ink(0.12f), ButtonShape)
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
                                .background(ink(0.07f), PanelShape)
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
            }
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

/**
 * The cover slot of a Library row: the cover of the last song added when there is one, otherwise JUST the icon with
 * nothing behind it. (The old tinted tile sat on top of softCover's drop shadow, which showed through the
 * translucent fill as a dark inner box.)
 */
@Composable
private fun RowCover(coverUrl: String?, icon: ImageVector) {
    if (!coverUrl.isNullOrBlank()) {
        CoverImage(remember(coverUrl) { resolveCoverUrl(coverUrl, 200) }, Modifier.size(58.dp).softCover())
    } else {
        Box(Modifier.size(58.dp), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = ink(0.8f), modifier = Modifier.size(28.dp))
        }
    }
}

private fun songCountLabel(n: Int) = when (n) {
    0 -> "Empty"
    1 -> "1 song"
    else -> "$n songs"
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