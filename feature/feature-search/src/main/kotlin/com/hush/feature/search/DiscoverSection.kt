package com.hush.feature.search

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import com.hush.core.designsystem.AcrylicHost
import com.hush.core.designsystem.AcrylicMaterials
import com.hush.core.designsystem.AcrylicSurface
import com.hush.core.designsystem.acrylicSource
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
import coil.compose.SubcomposeAsyncImage
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Light
import com.adamglin.phosphoricons.light.DotsThreeVertical
import com.adamglin.phosphoricons.light.MusicNote
import com.adamglin.phosphoricons.light.Play
import com.hush.core.designsystem.HushDisplayFont
import com.hush.core.designsystem.ink
import com.hush.core.designsystem.pressScale

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

@Composable
internal fun DiscoverContent(
    modifier: Modifier = Modifier,
    state: DiscoverState,
    onTrackClick: (com.hush.core.model.Track) -> Unit,
    onArtistClick: (Long) -> Unit,
    onAlbumClick: (DiscoverItem) -> Unit,
    /** Scroll state of the list, so the screen's floating header can frost up as content slides under it. */
    listState: LazyListState = rememberLazyListState(),
    /** Extra room at the top / bottom so content starts below the floating header and bar but scrolls under them. */
    contentPadding: PaddingValues = PaddingValues(),
    /** Scrolls with the list as its first item (title + search). Receives full screen width; pad it yourself. */
    header: @Composable () -> Unit = {},
) {
    var selected by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val albums = state.albums
    val artists = state.artists
    val tabs = state.tabs
    if (state.isLoading && tabs.isEmpty()) {
        Column(modifier.fillMaxSize()) {
            Box(Modifier.bleedSides(24.dp)) { header() }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = ink(), strokeWidth = 2.dp)
            }
        }
        return
    }
    if (tabs.isEmpty()) {
        Column(modifier.fillMaxSize()) {
            Box(Modifier.bleedSides(24.dp)) { header() }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(state.error ?: "Discover is unavailable", color = ink(0.65f))
            }
        }
        return
    }
    // The parent screen pads 24dp on both sides; Discover is allowed to use the full screen width, so the
    // card rows scroll edge to edge. Sections add their own 24dp padding back where they need it.
    LazyColumn(
        state = listState,
        modifier = modifier.bleedSides(24.dp),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding(),
            bottom = 20.dp + contentPadding.calculateBottomPadding(),
        ),
    ) {
        item(key = "header") { header() }
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
            }
        }

        // The vertical tabs sit down here, beside the list they switch, well below the card rows.
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                VerticalTabs(
                    labels = tabs.map { it.label },
                    selected = selected,
                    onSelect = { selected = it },
                    modifier = Modifier.padding(start = 10.dp),
                )
                Crossfade(
                    targetState = selected,
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
private fun AlbumCard(item: DiscoverItem, onTrackClick: (com.hush.core.model.Track) -> Unit, onAlbumClick: (DiscoverItem) -> Unit) {
    // Each card is its own little acrylic host: the play badge frosts this card's cover.
    AcrylicHost(
        Modifier
            .size(CardWidth, CardHeight)
            .pressScale(onClick = { item.track?.let(onTrackClick) ?: onAlbumClick(item) }, scaleDown = 0.97f)
            .shadow(14.dp, CardShape, clip = false, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(CardShape),
    ) {
        SubcomposeAsyncImage(
            model = item.imageUrl,
            contentDescription = null,
            modifier = Modifier.fillMaxSize().acrylicSource(),
            contentScale = ContentScale.Crop,
            loading = { CoverPlaceholder() },
            error = { CoverPlaceholder() },
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
        AcrylicSurface(
            Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 16.dp).size(38.dp),
            shape = CircleShape,
            material = AcrylicMaterials.UltraThin,
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
        SubcomposeAsyncImage(
            model = item.imageUrl,
            contentDescription = null,
            modifier = Modifier
                .size(92.dp)
                .shadow(8.dp, CircleShape, clip = false, ambientColor = Color.Black, spotColor = Color.Black)
                .clip(CircleShape),
            contentScale = ContentScale.Crop,
            loading = { CoverPlaceholder() },
            error = { CoverPlaceholder() },
        )
        Spacer(Modifier.height(10.dp))
        Text(
            item.title,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = HushDisplayFont, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp),
            color = ink(0.9f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun SongRow(item: DiscoverItem, onTrackClick: (com.hush.core.model.Track) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .pressScale(onClick = { item.track?.let(onTrackClick) }, scaleDown = 0.98f)
            .padding(start = 6.dp, end = 24.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SubcomposeAsyncImage(
            model = item.imageUrl,
            contentDescription = null,
            modifier = Modifier
                .size(58.dp)
                .shadow(4.dp, ThumbShape, clip = false, ambientColor = Color.Black, spotColor = Color.Black)
                .clip(ThumbShape),
            contentScale = ContentScale.Crop,
            loading = { CoverPlaceholder() },
            error = { CoverPlaceholder() },
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
private fun CoverPlaceholder() {
    Box(Modifier.fillMaxSize().background(ink(0.08f)), contentAlignment = Alignment.Center) {
        Icon(PhosphorIcons.Light.MusicNote, null, tint = ink(0.45f), modifier = Modifier.size(24.dp))
    }
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
    val track: com.hush.core.model.Track? = null,
    val artistId: Long? = null,
)

/** One tab of the list beside the vertical tabs: a heading and its rows. */
data class DiscoverTab(val label: String, val title: String, val items: List<DiscoverItem>)

/** Random but stable cover per seed. */
private fun cover(title: String, subtitle: String, seed: String) =
    DiscoverItem(id = "mock:$seed", title = title, subtitle = subtitle, imageUrl = "https://picsum.photos/seed/$seed/600/600")

internal val MockAlbums = listOf(
    cover("Slow Bloom", "Velvet Static", "hush-a1"),
    cover("Paper Moons", "Ada Lune", "hush-a2"),
    cover("Harbour Lights", "The Quiet Hours", "hush-a3"),
    cover("Glass Garden", "Noor", "hush-a4"),
    cover("Ninety Rooms", "Mira Vale", "hush-a5"),
)

internal val MockArtists = listOf(
    cover("Ada Lune", "Artist", "hush-r1"),
    cover("Velvet Static", "Artist", "hush-r2"),
    cover("Noor", "Artist", "hush-r3"),
    cover("Mira Vale", "Artist", "hush-r4"),
    cover("Pale Coast", "Artist", "hush-r5"),
    cover("Tomas Reyes", "Artist", "hush-r6"),
    cover("The Quiet Hours", "Artist", "hush-r7"),
)

internal val MockDiscoverTabs = listOf(
    DiscoverTab(
        label = "Top rated",
        title = "Top songs",
        items = listOf(
            cover("Afterglow Drive", "Ada Lune", "hush-s1"),
            cover("Soft Machinery", "Velvet Static", "hush-s2"),
            cover("Lantern Season", "Noor", "hush-s3"),
            cover("Half Asleep", "The Quiet Hours", "hush-s4"),
            cover("Salt & Silk", "Mira Vale", "hush-s5"),
        ),
    ),
    DiscoverTab(
        label = "Recent",
        title = "Played lately",
        items = listOf(
            cover("Slow Motion Rain", "Pale Coast", "hush-t1"),
            cover("Quiet Thunder", "Tomas Reyes", "hush-t2"),
            cover("Borrowed Light", "Ada Lune", "hush-t3"),
            cover("Fade to Blue", "Velvet Static", "hush-t4"),
            cover("Night Bus Poems", "Tomas Reyes", "hush-t5"),
        ),
    ),
    DiscoverTab(
        label = "Playlists",
        title = "Your playlists",
        items = listOf(
            cover("Late Night Drive", "24 songs", "hush-p1"),
            cover("Rainy Window", "18 songs", "hush-p2"),
            cover("Deep Focus", "40 songs", "hush-p3"),
            cover("Sunday Slow", "31 songs", "hush-p4"),
            cover("Daily Mix 1", "Ada Lune, Noor and more", "hush-p5"),
        ),
    ),
)