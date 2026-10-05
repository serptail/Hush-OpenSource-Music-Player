package com.gamut.feature.search

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import com.gamut.core.designsystem.AcrylicHost
import com.gamut.core.designsystem.AcrylicMaterials
import com.gamut.core.designsystem.AcrylicSurface
import com.gamut.core.designsystem.acrylicSource
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gamut.core.designsystem.ArtImage
import androidx.compose.foundation.border
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Light
import com.adamglin.phosphoricons.light.DotsThreeVertical
import com.adamglin.phosphoricons.light.MusicNote
import com.adamglin.phosphoricons.light.Play
import com.gamut.core.designsystem.GamutDisplayFont
import com.gamut.core.designsystem.ink
import com.gamut.core.designsystem.pressScale

// ---------------------------------------------------------------------------------------------
//  Discover (shown on the search screen while the query is empty)
//
//  MOCKUP: everything below the UI code is placeholder data with random picsum.photos covers.
//  To wire real data later, replace MockDiscoverTabs with state from the ViewModel; the composables
//  only need a list of DiscoverTab.
// ---------------------------------------------------------------------------------------------

private val CardShape = RoundedCornerShape(30.dp)
private val ThumbShape = RoundedCornerShape(14.dp)
private val CardWidth = 228.dp
private val CardHeight = 272.dp

/**
 * The Discover sections as items of the CALLER's LazyColumn (they add their own 24dp side padding, the list itself is
 * full width). The search screen puts its title + search field as the first item of that same list in every mode, so the
 * field is never rebuilt when Discover gives way to results (a different list used to drop its focus after one letter).
 */
internal fun LazyListScope.discoverItems(
    state: DiscoverState,
    selectedTab: Int,
    onSelectTab: (Int) -> Unit,
    onTrackClick: (com.gamut.core.model.Track) -> Unit,
    onArtistClick: (Long) -> Unit,
    onAlbumClick: (DiscoverItem) -> Unit,
) {
    val albums = state.albums
    val artists = state.artists
    val tabs = state.tabs
    if (tabs.isEmpty()) {
        item(key = "discover-status") {
            Box(Modifier.fillParentMaxWidth().height(260.dp), contentAlignment = Alignment.Center) {
                if (state.isLoading) CircularProgressIndicator(color = ink(), strokeWidth = 2.dp)
                else Text(state.error ?: "Discover is unavailable", color = ink(0.65f))
            }
        }
        return
    }
    item { SectionTitle("Top albums") }
    item {
        LazyRow(
            contentPadding = PaddingValues(start = 24.dp, top = 14.dp, end = 24.dp, bottom = 26.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(albums, key = { it.id }) { AlbumCard(it, onTrackClick, onAlbumClick) }
        }
    }

    item { SectionTitle("Artists in top songs") }
    item {
        LazyRow(
            contentPadding = PaddingValues(start = 24.dp, top = 14.dp, end = 24.dp, bottom = 26.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            items(artists, key = { it.id }) { ArtistItem(it, onArtistClick) }
            // Portraits arrive after the charts: hold the row's space with quiet circles until the first lands.
            if (artists.isEmpty() && state.error == null) {
                items(6) { Box(Modifier.size(92.dp).clip(CircleShape).background(ink(0.08f))) }
            }
        }
    }

    // The vertical tabs sit down here, beside the list they switch, well below the card rows.
    item {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            VerticalTabs(
                labels = tabs.map { it.label },
                selected = selectedTab,
                onSelect = onSelectTab,
                modifier = Modifier.padding(start = 10.dp),
            )
            Crossfade(
                targetState = selectedTab,
                modifier = Modifier.weight(1f),
                animationSpec = tween(260),
                label = "discover-tab",
            ) { index ->
                Column(Modifier.fillMaxWidth()) {
                    SectionTitle(tabs[index].title, startPadding = 6.dp)
                    Spacer(Modifier.height(6.dp))
                    tabs[index].items.forEach { SongRow(it, onTrackClick) }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String, startPadding: Dp = 24.dp) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        color = ink(),
        modifier = Modifier.padding(start = startPadding, end = 24.dp),
    )
}

/** Big rounded cover card: art fills it, dark fade at the bottom carries the title, small play badge. */
@Composable
private fun AlbumCard(item: DiscoverItem, onTrackClick: (com.gamut.core.model.Track) -> Unit, onAlbumClick: (DiscoverItem) -> Unit) {
    // No per-card acrylic host any more: each card used to register a blur source and run a live blur pass just for
    // the 38dp play badge. A translucent badge with a hairline looks the same on a cover and costs nothing per frame.
    Box(
        Modifier
            .size(CardWidth, CardHeight)
            .pressScale(onClick = { item.track?.let(onTrackClick) ?: onAlbumClick(item) }, scaleDown = 0.97f)
            .shadow(14.dp, CardShape, clip = false, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(CardShape),
    ) {
        ArtImage(
            url = item.imageUrl,
            modifier = Modifier.fillMaxSize(),
            fallback = { CoverFallbackIcon() },
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.78f)),
            ),
        )
        Column(Modifier.align(Alignment.BottomStart).padding(start = 20.dp, bottom = 20.dp, end = 64.dp)) {
            Text(
                item.title,
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                item.subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.75f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 16.dp)
                .size(38.dp)
                .background(Color.White.copy(alpha = 0.20f), CircleShape)
                .border(0.75.dp, Color.White.copy(alpha = 0.35f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(PhosphorIcons.Light.Play, "Play", tint = Color.White, modifier = Modifier.size(18.dp))
        }
    }
}

/** Round artist portrait with the name underneath. */
@Composable
private fun ArtistItem(item: DiscoverItem, onArtistClick: (Long) -> Unit) {
    Column(
        Modifier.width(92.dp).pressScale(onClick = { item.artistId?.let(onArtistClick) }, scaleDown = 0.96f),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ArtImage(
            url = item.imageUrl,
            modifier = Modifier
                .size(92.dp)
                .shadow(8.dp, CircleShape, clip = false, ambientColor = Color.Black, spotColor = Color.Black)
                .clip(CircleShape),
            fallback = { CoverFallbackIcon() },
        )
        Spacer(Modifier.height(10.dp))
        Text(
            item.title,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = GamutDisplayFont, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp),
            color = ink(0.9f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun SongRow(item: DiscoverItem, onTrackClick: (com.gamut.core.model.Track) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .pressScale(onClick = { item.track?.let(onTrackClick) }, scaleDown = 0.98f)
            .padding(start = 6.dp, end = 24.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtImage(
            url = item.imageUrl,
            modifier = Modifier
                .size(58.dp)
                .shadow(4.dp, ThumbShape, clip = false, ambientColor = Color.Black, spotColor = Color.Black)
                .clip(ThumbShape),
            fallback = { CoverFallbackIcon() },
        )
        Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            Text(
                item.title,
                style = MaterialTheme.typography.titleMedium,
                color = ink(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                item.subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = ink(0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(PhosphorIcons.Light.DotsThreeVertical, null, tint = ink(0.5f), modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun CoverFallbackIcon() {
    Icon(PhosphorIcons.Light.MusicNote, null, tint = ink(0.45f), modifier = Modifier.size(24.dp))
}

/** Rotated tab labels down the left edge; the active one is solid ink with a small dot beside it. */
@Composable
private fun VerticalTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.width(38.dp),
        verticalArrangement = Arrangement.spacedBy(30.dp, Alignment.CenterVertically),
    ) {
        labels.forEachIndexed { index, label ->
            val active = index == selected
            val color by animateColorAsState(if (active) ink() else ink(0.35f), tween(220), label = "tab-color")
            val dot by animateColorAsState(if (active) ink() else Color.Transparent, tween(220), label = "tab-dot")
            Row(
                Modifier.pressScale(onClick = { onSelect(index) }, scaleDown = 0.94f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(5.dp).background(dot, CircleShape))
                Spacer(Modifier.width(7.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.titleMedium,
                    color = color,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.verticalText(),
                )
            }
        }
    }
}

/** Rotates content -90 degrees and swaps its measured width and height so it takes the rotated space. */
private fun Modifier.verticalText(): Modifier = this
    .layout { measurable, _ ->
        val p = measurable.measure(Constraints())
        layout(p.height, p.width) { p.place((p.height - p.width) / 2, (p.width - p.height) / 2) }
    }
    .rotate(-90f)

/** Lets a child extend [amount] past the parent's left and right edges (Discover runs full screen width). */
private fun Modifier.bleedSides(amount: Dp): Modifier = layout { measurable, constraints ->
    val extra = amount.roundToPx()
    val width = constraints.maxWidth + extra * 2
    val p = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
    layout(constraints.maxWidth, p.height) { p.place(-extra, 0) }
}

// ------------------------------------------ MOCK DATA ------------------------------------------

data class DiscoverItem(
    val id: String = "",
    val title: String,
    val subtitle: String,
    val imageUrl: String,
    val track: com.gamut.core.model.Track? = null,
    val artistId: Long? = null,
)

/** One tab of the list beside the vertical tabs: a heading and its rows. */
data class DiscoverTab(val label: String, val title: String, val items: List<DiscoverItem>)

/** Random but stable cover per seed. */
private fun cover(title: String, subtitle: String, seed: String) =
    DiscoverItem(id = "mock:$seed", title = title, subtitle = subtitle, imageUrl = "https://picsum.photos/seed/$seed/600/600")

internal val MockAlbums = listOf(
    cover("Slow Bloom", "Velvet Static", "gamut-a1"),
    cover("Paper Moons", "Ada Lune", "gamut-a2"),
    cover("Harbour Lights", "The Quiet Hours", "gamut-a3"),
    cover("Glass Garden", "Noor", "gamut-a4"),
    cover("Ninety Rooms", "Mira Vale", "gamut-a5"),
)

internal val MockArtists = listOf(
    cover("Ada Lune", "Artist", "gamut-r1"),
    cover("Velvet Static", "Artist", "gamut-r2"),
    cover("Noor", "Artist", "gamut-r3"),
    cover("Mira Vale", "Artist", "gamut-r4"),
    cover("Pale Coast", "Artist", "gamut-r5"),
    cover("Tomas Reyes", "Artist", "gamut-r6"),
    cover("The Quiet Hours", "Artist", "gamut-r7"),
)

internal val MockDiscoverTabs = listOf(
    DiscoverTab(
        label = "Top rated",
        title = "Top songs",
        items = listOf(
            cover("Afterglow Drive", "Ada Lune", "gamut-s1"),
            cover("Soft Machinery", "Velvet Static", "gamut-s2"),
            cover("Lantern Season", "Noor", "gamut-s3"),
            cover("Half Asleep", "The Quiet Hours", "gamut-s4"),
            cover("Salt & Silk", "Mira Vale", "gamut-s5"),
        ),
    ),
    DiscoverTab(
        label = "Recent",
        title = "Played lately",
        items = listOf(
            cover("Slow Motion Rain", "Pale Coast", "gamut-t1"),
            cover("Quiet Thunder", "Tomas Reyes", "gamut-t2"),
            cover("Borrowed Light", "Ada Lune", "gamut-t3"),
            cover("Fade to Blue", "Velvet Static", "gamut-t4"),
            cover("Night Bus Poems", "Tomas Reyes", "gamut-t5"),
        ),
    ),
    DiscoverTab(
        label = "Playlists",
        title = "Your playlists",
        items = listOf(
            cover("Late Night Drive", "24 songs", "gamut-p1"),
            cover("Rainy Window", "18 songs", "gamut-p2"),
            cover("Deep Focus", "40 songs", "gamut-p3"),
            cover("Sunday Slow", "31 songs", "gamut-p4"),
            cover("Daily Mix 1", "Ada Lune, Noor and more", "gamut-p5"),
        ),
    ),
)