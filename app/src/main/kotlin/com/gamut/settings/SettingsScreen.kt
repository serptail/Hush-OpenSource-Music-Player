package com.gamut.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.rounded.Brightness4
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.CaretRight
import com.adamglin.phosphoricons.regular.ClockCounterClockwise
import com.adamglin.phosphoricons.regular.HardDrives
import com.adamglin.phosphoricons.regular.Headphones
import com.adamglin.phosphoricons.regular.Heart
import com.adamglin.phosphoricons.regular.Info
import com.adamglin.phosphoricons.regular.Palette
import com.adamglin.phosphoricons.regular.Playlist
import com.adamglin.phosphoricons.regular.Question
import com.adamglin.phosphoricons.regular.TipJar
import com.adamglin.phosphoricons.regular.User
import com.gamut.account.AccountViewModel
import com.gamut.core.designsystem.LocalNotifier
import com.gamut.core.designsystem.ProfileAvatar
import com.gamut.core.designsystem.ink
import com.gamut.core.designsystem.pressScale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class UpdateCheckStatus { Found, UpToDate, Failed, Unavailable }

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
    onCheckForUpdate: suspend () -> UpdateCheckStatus = { UpdateCheckStatus.Unavailable },
    vm: AccountViewModel = hiltViewModel(),
) {
    val notifier = LocalNotifier.current
    val soon: () -> Unit = { notifier.info("Coming soon") }
    val userId by vm.userId.collectAsState()
    val name by vm.displayName.collectAsState()
    val email by vm.email.collectAsState()
    val avatar by vm.avatar.collectAsState()
    val supportVm: SupportViewModel = hiltViewModel()
    val openKoFi = rememberOpenKoFi()
    var checking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val active = LocalPageActive.current
    LaunchedEffect(active) {
        if (active) {
            delay(600)
            supportVm.store.clearNudge()
        }
    }

    val checkForUpdate: () -> Unit = {
        if (!checking) {
            checking = true
            scope.launch {
                val status =
                    try {
                        onCheckForUpdate()
                    } finally {
                        checking = false
                    }
                when (status) {
                    UpdateCheckStatus.UpToDate -> notifier.info("You\u2019re on the latest version")
                    UpdateCheckStatus.Failed -> notifier.info("Couldn\u2019t check for updates. Try again later.")
                    UpdateCheckStatus.Unavailable -> notifier.info("Updates aren\u2019t available in this build")
                    UpdateCheckStatus.Found -> Unit
                }
            }
        }
    }

    val appearanceVm: AppearanceViewModel = hiltViewModel()
    val appearance by appearanceVm.settings.collectAsState()
    val context = LocalContext.current
    val version =
        remember(context) {
            runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        }

    GamutPage(
        title = "",
        onBack = onBack,
        nowPlaying = nowPlaying,
        actions = { UpdateCheckButton(checking = checking, onClick = checkForUpdate) },
    ) {
        Text(
            "Settings",
            style = MaterialTheme.typography.headlineLarge,
            color = ink(),
            modifier = Modifier.padding(top = 4.dp, start = 4.dp),
        )

        SettingsBlock {
            Row(
                Modifier
                    .fillMaxWidth()
                    .pressScale(onClick = onProfile, scaleDown = 0.985f)
                    .padding(horizontal = 14.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ProfileAvatar(name = name, seed = userId, size = 56.dp, guestIcon = PhosphorIcons.Regular.User, image = avatar)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.titleLarge, color = ink(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        email ?: "View profile",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ink(0.55f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(PhosphorIcons.Regular.CaretRight, null, tint = ink(0.35f), modifier = Modifier.size(14.dp))
            }

            GroupedHeader("Your music")
            GroupedRow("Liked Songs", PhosphorIcons.Regular.Heart, onClick = onLikedSongs)
            GroupedDivider()
            GroupedRow("Your Playlists", PhosphorIcons.Regular.Playlist, onClick = onPlaylists)
            GroupedDivider()
            GroupedRow("Recently Played", PhosphorIcons.Regular.ClockCounterClockwise, onClick = onRecentlyPlayed)

            GroupedHeader("Preferences")
            GroupedRow("Appearance", PhosphorIcons.Regular.Palette, value = appearance.mode.name, onClick = onAppearance)
            GroupedDivider()
            GroupedRow("Playback & Audio", PhosphorIcons.Regular.Headphones, onClick = onPlaybackAudio)
            GroupedDivider()
            GroupedRow("Storage & Downloads", PhosphorIcons.Regular.HardDrives, onClick = onStorage)

            GroupedHeader("Support")
            GroupedRow(
                "Support Gamut",
                PhosphorIcons.Regular.TipJar,
                onClick = {
                    supportVm.store.clearNudge()
                    openKoFi()
                },
            )
            GroupedDivider()
            GroupedRow("Help & Feedback", PhosphorIcons.Regular.Question, onClick = onFeedback)
            GroupedDivider()
            GroupedRow("About Gamut", PhosphorIcons.Regular.Info, onClick = onAbout)
            Spacer(Modifier.height(8.dp))
        }

        if (version != null) {
            Text(
                "Gamut $version",
                style = MaterialTheme.typography.labelMedium,
                color = ink(0.35f),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
        }
    }
}

@Composable
private fun UpdateCheckButton(
    checking: Boolean,
    onClick: () -> Unit,
) {
    Text(
        if (checking) "Checking\u2026" else "Check for updates",
        style = MaterialTheme.typography.labelLarge,
        color = ink(if (checking) 0.55f else 0.85f),
        maxLines = 1,
        modifier =
            Modifier
                .pressScale(onClick = onClick, scaleDown = 0.95f)
                .clip(CircleShape)
                .background(ink(0.07f))
                .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}