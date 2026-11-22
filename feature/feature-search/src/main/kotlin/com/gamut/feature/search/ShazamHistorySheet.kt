package com.gamut.feature.search

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.gamut.core.designsystem.GamutSheet
import com.gamut.core.designsystem.TablerIcons
import com.gamut.core.designsystem.ink
import com.gamut.core.designsystem.pressScale
import com.gamut.core.designsystem.thumbnailBorder
import com.gamut.core.model.Track

/**
 * Every song Shazam has identified, newest first. Tapping a row plays it; the cross takes it off the list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShazamHistorySheet(
    entries: List<ShazamHistoryEntry>,
    onDismiss: () -> Unit,
    onPlay: (Track) -> Unit,
    onRemove: (ShazamHistoryEntry) -> Unit,
    onClear: () -> Unit,
) {
    GamutSheet(onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 4.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Shazam history",
                    style = MaterialTheme.typography.titleLarge,
                    color = ink(),
                    modifier = Modifier.weight(1f),
                )
                if (entries.isNotEmpty()) {
                    TextButton(onClick = onClear) { Text("Clear all", color = ink(0.7f)) }
                }
            }
            if (entries.isEmpty()) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(TablerIcons.History, null, tint = ink(0.4f), modifier = Modifier.size(40.dp))
                    Text(
                        "No songs yet",
                        style = MaterialTheme.typography.titleMedium,
                        color = ink(),
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    Text(
                        "Songs you identify with Shazam will be saved here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ink(0.6f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            } else {
                LazyColumn(Modifier.heightIn(max = 520.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(entries, key = { "${it.atMs}-${it.track.isrc}" }) { entry ->
                        HistoryRow(entry, onPlay = { onPlay(entry.track) }, onRemove = { onRemove(entry) })
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(
    entry: ShazamHistoryEntry,
    onPlay: () -> Unit,
    onRemove: () -> Unit,
) {
    val track = entry.track
    val whenText = DateUtils.getRelativeTimeSpanString(entry.atMs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
    Row(
        Modifier.fillMaxWidth().pressScale(onClick = onPlay, scaleDown = 0.98f).padding(start = 24.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = track.albumArtUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)).background(ink(0.08f)).thumbnailBorder(RoundedCornerShape(10.dp)),
        )
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(track.title, style = MaterialTheme.typography.titleMedium, color = ink(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track.artist, style = MaterialTheme.typography.bodyMedium, color = ink(0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(whenText, style = MaterialTheme.typography.bodySmall, color = ink(0.5f), maxLines = 1)
        }
        Box(Modifier.size(44.dp).pressScale(onClick = onRemove, scaleDown = 0.88f), contentAlignment = Alignment.Center) {
            Icon(TablerIcons.Close, "Remove from history", tint = ink(0.5f), modifier = Modifier.size(20.dp))
        }
    }
}
