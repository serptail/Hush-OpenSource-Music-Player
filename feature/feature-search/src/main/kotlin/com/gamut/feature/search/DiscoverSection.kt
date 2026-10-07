package com.gamut.feature.search

import android.content.Intent
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.foundation.lazy.items
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import com.gamut.core.designsystem.LocalCoverPalette
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gamut.core.designsystem.ArtImage
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.ripple
import com.adamglin.phosphoricons.light.Shuffle
import com.gamut.core.model.Track
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

private val CardShape = RoundedCornerShape(22.dp)
private val ThumbShape = RoundedCornerShape(14.dp)
private val RowShape = RoundedCornerShape(18.dp)
private val CardWidth = 200.dp

/**
 * The Discover sections as items of the CALLER's LazyColumn (they add their own 24dp side padding, the list itself is
 * full width). The search screen puts its title + search field as the first item of that same list in every mode, so the
 * field is never rebuilt when Discover gives way to results (a different list used to drop its focus after one letter).
 */
internal fun LazyListScope.discoverItems(
    state: DiscoverState,
    selectedTab: Int,
    onSelectTab: (Int) -> Unit,
    /** The tapped track plus the list it came from (that list becomes the queue). */
    onTrackClick: (Track, List<Track>) -> Unit,
    /** No longer used: the three dots now open their own menu. Safe to remove here and at the call site. */
    onMoreClick: (Track) -> Unit = {},
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
                else Text(
                    state.error ?: "Discover is unavailable right now. Check your connection and try again.",
                    color = ink(0.6f),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 40.dp),
                )
            }
        }
        return
    }
    item { SectionTitle("Top albums") }
    item {
        LazyRow(
            contentPadding = PaddingValues(start = 24.dp, top = 14.dp, end = 24.dp, bottom = 34.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(albums, key = { it.id }) { AlbumCard(it, onAlbumClick) }
        }
    }

    item { SectionTitle("Artists in top songs") }
    item {
        LazyRow(
            contentPadding = PaddingValues(start = 24.dp, top = 14.dp, end = 24.dp, bottom = 34.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            items(artists, key = { it.id }) { ArtistItem(it, onArtistClick) }
            // Portraits arrive after the charts: hold the row's space with quiet circles until the first lands.
            if (artists.isEmpty() && state.error == null) {
                items(6) { Box(Modifier.size(96.dp).clip(CircleShape).background(ink(0.08f))) }
            }
        }
    }

    // The vertical tabs sit down here, beside the list they switch, well below the card rows.
    item {
        // Top-aligned: the rail used to be centred against the whole list, so it drifted further down the more rows a
        // tab had (Billboard / Trending) and ended up off-screen. Now it always starts level with the tab's heading.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            VerticalTabs(
                labels = tabs.map { it.label },
                selected = selectedTab,
                onSelect = onSelectTab,
                modifier = Modifier.padding(start = 10.dp, top = 6.dp),
            )
            Crossfade(
                targetState = selectedTab,
                modifier = Modifier.weight(1f),
                animationSpec = tween(260),
                label = "discover-tab",
            ) { index ->
                val tab = tabs[index.coerceIn(0, tabs.lastIndex)]
                val queue = remember(tab) { tab.items.mapNotNull { it.track } }
                val context = LocalContext.current
                Column(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(end = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SectionTitle(tab.title, startPadding = 6.dp, endPadding = 12.dp, modifier = Modifier.weight(1f))
                        if (queue.isNotEmpty()) {
                            PlayControls(
                                onPlay = { onTrackClick(queue.first(), queue) },
                                onShuffle = { queue.shuffled().let { onTrackClick(it.first(), it) } },
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    tab.items.forEachIndexed { index, item ->
                        val track = item.track
                        // Album/playlist rows have no track to act on, so they get no menu (and no dots).
                        val actions = if (track == null) emptyList() else buildList {
                            add(RowAction("Play") { onTrackClick(track, listOf(track)) })
                            // Plays this song, then carries on down the list.
                            add(RowAction("Play from here") {
                                val from = queue.indexOf(track).coerceAtLeast(0)
                                onTrackClick(track, queue.drop(from))
                            })
                            item.artistId?.let { id -> add(RowAction("Go to artist") { onArtistClick(id) }) }
                            add(RowAction("Share") {
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, "${item.title} \u2013 ${item.subtitle}")
                                }
                                context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            })
                        }
                        SongRow(
                            item = item,
                            onClick = { track?.let { onTrackClick(it, queue) } ?: onAlbumClick(item) },
                            actions = actions,
                            // Charts are ranked; playlists are not.
                            index = if (queue.isNotEmpty()) index + 1 else null,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(
    text: String,
    startPadding: Dp = 24.dp,
    endPadding: Dp = 24.dp,
    modifier: Modifier = Modifier,
) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge.copy(
            fontFamily = GamutDisplayFont,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 24.sp,
            letterSpacing = (-0.5).sp,
        ),
        color = ink(),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.padding(start = startPadding, end = endPadding),
    )
}

/** Clean cover card: just the rounded cover, with the title and artist underneath. No overlay, no gradient. */
@Composable
private fun AlbumCard(item: DiscoverItem, onAlbumClick: (DiscoverItem) -> Unit) {
    Column(Modifier.width(CardWidth).pressScale(onClick = { onAlbumClick(item) }, scaleDown = 0.97f)) {
        ArtImage(
            url = item.imageUrl,
            modifier = Modifier
                .size(CardWidth)
                .shadow(18.dp, CardShape, clip = false, ambientColor = Color.Black.copy(alpha = 0.28f), spotColor = Color.Black.copy(alpha = 0.40f))
                .clip(CardShape),
            fallback = { CoverFallbackIcon() },
        )
        Spacer(Modifier.height(14.dp))
        Text(
            item.title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = ink(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Text(
            item.subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = ink(0.6f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

/** Round artist portrait with the name underneath. */
@Composable
private fun ArtistItem(item: DiscoverItem, onArtistClick: (Long) -> Unit) {
    Column(
        Modifier.width(96.dp).pressScale(onClick = { item.artistId?.let(onArtistClick) }, scaleDown = 0.96f),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ArtImage(
            url = item.imageUrl,
            modifier = Modifier
                .size(96.dp)
                .shadow(10.dp, CircleShape, clip = false, ambientColor = Color.Black.copy(alpha = 0.25f), spotColor = Color.Black.copy(alpha = 0.35f))
                .clip(CircleShape)
                .border(1.dp, ink(0.12f), CircleShape),
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

/** Play (solid) and Shuffle (quiet) as compact round buttons, sitting at the right end of the list heading. */
@Composable
private fun PlayControls(onPlay: () -> Unit, onShuffle: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        CircleControl(PhosphorIcons.Light.Shuffle, "Shuffle", filled = false, onClick = onShuffle)
        CircleControl(PhosphorIcons.Light.Play, "Play", filled = true, onClick = onPlay)
    }
}

@Composable
private fun CircleControl(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    filled: Boolean,
    onClick: () -> Unit,
) {
    val isLight = LocalCoverPalette.current.isLight
    // Filled: text color as the fill, its inverse as the glyph, so it holds contrast on every cover palette.
    val glyph = if (filled) (if (isLight) Color.White else Color.Black) else ink()
    Box(
        Modifier
            .pressScale(onClick = onClick, scaleDown = 0.92f)
            .size(44.dp)
            .clip(CircleShape)
            .background(if (filled) ink() else ink(0.10f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = glyph, modifier = Modifier.size(20.dp))
    }
}

/** An action in a song row's three-dots menu. */
private class RowAction(val label: String, val onClick: () -> Unit)

/**
 * One chart row. Hover fades in a soft tint; press squeezes the row and springs it back (the bounce). The three dots
 * are their own tap target and open a menu right here, so they work without the caller having to host anything.
 * Rows with no [actions] show no dots.
 */
@Composable
private fun SongRow(item: DiscoverItem, onClick: () -> Unit, actions: List<RowAction>, index: Int? = null) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "song-scale",
    )
    val tint by animateColorAsState(
        targetValue = ink(if (pressed) 0.10f else if (hovered) 0.06f else 0f),
        animationSpec = tween(140),
        label = "song-tint",
    )
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(end = 12.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RowShape)
            .background(tint)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(start = 6.dp, end = 4.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (index != null) {
            Text(
                index.toString(),
                style = MaterialTheme.typography.bodyMedium,
                color = ink(0.35f),
                modifier = Modifier.width(22.dp),
            )
        }
        ArtImage(
            url = item.imageUrl,
            modifier = Modifier
                .size(54.dp)
                .shadow(6.dp, ThumbShape, clip = false, ambientColor = Color.Black.copy(alpha = 0.20f), spotColor = Color.Black.copy(alpha = 0.30f))
                .clip(ThumbShape),
            fallback = { CoverFallbackIcon() },
        )
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(
                item.title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium),
                color = ink(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(item.subtitle, style = MaterialTheme.typography.bodyMedium, color = ink(0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (actions.isNotEmpty()) {
            // The menu is a child of this Box so it anchors to the dots.
            Box {
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(color = ink()),
                            onClickLabel = "More options",
                        ) { menuOpen = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(PhosphorIcons.Light.DotsThreeVertical, "More options", tint = ink(0.6f), modifier = Modifier.size(22.dp))
                }
                RowMenu(expanded = menuOpen, actions = actions, onDismiss = { menuOpen = false })
            }
        }
    }
}

private val MenuShape = RoundedCornerShape(18.dp)

/**
 * The three-dots menu. A themed popup instead of the stock DropdownMenu, which paints its own white Material surface:
 * this one follows the cover palette (dark surface on dark covers, light on light) and uses the same ink for text.
 */
@Composable
private fun RowMenu(expanded: Boolean, actions: List<RowAction>, onDismiss: () -> Unit) {
    if (!expanded) return
    val isLight = LocalCoverPalette.current.isLight
    val container = if (isLight) Color.White else Color(0xFF26262B)
    // Opens just below the dots, right-aligned to them.
    val below = with(LocalDensity.current) { 46.dp.roundToPx() }
    Popup(
        alignment = Alignment.TopEnd,
        offset = IntOffset(0, below),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            Modifier
                .width(208.dp)
                .shadow(24.dp, MenuShape, ambientColor = Color.Black.copy(alpha = 0.30f), spotColor = Color.Black.copy(alpha = 0.45f))
                .clip(MenuShape)
                .background(container)
                .border(1.dp, ink(0.08f), MenuShape)
                .padding(vertical = 6.dp),
        ) {
            actions.forEach { action ->
                Text(
                    action.label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = ink(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(color = ink()),
                        ) {
                            onDismiss()
                            action.onClick()
                        }
                        .padding(horizontal = 18.dp, vertical = 13.dp),
                )
            }
        }
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
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
    ) {
        labels.forEachIndexed { index, label ->
            val active = index == selected
            val color by animateColorAsState(if (active) ink() else ink(0.35f), tween(220), label = "tab-color")
            val dot by animateColorAsState(if (active) ink() else Color.Transparent, tween(220), label = "tab-dot")
            Row(
                Modifier.pressScale(onClick = { onSelect(index) }, scaleDown = 0.94f).padding(vertical = 8.dp),
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