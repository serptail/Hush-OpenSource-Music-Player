package com.hush.feature.library

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.Light
import com.adamglin.phosphoricons.fill.BookmarkSimple
import com.adamglin.phosphoricons.light.ArrowLeft
import com.adamglin.phosphoricons.light.BookmarkSimple
import com.adamglin.phosphoricons.light.ShareNetwork
import com.adamglin.phosphoricons.light.Shuffle
import com.hush.core.designsystem.CoverTheme
import com.hush.core.designsystem.LocalCoverPalette
import com.hush.core.designsystem.TransparentStatusBar
import com.hush.core.designsystem.VerticalTabs
import com.hush.core.designsystem.fadeEdges
import com.hush.core.designsystem.glass
import com.hush.core.designsystem.ink
import com.hush.core.designsystem.pressScale

private val Tabs = listOf("Overview", "Songs", "Albums", "Singles", "Library")
private val PillShape = RoundedCornerShape(20.dp)

/** Artist page: back + name on top, a vertical tab rail on the left, circular portrait on Overview. */
@Composable
fun ArtistScreen(
    artistId: String,
    onBack: () -> Unit,
    onPlayer: () -> Unit = {},
    nowPlaying: @Composable () -> Unit = {},
) {
    val artist = LibraryMock.artist(artistId)
    val palette = LocalCoverPalette.current
    TransparentStatusBar(darkIcons = palette.isLight)

    CoverTheme(palette) {
        if (artist == null) {
            Column(Modifier.fillMaxSize().statusBarsPadding().padding(24.dp)) {
                BackButton(onBack)
                Text("Artist not found", style = MaterialTheme.typography.titleLarge, color = ink(), modifier = Modifier.padding(top = 16.dp))
            }
            return@CoverTheme
        }

        var tab by rememberSaveable { mutableIntStateOf(0) }
        val following = artist.id in LibraryMock.followedIds

        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 24.dp, top = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BackButton(onBack)
                Text(
                    artist.name,
                    style = MaterialTheme.typography.displayMedium,
                    color = ink(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f).padding(start = 12.dp),
                )
            }

            Row(Modifier.weight(1f).fillMaxWidth()) {
                VerticalTabs(
                    labels = Tabs,
                    selected = tab,
                    onSelect = { tab = it },
                    modifier = Modifier.fillMaxHeight().padding(start = 12.dp),
                )

                Column(Modifier.weight(1f).padding(start = 12.dp, end = 24.dp)) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            Modifier.pressScale(onClick = onPlayer).glass(PillShape).padding(horizontal = 16.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(PhosphorIcons.Light.Shuffle, null, tint = ink(), modifier = Modifier.size(16.dp))
                            Text(
                                "Shuffle",
                                style = MaterialTheme.typography.labelLarge,
                                color = ink(),
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                        Box(Modifier.weight(1f))
                        Box(
                            Modifier.size(44.dp).pressScale(onClick = { LibraryMock.toggleFollow(artist.id) }),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                if (following) PhosphorIcons.Fill.BookmarkSimple else PhosphorIcons.Light.BookmarkSimple,
                                if (following) "Unfollow" else "Follow",
                                tint = ink(if (following) 1f else 0.8f),
                                modifier = Modifier.size(22.dp),
                            )
                        }
                        Box(Modifier.size(44.dp).pressScale(onClick = {}), contentAlignment = Alignment.Center) {
                            Icon(PhosphorIcons.Light.ShareNetwork, "Share", tint = ink(0.8f), modifier = Modifier.size(22.dp))
                        }
                    }

                    Crossfade(tab, label = "artist-tab", modifier = Modifier.weight(1f)) { current ->
                        when (current) {
                            0 -> Overview(artist, onViewAllSongs = { tab = 1 }, onSong = onPlayer)
                            1 -> SongList(artist.songs, onPlayer)
                            2 -> AlbumGrid(artist.albums)
                            3 -> SongList(artist.singles, onPlayer)
                            else -> SongList(artist.inLibrary, onPlayer)
                        }
                    }
                }
            }

            nowPlaying()
        }
    }
}

@Composable
private fun BackButton(onBack: () -> Unit) {
    Box(Modifier.size(44.dp).pressScale(onClick = onBack), contentAlignment = Alignment.Center) {
        Icon(PhosphorIcons.Light.ArrowLeft, "Back", tint = ink(0.85f), modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun Overview(artist: LibArtist, onViewAllSongs: () -> Unit, onSong: () -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize().fadeEdges(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            Column(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                CoverImage(artist.imageUrl, Modifier.fillMaxWidth(0.92f).aspectRatio(1f).softCover(CircleShape))
                Text(
                    "${artist.listeners} monthly listeners",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ink(0.6f),
                    modifier = Modifier.padding(top = 14.dp),
                )
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Songs", style = MaterialTheme.typography.titleLarge, color = ink(), modifier = Modifier.weight(1f))
                Text(
                    "View all",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ink(0.6f),
                    modifier = Modifier.pressScale(onClick = onViewAllSongs).padding(vertical = 6.dp),
                )
            }
        }
        items(artist.songs.take(4), key = { it.id }) { song -> SongRow(song, onClick = onSong) }
        item {
            Text(
                "Albums",
                style = MaterialTheme.typography.titleLarge,
                color = ink(),
                modifier = Modifier.padding(top = 24.dp, bottom = 12.dp),
            )
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                items(artist.albums, key = { it.id }) { album -> AlbumCard(album, Modifier.width(130.dp)) }
            }
        }
    }
}

@Composable
private fun SongList(songs: List<LibSong>, onSong: () -> Unit) {
    if (songs.isEmpty()) {
        Text("Nothing here yet.", style = MaterialTheme.typography.bodyMedium, color = ink(0.6f), modifier = Modifier.padding(top = 16.dp))
        return
    }
    LazyColumn(Modifier.fillMaxSize().fadeEdges(), contentPadding = PaddingValues(bottom = 24.dp)) {
        items(songs, key = { it.id }) { song -> SongRow(song, onClick = onSong) }
    }
}

@Composable
private fun AlbumGrid(albums: List<LibAlbum>) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize().fadeEdges(),
        contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        items(albums, key = { it.id }) { album -> AlbumCard(album, Modifier.fillMaxWidth()) }
    }
}

@Composable
private fun AlbumCard(album: LibAlbum, modifier: Modifier = Modifier) {
    Column(modifier.pressScale(onClick = {}, scaleDown = 0.97f)) {
        CoverImage(album.imageUrl, Modifier.fillMaxWidth().aspectRatio(1f).softCover())
        Text(
            album.title,
            style = MaterialTheme.typography.titleMedium,
            color = ink(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(album.year, style = MaterialTheme.typography.bodyMedium, color = ink(0.6f))
    }
}