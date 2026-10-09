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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Light
import com.adamglin.phosphoricons.light.ArrowsClockwise
import com.adamglin.phosphoricons.light.Camera
import com.adamglin.phosphoricons.light.ClockCounterClockwise
import com.adamglin.phosphoricons.light.DownloadSimple
import com.adamglin.phosphoricons.light.FloppyDisk
import com.adamglin.phosphoricons.light.SignOut
import com.adamglin.phosphoricons.light.Trash
import com.adamglin.phosphoricons.light.UploadSimple
import com.adamglin.phosphoricons.light.User
import com.gamut.core.designsystem.LocalCoverPalette
import com.gamut.core.designsystem.LocalNotifier
import com.gamut.core.designsystem.ProfileAvatar
import com.gamut.core.designsystem.ink
import com.gamut.core.designsystem.pressScale
import com.gamut.core.sync.SyncConfig
import com.gamut.core.sync.SyncStatus
import com.gamut.settings.Cell
import com.gamut.settings.GamutButton
import com.gamut.settings.GamutCheck
import com.gamut.settings.GamutDestructive
import com.gamut.settings.GamutDialog
import com.gamut.settings.GamutField
import com.gamut.settings.GamutHeading
import com.gamut.settings.GamutPage
import com.gamut.settings.GamutSwitch
import com.gamut.settings.GroupLabel
import com.gamut.settings.Tile

/** Centered hero (circular avatar, name, status), rounded section cards, square backup tiles. */
@Composable
fun ProfileScreen(onBack: () -> Unit, nowPlaying: @Composable () -> Unit, vm: AccountViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val notifier = LocalNotifier.current
    val palette = LocalCoverPalette.current
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
        // ---- hero ----
        Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.pressScale(onClick = pickPhoto)) {
                ProfileAvatar(name = name, seed = userId, size = 132.dp, guestIcon = PhosphorIcons.Light.User, image = avatar, shape = CircleShape)
                val onInk = if (palette.isLight) Color.White else Color.Black
                Box(
                    Modifier.align(Alignment.BottomEnd).size(40.dp)
                        .background(ink(0.95f), CircleShape).border(3.dp, palette.base, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Icon(PhosphorIcons.Light.Camera, "Change photo", tint = onInk, modifier = Modifier.size(19.dp)) }
            }
            Text(
                name, style = GamutHeading, color = ink(), maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 18.dp).pressScale(onClick = openEditName, scaleDown = 0.98f),
            )
            Text(
                email ?: "Using Gamut locally", style = MaterialTheme.typography.bodyMedium, color = ink(0.55f),
                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp),
            )
            Box(Modifier.padding(top = 12.dp).clip(RoundedCornerShape(50)).background(ink(0.08f)).padding(horizontal = 12.dp, vertical = 5.dp)) {
                Text(
                    if (userId != null) "Signed in" else "Local only",
                    style = MaterialTheme.typography.bodyMedium, color = ink(0.7f), maxLines = 1,
                )
            }
            Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GamutButton("Edit name", onClick = openEditName, filled = false)
                if (avatar != null) GamutButton("Remove photo", onClick = { vm.removeAvatar() }, filled = false)
            }
        }

        // ---- sync ----
        if (SyncConfig.enabled) {
            GroupLabel("Sync")
            SectionCard {
                if (userId == null) {
                    Column(Modifier.padding(vertical = 12.dp)) {
                        GamutButton("Continue with Google", onClick = { vm.signIn(context) }, modifier = Modifier.fillMaxWidth())
                        Text(
                            "Sync and back up your library across devices.",
                            style = MaterialTheme.typography.bodyMedium, color = ink(0.5f), modifier = Modifier.padding(top = 10.dp),
                        )
                    }
                } else {
                    val failed = status is SyncStatus.Error
                    val line = when (val s = status) {
                        SyncStatus.Syncing -> "Syncing\u2026"
                        is SyncStatus.Error -> "Sync error \u00B7 ${s.message}"
                        SyncStatus.Idle -> lastSynced?.let { "Last synced ${DateUtils.getRelativeTimeSpanString(it)}" } ?: "Not synced yet"
                    } + if (pending > 0) " \u00B7 $pending waiting" else ""
                    Cell(
                        "Sync now", icon = PhosphorIcons.Light.ArrowsClockwise, subtitle = line,
                        subtitleColor = if (failed) GamutDestructive else null, chevron = false, onClick = vm::syncNow,
                    )
                }
            }
        }

        // ---- backup: 2x2 square tiles ----
        GroupLabel("Backup")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Tile("Export", PhosphorIcons.Light.UploadSimple, Modifier.weight(1f), subtitle = "Save to a file") {
                exportPassword = ""; exportConfirm = ""; exportMismatch = false; exportDialog = true
            }
            Tile("Import", PhosphorIcons.Light.DownloadSimple, Modifier.weight(1f), subtitle = "Merge or replace") {
                importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Tile(
                "Daily backup", PhosphorIcons.Light.ClockCounterClockwise, Modifier.weight(1f), subtitle = "Last 10 days, offline",
                badge = { GamutSwitch(autoBackup, vm::setAutoBackup) }, onClick = { vm.setAutoBackup(!autoBackup) },
            )
            Tile("Back up now", PhosphorIcons.Light.FloppyDisk, Modifier.weight(1f), subtitle = "On this device", onClick = vm::backupNow)
        }

        if (backups.isNotEmpty()) {
            GroupLabel("Restore")
            SectionCard {
                backups.forEach { b ->
                    Cell(
                        DateUtils.formatDateTime(context, b.modifiedMs, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH),
                        subtitle = "Tap to restore", chevron = false, onClick = { vm.restore(b) },
                    )
                }
            }
        }

        if (SyncConfig.enabled && userId != null) {
            GroupLabel("Account")
            SectionCard {
                Cell("Disconnect Google", icon = PhosphorIcons.Light.SignOut, onClick = { allDevices = false; clearLocal = false; disconnect = true })
                Cell("Delete account", icon = PhosphorIcons.Light.Trash, destructive = true, onClick = { clearLocal = false; delete = true })
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

/** Soft rounded surface that groups rows. */
@Composable
private fun SectionCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(ink(0.07f)).padding(horizontal = 18.dp, vertical = 4.dp),
        content = content,
    )
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