package com.gamut.onboarding.importer

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.gamut.R
import com.gamut.core.designsystem.LocalCoverPalette
import com.gamut.core.designsystem.ink
import com.gamut.onboarding.applemusic.AppleMusicLinks
import com.gamut.onboarding.spotify.SpotifyLinks
import com.gamut.onboarding.ytmusic.YtMusicLinks

private const val SPOTIFY_DATA_URL = "https://www.spotify.com/account/privacy/"

@Composable
fun SpotifyImportSheet(
    visible: Boolean,
    busy: Boolean,
    onImportLinks: (String) -> Unit,
    onImportFiles: (List<Uri>) -> Unit,
    onDismiss: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val files =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) onImportFiles(uris)
        }
    LinkImportSheet(
        visible = visible,
        busy = busy,
        logo = {
            Image(
                painterResource(R.drawable.ic_spotify_full),
                contentDescription = "Spotify",
                modifier = Modifier.weight(1f).height(28.dp),
                alignment = Alignment.CenterStart,
                contentScale = ContentScale.Fit,
            )
        },
        placeholder = "https://open.spotify.com/playlist/\u2026",
        hint = "Public playlists and albums only. One link per line, or paste several at once.",
        countLinks = { SpotifyLinks.parse(it).size + SpotifyLinks.shortLinks(it).size },
        onImport = onImportLinks,
        onDismiss = onDismiss,
        footer = {
            TextLink("Choose data files") { files.launch(arrayOf("*/*")) }
            TextLink("Where to find it") { uriHandler.openUri(SPOTIFY_DATA_URL) }
        },
    )
}

@Composable
fun YouTubeMusicImportSheet(
    visible: Boolean,
    busy: Boolean,
    onImportLinks: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val light = LocalCoverPalette.current.isLight
    LinkImportSheet(
        visible = visible,
        busy = busy,
        logo = {
            Image(
                painterResource(if (light) R.drawable.ic_ytmusic_full else R.drawable.ic_ytmusic_full_dark),
                contentDescription = "YouTube Music",
                modifier = Modifier.weight(1f).height(28.dp),
                alignment = Alignment.CenterStart,
                contentScale = ContentScale.Fit,
            )
        },
        placeholder = "https://music.youtube.com/playlist?list=\u2026",
        hint = "Public or unlisted playlists, no sign-in needed. One link per line, or paste several at once.",
        countLinks = { YtMusicLinks.parse(it).size },
        onImport = onImportLinks,
        onDismiss = onDismiss,
    )
}

@Composable
fun AppleMusicImportSheet(
    visible: Boolean,
    busy: Boolean,
    onImportLinks: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    LinkImportSheet(
        visible = visible,
        busy = busy,
        logo = {
            Icon(
                painterResource(R.drawable.ic_applemusic_full),
                contentDescription = "Apple Music",
                tint = ink(0.9f),
                modifier = Modifier.height(20.dp),
            )
        },
        placeholder = "https://music.apple.com/us/playlist/\u2026",
        hint = "Public playlists and albums only. One link per line, or paste several at once.",
        countLinks = { AppleMusicLinks.parse(it).size },
        onImport = onImportLinks,
        onDismiss = onDismiss,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LinkImportSheet(
    visible: Boolean,
    busy: Boolean,
    logo: @Composable RowScope.() -> Unit,
    placeholder: String,
    hint: String,
    countLinks: (String) -> Int,
    onImport: (String) -> Unit,
    onDismiss: () -> Unit,
    footer: @Composable RowScope.() -> Unit = {},
) {
    if (!visible) return
    val clipboard = LocalClipboardManager.current
    var text by rememberSaveable { mutableStateOf("") }
    val count = remember(text) { countLinks(text) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = LocalCoverPalette.current.base,
        dragHandle = { BottomSheetDefaults.DragHandle(color = ink(0.25f)) },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) { logo() }
            Spacer(Modifier.height(16.dp))
            LinkField(text, onChange = { text = it }, placeholder = placeholder)
            Spacer(Modifier.height(8.dp))
            Text(hint, style = MaterialTheme.typography.bodySmall, color = ink(0.5f))
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        busy -> "Another import is running"
                        count == 0 -> "No links yet"
                        count == 1 -> "1 link ready"
                        else -> "$count links ready"
                    },
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = ink(if (count > 0 && !busy) 0.95f else 0.5f),
                    modifier = Modifier.weight(1f),
                )
                Pill("Paste") {
                    clipboard.getText()?.text?.let { pasted -> text = if (text.isBlank()) pasted else text + "\n" + pasted }
                }
            }
            Spacer(Modifier.height(16.dp))
            ImportButton(
                label = if (count > 1) "Import $count links" else "Import",
                enabled = count > 0 && !busy,
                onClick = { onImport(text) },
            )
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { footer() }
        }
    }
}

@Composable
private fun LinkField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
) {
    val shape = RoundedCornerShape(18.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 96.dp)
            .clip(shape)
            .background(ink(0.08f))
            .padding(16.dp),
    ) {
        if (value.isEmpty()) {
            Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = ink(0.35f))
        }
        BasicTextField(
            value = value,
            onValueChange = onChange,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = ink()),
            cursorBrush = SolidColor(ink()),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Default),
            maxLines = 5,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ImportButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val onPrimary = if (LocalCoverPalette.current.isLight) Color.White else Color.Black
    Box(
        Modifier
            .fillMaxWidth()
            .height(54.dp)
            .clip(CircleShape)
            .background(ink(if (enabled) 0.95f else 0.12f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = if (enabled) onPrimary else ink(0.4f),
            maxLines = 1,
        )
    }
}

@Composable
private fun Pill(
    label: String,
    onClick: () -> Unit,
) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
        color = ink(0.9f),
        maxLines = 1,
        modifier =
            Modifier
                .clip(CircleShape)
                .background(ink(0.12f))
                .clickable(onClick = onClick)
                .padding(horizontal = 18.dp, vertical = 9.dp),
    )
}

@Composable
private fun TextLink(
    label: String,
    onClick: () -> Unit,
) {
    Text(
        label,
        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
        color = ink(0.75f),
        maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(vertical = 8.dp, horizontal = 4.dp),
    )
}
