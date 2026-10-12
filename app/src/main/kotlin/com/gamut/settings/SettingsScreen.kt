package com.gamut.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Light
import com.adamglin.phosphoricons.light.CaretRight
import com.adamglin.phosphoricons.light.ClockCounterClockwise
import com.adamglin.phosphoricons.light.HardDrives
import com.adamglin.phosphoricons.light.Heart
import com.adamglin.phosphoricons.light.Info
import com.adamglin.phosphoricons.light.Palette
import com.adamglin.phosphoricons.light.Playlist
import com.adamglin.phosphoricons.light.Question
import com.adamglin.phosphoricons.light.User
import com.adamglin.phosphoricons.light.Waveform
import com.gamut.account.AccountViewModel
import com.gamut.core.designsystem.LocalNotifier
import com.gamut.core.designsystem.ProfileAvatar
import com.gamut.core.designsystem.ink
import com.gamut.core.designsystem.pressScale

/** Opened from the profile circle on Discover. Flat rows under big headings; rows without a screen say "Coming soon". */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onLikedSongs: () -> Unit,
    onPlaylists: () -> Unit,
    onRecentlyPlayed: () -> Unit,
    onProfile: () -> Unit,
    onAppearance: () -> Unit,
    onPlaybackAudio: () -> Unit,
    onStorage: () -> Unit,
    onFeedback: () -> Unit,
    onAbout: () -> Unit,
    nowPlaying: @Composable () -> Unit,
    vm: AccountViewModel = hiltViewModel(),
) {
    val notifier = LocalNotifier.current
    val soon: () -> Unit = { notifier.info("Coming soon") }
    val userId by vm.userId.collectAsState()
    val name by vm.displayName.collectAsState()
    val email by vm.email.collectAsState()
    val avatar by vm.avatar.collectAsState()

    GamutPage(title = "Settings", onBack = onBack, nowPlaying = nowPlaying) {
        // Profile header: no container, just the row.
        Row(
            Modifier.fillMaxWidth().padding(top = 20.dp).pressScale(onClick = onProfile, scaleDown = 0.985f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProfileAvatar(name = name, seed = userId, size = 60.dp, guestIcon = PhosphorIcons.Light.User, image = avatar)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleLarge, color = ink(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(email ?: "View profile", style = MaterialTheme.typography.bodyMedium, color = ink(0.55f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(PhosphorIcons.Light.CaretRight, null, tint = ink(0.45f), modifier = Modifier.size(18.dp))
        }

        GroupLabel("Your music")
        Cell("Liked Songs", icon = PhosphorIcons.Light.Heart, onClick = onLikedSongs)
        Cell("Your Playlists", icon = PhosphorIcons.Light.Playlist, onClick = onPlaylists)
        Cell("Recently Played", icon = PhosphorIcons.Light.ClockCounterClockwise, onClick = onRecentlyPlayed)

        GroupLabel("Preferences")
        Cell("Appearance", icon = PhosphorIcons.Light.Palette, subtitle = "Theme, colors and glass", onClick = onAppearance)
        Cell("Playback & Audio", icon = PhosphorIcons.Light.Waveform, subtitle = "Quality, equalizer, fades and more", onClick = onPlaybackAudio)
        Cell("Storage & Downloads", icon = PhosphorIcons.Light.HardDrives, subtitle = "Cache and offline songs", onClick = onStorage)

        GroupLabel("Support")
        Cell("Help & Feedback", icon = PhosphorIcons.Light.Question, subtitle = "Report a bug or share an idea", onClick = onFeedback)
        Cell("About Gamut", icon = PhosphorIcons.Light.Info, subtitle = "Version, license and credits", onClick = onAbout)
    }
}