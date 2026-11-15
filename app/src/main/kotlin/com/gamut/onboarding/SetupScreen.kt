package com.gamut.onboarding

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.gamut.core.designsystem.AppColorPreset
import com.gamut.core.designsystem.AppColorPresets
import com.gamut.core.designsystem.AppThemeMode
import com.gamut.core.designsystem.AppearanceSettings
import com.gamut.core.designsystem.GamutDisplayFont
import com.gamut.core.designsystem.LocalCoverPalette
import com.gamut.core.designsystem.focusRing
import com.gamut.core.designsystem.ink
import com.gamut.onboarding.spotify.ImportState
import kotlinx.coroutines.delay

private const val PAGE_NAME = 0
private const val PAGE_LOOK = 1
private const val PAGE_FOLDER = 2
private const val PAGE_IMPORT = 3
private const val PAGE_COUNT = 4

private const val NAME_LIMIT = 40
private const val SPOTIFY_DATA_URL = "https://www.spotify.com/account/privacy/"
private const val EXPORTIFY_URL = "https://exportify.net"

@Composable
fun SetupScreen(vm: SetupViewModel = hiltViewModel()) {
    val appearance by vm.appearance.collectAsState()
    val palette = LocalCoverPalette.current
    val focus = LocalFocusManager.current

    var page by rememberSaveable { mutableIntStateOf(PAGE_NAME) }
    var name by rememberSaveable { mutableStateOf(vm.initialName) }
    BackHandler(enabled = page > PAGE_NAME) { page -= 1 }

    var step by remember { mutableIntStateOf(0) }
    LaunchedEffect(appearance.flowAnimated) {
        if (appearance.flowAnimated) {
            while (true) {
                delay(3_600)
                step++
            }
        }
    }
    val meshColors = if (appearance.adaptive) SetupPalettes[step % SetupPalettes.size] else presetMesh(appearance, dark = !palette.isLight)

    Box(
        Modifier
            .fillMaxSize()
            .background(palette.base)
            .pointerInput(Unit) { detectTapGestures { focus.clearFocus() } },
    ) {
        SetupMesh(colors = meshColors, animated = appearance.flowAnimated, intensity = if (palette.isLight) 0.42f else 0.6f)
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(0f to Color.Transparent, 1f to palette.base.copy(alpha = 0.55f))),
        )

        Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
            SetupHeader(page = page, onSkip = vm::finish)
            AnimatedContent(
                targetState = page,
                transitionSpec = {
                    val forward = targetState > initialState
                    val slide = tween<IntOffset>(380)
                    (slideInHorizontally(slide) { if (forward) it / 3 else -it / 3 } + fadeIn(tween(300, 80)))
                        .togetherWith(slideOutHorizontally(slide) { if (forward) -it / 3 else it / 3 } + fadeOut(tween(180)))
                },
                label = "setupPage",
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) { shown ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    Column(Modifier.widthIn(max = 520.dp).fillMaxSize().padding(horizontal = 28.dp)) {
                        when (shown) {
                            PAGE_NAME -> {
                                NamePage(
                                    name = name,
                                    onNameChange = { name = it.take(NAME_LIMIT) },
                                    onNext = {
                                        focus.clearFocus()
                                        if (name.isNotBlank()) vm.saveName(name)
                                        page = PAGE_LOOK
                                    },
                                    onSkip = { page = PAGE_LOOK },
                                )
                            }

                            PAGE_LOOK -> {
                                LookPage(vm, appearance, onNext = { page = PAGE_FOLDER })
                            }

                            PAGE_FOLDER -> {
                                FolderPage(vm, onNext = { page = PAGE_IMPORT })
                            }

                            else -> {
                                ImportPage(vm, onFinish = vm::finish)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun presetMesh(
    settings: AppearanceSettings,
    dark: Boolean,
): List<Color> {
    val preset = AppColorPresets.byId(settings.preset).let { if (dark) it.dark else it.light }
    val accent = preset.accent
    return listOf(accent, lerp(accent, preset.base, 0.45f), lerp(accent, Color.White, 0.25f), lerp(preset.base, accent, 0.6f))
}

@Composable
private fun NamePage(
    name: String,
    onNameChange: (String) -> Unit,
    onNext: () -> Unit,
    onSkip: () -> Unit,
) {
    PageLayout(
        content = {
            Spacer(Modifier.height(36.dp))
            SetupTitle("What should we call you?")
            Spacer(Modifier.height(14.dp))
            SetupBody("This is the name on your profile. You can change it any time in Settings.")
            Spacer(Modifier.height(32.dp))
            NameField(name, onNameChange, onDone = { if (name.isNotBlank()) onNext() })
        },
        actions = {
            SetupButton("Next", onNext, primary = true, enabled = name.isNotBlank())
            Spacer(Modifier.height(12.dp))
            SetupButton("Skip", onSkip, primary = false)
        },
    )
}

@Composable
private fun LookPage(
    vm: SetupViewModel,
    appearance: AppearanceSettings,
    onNext: () -> Unit,
) {
    val dark = !LocalCoverPalette.current.isLight
    PageLayout(
        content = {
            Spacer(Modifier.height(36.dp))
            SetupTitle("Make it yours")
            Spacer(Modifier.height(14.dp))
            SetupBody("Pick how Gamut looks. You can change all of this later in Settings \u203A Appearance.")
            Spacer(Modifier.height(28.dp))

            SetupLabel("THEME")
            Spacer(Modifier.height(10.dp))
            val modes =
                buildList {
                    if (appearance.adaptive) add(AppThemeMode.Auto to "Auto")
                    add(AppThemeMode.System to "System")
                    add(AppThemeMode.Light to "Light")
                    add(AppThemeMode.Dark to "Dark")
                }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                modes.forEach { (mode, label) ->
                    ChoiceChip(label, selected = appearance.mode == mode, onClick = { vm.setMode(mode) })
                }
            }

            Spacer(Modifier.height(26.dp))
            SetupLabel("COLOR")
            Spacer(Modifier.height(10.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                AdaptiveSwatch(selected = appearance.adaptive, onClick = vm::useAdaptive)
                AppColorPresets.all.forEach { preset ->
                    PresetSwatch(
                        preset = preset,
                        dark = dark,
                        selected = !appearance.adaptive && appearance.preset == preset.id,
                        onClick = { vm.usePreset(preset.id) },
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            SetupBody(if (appearance.adaptive) "Colors follow the album that\u2019s playing." else "A fixed color for the whole app.")
        },
        actions = { SetupButton("Next", onNext, primary = true) },
    )
}

@Composable
private fun FolderPage(
    vm: SetupViewModel,
    onNext: () -> Unit,
) {
    val folder by vm.folder.collectAsState()
    var rejected by remember { mutableStateOf(false) }
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree: Uri? ->
            if (tree != null) rejected = !vm.chooseFolder(tree)
        }
    PageLayout(
        content = {
            Spacer(Modifier.height(36.dp))
            SetupTitle("Where should your music live?")
            Spacer(Modifier.height(14.dp))
            SetupBody(
                "Downloaded songs are saved to a folder you pick, with their cover, artist info and lyrics inside the file, so other apps can play them too.",
            )
            Spacer(Modifier.height(28.dp))
            folder?.let { label ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(
                            RoundedCornerShape(18.dp),
                        ).background(ink(0.10f))
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                ) {
                    SetupLabel("MUSIC FOLDER")
                    Spacer(Modifier.height(4.dp))
                    Text(label, style = MaterialTheme.typography.titleMedium, color = ink(), maxLines = 2)
                }
            }
            if (rejected) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Pick a folder on your phone or SD card.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        actions = {
            if (folder == null) {
                SetupButton("Choose a folder", {
                    rejected = false
                    picker.launch(null)
                }, primary = true)
                Spacer(Modifier.height(12.dp))
                SetupButton("Keep in app storage", onNext, primary = false)
            } else {
                SetupButton("Next", onNext, primary = true)
                Spacer(Modifier.height(12.dp))
                SetupButton("Change folder", {
                    rejected = false
                    picker.launch(null)
                }, primary = false)
            }
        },
    )
}

@Composable
private fun ImportPage(
    vm: SetupViewModel,
    onFinish: () -> Unit,
) {
    val state by vm.importState.collectAsState()
    val links = LocalUriHandler.current
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) vm.startImport(uris)
        }
    PageLayout(
        content = {
            Spacer(Modifier.height(36.dp))
            SetupTitle("Bring your Spotify music")
            Spacer(Modifier.height(14.dp))
            SetupBody(
                "Get your playlists from Spotify as files, then pick them here. Gamut finds each song in its own catalog, so nothing is copied from Spotify.",
            )
            Spacer(Modifier.height(18.dp))
            SetupLink("Download my Spotify data (a .zip, takes a few days)") { links.openUri(SPOTIFY_DATA_URL) }
            Spacer(Modifier.height(10.dp))
            SetupLink("Export playlists instantly with Exportify (.csv)") { links.openUri(EXPORTIFY_URL) }
            Spacer(Modifier.height(10.dp))
            Text("YouTube Music import is coming soon.", style = MaterialTheme.typography.bodyMedium, color = ink(0.4f))
            Spacer(Modifier.height(24.dp))
            ImportStatus(state)
        },
        actions = {
            when (state) {
                is ImportState.Reading, is ImportState.Matching -> {
                    SetupButton("Continue in the background", onFinish, primary = true)
                    Spacer(Modifier.height(12.dp))
                    SetupButton("Cancel import", vm::cancelImport, primary = false)
                }

                is ImportState.Done -> {
                    SetupButton("Finish", {
                        vm.importSeen()
                        onFinish()
                    }, primary = true)
                }

                else -> {
                    SetupButton("Choose Spotify files", { picker.launch(arrayOf("*/*")) }, primary = true)
                    Spacer(Modifier.height(12.dp))
                    SetupButton(if (state is ImportState.Failed) "Skip for now" else "Not now", {
                        vm.importSeen()
                        onFinish()
                    }, primary = false)
                }
            }
        },
    )
}

@Composable
private fun ImportStatus(state: ImportState) {
    when (state) {
        ImportState.Idle -> {
            Unit
        }

        ImportState.Reading -> {
            Text("Reading your files\u2026", style = MaterialTheme.typography.titleMedium, color = ink())
        }

        is ImportState.Matching -> {
            val fraction = (state.done.toFloat() / state.total.coerceAtLeast(1)).coerceIn(0f, 1f)
            val bar by animateFloatAsState(fraction, tween(300), label = "importBar")
            Text("Matching ${state.done} of ${state.total}", style = MaterialTheme.typography.titleMedium, color = ink())
            state.current?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = ink(0.6f),
                    maxLines = 1,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(CircleShape)
                    .background(ink(0.12f)),
            ) {
                Box(Modifier.fillMaxWidth(bar.coerceAtLeast(0.02f)).fillMaxHeight().background(ink(0.9f), CircleShape))
            }
        }

        is ImportState.Done -> {
            Column {
                Text(
                    when {
                        state.songs == 0 -> "Nothing could be matched"
                        state.cancelled -> "Stopped after ${state.songs} songs"
                        else -> "Imported ${state.songs} songs" + if (state.playlists > 0) " into ${state.playlists} playlists" else ""
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = ink(),
                )
                if (state.missed > 0) {
                    Text(
                        "${state.missed} songs couldn\u2019t be found in the catalog.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ink(0.6f),
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }

        is ImportState.Failed -> {
            Text(state.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun PageLayout(
    content: @Composable ColumnScope.() -> Unit,
    actions: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), content = content)
        Column(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 20.dp), content = actions)
    }
}

@Composable
private fun SetupHeader(
    page: Int,
    onSkip: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(PAGE_COUNT) { index ->
                val color by animateColorAsState(if (index <= page) ink(0.9f) else ink(0.22f), tween(250), label = "dot$index")
                Box(Modifier.size(width = if (index == page) 22.dp else 8.dp, height = 8.dp).clip(CircleShape).background(color))
            }
        }
        Text(
            "Skip setup",
            style = MaterialTheme.typography.titleSmall,
            color = ink(0.6f),
            modifier = Modifier.clip(CircleShape).clickable(onClick = onSkip).padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun SetupTitle(text: String) {
    Text(
        text,
        style =
            MaterialTheme.typography.displaySmall.copy(
                fontFamily = GamutDisplayFont,
                fontSize = 38.sp,
                lineHeight = 41.sp,
                letterSpacing = (-1.2).sp,
                fontWeight = FontWeight.ExtraBold,
            ),
        color = ink(),
    )
}

@Composable
private fun SetupBody(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = ink(0.65f))
}

@Composable
private fun SetupLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold), color = ink(0.5f))
}

@Composable
private fun SetupLink(
    label: String,
    onClick: () -> Unit,
) {
    Text(
        label,
        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
        color = ink(0.9f),
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(vertical = 4.dp),
    )
}

@Composable
private fun NameField(
    value: String,
    onValueChange: (String) -> Unit,
    onDone: () -> Unit,
) {
    val style =
        MaterialTheme.typography.displaySmall.copy(
            fontFamily = GamutDisplayFont,
            fontSize = 26.sp,
            lineHeight = 30.sp,
            letterSpacing = (-0.6).sp,
            fontWeight = FontWeight.ExtraBold,
            color = ink(),
        )
    Box(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(CircleShape)
            .background(ink(0.10f))
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) Text("Your name", style = style, color = ink(0.35f), maxLines = 1)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = style,
            cursorBrush = SolidColor(ink()),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SetupButton(
    label: String,
    onClick: () -> Unit,
    primary: Boolean,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, spring(stiffness = 900f), label = "setupButtonScale")
    val fade by animateFloatAsState(if (enabled) 1f else 0.4f, tween(180), label = "setupButtonAlpha")
    val onPrimary = if (LocalCoverPalette.current.isLight) Color.White else Color.Black
    Box(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = fade
            }.focusRing(CircleShape)
            .clip(CircleShape)
            .background(if (primary) ink(0.95f) else ink(0.12f))
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp, fontWeight = FontWeight.Bold),
            color = if (primary) onPrimary else ink(),
            maxLines = 1,
        )
    }
}

@Composable
private fun RowScope.ChoiceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val onPrimary = if (LocalCoverPalette.current.isLight) Color.White else Color.Black
    val background by animateColorAsState(if (selected) ink(0.95f) else ink(0.12f), tween(200), label = "chipBg")
    Box(
        Modifier
            .weight(1f)
            .height(44.dp)
            .clip(CircleShape)
            .background(background)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            color = if (selected) onPrimary else ink(),
            maxLines = 1,
        )
    }
}

@Composable
private fun SwatchFrame(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    fill: @Composable () -> Unit,
) {
    val ring by animateColorAsState(if (selected) ink(0.95f) else ink(0.2f), tween(200), label = "swatchRing")
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(54.dp)
                .border(if (selected) 2.5.dp else 1.dp, ring, CircleShape)
                .padding(5.dp)
                .clip(CircleShape)
                .clickable(onClick = onClick),
        ) { fill() }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = ink(if (selected) 0.95f else 0.55f), maxLines = 1)
    }
}

@Composable
private fun AdaptiveSwatch(
    selected: Boolean,
    onClick: () -> Unit,
) {
    SwatchFrame("Adaptive", selected, onClick) {
        Box(
            Modifier.fillMaxSize().background(
                Brush.sweepGradient(
                    listOf(
                        Color(0xFFFA2D48),
                        Color(0xFFFFB300),
                        Color(0xFF00C853),
                        Color(0xFF1E88E5),
                        Color(0xFFD500F9),
                        Color(0xFFFA2D48),
                    ),
                ),
            ),
        )
    }
}

@Composable
private fun PresetSwatch(
    preset: AppColorPreset,
    dark: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val palette = if (dark) preset.dark else preset.light
    SwatchFrame(preset.name, selected, onClick) {
        Box(Modifier.fillMaxSize().background(palette.base)) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(palette.accent),
            )
        }
    }
}
