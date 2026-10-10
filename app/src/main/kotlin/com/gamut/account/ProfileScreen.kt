package com.gamut.account

import android.net.Uri
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Light
import com.adamglin.phosphoricons.light.ArrowsClockwise
import com.adamglin.phosphoricons.light.Camera
import com.adamglin.phosphoricons.light.CloudCheck
import com.adamglin.phosphoricons.light.CloudSlash
import com.adamglin.phosphoricons.light.ClockCounterClockwise
import com.adamglin.phosphoricons.light.DownloadSimple
import com.adamglin.phosphoricons.light.FloppyDisk
import com.adamglin.phosphoricons.light.PencilSimple
import com.adamglin.phosphoricons.light.SignOut
import com.adamglin.phosphoricons.light.Trash
import com.adamglin.phosphoricons.light.UploadSimple
import com.adamglin.phosphoricons.light.User
import com.gamut.R
import com.gamut.core.designsystem.LocalNotifier
import com.gamut.core.designsystem.ProfileAvatar
import com.gamut.core.designsystem.ink
import com.gamut.core.designsystem.pressScale
import com.gamut.core.sync.SyncConfig
import com.gamut.core.sync.SyncStatus
import com.gamut.settings.GamutCheck
import com.gamut.settings.GamutDestructive
import com.gamut.settings.GamutDialog
import com.gamut.settings.GamutField
import com.gamut.settings.GamutHeading
import com.gamut.settings.GamutPage
import com.gamut.settings.PillSwitch
import com.gamut.settings.SoftCard
import com.gamut.settings.SoftHeader

/** Centered hero (circular avatar, name, status), icon pills, and soft section cards with an icon on every row. */
@Composable
fun ProfileScreen(onBack: () -> Unit, nowPlaying: @Composable () -> Unit, vm: AccountViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val notifier = LocalNotifier.current
    val userId by vm.userId.collectAsState()
    val name by vm.displayName.collectAsState()
    val customName by vm.customName.collectAsState()
    val email by vm.email.collectAsState()
    val avatar by vm.avatar.collectAsState()
    val status by vm.status.collectAsState()
    val lastSynced by vm.lastSyncedMs.collectAsState()
    val pending by vm.pendingCount.collectAsState()
    val autoBackup by vm.autoBackup.collectAsState()
    val backups by vm.backups.collectAsState()
    val importSummary by vm.importSummary.collectAsState()

    // Saveable: the system file picker can recreate the activity, and the user must come back to the same screen,
    // with the same dialog state, password and chosen file.
    var editName by rememberSaveable { mutableStateOf(false) }
    var draft by remember(customName) { mutableStateOf(customName) }
    var disconnect by rememberSaveable { mutableStateOf(false) }
    var delete by rememberSaveable { mutableStateOf(false) }
    var allDevices by rememberSaveable { mutableStateOf(false) }
    var clearLocal by rememberSaveable { mutableStateOf(false) }

    var exportDialog by rememberSaveable { mutableStateOf(false) }
    var exportPassword by rememberSaveable { mutableStateOf("") }
    var exportConfirm by rememberSaveable { mutableStateOf("") }
    var exportMismatch by rememberSaveable { mutableStateOf(false) }

    var pendingImport by rememberSaveable { mutableStateOf<Uri?>(null) }
    var importPassword by rememberSaveable { mutableStateOf("") }
    var importReplace by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        vm.events.collect {
            when (it) {
                is Notice.Success -> notifier.success(it.text)
                is Notice.Info -> notifier.info(it.text)
                is Notice.Error -> notifier.error(it.text)
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { vm.export(context.contentResolver, it, exportPassword) }
        exportPassword = ""; exportConfirm = ""
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { importPassword = ""; importReplace = false; pendingImport = uri }
    }
    val photoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { vm.setAvatar(context.contentResolver, it) }
    }
    val pickPhoto = { photoLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    val openEditName = { draft = customName; editName = true }

    GamutPage(title = "", onBack = onBack, nowPlaying = nowPlaying) {
        // ---- hero: avatar on the left, identity on the right, actions in one row underneath ----
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.pressScale(onClick = pickPhoto)) {
                ProfileAvatar(name = name, seed = userId, size = 92.dp, guestIcon = PhosphorIcons.Light.User, image = avatar, shape = CircleShape)
            }
            Column(Modifier.weight(1f).padding(start = 18.dp)) {
                Text(
                    name, style = GamutHeading, color = ink(), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.pressScale(onClick = openEditName, scaleDown = 0.98f),
                )
                Text(
                    email ?: "Using Gamut locally", style = MaterialTheme.typography.bodyMedium, color = ink(0.55f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp),
                )
                Row(
                    Modifier.padding(top = 10.dp).clip(RoundedCornerShape(50)).background(ink(0.08f)).padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (userId != null) PhosphorIcons.Light.CloudCheck else PhosphorIcons.Light.CloudSlash,
                        null, tint = ink(0.7f), modifier = Modifier.size(15.dp),
                    )
                    Text(
                        if (userId != null) "Signed in" else "Local only",
                        style = MaterialTheme.typography.bodyMedium, color = ink(0.7f), maxLines = 1, modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            IconPill(PhosphorIcons.Light.PencilSimple, "Edit name", openEditName, Modifier.weight(1f))
            IconPill(PhosphorIcons.Light.Camera, if (avatar != null) "Change photo" else "Add photo", pickPhoto, Modifier.weight(1f))
            if (avatar != null) {
                Box(
                    Modifier.size(44.dp).pressScale(onClick = { vm.removeAvatar() }).clip(CircleShape).background(ink(0.1f)),
                    contentAlignment = Alignment.Center,
                ) { Icon(PhosphorIcons.Light.Trash, "Remove photo", tint = ink(0.85f), modifier = Modifier.size(20.dp)) }
            }
        }

        // ---- sync ----
        if (SyncConfig.enabled) {
            SoftHeader("Sync")
            SoftCard {
                if (userId == null) {
                    IconRow(
                        icon = null, painter = painterResource(R.drawable.ic_google), title = "Continue with Google",
                        subtitle = "Sync and back up your library across devices.",
                        onClick = { vm.signIn(context) },
                    )
                } else {
                    val failed = status is SyncStatus.Error
                    val line = when (val s = status) {
                        SyncStatus.Syncing -> "Syncing\u2026"
                        is SyncStatus.Error -> "Sync error \u00B7 ${s.message}"
                        SyncStatus.Idle -> lastSynced?.let { "Last synced ${DateUtils.getRelativeTimeSpanString(it)}" } ?: "Not synced yet"
                    } + if (pending > 0) " \u00B7 $pending waiting" else ""
                    IconRow(
                        PhosphorIcons.Light.ArrowsClockwise, "Sync now",
                        subtitle = line, subtitleColor = if (failed) GamutDestructive else null, onClick = vm::syncNow,
                    )
                }
            }
        }

        // ---- backup ----
        SoftHeader("Backup")
        SoftCard {
            IconRow(
                PhosphorIcons.Light.UploadSimple, "Export", subtitle = "Save to a file",
                onClick = { exportPassword = ""; exportConfirm = ""; exportMismatch = false; exportDialog = true },
            )
            IconDivider()
            IconRow(
                PhosphorIcons.Light.DownloadSimple, "Import", subtitle = "Merge or replace",
                onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
            )
            IconDivider()
            IconRow(PhosphorIcons.Light.FloppyDisk, "Back up now", subtitle = "On this device", onClick = vm::backupNow)
            IconDivider()
            IconRow(
                PhosphorIcons.Light.ClockCounterClockwise, "Daily backup", subtitle = "Last 10 days, offline",
                onClick = { vm.setAutoBackup(!autoBackup) },
                trailing = { PillSwitch(autoBackup) },
            )
        }

        if (backups.isNotEmpty()) {
            SoftHeader("Restore")
            SoftCard {
                backups.forEachIndexed { i, b ->
                    if (i > 0) IconDivider()
                    IconRow(
                        PhosphorIcons.Light.ClockCounterClockwise,
                        DateUtils.formatDateTime(context, b.modifiedMs, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH),
                        subtitle = "Tap to restore", onClick = { vm.restore(b) },
                    )
                }
            }
        }

        if (SyncConfig.enabled && userId != null) {
            SoftHeader("Account")
            SoftCard {
                IconRow(
                    PhosphorIcons.Light.SignOut, "Disconnect Google",
                    onClick = { allDevices = false; clearLocal = false; disconnect = true },
                )
                IconDivider()
                IconRow(
                    PhosphorIcons.Light.Trash, "Delete account", destructive = true,
                    onClick = { clearLocal = false; delete = true },
                )
            }
        }
    }

    // ---- export: ask for the (optional) password right here, then open the file picker ----
    if (exportDialog) {
        GamutDialog(
            title = "Export backup",
            confirmLabel = "Choose location",
            onDismiss = { exportDialog = false },
            onConfirm = {
                if (exportPassword.isNotEmpty() && exportPassword != exportConfirm) {
                    exportMismatch = true
                } else {
                    exportDialog = false
                    exportLauncher.launch("gamut-backup.json")
                }
            },
        ) {
            GamutField(value = exportPassword, onChange = { exportPassword = it; exportMismatch = false }, placeholder = "Password (optional)", password = true)
            if (exportPassword.isNotEmpty()) {
                GamutField(
                    value = exportConfirm, onChange = { exportConfirm = it; exportMismatch = false }, placeholder = "Repeat password",
                    password = true, modifier = Modifier.padding(top = 8.dp),
                )
            }
            Text(
                if (exportMismatch) "Passwords don\u2019t match." else "A password encrypts the file. You\u2019ll need the same one to import it.",
                style = MaterialTheme.typography.bodyMedium, color = if (exportMismatch) GamutDestructive else ink(0.5f),
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }

    // ---- import: same pop-up style, password + merge / replace ----
    pendingImport?.let { uri ->
        GamutDialog(
            title = "Import backup",
            body = "Enter the password if the file is encrypted, then choose how to bring it in.",
            confirmLabel = if (importReplace) "Replace & import" else "Import",
            destructive = importReplace,
            onDismiss = { pendingImport = null; importPassword = "" },
            onConfirm = {
                pendingImport = null
                vm.import(context.contentResolver, uri, importPassword, importReplace)
                importPassword = ""
            },
        ) {
            GamutField(value = importPassword, onChange = { importPassword = it }, placeholder = "Password (if encrypted)", password = true)
            Column(Modifier.padding(top = 10.dp)) {
                ChoiceRow(!importReplace, "Merge", "Add to what you have. Nothing is deleted.", { importReplace = false })
                ChoiceRow(importReplace, "Replace", "Delete your current favorites and playlists first.", { importReplace = true }, warn = true)
            }
        }
    }

    // ---- import summary ----
    importSummary?.let { s ->
        GamutDialog(
            title = "Import complete",
            body = if (s.replaced) "Your previous data was replaced." else "The backup was merged into your library.",
            confirmLabel = "Done",
            dismissLabel = null,
            onDismiss = vm::dismissImportSummary,
            onConfirm = vm::dismissImportSummary,
        ) {
            SummaryRow("Favorites", s.favorites)
            SummaryRow("Playlists", s.playlists)
        }
    }

    if (editName) {
        GamutDialog(
            title = "Display name",
            confirmLabel = "Save",
            onDismiss = { editName = false },
            onConfirm = { editName = false; vm.setName(draft) },
        ) {
            GamutField(value = draft, onChange = { draft = it.take(40) }, placeholder = "Your Google name")
            Text(
                "Leave empty to use the name from your Google account.",
                style = MaterialTheme.typography.bodyMedium, color = ink(0.5f), modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
    if (disconnect) {
        GamutDialog(
            title = "Disconnect Google?",
            body = "Your library stays on this device unless you clear it.",
            confirmLabel = "Disconnect",
            destructive = clearLocal,
            onDismiss = { disconnect = false },
            onConfirm = { disconnect = false; vm.signOut(allDevices, clearLocal) },
        ) {
            GamutCheck(allDevices, "Also sign out of other devices", { allDevices = it })
            GamutCheck(clearLocal, "Clear library on this device", { clearLocal = it }, warn = true)
            if (clearLocal && pending > 0) {
                Text(
                    "$pending change(s) haven\u2019t synced and will be lost.",
                    style = MaterialTheme.typography.bodyMedium, color = GamutDestructive, modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
    if (delete) {
        GamutDialog(
            title = "Delete account?",
            body = "This permanently deletes your cloud library. Export a backup first.",
            confirmLabel = "Delete",
            destructive = true,
            onDismiss = { delete = false },
            onConfirm = { delete = false; vm.deleteAccount(clearLocal) },
        ) {
            GamutCheck(clearLocal, "Also clear data on this device", { clearLocal = it }, warn = true)
        }
    }
}

/** A card row with a tinted icon badge on the left, title + optional subtitle, and an optional trailing control. */
@Composable
private fun IconRow(
    icon: ImageVector?,
    title: String,
    onClick: () -> Unit,
    painter: Painter? = null,
    subtitle: String? = null,
    destructive: Boolean = false,
    subtitleColor: Color? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val tone = if (destructive) GamutDestructive else ink()
    Row(
        Modifier.fillMaxWidth().pressScale(onClick = onClick, scaleDown = 0.99f).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(14.dp))
                .background(if (destructive) GamutDestructive.copy(alpha = 0.14f) else ink(0.08f)),
            contentAlignment = Alignment.Center,
        ) {
            when {
                // Full-colour drawables (the Google logo) keep their own colours.
                painter != null -> Icon(painter, null, tint = Color.Unspecified, modifier = Modifier.size(22.dp))
                icon != null -> Icon(icon, null, tint = if (destructive) GamutDestructive else ink(0.85f), modifier = Modifier.size(22.dp))
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = tone, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(
                    subtitle, style = MaterialTheme.typography.bodyMedium, color = subtitleColor ?: ink(0.55f),
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

/** Divider inset past the icon badge. */
@Composable
private fun IconDivider() {
    Box(Modifier.padding(start = 76.dp).fillMaxWidth().height(1.dp).background(ink(0.06f)))
}

/** Rounded pill with an icon and a label. */
@Composable
private fun IconPill(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.pressScale(onClick = onClick).clip(RoundedCornerShape(50)).background(ink(0.1f))
            .padding(horizontal = 14.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = ink(0.85f), modifier = Modifier.size(18.dp))
        Text(label, style = MaterialTheme.typography.titleMedium, color = ink(), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp))
    }
}

/** Radio-style option for the import dialog. */
@Composable
private fun ChoiceRow(selected: Boolean, title: String, subtitle: String, onClick: () -> Unit, warn: Boolean = false) {
    val dot = if (warn) GamutDestructive else ink(0.95f)
    Row(Modifier.fillMaxWidth().pressScale(onClick = onClick, scaleDown = 0.98f).padding(vertical = 8.dp)) {
        Box(
            Modifier.padding(top = 2.dp).size(22.dp).border(1.5.dp, if (selected) dot else ink(0.3f), CircleShape),
            contentAlignment = Alignment.Center,
        ) { if (selected) Box(Modifier.size(12.dp).background(dot, CircleShape)) }
        Column(Modifier.padding(start = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = if (warn && selected) GamutDestructive else ink())
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = ink(0.55f))
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = ink(0.7f))
        Text(value, style = MaterialTheme.typography.titleMedium, color = ink())
    }
}