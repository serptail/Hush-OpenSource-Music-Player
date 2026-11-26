package com.gamut.feature.search

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gamut.core.designsystem.ArtImage
import com.gamut.core.designsystem.ExplicitMark
import com.gamut.core.designsystem.GamutSheet
import com.gamut.core.designsystem.LocalCoverPalette
import com.gamut.core.designsystem.TablerIcons
import com.gamut.core.designsystem.ink
import com.gamut.core.designsystem.squareArtUrl
import com.gamut.core.designsystem.thumbnailBorder
import com.gamut.core.model.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** How many songs the popup lists before it rounds off with "+N more". */
private const val PREVIEW_ROWS = 10
private val RowArtShape = RoundedCornerShape(8.dp)

/**
 * The popup behind a mood, mix or radio card: the same card as on Discover in the middle, the things to do with it, and
 * what it holds (the first few songs, loading in as they arrive). Tapping a song plays the station from that song.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StationPreviewSheet(
    item: DiscoverItem,
    cardIndex: Int,
    busy: Boolean,
    loadTracks: suspend (DiscoverItem) -> List<Track>,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    onPlayTrack: (Track, List<Track>) -> Unit,
    onAddToFavorites: () -> Unit,
    onAddToPlaylist: () -> Unit,
) {
    var tracks by remember(item.id) { mutableStateOf<List<Track>?>(null) }
    var failed by remember(item.id) { mutableStateOf(false) }
    LaunchedEffect(item.id) {
        failed = false
        tracks =
            try {
                loadTracks(item).also { if (it.isEmpty()) failed = true }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                failed = true
                emptyList()
            }
    }

    GamutSheet(onDismiss = onDismiss, shape = SheetShape) {
        val loaded = tracks
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 640.dp),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 12.dp),
        ) {
            item(key = "cover") { PreviewCover(item, cardIndex) }
            item(key = "actions") {
                PreviewActions(
                    item = item,
                    enabled = !failed,
                    busy = busy,
                    onPlay = onPlay,
                    onAddToFavorites = onAddToFavorites,
                    onAddToPlaylist = onAddToPlaylist,
                )
            }
            item(key = "label") {
                Text(
                    "In this ${if (StationId.kindOf(item.id) == StationId.MOOD) "mood" else "station"}",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.4.sp),
                    color = ink(0.55f),
                    modifier = Modifier.padding(top = 22.dp, bottom = 6.dp, start = 4.dp),
                )
            }
            when {
                loaded == null -> {
                    items(6) { PreviewSkeletonRow() }
                }

                failed -> {
                    item(key = "failed") {
                        Text(
                            "Couldn’t load the songs. Check your connection and try again.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = ink(0.6f),
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 16.dp),
                        )
                    }
                }

                else -> {
                    val shown = loaded.take(PREVIEW_ROWS)
                    itemsIndexed(shown, key = { i, t -> "p-$i-${t.isrc}" }) { index, track ->
                        PreviewRow(track, index) { onPlayTrack(track, loaded.drop(index) + loaded.take(index)) }
                    }
                    if (loaded.size > shown.size) {
                        item(key = "more") {
                            Text(
                                "+ ${loaded.size - shown.size} more songs",
                                style = MaterialTheme.typography.labelLarge,
                                color = ink(0.5f),
                                modifier = Modifier.padding(start = 4.dp, top = 12.dp),
                            )
                        }
                    }
                }
            }
            item(key = "nav-gap") { Spacer(Modifier.navigationBarsPadding().height(8.dp)) }
        }
    }
}

/** The station's own card, drawn by the same code as on Discover, centred. */
@Composable
private fun PreviewCover(
    item: DiscoverItem,
    cardIndex: Int,
) {
    Box(Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.Center) {
        val mood = if (StationId.kindOf(item.id) == StationId.MOOD) Mood.bySlug(item.id.substringAfterLast(':')) else null
        if (mood != null) {
            MoodCard(
                label = item.title,
                colors = Color(mood.from) to Color(mood.to),
                cover = item.imageUrl.takeIf { it.isNotBlank() },
            )
        } else {
            StationCardBody(item, cardIndex)
        }
    }
}

@Composable
private fun PreviewActions(
    item: DiscoverItem,
    enabled: Boolean,
    busy: Boolean,
    onPlay: () -> Unit,
    onAddToFavorites: () -> Unit,
    onAddToPlaylist: () -> Unit,
) {
    // The theme's own pill: its text colour as the fill, its background colour for the label, so it reads on any theme.
    val onPill = LocalCoverPalette.current.base
    Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier
                .weight(1f)
                .height(52.dp)
                .clip(CircleShape)
                .background(ink(if (enabled) 1f else 0.4f))
                .bounceClick { if (enabled) onPlay() },
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(TablerIcons.Play, null, tint = onPill, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Play", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = onPill)
        }
        Spacer(Modifier.width(10.dp))
        RoundAction(TablerIcons.Heart, "Add songs to favorites", enabled && !busy, busy, onAddToFavorites)
        Spacer(Modifier.width(10.dp))
        RoundAction(TablerIcons.ListPlus, "Add songs to playlist", enabled && !busy, false, onAddToPlaylist)
    }
}

@Composable
private fun RoundAction(
    icon: ImageVector,
    description: String,
    enabled: Boolean,
    working: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(ink(0.08f))
            .bounceClick { if (enabled) onClick() },
        contentAlignment = Alignment.Center,
    ) {
        if (working) {
            CircularProgressIndicator(Modifier.size(20.dp), color = ink(), strokeWidth = 2.dp)
        } else {
            Icon(icon, description, tint = ink(if (enabled) 1f else 0.35f), modifier = Modifier.size(22.dp))
        }
    }
}

/** One song of the preview. Rows slide up and fade in one after another, so the list settles instead of popping in. */
@Composable
private fun PreviewRow(
    track: Track,
    index: Int,
    onClick: () -> Unit,
) {
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(index * 35L)
        reveal.animateTo(1f, tween(280, easing = FastOutSlowInEasing))
    }
    Row(
        Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = reveal.value
                translationY = (1f - reveal.value) * 14.dp.toPx()
            }.bounceClick(onClick)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtImage(
            url = remember(track.albumArtUrl) { squareArtUrl(track.albumArtUrl, 160) },
            modifier = Modifier.size(46.dp).clip(RowArtShape).thumbnailBorder(RowArtShape),
            fallback = { Icon(TablerIcons.MusicNote, null, tint = ink(0.5f)) },
        )
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ExplicitMark(track.isExplicit, ink(0.6f))
                Text(
                    track.title,
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                    color = ink(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            Text(
                track.artist,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                color = ink(0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A row-shaped placeholder that breathes while the songs load. */
@Composable
private fun PreviewSkeletonRow() {
    val pulse by rememberInfiniteTransition(label = "preview-skeleton").animateFloat(
        initialValue = 0.05f,
        targetValue = 0.12f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label = "preview-skeleton-alpha",
    )
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(46.dp).clip(RowArtShape).background(ink(pulse)))
        Column(Modifier.padding(start = 14.dp)) {
            Box(Modifier.width(150.dp).height(13.dp).clip(CircleShape).background(ink(pulse)))
            Spacer(Modifier.height(8.dp))
            Box(Modifier.width(96.dp).height(11.dp).clip(CircleShape).background(ink(pulse)))
        }
    }
}
