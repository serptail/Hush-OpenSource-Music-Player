package com.gamut.settings

import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ArrowCounterClockwise
import com.adamglin.phosphoricons.regular.CircleHalf
import com.adamglin.phosphoricons.regular.DropHalf
import com.adamglin.phosphoricons.regular.Gradient
import com.adamglin.phosphoricons.regular.Heart
import com.adamglin.phosphoricons.regular.MonitorPlay
import com.adamglin.phosphoricons.regular.Palette
import com.adamglin.phosphoricons.regular.Play
import com.adamglin.phosphoricons.regular.Snowflake
import com.adamglin.phosphoricons.regular.Sparkle
import com.adamglin.phosphoricons.regular.Speedometer
import com.adamglin.phosphoricons.regular.Stack
import com.adamglin.phosphoricons.regular.Waves
import com.gamut.core.designsystem.AcrylicHost
import com.gamut.core.designsystem.AcrylicPill
import com.gamut.core.designsystem.AppColorPresets
import com.gamut.core.designsystem.AlbumStyle
import com.gamut.core.designsystem.AppThemeMode
import com.gamut.core.designsystem.ArtistStyle
import com.gamut.core.designsystem.AppearanceLevel
import com.gamut.core.designsystem.AppearanceSettings
import com.gamut.core.designsystem.FlowStyle
import com.gamut.core.designsystem.FlowingBackground
import com.gamut.core.designsystem.GamutMotion
import com.gamut.core.designsystem.LocalCoverPalette
import com.gamut.core.designsystem.FavoritesStyle
import com.gamut.core.designsystem.PlayerStyle
import com.gamut.core.designsystem.VideoViewMode
import com.gamut.core.designsystem.PlaylistStyle
import com.gamut.core.designsystem.acrylicSource
import com.gamut.core.designsystem.ink
import com.gamut.core.designsystem.pressScale
import com.gamut.core.designsystem.softResize
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import kotlin.math.min
import kotlin.math.roundToInt

@HiltViewModel
class AppearanceViewModel
    @Inject
    constructor(
        private val store: AppearanceStore,
    ) : ViewModel() {
        val settings: StateFlow<AppearanceSettings> = store.settings
        val surfaceOpacity: StateFlow<Float> = store.surfaceOpacity
        val surfaceSolid: StateFlow<Boolean> = store.surfaceSolid
        val surfaceColor: StateFlow<Int> = store.surfaceColor
        val favoritesStyle: StateFlow<FavoritesStyle> = store.favoritesStyle
        val albumStyle: StateFlow<AlbumStyle> = store.albumStyle
        val artistStyle: StateFlow<ArtistStyle> = store.artistStyle
        val playlistStyle: StateFlow<PlaylistStyle> = store.playlistStyle

        fun setFavoritesStyle(value: FavoritesStyle) = store.setFavoritesStyle(value)

        fun setAlbumStyle(value: AlbumStyle) = store.setAlbumStyle(value)

        fun setArtistStyle(value: ArtistStyle) = store.setArtistStyle(value)

        fun setPlaylistStyle(value: PlaylistStyle) = store.setPlaylistStyle(value)

        fun setSurfaceOpacity(value: Float) = store.setSurfaceOpacity(value)

        fun setSurfaceSolid(value: Boolean) = store.setSurfaceSolid(value)

        fun setSurfaceColor(argb: Int) = store.setSurfaceColor(argb)

        fun update(transform: (AppearanceSettings) -> AppearanceSettings) = store.update(transform)

        fun reset() = store.reset()
    }

@Composable
fun AppearanceScreen(
    onBack: () -> Unit,
    nowPlaying: @Composable () -> Unit,
    vm: AppearanceViewModel = hiltViewModel(),
) {
    val s by vm.settings.collectAsState()
    val surface by vm.surfaceOpacity.collectAsState()
    val solid by vm.surfaceSolid.collectAsState()
    val surfaceColor by vm.surfaceColor.collectAsState()
    val favoritesStyle by vm.favoritesStyle.collectAsState()
    val albumStyle by vm.albumStyle.collectAsState()
    val artistStyle by vm.artistStyle.collectAsState()
    val playlistStyle by vm.playlistStyle.collectAsState()
    val systemDark = isSystemInDarkTheme()
    val showDark = s.resolvedDark(systemDark) ?: !LocalCoverPalette.current.isLight
    var confirmReset by remember { mutableStateOf(false) }
    val icons = PhosphorIcons.Regular

    val modes =
        buildList {
            if (s.adaptive) add(Choice(AppThemeMode.Auto, "Auto"))
            add(Choice(AppThemeMode.System, "System"))
            add(Choice(AppThemeMode.Light, "Light"))
            add(Choice(AppThemeMode.Dark, "Dark"))
        }

    GamutPage(title = "", onBack = onBack, nowPlaying = nowPlaying) {
        Text(
            "Appearance",
            style = MaterialTheme.typography.headlineLarge,
            color = ink(),
            modifier = Modifier.padding(top = 4.dp, start = 4.dp),
        )
        Preview(Modifier.padding(top = 16.dp))

        SettingsBlock(Modifier.softResize()) {
            GroupedHeader("Theme")
            GroupedSegmentedRow(
                title = "Mode",
                icon = icons.CircleHalf,
                options = modes,
                selected = s.effectiveMode,
                onSelect = { m -> vm.update { it.copy(mode = m) } },
                hint =
                    when (s.effectiveMode) {
                        AppThemeMode.Auto -> "Each album decides: light covers give a light app, dark covers a dark one."
                        AppThemeMode.System -> "Follows your phone's light / dark setting."
                        AppThemeMode.Light -> "Light everywhere, including sheets and pop-ups."
                        AppThemeMode.Dark -> "Dark everywhere, including sheets and pop-ups."
                    },
            )

            GroupedHeader("Background")
            GroupedSwitchRow(
                "Adaptive background",
                icons.Gradient,
                checked = s.adaptive,
                onChange = { v -> vm.update { it.copy(adaptive = v) } },
                subtitle = "Colors flow from the album you're playing",
            )
            AnimatedVisibility(s.adaptive, enter = GamutMotion.expandIn, exit = GamutMotion.collapseOut) {
                Column {
                    GroupedDivider()
                    GroupedSegmentedRow(
                        title = "Style",
                        icon = icons.Waves,
                        options =
                            listOf(
                                Choice(FlowStyle.Flow, "Flow"),
                                Choice(FlowStyle.Mercury, "Mercury"),
                                Choice(FlowStyle.Metaballs, "Blobs"),
                                Choice(FlowStyle.Dithering, "Dither"),
                            ),
                        selected = s.flowStyle,
                        onSelect = { v -> vm.update { it.copy(flowStyle = v) } },
                    )
                    GroupedDivider()
                    GroupedSwitchRow(
                        "Animate",
                        icons.Play,
                        checked = s.flowAnimated,
                        onChange = { v -> vm.update { it.copy(flowAnimated = v) } },
                        subtitle = "Slowly drifting color",
                    )
                    AnimatedVisibility(s.flowAnimated, enter = GamutMotion.expandIn, exit = GamutMotion.collapseOut) {
                        Column {
                            GroupedDivider()
                            GroupedSegmentedRow(
                                title = "Speed",
                                icon = icons.Speedometer,
                                options = levels("Slow", "Normal", "Fast"),
                                selected = s.flowSpeed,
                                onSelect = { v -> vm.update { it.copy(flowSpeed = v) } },
                            )
                            GroupedDivider()
                            GroupedSwitchRow(
                                "Freeze when paused",
                                icons.Snowflake,
                                checked = s.freezeWhenPaused,
                                onChange = { v -> vm.update { it.copy(freezeWhenPaused = v) } },
                                subtitle = "Stops the motion while nothing is playing",
                            )
                        }
                    }
                    GroupedDivider()
                    GroupedSegmentedRow(
                        title = "Intensity",
                        icon = icons.Sparkle,
                        options = levels("Calm", "Balanced", "Vivid"),
                        selected = s.flowIntensity,
                        onSelect = { v -> vm.update { it.copy(flowIntensity = v) } },
                    )
                }
            }
            AnimatedVisibility(!s.adaptive, enter = GamutMotion.expandIn, exit = GamutMotion.collapseOut) {
                Column {
                    GroupedDivider()
                    GroupedRow(
                        "Color",
                        icons.Palette,
                        subtitle = "Fixed colors in light and dark. They don't change with the music.",
                    )
                    Column(Modifier.padding(horizontal = GroupedRowInset).padding(top = 2.dp, bottom = 16.dp)) {
                        PresetGrid(selectedId = s.preset, dark = showDark) { id -> vm.update { it.copy(preset = id) } }
                    }
                }
            }

            GroupedHeader("Player")
            GroupedSegmentedRow(
                title = "Style",
                icon = icons.MonitorPlay,
                options =
                    listOf(
                        Choice(PlayerStyle.Classic, "Classic"),
                        Choice(PlayerStyle.Mesh, "Mesh"),
                        Choice(PlayerStyle.Cover, "Cover"),
                    ),
                selected = s.playerStyle,
                onSelect = { v -> vm.update { it.copy(playerStyle = v) } },
                hint =
                    when (s.playerStyle) {
                        PlayerStyle.Classic -> "The cover blurred behind the player, with the artwork in a progress ring."
                        PlayerStyle.Mesh -> "A plain cover that eases back when paused, with the screen below it carrying on in the cover's own colours."
                        PlayerStyle.Cover -> "Full-screen artwork that dissolves into a blurred reflection, with flowing lyrics."
                    },
            )
            GroupedDivider()
            GroupedSegmentedRow(
                title = "Video view",
                icon = icons.MonitorPlay,
                options =
                    listOf(
                        Choice(VideoViewMode.Full, "Full"),
                        Choice(VideoViewMode.Boxed, "Boxed"),
                    ),
                selected = s.videoViewMode,
                onSelect = { v -> vm.update { it.copy(videoViewMode = v) } },
                hint =
                    when (s.videoViewMode) {
                        VideoViewMode.Full -> "Music videos turn sideways and fill the whole screen, with the controls over the picture."
                        VideoViewMode.Boxed -> "Music videos play upright in a box under the top bar, with their own controls and a button to expand to landscape. The player stays as it is below."
                    },
            )

            GroupedHeader("Favorites")
            GroupedSegmentedRow(
                title = "Layout",
                icon = icons.Heart,
                options =
                    listOf(
                        Choice(FavoritesStyle.Grouped, "List"),
                        Choice(FavoritesStyle.Wheel, "Wheel"),
                    ),
                selected = favoritesStyle,
                onSelect = { v -> vm.setFavoritesStyle(v) },
                hint =
                    when (favoritesStyle) {
                        FavoritesStyle.Grouped -> "A cover mosaic on top, with your songs stacked in one block."
                        FavoritesStyle.Wheel -> "Songs docked on a thin disc. Scroll to the lit slot; only that song responds to a tap."
                    },
            )

            GroupedHeader("Album")
            GroupedSegmentedRow(
                title = "Layout",
                icon = icons.Stack,
                options =
                    listOf(
                        Choice(AlbumStyle.Classic, "Classic"),
                        Choice(AlbumStyle.Soft, "Soft"),
                        Choice(AlbumStyle.Full, "Full"),
                    ),
                selected = albumStyle,
                onSelect = { v -> vm.setAlbumStyle(v) },
                hint =
                    when (albumStyle) {
                        AlbumStyle.Classic -> "A large cover with the title beside a play button, and songs as flat rows."
                        AlbumStyle.Soft -> "A centered cover over a faint glow of its colours, Play and Shuffle pills, and songs in one rounded block."
                        AlbumStyle.Full -> "The cover fills the top edge to edge and melts into the page, with the title written over it."
                    },
            )

            GroupedHeader("Artist")
            GroupedSegmentedRow(
                title = "Layout",
                icon = icons.Stack,
                options =
                    listOf(
                        Choice(ArtistStyle.Classic, "Classic"),
                        Choice(ArtistStyle.Full, "Full"),
                        Choice(ArtistStyle.Showcase, "Showcase"),
                    ),
                selected = artistStyle,
                onSelect = { v -> vm.setArtistStyle(v) },
                hint =
                    when (artistStyle) {
                        ArtistStyle.Classic -> "A round portrait with the name on top and tabs down the side."
                        ArtistStyle.Full -> "The portrait fills the top edge to edge and melts into the page, with tab chips under it."
                        ArtistStyle.Showcase -> "A compact header, then the albums as big swipeable covers with the songs below."
                    },
            )

            GroupedHeader("Playlist")
            GroupedSegmentedRow(
                title = "Layout",
                icon = icons.Stack,
                options =
                    listOf(
                        Choice(PlaylistStyle.Classic, "Classic"),
                        Choice(PlaylistStyle.Vinyl, "Vinyl"),
                        Choice(PlaylistStyle.Immersive, "Immersive"),
                    ),
                selected = playlistStyle,
                onSelect = { v -> vm.setPlaylistStyle(v) },
                hint =
                    when (playlistStyle) {
                        PlaylistStyle.Classic -> "A centered cover with Play and Shuffle pills and a plain list of songs."
                        PlaylistStyle.Vinyl -> "The cover as a record sleeve with a record that spins while it plays, and songs in a numbered table."
                        PlaylistStyle.Immersive -> "A full-width cover that fades into the page, round action buttons and clean song rows."
                    },
            )

            GroupedHeader("Glass")
            GroupedSwitchRow(
                "Frosted glass",
                GamutIcons.FrostedGlass,
                checked = s.glass,
                onChange = { v -> vm.update { it.copy(glass = v) } },
                subtitle = "Blur behind the mini player, bars and buttons",
            )
            AnimatedVisibility(s.glass, enter = GamutMotion.expandIn, exit = GamutMotion.collapseOut) {
                Column {
                    GroupedDivider()
                    GroupedSegmentedRow(
                        title = "Density",
                        icon = icons.Stack,
                        options = levels("Light", "Balanced", "Dense"),
                        selected = s.glassDensity,
                        onSelect = { v -> vm.update { it.copy(glassDensity = v) } },
                    )
                }
            }
            GroupedDivider()
            GroupedSwitchRow(
                "Solid surface",
                icons.Stack,
                checked = solid,
                onChange = vm::setSurfaceSolid,
                subtitle = "Fill the setting blocks with a solid color instead of a see-through tint",
            )
            AnimatedVisibility(solid, enter = GamutMotion.expandIn, exit = GamutMotion.collapseOut) {
                Column {
                    GroupedDivider()
                    GroupedRow("Fill color", icons.Palette, subtitle = "Text adjusts to stay readable on it.")
                    SurfaceColorPicker(
                        selected = surfaceColor,
                        autoPreview = autoSurfaceColor(showDark),
                        onPick = vm::setSurfaceColor,
                    )
                }
            }
            AnimatedVisibility(!solid, enter = GamutMotion.expandIn, exit = GamutMotion.collapseOut) {
                Column {
                    GroupedDivider()
                    GroupedSliderRow(
                        title = "Surface opacity",
                        icon = icons.DropHalf,
                        valueLabel = "${(surface * 100).roundToInt()}%",
                        value = surface,
                        range = AppearanceStore.MIN_SURFACE_OPACITY..AppearanceStore.MAX_SURFACE_OPACITY,
                        onChange = vm::setSurfaceOpacity,
                        subtitle = "How strong the tint behind the setting blocks is",
                    )
                }
            }
            GroupedFooter("Turning glass off is lighter on battery.")

            GroupedHeader("Reset")
            GroupedRow(
                "Reset to defaults",
                icons.ArrowCounterClockwise,
                destructive = true,
                onClick = { confirmReset = true },
            )
            GroupedFooter("Puts every option on this page back to its default.")
            Spacer(Modifier.height(8.dp))
        }
    }

    if (confirmReset) {
        GamutDialog(
            title = "Reset appearance?",
            body = "Theme, background, player, glass and surface fill go back to their defaults.",
            confirmLabel = "Reset",
            destructive = true,
            onDismiss = { confirmReset = false },
            onConfirm = {
                vm.reset()
                confirmReset = false
            },
        )
    }
}

private fun levels(
    low: String,
    mid: String,
    high: String,
) = listOf(
    Choice(AppearanceLevel.Low, low),
    Choice(AppearanceLevel.Medium, mid),
    Choice(AppearanceLevel.High, high),
)

private enum class DeviceKind { Phone, Tablet, Tv }

/**
 * A framed miniature of the real screen. The background is laid out at the device's real size and
 * scaled down into the frame, so blob size and proportions match the app; the pill sits on top,
 * unscaled. The frame follows the device: phone, tablet or TV.
 */
@Composable
private fun Preview(modifier: Modifier = Modifier) {
    val palette = LocalCoverPalette.current
    val active = LocalPageActive.current
    val cfg = LocalConfiguration.current

    val kind =
        when {
            cfg.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION -> DeviceKind.Tv
            cfg.smallestScreenWidthDp >= 600 -> DeviceKind.Tablet
            else -> DeviceKind.Phone
        }
    val bezel = when (kind) { DeviceKind.Phone -> 5.dp; DeviceKind.Tablet -> 7.dp; DeviceKind.Tv -> 3.dp }
    val outerR = when (kind) { DeviceKind.Phone -> 28.dp; DeviceKind.Tablet -> 18.dp; DeviceKind.Tv -> 8.dp }
    val bezelColor = Color(0xFF0E0E10)

    val screenW = cfg.screenWidthDp.dp
    val screenH = cfg.screenHeightDp.dp

    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        // Fit the screen inside a max height (and the available width) without changing its shape.
        val maxScreenH = if (kind == DeviceKind.Tv) 200.dp else 240.dp
        val maxScreenW = (maxWidth - bezel * 2).coerceAtMost(if (kind == DeviceKind.Phone) 260.dp else 420.dp)
        val scale = min(maxScreenW / screenW, maxScreenH / screenH)
        val sw = screenW * scale
        val sh = screenH * scale

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .size(sw + bezel * 2, sh + bezel * 2)
                    .clip(RoundedCornerShape(outerR))
                    .background(bezelColor)
                    .border(1.dp, ink(0.14f), RoundedCornerShape(outerR))
                    .padding(bezel),
            ) {
                Box(Modifier.fillMaxSize().clip(RoundedCornerShape((outerR - bezel).coerceAtLeast(4.dp)))) {
                    AcrylicHost(Modifier.fillMaxSize()) {
                        Box(
                            Modifier
                                .requiredSize(screenW, screenH)
                                .align(Alignment.Center)
                                .graphicsLayer {
                                    scaleX = scale
                                    scaleY = scale
                                },
                        ) {
                            FlowingBackground(
                                palette = palette,
                                isPlaying = active,
                                visible = active,
                                modifier = Modifier.fillMaxSize().acrylicSource(),
                            )
                        }
                        AcrylicPill(Modifier.align(Alignment.Center).padding(horizontal = 8.dp)) {
                            Text("Now playing", style = MaterialTheme.typography.labelMedium, color = ink(), maxLines = 1)
                        }
                    }
                    if (kind == DeviceKind.Phone) {
                        Box(
                            Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 7.dp)
                                .size(6.dp)
                                .background(bezelColor, CircleShape),
                        )
                    }
                }
            }
            if (kind == DeviceKind.Tv) {
                // Stand
                Box(Modifier.size(sw * 0.28f, 6.dp).background(bezelColor, RoundedCornerShape(bottomStart = 4.dp, bottomEnd = 4.dp)))
            }
        }
    }
}

private val SurfacePresets =
    listOf(0xFFE7E7EB, 0xFFD9DDE6, 0xFFEDE6DC, 0xFF27272C, 0xFF1C2433, 0xFF2A2233)

@Composable
private fun SurfaceColorPicker(
    selected: Int,
    autoPreview: Color,
    onPick: (Int) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = GroupedRowInset).padding(top = 2.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        SurfaceSwatch(autoPreview, selected == AppearanceStore.SURFACE_COLOR_AUTO, label = "Auto") {
            onPick(AppearanceStore.SURFACE_COLOR_AUTO)
        }
        SurfacePresets.forEach { c ->
            SurfaceSwatch(Color(c), selected == c.toInt()) { onPick(c.toInt()) }
        }
    }
}

@Composable
private fun SurfaceSwatch(
    color: Color,
    selected: Boolean,
    label: String? = null,
    onClick: () -> Unit,
) {
    val ringWidth by animateDpAsState(if (selected) 2.dp else 1.dp, tween(GamutMotion.CONTENT_MS), label = "swatch-ring-width")
    val ringColor by animateColorAsState(
        if (selected) ink(0.95f) else ink(0.2f),
        tween(GamutMotion.CONTENT_MS),
        label = "swatch-ring-color",
    )
    Box(
        Modifier
            .size(38.dp)
            .pressScale(onClick = onClick, scaleDown = 0.95f)
            .clip(CircleShape)
            .background(color)
            .border(ringWidth, ringColor, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (label != null) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = if (color.luminance() > 0.5f) Color(0xFF111114) else Color(0xFFF2F2F5),
            )
        }
    }
}

@Composable
private fun PresetGrid(
    selectedId: String,
    dark: Boolean,
    onPick: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        AppColorPresets.all.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { p ->
                    val pal = if (dark) p.dark else p.light
                    val selected = p.id == selectedId
                    val ringWidth by animateDpAsState(
                        if (selected) 2.dp else 1.dp,
                        tween(GamutMotion.CONTENT_MS),
                        label = "preset-ring-width",
                    )
                    val ringColor by animateColorAsState(
                        if (selected) ink(0.95f) else ink(0.2f),
                        tween(GamutMotion.CONTENT_MS),
                        label = "preset-ring-color",
                    )
                    val labelColor by animateColorAsState(
                        ink(if (selected) 0.95f else 0.6f),
                        tween(GamutMotion.CONTENT_MS),
                        label = "preset-label",
                    )
                    Column(
                        Modifier.weight(1f).pressScale(onClick = { onPick(p.id) }, scaleDown = 0.95f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            Modifier
                                .size(52.dp)
                                .clip(CircleShape)
                                .background(pal.base)
                                .border(ringWidth, ringColor, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) { Box(Modifier.size(22.dp).background(pal.accent, CircleShape)) }
                        Text(
                            p.name,
                            style = MaterialTheme.typography.labelMedium,
                            color = labelColor,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}