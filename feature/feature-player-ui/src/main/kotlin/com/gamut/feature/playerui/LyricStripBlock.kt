package com.gamut.feature.playerui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
internal fun LyricStripBlock(
    lyricsOn: Boolean,
    lyricsState: LyricsState,
    flowLines: List<FlowLine>?,
    trackKey: String,
    playhead: FlowPlayhead,
    isPlaying: Boolean,
    durationMs: Long,
    onOpen: () -> Unit,
    highlight: Color,
    modifier: Modifier = Modifier,
) {
    val loaded = lyricsState as? LyricsState.Loaded
    if (!lyricsOn || loaded == null || !loaded.isSynced || flowLines.isNullOrEmpty()) return
    Box(modifier.fillMaxWidth().height(56.dp), contentAlignment = Alignment.Center) {
        FlowLyricStrip(
            lines = flowLines,
            trackKey = trackKey,
            playhead = playhead,
            isPlaying = isPlaying,
            durationMs = durationMs,
            onClick = onOpen,
            maxLines = 2,
            centered = true,
            highlight = highlight,
        )
    }
}
