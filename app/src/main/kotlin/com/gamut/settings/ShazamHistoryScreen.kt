package com.gamut.settings

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gamut.core.designsystem.ArtImage
import com.gamut.core.designsystem.GamutMotion
import com.gamut.core.designsystem.TablerIcons
import com.gamut.core.designsystem.ink
import com.gamut.core.designsystem.pressScale
import com.gamut.core.designsystem.squareArtUrl
import com.gamut.core.designsystem.thumbnailBorder
import com.gamut.core.model.Track
import com.gamut.feature.search.ShazamHistoryEntry

private val ArtSize = 52.dp
private val RowGap = 14.dp
private val TextInset = ArtSize + RowGap

/**
 * Every song Shazam has identified, newest first. Laid out like Recently Played: a plain list straight on the
 * page under a big title. Tapping a row plays it; the cross takes it off the list.
 */
@Composable
fun ShazamHistoryScreen(
    entries: List<ShazamHistoryEntry>,
    onBack: () -> Unit,
    onPlay: (Track) -> Unit,
    onRemove: (ShazamHistoryEntry) -> Unit,
    onClear: () -> Unit,
    nowPlaying: @Composable () -> Unit,
) {
    SettingsScaffold(title = "", onBack = onBack, nowPlaying = nowPlaying) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 28.dp, end = 12.dp, top = 4.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(
                    "Shazam",
                    style = MaterialTheme.typography.headlineLarge,
                    color = ink(),
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                if (entries.isNotEmpty()) {
                    TextButton(onClick = onClear) { Text("Clear all", color = ink(0.7f)) }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (entries.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        EmptyNote(TablerIcons.BrandShazam, "Songs you identify with Shazam will be saved here.")
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 24.dp)) {
                        item(key = "count", contentType = "count") {
                            Row(
                                Modifier.padding(start = 4.dp, top = 2.dp, bottom = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(TablerIcons.BrandShazam, null, tint = ink(0.45f), modifier = Modifier.size(16.dp))
                                Text(
                                    if (entries.size == 1) "1 song" else "${entries.size} songs",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = ink(0.45f),
                                    modifier = Modifier.padding(start = 6.dp),
                                )
                            }
                        }
                        itemsIndexed(
                            items = entries,
                            key = { _, e -> "${e.atMs}-${e.track.isrc}" },
                            contentType = { _, _ -> "shazam-row" },
                        ) { index, entry ->
                            Column(
                                Modifier.animateItem(
                                    fadeInSpec = null,
                                    placementSpec = GamutMotion.itemPlacement,
                                    fadeOutSpec = null,
                                ),
                            ) {
                                ShazamRow(entry, onPlay = { onPlay(entry.track) }, onRemove = { onRemove(entry) })
                                if (index < entries.lastIndex) GroupedDivider(start = TextInset)
                            }
                        }
                        item(contentType = "bottom-space") { Spacer(Modifier.navigationBarsPadding().height(16.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ShazamRow(
    entry: ShazamHistoryEntry,
    onPlay: () -> Unit,
    onRemove: () -> Unit,
) {
    val track = entry.track
    val whenText =
        remember(entry.atMs) {
            DateUtils.getRelativeTimeSpanString(entry.atMs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
        }
    Row(
        Modifier.fillMaxWidth().pressScale(onClick = onPlay, scaleDown = 0.985f).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtImage(
            url = remember(track.albumArtUrl) { squareArtUrl(track.albumArtUrl, 200) },
            modifier = Modifier.size(ArtSize).clip(RoundedCornerShape(10.dp)).thumbnailBorder(RoundedCornerShape(10.dp)),
        )
        Column(Modifier.weight(1f).padding(start = RowGap)) {
            Text(
                track.title,
                style = MaterialTheme.typography.bodyLarge,
                color = ink(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                track.artist,
                style = MaterialTheme.typography.bodyMedium,
                color = ink(0.55f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(whenText, style = MaterialTheme.typography.bodySmall, color = ink(0.4f), maxLines = 1)
        }
        Box(Modifier.size(44.dp).pressScale(onClick = onRemove, scaleDown = 0.88f), contentAlignment = Alignment.Center) {
            Icon(TablerIcons.Close, "Remove from history", tint = ink(0.5f), modifier = Modifier.size(20.dp))
        }
    }
}
