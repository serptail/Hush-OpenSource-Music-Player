package com.gamut.onboarding

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.Folder
import com.adamglin.phosphoricons.regular.LockKey
import com.gamut.R
import com.gamut.core.designsystem.AcrylicCard
import com.gamut.core.designsystem.AcrylicHost
import com.gamut.core.designsystem.AcrylicMaterials
import com.gamut.core.designsystem.AppColorPreset
import com.gamut.core.designsystem.AppColorPresets
import com.gamut.core.designsystem.AppThemeMode
import com.gamut.core.designsystem.GamutDisplayFont
import com.gamut.core.designsystem.LocalCoverPalette
import com.gamut.core.designsystem.acrylicSource
import com.gamut.core.designsystem.bounceClickable
import com.gamut.core.designsystem.collectIsPulsedAsState
import com.gamut.core.designsystem.focusRing
import com.gamut.core.designsystem.ink
import com.gamut.onboarding.applemusic.AppleMusicLinks
import com.gamut.onboarding.importer.ImportState
import com.gamut.onboarding.spotify.SpotifyLinks
import com.gamut.onboarding.ytmusic.YtMusicLoginScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val PAGE_NAME = 0
private const val PAGE_LOOK = 1
private const val PAGE_FOLDER = 2
private const val PAGE_IMPORT = 3
private const val PAGE_ACCOUNT = 4

private const val NAME_LIMIT = 40
private val BUTTON_HEIGHT = 58.dp
private const val SPOTIFY_DATA_URL = "https://www.spotify.com/account/privacy/"

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SetupScreen(vm: SetupViewModel = hiltViewModel()) {
    val appearance by vm.appearance.collectAsState()
    val palette = LocalCoverPalette.current
    val light = palette.isLight
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val view = LocalView.current

    DisposableEffect(view) {
        val window = view.context.findActivity()?.window
        val previous = window?.attributes?.softInputMode
        window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        onDispose { if (window != null && previous != null) window.setSoftInputMode(previous) }
    }

    val pageCount = if (vm.syncAvailable) 5 else 4
    val pager = rememberPagerState(pageCount = { pageCount })
    var name by rememberSaveable { mutableStateOf(vm.initialName) }
    var showYouTubeLogin by rememberSaveable { mutableStateOf(false) }
    var spotifyOpen by rememberSaveable { mutableStateOf(false) }
    var pasted by rememberSaveable { mutableStateOf("") }
    var appleOpen by rememberSaveable { mutableStateOf(false) }
    var applePasted by rememberSaveable { mutableStateOf("") }
    val importState by vm.importState.collectAsState()
    val busy = importState is ImportState.Reading || importState is ImportState.Matching
    val keyboard = WindowInsets.isImeVisible

    LaunchedEffect(busy) {
        if (busy) {
            spotifyOpen = false
            appleOpen = false
        }
    }

    fun goTo(page: Int) {
        focus.clearFocus()
        scope.launch { pager.animateScrollToPage(page.coerceIn(0, pageCount - 1)) }
    }
    BackHandler(enabled = spotifyOpen) { spotifyOpen = false }
    BackHandler(enabled = appleOpen) { appleOpen = false }
    BackHandler(enabled = pager.currentPage > 0 && !showYouTubeLogin && !spotifyOpen && !appleOpen) { goTo(pager.currentPage - 1) }

    LaunchedEffect(pager.settledPage) {
        if (pager.settledPage != PAGE_NAME) {
            focus.clearFocus()
            if (name.isNotBlank()) vm.saveName(name)
        }
    }

    val tint: Color? =
        if (appearance.adaptive) {
            null
        } else {
            AppColorPresets.byId(appearance.preset).let { if (light) it.light else it.dark }.accent
        }

    val handle = ink()
    val selection = remember(handle) { TextSelectionColors(handleColor = handle, backgroundColor = handle.copy(alpha = 0.25f)) }

    CompositionLocalProvider(LocalTextSelectionColors provides selection) {
        AcrylicHost(
            Modifier
                .fillMaxSize()
                .background(palette.base)
                .pointerInput(Unit) { detectTapGestures { focus.clearFocus() } },
        ) {
            GamutBackground(page = pager.currentPage, tint = tint, modifier = Modifier.acrylicSource())

            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                ProgressDots(current = pager.currentPage, count = pageCount, onSelect = ::goTo)

                HorizontalPager(state = pager, modifier = Modifier.weight(1f).fillMaxWidth(), key = { it }) { page ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                        BoxWithConstraints(Modifier.widthIn(max = 560.dp).fillMaxSize()) {
                            val top by animateDpAsState(
                                if (keyboard) 16.dp else (maxHeight * 0.12f).coerceAtLeast(28.dp),
                                tween(200),
                                label = "pageTop",
                            )
                            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp)) {
                                Spacer(Modifier.height(top))
                                when (page) {
                                    PAGE_NAME -> {
                                        NamePage(name, onNameChange = { name = it.take(NAME_LIMIT) })
                                    }

                                    PAGE_LOOK -> {
                                        LookPage(vm)
                                    }

                                    PAGE_FOLDER -> {
                                        FolderPage(vm)
                                    }

                                    PAGE_IMPORT -> {
                                        ImportPage(
                                            vm,
                                            onYouTube = { showYouTubeLogin = true },
                                            onSpotify = { spotifyOpen = true },
                                            onAppleMusic = { appleOpen = true },
                                        )
                                    }

                                    else -> {
                                        AccountPage(vm)
                                    }
                                }
                                Spacer(Modifier.height(24.dp))
                            }
                        }
                    }
                }

                AnimatedVisibility(visible = !keyboard) {
                    val page = pager.currentPage
                    if (vm.syncAvailable && page == PAGE_ACCOUNT) {
                        AccountActions(vm, onBack = { goTo(page - 1) })
                    } else {
                        val isLast = page == pageCount - 1
                        val nothingImported = page == PAGE_IMPORT && (importState is ImportState.Idle || importState is ImportState.Failed)
                        NavBar(
                            showBack = page > 0,
                            label =
                                when {
                                    isLast && nothingImported -> "Skip and finish"
                                    isLast -> "Finish setup"
                                    nothingImported -> "Skip for now"
                                    else -> "Continue"
                                },
                            onBack = { goTo(page - 1) },
                            onNext = { if (isLast) vm.finish() else goTo(page + 1) },
                        )
                    }
                }
            }

            SpotifySheet(
                visible = spotifyOpen,
                text = pasted,
                onText = { pasted = it },
                onImport = { vm.startSpotifyLinks(pasted) },
                onFiles = { uris ->
                    spotifyOpen = false
                    vm.startFileImport(uris)
                },
                onClose = {
                    focus.clearFocus()
                    spotifyOpen = false
                },
            )

            AppleMusicSheet(
                visible = appleOpen,
                text = applePasted,
                onText = { applePasted = it },
                onImport = { vm.startAppleMusicLinks(applePasted) },
                onClose = {
                    focus.clearFocus()
                    appleOpen = false
                },
            )

            if (showYouTubeLogin) {
                YtMusicLoginScreen(
                    onCaptured = { session ->
                        showYouTubeLogin = false
                        vm.startYouTubeImport(session)
                    },
                    onClose = { showYouTubeLogin = false },
                )
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

@Composable
private fun NamePage(
    name: String,
    onNameChange: (String) -> Unit,
) {
    PageTitle("Hey. What should we call you?")
    Spacer(Modifier.height(14.dp))
    PageBody("A first name or a nickname is perfect. It shows on your profile, and you can change it any time in Settings.")
    Spacer(Modifier.height(40.dp))
    NameField(name, onNameChange)
}

@Composable
private fun LookPage(vm: SetupViewModel) {
    val appearance by vm.appearance.collectAsState()
    val dark = !LocalCoverPalette.current.isLight
    PageTitle("Pick your palette")
    Spacer(Modifier.height(14.dp))
    PageBody("Everything you see here restyles as you choose. You can change it any time in Settings, then Appearance.")
    Spacer(Modifier.height(28.dp))

    Sheet {
        SheetLabel("THEME")
        Spacer(Modifier.height(10.dp))
        val modes =
            buildList {
                if (appearance.adaptive) add(AppThemeMode.Auto to "Auto")
                add(AppThemeMode.System to "System")
                add(AppThemeMode.Light to "Light")
                add(AppThemeMode.Dark to "Dark")
            }
        Segmented(modes.map { it.second }, selected = modes.indexOfFirst { it.first == appearance.mode }) { vm.setMode(modes[it].first) }

        Spacer(Modifier.height(24.dp))
        SheetLabel("COLOR")
        Spacer(Modifier.height(12.dp))
        val tiles: List<@Composable (Modifier) -> Unit> =
            buildList {
                add { modifier -> AdaptiveTile(modifier, selected = appearance.adaptive, onClick = vm::useAdaptive) }
                AppColorPresets.all.forEach { preset ->
                    add { modifier ->
                        PresetTile(
                            modifier,
                            preset,
                            dark,
                            selected = !appearance.adaptive && appearance.preset == preset.id,
                            onClick = { vm.usePreset(preset.id) },
                        )
                    }
                }
            }
        tiles.chunked(5).forEachIndexed { rowIndex, rowTiles ->
            if (rowIndex > 0) Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                rowTiles.forEach { tile -> tile(Modifier.weight(1f)) }
                repeat(5 - rowTiles.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    PageBody(if (appearance.adaptive) "Follows the album that\u2019s playing." else "One fixed color everywhere.")
}

@Composable
private fun FolderPage(vm: SetupViewModel) {
    val folder by vm.folder.collectAsState()
    var rejected by remember { mutableStateOf(false) }
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree: Uri? ->
            if (tree != null) rejected = !vm.chooseFolder(tree)
        }
    PageTitle("Where should music live?")
    Spacer(Modifier.height(14.dp))
    PageBody(
        "Downloads go to the place you pick, with cover, artist info and lyrics saved inside each file, so other apps can play them too.",
    )
    Spacer(Modifier.height(28.dp))

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        OptionCard(
            modifier = Modifier.weight(1f),
            label = "App storage",
            hint = "Private to Gamut",
            selected = folder == null,
            onClick = {
                rejected = false
                vm.useAppStorage()
            },
        ) { color -> PhosphorGlyph(PhosphorIcons.Regular.LockKey, color) }
        OptionCard(
            modifier = Modifier.weight(1f),
            label = "Your folder",
            hint = "Phone or SD card",
            selected = folder != null,
            onClick = {
                rejected = false
                picker.launch(null)
            },
        ) { color -> PhosphorGlyph(PhosphorIcons.Regular.Folder, color) }
    }

    AnimatedVisibility(
        visible = folder != null,
        enter = fadeIn(tween(250)) + expandVertically(tween(300)),
        exit = fadeOut(tween(150)) + shrinkVertically(tween(200)),
    ) {
        Column {
            Spacer(Modifier.height(12.dp))
            Panel(Modifier.fillMaxWidth(), cornerRadius = 20.dp) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        folder.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = ink(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Pill("Change", onClick = { picker.launch(null) })
                }
            }
        }
    }
    if (rejected) {
        Spacer(Modifier.height(10.dp))
        Text(
            "Pick a folder on your phone or SD card.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun ImportPage(
    vm: SetupViewModel,
    onYouTube: () -> Unit,
    onSpotify: () -> Unit,
    onAppleMusic: () -> Unit,
) {
    val state by vm.importState.collectAsState()
    val busy = state is ImportState.Reading || state is ImportState.Matching
    val light = LocalCoverPalette.current.isLight

    PageTitle("Bring your music along")
    Spacer(Modifier.height(14.dp))
    PageBody(
        "Import your playlists and liked songs. Gamut finds each song in its own catalog, so nothing is copied from the other service.",
    )
    Spacer(Modifier.height(28.dp))

    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        SourceTile(
            Modifier.fillMaxWidth(),
            description = "Sign in with Google to import your liked songs and playlists.",
            label = "Sign in",
            enabled = !busy,
            appearDelay = 0,
            onClick = onYouTube,
        ) {
            Image(
                painterResource(if (light) R.drawable.ic_ytmusic_full else R.drawable.ic_ytmusic_full_dark),
                contentDescription = "YouTube Music",
                modifier = Modifier.fillMaxWidth().height(30.dp),
                contentScale = ContentScale.Fit,
                alignment = Alignment.CenterStart,
            )
        }
        SourceTile(
            Modifier.fillMaxWidth(),
            description = "Paste playlist links, or use your Spotify data download.",
            label = "Paste links",
            enabled = !busy,
            appearDelay = 90,
            onClick = onSpotify,
        ) {
            Image(
                painterResource(R.drawable.ic_spotify_full),
                contentDescription = "Spotify",
                modifier = Modifier.fillMaxWidth().height(30.dp),
                contentScale = ContentScale.Fit,
                alignment = Alignment.CenterStart,
            )
        }
        SourceTile(
            Modifier.fillMaxWidth(),
            description = "Paste links to public playlists and albums.",
            label = "Paste links",
            enabled = !busy,
            appearDelay = 180,
            onClick = onAppleMusic,
        ) {
            AppleMusicLogo(Modifier.fillMaxWidth())
        }
    }
    ImportStatus(state, onCancel = vm::cancelImport)
}

@Composable
private fun AppleMusicLogo(modifier: Modifier = Modifier) {
    Box(modifier.height(30.dp), contentAlignment = Alignment.CenterStart) {
        Icon(
            painterResource(R.drawable.ic_applemusic_full),
            contentDescription = "Apple Music",
            tint = ink(0.9f),
            modifier = Modifier.height(18.dp),
        )
    }
}

@Composable
private fun AccountPage(vm: SetupViewModel) {
    val account by vm.account.collectAsState()
    PageTitle("Keep it all in sync")
    Spacer(Modifier.height(14.dp))
    PageBody(
        "Sign in with Google to back up your library and settings and have them on all your devices. Or keep everything on this device, with a daily backup.",
    )
    account.error?.let {
        Spacer(Modifier.height(20.dp))
        Sheet { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun SourceTile(
    modifier: Modifier,
    description: String,
    label: String,
    enabled: Boolean,
    appearDelay: Int,
    onClick: () -> Unit,
    logo: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pulsed by interaction.collectIsPulsedAsState()
    val scale by animateFloatAsState(if (pulsed) 0.96f else 1f, spring(stiffness = 900f), label = "tileScale")
    val appear = rememberAppear(appearDelay)
    Panel(
        modifier =
            modifier
                .heightIn(min = 96.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    alpha = appear.value * (if (enabled) 1f else 0.5f)
                    translationY = (1f - appear.value) * 40f
                }.clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick),
        cornerRadius = 26.dp,
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                logo()
                Spacer(Modifier.height(8.dp))
                Text(description, style = MaterialTheme.typography.bodySmall, color = ink(0.62f))
            }
            Spacer(Modifier.width(16.dp))
            Pill(label)
        }
    }
}

@Composable
private fun Panel(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 24.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = RoundedCornerShape(cornerRadius)
    Box(modifier.clip(shape).background(ink(0.09f)).border(1.dp, ink(0.08f), shape), content = content)
}

@Composable
private fun rememberAppear(delayMs: Int): State<Float> {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(delayMs.toLong())
        shown = true
    }
    return animateFloatAsState(if (shown) 1f else 0f, tween(450), label = "appear")
}

@Composable
private fun Pill(
    label: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
        color = ink(0.9f),
        maxLines = 1,
        modifier =
            modifier
                .clip(CircleShape)
                .background(ink(0.12f))
                .then(if (onClick != null) Modifier.bounceClickable(onClick = onClick) else Modifier)
                .padding(horizontal = 18.dp, vertical = 9.dp),
    )
}

@Composable
private fun ImportStatus(
    state: ImportState,
    onCancel: () -> Unit,
) {
    AnimatedVisibility(
        visible = state !is ImportState.Idle,
        enter = fadeIn(tween(250)) + expandVertically(tween(300)),
        exit = fadeOut(tween(150)) + shrinkVertically(tween(200)),
    ) {
        Column {
            Spacer(Modifier.height(14.dp))
            Panel(Modifier.fillMaxWidth(), cornerRadius = 24.dp) {
                Column(Modifier.fillMaxWidth().padding(18.dp).animateContentSize()) {
                    when (state) {
                        ImportState.Idle -> {
                            Unit
                        }

                        is ImportState.Reading -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.5.dp, color = ink())
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    "Reading ${state.what}\u2026",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = ink(),
                                )
                            }
                        }

                        is ImportState.Matching -> {
                            val fraction = (state.done.toFloat() / state.total.coerceAtLeast(1)).coerceIn(0f, 1f)
                            val bar by animateFloatAsState(fraction, tween(300), label = "importBar")
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(
                                    "${state.done}",
                                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.ExtraBold),
                                    color = ink(),
                                )
                                Text(
                                    " / ${state.total}",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = ink(0.5f),
                                    modifier = Modifier.padding(bottom = 3.dp).weight(1f),
                                )
                                Pill("Cancel", onClick = onCancel)
                            }
                            Spacer(Modifier.height(12.dp))
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(CircleShape)
                                    .background(ink(0.12f)),
                            ) {
                                Box(Modifier.fillMaxWidth(bar.coerceAtLeast(0.02f)).fillMaxHeight().background(ink(0.95f), CircleShape))
                            }
                            state.current?.let {
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = ink(0.6f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "You can keep going. The import carries on in the background, and its progress shows in your Library.",
                                style = MaterialTheme.typography.bodySmall,
                                color = ink(0.5f),
                            )
                        }

                        is ImportState.Done -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (state.songs > 0) {
                                    CheckBadge()
                                    Spacer(Modifier.width(14.dp))
                                }
                                Column {
                                    Text(
                                        when {
                                            state.songs == 0 -> {
                                                "Nothing could be matched"
                                            }

                                            state.cancelled -> {
                                                "Stopped after ${state.songs} songs"
                                            }

                                            else -> {
                                                "Imported ${state.songs} songs" +
                                                    if (state.playlists > 0) " into ${state.playlists} playlists" else ""
                                            }
                                        },
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                        color = ink(),
                                    )
                                    val detail =
                                        buildList {
                                            if (state.missed > 0) add("${state.missed} not found")
                                            if (state.unreadable > 0) add("${state.unreadable} unreadable")
                                        }.joinToString(" \u00B7 ")
                                    if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodyMedium, color = ink(0.6f))
                                }
                            }
                        }

                        is ImportState.Failed -> {
                            Text(state.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CheckBadge() {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) { progress.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 300f)) }
    val fill = ink(0.95f)
    val tick = if (LocalCoverPalette.current.isLight) Color.White else Color.Black
    Canvas(Modifier.size(34.dp)) {
        val p = progress.value.coerceIn(0f, 1.2f)
        drawCircle(fill, radius = size.minDimension / 2 * p)
        val path =
            Path().apply {
                moveTo(size.width * 0.28f, size.height * 0.52f)
                lineTo(size.width * 0.44f, size.height * 0.67f)
                lineTo(size.width * 0.73f, size.height * 0.35f)
            }
        drawPath(
            path,
            tick.copy(alpha = progress.value.coerceIn(0f, 1f)),
            style = Stroke(size.width * 0.09f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

@Composable
private fun SpotifySheet(
    visible: Boolean,
    text: String,
    onText: (String) -> Unit,
    onImport: () -> Unit,
    onFiles: (List<Uri>) -> Unit,
    onClose: () -> Unit,
) {
    val links = LocalUriHandler.current
    val files =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) onFiles(uris)
        }
    LinkSheet(
        visible = visible,
        text = text,
        onText = onText,
        count = remember(text) { SpotifyLinks.parse(text).size + SpotifyLinks.shortLinks(text).size },
        placeholder = "https://open.spotify.com/playlist/\u2026",
        hint = "Public playlists and albums only. One link per line, or paste several at once.",
        onImport = onImport,
        onClose = onClose,
        logo = {
            Image(
                painterResource(R.drawable.ic_spotify_full),
                contentDescription = "Spotify",
                modifier = Modifier.weight(1f).height(28.dp),
                contentScale = ContentScale.Fit,
                alignment = Alignment.CenterStart,
            )
        },
        footer = {
            SetupLink("Choose data files") { files.launch(arrayOf("*/*")) }
            SetupLink("Where to find it") { links.openUri(SPOTIFY_DATA_URL) }
        },
    )
}

@Composable
private fun AppleMusicSheet(
    visible: Boolean,
    text: String,
    onText: (String) -> Unit,
    onImport: () -> Unit,
    onClose: () -> Unit,
) {
    LinkSheet(
        visible = visible,
        text = text,
        onText = onText,
        count = remember(text) { AppleMusicLinks.parse(text).size },
        placeholder = "https://music.apple.com/us/playlist/\u2026",
        hint = "Public playlists and albums only. One link per line, or paste several at once.",
        onImport = onImport,
        onClose = onClose,
        logo = {
            AppleMusicLogo(Modifier.weight(1f))
        },
    )
}

@Composable
private fun LinkSheet(
    visible: Boolean,
    text: String,
    onText: (String) -> Unit,
    count: Int,
    placeholder: String,
    hint: String,
    onImport: () -> Unit,
    onClose: () -> Unit,
    logo: @Composable RowScope.() -> Unit,
    footer: (@Composable RowScope.() -> Unit)? = null,
) {
    val clipboard = LocalClipboardManager.current
    val base = LocalCoverPalette.current.base
    val pop = remember { Animatable(1f) }
    LaunchedEffect(count) {
        if (count > 0) {
            pop.snapTo(1.15f)
            pop.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 500f))
        }
    }

    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible = visible, enter = fadeIn(tween(200)), exit = fadeOut(tween(160))) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.38f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose),
            )
        }
        AnimatedVisibility(
            visible = visible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(spring(dampingRatio = 0.85f, stiffness = 380f)) { it } + fadeIn(tween(160)),
            exit = slideOutVertically(tween(180)) { it } + fadeOut(tween(140)),
        ) {
            val shape = RoundedCornerShape(30.dp)
            Box(
                Modifier
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(12.dp)
                    .widthIn(max = 536.dp)
                    .fillMaxWidth(),
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .background(base)
                        .border(1.dp, ink(0.10f), shape)
                        .padding(20.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        logo()
                        Pill("Close", onClick = onClose)
                    }
                    Spacer(Modifier.height(16.dp))
                    LinkField(text, onText, placeholder)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = ink(0.5f),
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            when (count) {
                                0 -> "No links yet"
                                1 -> "1 link ready"
                                else -> "$count links ready"
                            },
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = ink(if (count > 0) 0.95f else 0.5f),
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .graphicsLayer {
                                        scaleX = pop.value
                                        scaleY = pop.value
                                        transformOrigin =
                                            androidx.compose.ui.graphics
                                                .TransformOrigin(0f, 0.5f)
                                    },
                        )
                        Pill("Paste", onClick = { clipboard.getText()?.text?.let { onText(if (text.isBlank()) it else text + "\n" + it) } })
                    }
                    Spacer(Modifier.height(16.dp))
                    PrimaryButton(
                        label = if (count > 1) "Import $count links" else "Import",
                        onClick = onImport,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = count > 0,
                    )
                    if (footer != null) {
                        Spacer(Modifier.height(6.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { footer() }
                    }
                }
            }
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
private fun OptionCard(
    modifier: Modifier,
    label: String,
    hint: String,
    selected: Boolean,
    onClick: () -> Unit,
    glyph: @Composable (Color) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pulsed by interaction.collectIsPulsedAsState()
    val scale by animateFloatAsState(if (pulsed) 0.96f else 1f, spring(stiffness = 900f), label = "optionScale")
    val ring by animateColorAsState(if (selected) ink(0.95f) else Color.Transparent, tween(220), label = "optionRing")
    val badge by animateFloatAsState(if (selected) 1f else 0f, spring(dampingRatio = 0.5f, stiffness = 400f), label = "optionBadge")
    val shape = RoundedCornerShape(26.dp)
    val glyphColor = ink(if (selected) 0.95f else 0.6f)
    val tick = if (LocalCoverPalette.current.isLight) Color.White else Color.Black
    Panel(
        modifier =
            modifier
                .height(170.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }.clickable(interactionSource = interaction, indication = null, onClick = onClick),
        cornerRadius = 26.dp,
    ) {
        Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { glyph(glyphColor) }
            Text(label, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), color = ink(), maxLines = 1)
            Text(hint, style = MaterialTheme.typography.labelSmall, color = ink(0.55f), maxLines = 1)
        }
        Box(Modifier.fillMaxSize().border(2.5.dp, ring, shape))
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp)
                .size(24.dp)
                .graphicsLayer {
                    scaleX = badge
                    scaleY = badge
                    alpha = badge.coerceIn(0f, 1f)
                }.clip(CircleShape)
                .background(ink(0.95f)),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(12.dp)) {
                val path =
                    Path().apply {
                        moveTo(size.width * 0.1f, size.height * 0.55f)
                        lineTo(size.width * 0.38f, size.height * 0.82f)
                        lineTo(size.width * 0.92f, size.height * 0.18f)
                    }
                drawPath(path, tick, style = Stroke(size.width * 0.16f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

@Composable
private fun PhosphorGlyph(
    icon: ImageVector,
    color: Color,
) {
    Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(60.dp))
}

@Composable
private fun ProgressDots(
    current: Int,
    count: Int,
    onSelect: (Int) -> Unit,
) {
    Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            repeat(count) { index ->
                val width by animateDpAsState(
                    if (index ==
                        current
                    ) {
                        26.dp
                    } else {
                        8.dp
                    },
                    spring(dampingRatio = 0.8f, stiffness = 500f),
                    label = "dotWidth$index",
                )
                val color by animateColorAsState(if (index <= current) ink(1f) else ink(0.22f), tween(300), label = "dotColor$index")
                Box(
                    Modifier
                        .height(40.dp)
                        .bounceClickable(scaleDown = 0.7f) { onSelect(index) }
                        .padding(horizontal = 7.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .width(width)
                            .height(8.dp)
                            .clip(CircleShape)
                            .background(color),
                    )
                }
            }
        }
    }
}

@Composable
private fun BarFrame(content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(start = 28.dp, end = 28.dp, top = 12.dp, bottom = 16.dp),
            content = content,
        )
    }
}

@Composable
private fun BarRow(
    showBack: Boolean,
    onBack: () -> Unit,
    backEnabled: Boolean = true,
    main: @Composable (Modifier) -> Unit,
) {
    val backWeight by animateFloatAsState(if (showBack) 1f else 0f, tween(260), label = "backWeight")
    Row(Modifier.fillMaxWidth()) {
        if (backWeight > 0.01f) {
            GhostButton("Back", onBack, Modifier.weight(backWeight).graphicsLayer { alpha = backWeight }, enabled = backEnabled)
            Spacer(Modifier.width(12.dp * backWeight))
        }
        main(Modifier.weight(2f))
    }
}

@Composable
private fun NavBar(
    showBack: Boolean,
    label: String,
    onBack: () -> Unit,
    onNext: () -> Unit,
) {
    BarFrame {
        BarRow(showBack, onBack) { modifier -> PrimaryButton(label, onNext, modifier) }
    }
}

@Composable
private fun AccountActions(
    vm: SetupViewModel,
    onBack: () -> Unit,
) {
    val account by vm.account.collectAsState()
    val context = LocalContext.current
    BarFrame {
        GoogleButton(busy = account.busy, onClick = { vm.signIn(context) })
        Spacer(Modifier.height(10.dp))
        BarRow(showBack = true, onBack = onBack, backEnabled = !account.busy) { modifier ->
            GhostButton("Keep it on this device", vm::continueWithoutAccount, modifier, enabled = !account.busy)
        }
    }
}

@Composable
private fun PageTitle(text: String) {
    Text(
        text,
        style =
            MaterialTheme.typography.displaySmall.copy(
                fontFamily = GamutDisplayFont,
                fontSize = 40.sp,
                lineHeight = 42.sp,
                letterSpacing = (-1.4).sp,
                fontWeight = FontWeight.ExtraBold,
            ),
        color = ink(),
    )
}

@Composable
private fun PageBody(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = ink(0.62f))
}

@Composable
private fun SheetLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.4.sp, fontWeight = FontWeight.Bold), color = ink(0.5f))
}

@Composable
private fun Sheet(content: @Composable ColumnScope.() -> Unit) {
    AcrylicCard(Modifier.fillMaxWidth(), cornerRadius = 26.dp, material = AcrylicMaterials.Regular) {
        Column(Modifier.fillMaxWidth().padding(20.dp), content = content)
    }
}

@Composable
private fun SetupLink(
    label: String,
    onClick: () -> Unit,
) {
    Text(
        label,
        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
        color = ink(0.75f),
        maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).bounceClickable(onClick = onClick).padding(vertical = 8.dp, horizontal = 4.dp),
    )
}

@Composable
private fun NameField(
    value: String,
    onValueChange: (String) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val line by animateColorAsState(if (focused) ink(0.95f) else ink(0.25f), tween(220), label = "nameLine")
    val style =
        MaterialTheme.typography.displaySmall.copy(
            fontFamily = GamutDisplayFont,
            fontSize = 34.sp,
            lineHeight = 38.sp,
            letterSpacing = (-0.8).sp,
            fontWeight = FontWeight.ExtraBold,
            color = ink(),
        )
    Box(Modifier.fillMaxWidth()) {
        if (value.isEmpty()) Text("Your name", style = style, color = ink(0.28f), maxLines = 1)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = style,
            cursorBrush = SolidColor(ink()),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
            modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
        )
    }
    Spacer(Modifier.height(10.dp))
    Box(
        Modifier
            .fillMaxWidth()
            .height(3.dp)
            .clip(CircleShape)
            .background(line),
    )
}

@Composable
private fun Modifier.chrome(shape: Shape): Modifier {
    val light = LocalCoverPalette.current.isLight
    val top = if (light) Color(0xFF494B54) else Color.White
    val bottom = if (light) Color(0xFF0E0E10) else Color(0xFFD3D6DF)
    val rim =
        Brush.linearGradient(
            listOf(Color.White.copy(alpha = 0.55f), Color.White.copy(alpha = 0.05f), Color.White.copy(alpha = 0.25f)),
        )
    return this
        .clip(shape)
        .background(Brush.verticalGradient(listOf(top, bottom)))
        .border(0.75.dp, rim, shape)
}

@Composable
private fun PrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val pulsed by interaction.collectIsPulsedAsState()
    val scale by animateFloatAsState(if (pulsed) 0.97f else 1f, spring(stiffness = 900f), label = "primaryScale")
    val onPrimary = if (LocalCoverPalette.current.isLight) Color.White else Color.Black
    Box(
        modifier
            .height(BUTTON_HEIGHT)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }.focusRing(CircleShape)
            .then(if (enabled) Modifier.chrome(CircleShape) else Modifier.clip(CircleShape).background(ink(0.12f)))
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp, fontWeight = FontWeight.Bold),
            color = if (enabled) onPrimary else ink(0.4f),
            maxLines = 1,
        )
    }
}

@Composable
private fun GhostButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val pulsed by interaction.collectIsPulsedAsState()
    val scale by animateFloatAsState(if (pulsed) 0.97f else 1f, spring(stiffness = 900f), label = "ghostScale")
    Box(
        modifier
            .height(BUTTON_HEIGHT)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha *= if (enabled) 1f else 0.4f
            }.focusRing(CircleShape)
            .clip(CircleShape)
            .background(ink(0.12f))
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold),
            color = ink(0.92f),
            maxLines = 1,
        )
    }
}

@Composable
private fun Segmented(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    val onPrimary = if (LocalCoverPalette.current.isLight) Color.White else Color.Black
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(ink(0.08f))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEachIndexed { index, label ->
            val chosen = index == selected
            val background by animateColorAsState(if (chosen) ink(0.95f) else Color.Transparent, tween(200), label = "seg$index")
            Box(
                Modifier
                    .weight(1f)
                    .height(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(background)
                    .bounceClickable { onSelect(index) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = if (chosen) onPrimary else ink(0.8f),
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun TileFrame(
    modifier: Modifier,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    fill: @Composable () -> Unit,
) {
    val ring by animateColorAsState(if (selected) ink(0.95f) else Color.Transparent, tween(200), label = "tileRing")
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .border(2.5.dp, ring, RoundedCornerShape(18.dp))
                .padding(4.dp)
                .clip(RoundedCornerShape(14.dp))
                .bounceClickable(onClick = onClick),
        ) { fill() }
        Spacer(Modifier.height(5.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = ink(if (selected) 0.95f else 0.55f), maxLines = 1)
    }
}

@Composable
private fun AdaptiveTile(
    modifier: Modifier,
    selected: Boolean,
    onClick: () -> Unit,
) {
    TileFrame(modifier, "Adaptive", selected, onClick) {
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
private fun PresetTile(
    modifier: Modifier,
    preset: AppColorPreset,
    dark: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val palette = if (dark) preset.dark else preset.light
    TileFrame(modifier, preset.name, selected, onClick) {
        Box(Modifier.fillMaxSize().background(palette.base)) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .fillMaxSize(0.5f)
                    .clip(CircleShape)
                    .background(palette.accent),
            )
        }
    }
}

@Composable
private fun GoogleButton(
    busy: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pulsed by interaction.collectIsPulsedAsState()
    val scale by animateFloatAsState(if (pulsed) 0.97f else 1f, spring(stiffness = 900f), label = "googleButtonScale")
    val onPrimary = if (LocalCoverPalette.current.isLight) Color.White else Color.Black
    Row(
        Modifier
            .fillMaxWidth()
            .height(BUTTON_HEIGHT)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }.focusRing(CircleShape)
            .chrome(CircleShape)
            .clickable(interactionSource = interaction, indication = null, enabled = !busy, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.Black)
            } else {
                Image(painterResource(R.drawable.ic_google), contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.size(12.dp))
        Text(
            "Continue with Google",
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp, fontWeight = FontWeight.Bold),
            color = onPrimary,
            maxLines = 1,
        )
    }
}
