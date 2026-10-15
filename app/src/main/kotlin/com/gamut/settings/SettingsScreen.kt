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
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Bold
import com.adamglin.phosphoricons.bold.CaretRight
import com.adamglin.phosphoricons.bold.ClockCounterClockwise
import com.adamglin.phosphoricons.bold.HardDrives
import com.adamglin.phosphoricons.bold.Heart
import com.adamglin.phosphoricons.bold.Info
import com.adamglin.phosphoricons.bold.Palette
import com.adamglin.phosphoricons.bold.Playlist
import com.adamglin.phosphoricons.bold.Question
import com.adamglin.phosphoricons.bold.User
import com.adamglin.phosphoricons.bold.Headphones
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
    // Opening Settings is the answer to the glow around the profile circle: once seen, it goes away.
    val supportVm: SupportViewModel = hiltViewModel()
    // The screen is pre-composed while parked: only clear the nudge once the page is really open.
    val active = LocalPageActive.current
    LaunchedEffect(active) { if (active) { delay(600); supportVm.store.clearNudge() } }

    GamutPage(title = "Settings", onBack = onBack, nowPlaying = nowPlaying) {
        // Profile header: no container, just the row.
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp).pressScale(onClick = onProfile, scaleDown = 0.985f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProfileAvatar(name = name, seed = userId, size = 60.dp, guestIcon = PhosphorIcons.Bold.User, image = avatar)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleLarge, color = ink(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(email ?: "View profile", style = MaterialTheme.typography.bodyMedium, color = ink(0.55f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(PhosphorIcons.Bold.CaretRight, null, tint = ink(0.45f), modifier = Modifier.size(18.dp))
        }

        GroupLabel("Your music")
        Cell("Liked Songs", icon = PhosphorIcons.Bold.Heart, onClick = onLikedSongs)
        Cell("Your Playlists", icon = PhosphorIcons.Bold.Playlist, onClick = onPlaylists)
        Cell("Recently Played", icon = PhosphorIcons.Bold.ClockCounterClockwise, onClick = onRecentlyPlayed)

        GroupLabel("Preferences")
        Cell("Appearance", icon = PhosphorIcons.Bold.Palette, subtitle = "Theme, colors and glass", onClick = onAppearance)
        Cell("Playback & Audio", icon = PhosphorIcons.Bold.Headphones, subtitle = "Quality, equalizer, fades and more", onClick = onPlaybackAudio)
        Cell("Storage & Downloads", icon = PhosphorIcons.Bold.HardDrives, subtitle = "Cache and offline songs", onClick = onStorage)

        GroupLabel("Support")
        Cell("Help & Feedback", icon = PhosphorIcons.Bold.Question, subtitle = "Report a bug or share an idea", onClick = onFeedback)
        Cell("About Gamut", icon = PhosphorIcons.Bold.Info, subtitle = "Version, license and credits", onClick = onAbout)
        // Always available, never nags: a small pill at the very bottom.
        SupportRow()
    }
}