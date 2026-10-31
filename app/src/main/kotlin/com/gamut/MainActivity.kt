package com.gamut

import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.activity.ComponentActivity
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import com.gamut.core.designsystem.MiniPlayerStrip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import com.gamut.account.AccountViewModel
import com.gamut.account.ProfileScreen
import com.gamut.settings.RecentlyPlayedScreen
import com.gamut.settings.LocalPageActive
import com.gamut.settings.RecentlyPlayedViewModel
import com.gamut.settings.StatsViewModel
import com.gamut.settings.PlaybackSettingsScreen
import com.gamut.settings.StorageSettingsScreen
import com.gamut.settings.FeedbackScreen
import com.gamut.settings.AboutScreen
import com.gamut.settings.TelemetryCollector
import com.gamut.settings.SettingsScreen
import com.gamut.settings.LocalSupportNudge
import com.gamut.settings.SupportConfig
import com.gamut.settings.supportNudgeCorona
import com.gamut.settings.SupportSheetHost
import com.gamut.settings.SupportViewModel
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import com.gamut.core.designsystem.GamutMotion
import com.gamut.core.designsystem.LocalNavSettled
import com.gamut.core.designsystem.LocalArtistPhotoResolver
import androidx.compose.ui.platform.LocalFocusManager
import com.gamut.feature.search.DiscoverRepository
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.gamut.core.designsystem.TransparentStatusBar
import kotlinx.coroutines.delay
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowCompat
import com.gamut.core.designsystem.CoverPalette
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.foundation.layout.Box
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.zIndex
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Bold
import com.adamglin.phosphoricons.bold.MagnifyingGlass
import com.adamglin.phosphoricons.bold.BookmarkSimple
import com.adamglin.phosphoricons.bold.User
import com.gamut.core.designsystem.BottomBarItem
import com.gamut.core.designsystem.GamutBottomBar
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.layout.fillMaxSize
import com.gamut.core.designsystem.GamutBoot
import com.gamut.core.designsystem.GamutLoadingScreen
import androidx.compose.ui.Modifier
import com.gamut.core.designsystem.AcrylicHost
import com.gamut.core.designsystem.CoverTheme
import com.gamut.core.designsystem.LocalNotifier
import com.gamut.core.designsystem.NoticeHost
import com.gamut.core.designsystem.rememberNotifier
import com.gamut.core.designsystem.acrylicSource
import com.gamut.core.designsystem.GamutBackdrop
import com.gamut.core.designsystem.FlowingBackground
import com.gamut.core.designsystem.ProfileAvatar
import com.gamut.core.designsystem.GamutTheme
import com.gamut.core.designsystem.LocalCoverPalette
import com.gamut.core.designsystem.AppearanceSettings
import com.gamut.core.designsystem.LocalAppearance
import com.gamut.core.designsystem.appNeutralPalette
import com.gamut.core.designsystem.rememberAppPalette
import com.gamut.settings.AppearanceScreen
import com.gamut.settings.AppearanceStore
import com.gamut.core.designsystem.squareArtUrl
import com.gamut.core.model.PlaybackState
import com.gamut.core.model.Track
import com.gamut.core.player.MusicPlayer
import com.gamut.feature.library.FavoritesScreen
import com.gamut.feature.library.EmptyStateArtistCovers
import com.gamut.feature.library.EmptyStateCovers
import com.gamut.feature.library.LibraryScreen
import com.gamut.core.database.DownloadIndex
import com.gamut.feature.library.LibraryViewModel
import com.gamut.feature.library.LocalMusicScreen
import com.gamut.feature.library.LocalAvailabilityViewModel
import com.gamut.feature.library.LocalMusicViewModel
import com.gamut.feature.library.withoutMissingLocal
import com.gamut.feature.library.localUri
import com.gamut.feature.library.playlistIdsContaining
import com.gamut.feature.resolver.TrackDownloadManager
import com.gamut.feature.library.PlaylistDownloadsViewModel
import com.gamut.feature.library.PlaylistScreen
import com.gamut.feature.playerui.PlaylistPickerItem
import com.gamut.feature.library.CreatePlaylistScreen
import com.gamut.feature.playerui.PlayerScreen
import com.gamut.feature.playerui.ConnectSheet
import com.gamut.feature.playerui.rememberOutputDevices
import com.gamut.feature.playerui.TrackActionsSheet
import com.gamut.feature.resolver.ResolveTrackSourceUseCase
import com.gamut.feature.resolver.ResolveFailure
import com.gamut.feature.resolver.AudioSourceRepository
import com.gamut.feature.resolver.NewPipeYoutubeSource
import com.gamut.feature.search.LocalPlaylistRemove
import com.gamut.feature.search.SearchScreen
import com.gamut.feature.search.TrackPlaylistOption
import com.gamut.feature.search.SearchViewModel
import com.gamut.feature.search.ShazamSheet
import com.gamut.feature.search.ShazamState
import com.gamut.feature.search.ShazamViewModel
import com.gamut.feature.search.ArtistScreen as DiscoverArtistScreen
import com.gamut.feature.search.ArtistViewModel
import com.gamut.feature.search.AlbumScreen
import com.gamut.feature.search.AlbumViewModel
import com.gamut.feature.search.DiscoverItem
import androidx.navigation.navArgument
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlin.math.abs

@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    @Inject lateinit var player: MusicPlayer
    @Inject lateinit var resolveTrackSource: ResolveTrackSourceUseCase
    @Inject lateinit var audioSources: AudioSourceRepository
    @Inject lateinit var newPipe: NewPipeYoutubeSource
    @Inject lateinit var downloadIndex: DownloadIndex
    @Inject lateinit var downloadManager: TrackDownloadManager
    @Inject lateinit var appearanceStore: AppearanceStore
    @Inject lateinit var telemetry: TelemetryCollector
    // Feeds the artist-stack portraits (see LocalArtistPhotoResolver in GamutApp).
    @Inject lateinit var discoverRepository: DiscoverRepository
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        // As early as possible (right after Hilt injects): installs the crash recorder and lifecycle breadcrumbs.
        telemetry.install()
        com.gamut.feature.playerui.components.WaveformSource.init(applicationContext)
        // Configure the window ONCE, up front. Doing this lazily when the player opens re-lays out the window
        // and briefly shows the default (white) window background.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        // The store is read synchronously, so the very first frame already has the saved look (no theme flash).
        val systemDark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val startPalette = appearanceStore.settings.value.neutralPalette(systemDark)
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = startPalette.isLight
        window.setBackgroundDrawable(ColorDrawable(startPalette.base.toArgb()))
        // Last resort for the back button: when no screen, sheet or layer wants it (their handlers are registered later, so
        // they run first), send the app to the background instead of finishing it. Finishing throws away every
        // ViewModel and replays the loading screen on the next launch; backgrounding keeps everything as it was.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { moveTaskToBack(true) }
        })
        setContent {
            val appearance by appearanceStore.settings.collectAsState()
            CompositionLocalProvider(LocalAppearance provides appearance) {
                // Outermost: nothing else is shown while a required update is pending.
                com.gamut.update.UpdateGate {
                    com.gamut.onboarding.OnboardingGate {
                        GamutApp(player, resolveTrackSource, audioSources, newPipe, downloadIndex, downloadManager, appearance, telemetry, discoverRepository)
                    }
                }
            }
        }
    }
}

@Composable
private fun GamutApp(player: MusicPlayer, resolveTrackSource: ResolveTrackSourceUseCase, audioSources: AudioSourceRepository, newPipe: NewPipeYoutubeSource, downloadIndex: DownloadIndex, downloadManager: TrackDownloadManager, appearance: AppearanceSettings, telemetry: TelemetryCollector, discoverRepository: DiscoverRepository) {
    val nav = rememberNavController()
    // App-wide toast pill (top). Screens reach it with LocalNotifier.current.
    val notifier = rememberNotifier()
    // One toast when a run of downloads ends: the song (if one was queued) or the playlist (if all of it was).
    val supportVm = hiltViewModel<SupportViewModel>()
    val supportNudge by supportVm.store.nudge.collectAsState()
    LaunchedEffect(downloadManager) {
        downloadManager.summaries.collect { s ->
            if (s.succeeded > 0) supportVm.store.recordDownloadSuccess()   // support: "first download" moment (once)
            when {
                s.succeeded == 0 -> notifier.error(if (s.failed == 1) "Couldn\u2019t download this song" else "Downloads failed")
                s.failed > 0 -> notifier.info("${s.succeeded} downloaded \u00B7 ${s.failed} failed")
                s.succeeded == 1 -> notifier.success("Downloaded \u201C${s.title}\u201D")
                s.label != null -> notifier.success("\u201C${s.label}\u201D downloaded")
                else -> notifier.success("${s.succeeded} songs downloaded")
            }
        }
    }
    // Shared with the search screen so Discover loads once; also drives the startup loading screen.
    val searchVm = hiltViewModel<SearchViewModel>()
    val discover by searchVm.discover.collectAsState()
    // Cover art for the empty-state cards (Library, empty playlist): five covers from the Discover charts we already fetched.
    LaunchedEffect(discover.albums, discover.artists) {
        EmptyStateCovers.offer(discover.albums.map { it.imageUrl } + discover.artists.map { it.imageUrl })
    }
    // Artist photos for the round covers on the Library "no artists yet" card (it reads EmptyStateArtistCovers).
    LaunchedEffect(discover.artists) {
        EmptyStateArtistCovers.offer(discover.artists.map { it.imageUrl })
    }
    // Bumped by the empty-state "Find ..." buttons: HomeTabs reacts by sliding to the Discover (search) tab.
    var openSearchTick by remember { mutableIntStateOf(0) }
    // Bumped by Settings > Your Playlists: HomeTabs slides to the Library tab.
    var openLibraryTick by remember { mutableIntStateOf(0) }
    // Never replay the splash once it has played in this process (GamutBoot, survives the activity and the composition),
    // nor after the system recreates the activity (rememberSaveable restores the saved value, also after process death).
    var showLoading by rememberSaveable { mutableStateOf(!GamutBoot.finished) }
    var forceReady by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(10_000); forceReady = true } // never trap the user if Discover fails
    val dataReady = forceReady || discover.tabs.isNotEmpty() || (!discover.isLoading && discover.error != null)
    // Warm-ups run only after the first screen is up, so they never compete with the startup requests or the
    // first frames: NewPipe once Discover has its charts, ExoPlayer once the loading screen has gone.
    LaunchedEffect(dataReady) { if (dataReady) { delay(500); newPipe.warmUp() } }
    LaunchedEffect(showLoading) { if (!showLoading) { delay(300); player.warmUp() } }
    // The player draws its own opaque background, so the flowing one underneath is paused while it is open.
    val currentRoute = nav.currentBackStackEntryAsState().value?.destination?.route
    // Discover / Library (HomeTabs) stay composed for the whole session and are drawn BELOW the NavHost. They are
    // "showing" on the home route, and also while the player slides over them.
    val homeShowing = currentRoute == null || currentRoute == "home"
    // The search field lives in HomeTabs, which stays composed UNDER every detail screen. If it keeps focus, its keyboard
    // stays up over the artist / album page and swallows the first back press (and the field keeps stealing the focus
    // ring). Dropping focus the moment home is covered gives the detail screen the keyboard-free window it expects.
    val focusManager = LocalFocusManager.current
    LaunchedEffect(homeShowing) { if (!homeShowing) focusManager.clearFocus(force = true) }
    // Name -> verified portrait, for the faded artist stacks (songs with several artists). Null = show the initial.
    val photoResolver = remember(discoverRepository) {
        val resolve: suspend (String, String?) -> String? = { name, album -> discoverRepository.artistPhotoFor(name, album.orEmpty()) }
        resolve
    }
    // Fade-through: home fades out fast (90 ms), and only then does a detail screen fade in (see the NavHost), so
    // two transparent screens never overlap. Coming back, the order is reversed.
    val homeAlpha = animateFloatAsState(
        targetValue = if (homeShowing) 1f else 0f,
        // Same duration and easing as the NavHost's strip slide, so the home layer and the detail screen move as one.
        animationSpec = tween(GamutMotion.NAV_MS, easing = GamutMotion.Standard),
        label = "home-alpha",
    )
    val scope = rememberCoroutineScope()
    var selectedTrack by remember { mutableStateOf<Track?>(null) }
    // --- Settings sub-pages (Recently played, Appearance, Playback & Audio, Profile, Feedback, About) are persistent slide LAYERS, not
    // NavHost destinations: same mechanism as the Discover <-> Library tabs. Each is composed once while the app is idle
    // and parked off-screen (alpha 0: not drawn, not hit-testable). Opening one only animates [layerPos] 0 -> 1, which is
    // read in graphicsLayer lambdas, so nothing recomposes and no screen is composed during the slide.
    // Only one is ever open (they are all children of Settings), so one shared position is enough.
    val layerPos = remember { Animatable(0f) }
    var layerPage by remember { mutableStateOf<SettingsPage?>(null) }   // the one that is (or was last) open
    var layerOpen by remember { mutableStateOf(false) }
    var composedPages by remember { mutableStateOf(emptySet<SettingsPage>()) }
    // One page every 400 ms once the loading screen has gone, so the pre-composition never lands as one big hitch.
    LaunchedEffect(showLoading) {
        if (!showLoading) {
            delay(1500)
            for (page in SettingsPage.entries) { composedPages = composedPages + page; delay(400) }
        }
    }
    // --- Settings itself is a persistent layer too: composed once while idle, then only translated. The NavHost "settings"
    // route stays (back stack, back handlers) but renders nothing; this position follows the route.
    // 1 = parked right, 0 = open, -1 = pushed left by a NavHost screen (favorites ...).
    val settingsX = remember { Animatable(1f) }
    var settingsComposed by remember { mutableStateOf(false) }
    var prevRoute by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(showLoading) { if (!showLoading) { delay(300); settingsComposed = true } }
    LaunchedEffect(currentRoute) {
        val prev = prevRoute
        val r = currentRoute
        prevRoute = r
        val spec = tween<Float>(GamutMotion.NAV_MS, easing = GamutMotion.Standard)
        when {
            r == "settings" -> settingsX.animateTo(0f, spec)
            prev == "settings" && (r == null || r == "home") -> settingsX.animateTo(1f, spec)
            prev == "settings" -> settingsX.animateTo(-1f, spec)
            r == null || r == "home" -> settingsX.snapTo(1f)
        }
    }
    val openLayer: (SettingsPage) -> Unit = { page ->
        telemetry.breadcrumb("nav", "opened ${page.name}")
        scope.launch {
            // Tapped before its idle pre-composition: compose it first, then slide.
            if (page !in composedPages) { composedPages = composedPages + page; withFrameNanos { }; withFrameNanos { } }
            if (layerPos.value > 0f && layerPage != page) layerPos.snapTo(0f)
            layerPage = page
            layerOpen = true
            layerPos.animateTo(1f, tween(GamutMotion.TAB_MS, easing = GamutMotion.Standard))
        }
    }
    val closeLayer: () -> Unit = {
        layerOpen = false
        scope.launch { layerPos.animateTo(0f, tween(GamutMotion.TAB_MS, easing = GamutMotion.Standard)) }
    }
    // --- The PLAYER is a persistent layer too (no NavHost route): composed once while idle, parked below the screen with
    // alpha 0. Opening only animates [playerPos] 0 -> 1 (translationY, read in a graphicsLayer lambda), closing animates it
    // back, so nothing is composed or recomposed during either slide. It sits ABOVE the NavHost, so it covers whatever
    // screen is current and going back simply reveals that screen: no back-stack entry, no popUpTo.
    val playerPos = remember { Animatable(0f) }
    var playerOpen by remember { mutableStateOf(false) }
    var playerComposed by remember { mutableStateOf(false) }
    // True only while the player is fully open and opaque: then the flowing background + the bar underneath can sleep.
    var playerCovering by remember { mutableStateOf(false) }
    LaunchedEffect(showLoading) {
        if (!showLoading) { delay(4400); playerComposed = true }   // after the seven settings pages (1.2 s + 7 x 0.4 s)
    }
    val openPlayer: () -> Unit = {
        if (!playerOpen) {
            playerOpen = true
            scope.launch {
                // Tapped before the idle pre-composition: compose it first, then slide.
                if (!playerComposed) { playerComposed = true; withFrameNanos { }; withFrameNanos { } }
                playerPos.animateTo(1f, tween(GamutMotion.PLAYER_LAYER_IN_MS, easing = GamutMotion.EmphasizedDecelerate))
                playerCovering = true   // not reached when a close cancels this animation
            }
        }
    }
    val closePlayer: () -> Unit = {
        playerOpen = false
        playerCovering = false   // wake the background / bar NOW: they are drawn (and opaque-covered) before the drop starts
        scope.launch { playerPos.animateTo(0f, tween(GamutMotion.PLAYER_LAYER_OUT_MS, easing = GamutMotion.PlayerClose)) }
    }
    // --- "New playlist" is a persistent slide layer too (no NavHost route): composed once while idle, parked off-screen,
    // slid with [createPos] exactly like the Settings pages, and Discover / Library shift left with it (see [homeShift]).
    // After it has closed, [createSession] is bumped: the form is rebuilt EMPTY while it is parked, so the next opening
    // starts clean without composing anything during the slide.
    val createPos = remember { Animatable(0f) }
    var createOpen by remember { mutableStateOf(false) }
    var createComposed by remember { mutableStateOf(false) }
    var createCovering by remember { mutableStateOf(false) }   // fully open and opaque: background + bar underneath can sleep
    var createSession by remember { mutableIntStateOf(0) }
    LaunchedEffect(showLoading) {
        if (!showLoading) { delay(4800); createComposed = true }   // after the settings pages and the player
    }
    val openCreate: () -> Unit = {
        if (!createOpen) {
            createOpen = true
            scope.launch {
                if (!createComposed) { createComposed = true; withFrameNanos { }; withFrameNanos { } }
                createPos.animateTo(1f, tween(GamutMotion.TAB_MS, easing = GamutMotion.Standard))
                createCovering = true
            }
        }
    }
    val closeCreate: () -> Unit = {
        createOpen = false
        createCovering = false
        scope.launch {
            createPos.animateTo(0f, tween(GamutMotion.TAB_MS, easing = GamutMotion.Standard))
            createSession++
        }
    }
    // Home slides out when a NavHost screen covers it AND when the New playlist layer comes in: whichever is further along.
    val homeShift = remember { derivedStateOf { minOf(homeAlpha.value, 1f - createPos.value) } }
    // Safety: if the route ever returns to home while a layer is open, close it instantly instead of leaving it on Discover.
    LaunchedEffect(currentRoute) {
        if (layerOpen && (currentRoute == null || currentRoute == "home")) { layerOpen = false; layerPos.snapTo(0f) }
    }
    var showConnect by remember { mutableStateOf(false) }
    var resolvingIsrc by remember { mutableStateOf<String?>(null) }
    var resolveJob by remember { mutableStateOf<Job?>(null) }
    // Whatever is loaded in the player (playing or paused) colors the search background.
    val playback by player.playbackState.collectAsState()
    // The root composable must not read `playback` directly: it changes every position tick and would recompose
    // the whole app (NavHost included) twice a second while music plays.
    val loadedTrack by remember { derivedStateOf { (playback as? PlaybackState.Playing)?.track ?: (playback as? PlaybackState.Paused)?.track } }
    val isPlayingNow by remember { derivedStateOf { playback is PlaybackState.Playing } }
    // The song the Library "Ready to add" banner and the New playlist screen offer. It must follow whatever the player
    // loads, from ANY source (search, favorites, artist, album, queue skips, auto-advance, local files), not only taps
    // that go through playTrackWithQueue. Keeps the last song while the next one is loading, so the banner never flickers.
    LaunchedEffect(loadedTrack?.isrc) { loadedTrack?.let { selectedTrack = it } }

    // --- "What's this song?": listens to the microphone, then offers to save / play what it found ---
    val shazamVm = hiltViewModel<ShazamViewModel>()
    val shazamState by shazamVm.state.collectAsState()
    val appLibraryVm = hiltViewModel<LibraryViewModel>()
    // Support: "a playlist reached ~10 songs" moment. Collected inside the effect so the root does not recompose.
    LaunchedEffect(appLibraryVm) {
        appLibraryVm.tracksByPlaylist.collect { map -> supportVm.store.recordPlaylistSize(map.values.maxOfOrNull { it.size } ?: 0) }
    }
    // Support: the sheet may only show at a quiet moment (see supportSafe below).
    var coldStartQuiet by remember { mutableStateOf(false) }
    LaunchedEffect(showLoading) { if (!showLoading) { delay(SupportConfig.COLD_START_QUIET_MS); coldStartQuiet = true } }
    // A local song deleted from the phone is dropped from Favorites right away. (Collected inside the effect, so the
    // root does not recompose.) Playlists clean themselves the same way in PlaylistScreen.
    val localAvailabilityVm = hiltViewModel<LocalAvailabilityViewModel>()
    LaunchedEffect(appLibraryVm, localAvailabilityVm) {
        kotlinx.coroutines.flow.combine(appLibraryVm.favorites, localAvailabilityVm.existing) { favs, existing -> favs to existing }
            .collect { (favs, existing) ->
                if (existing == null) return@collect
                val kept = favs.withoutMissingLocal(existing)
                if (kept.size != favs.size) appLibraryVm.setFavoriteOrder(kept)
            }
    }
    // One toast for every "add to playlist" entry point (search, artist, album, player, Shazam): the song was already there.
    LaunchedEffect(appLibraryVm) { appLibraryVm.duplicateAdds.collect { name -> notifier.info("Already in \u201C$name\u201D") } }
    // Recently played: whatever the player loads (tap, skip, auto-advance) is recorded once it starts.
    val recentVm = hiltViewModel<RecentlyPlayedViewModel>()
    LaunchedEffect(loadedTrack?.isrc) { loadedTrack?.let(recentVm::record) }
    // Statistics (Recently played > Statistics): a song counts as played when it is actually PLAYING, once per start.
    // A track restored paused does not count, pause/resume does not count again, skipping away and back does, and so
    // does the same song starting over from the beginning (repeat one, replay). Reads the position inside snapshotFlow,
    // so the root composable still does not recompose on every tick. The two values survive rotation.
    val statsVm = hiltViewModel<StatsViewModel>()
    var countedIsrc by rememberSaveable { mutableStateOf<String?>(null) }
    var lastPositionMs by rememberSaveable { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        snapshotFlow { (playback as? PlaybackState.Playing)?.let { it.track to it.positionMs } }
            .filterNotNull()
            .collect { (track, positionMs) ->
                val isNewSong = track.isrc != countedIsrc
                val startedOver = !isNewSong && positionMs < 2_000L && lastPositionMs > 10_000L
                if (isNewSong || startedOver) { statsVm.record(track); supportVm.store.recordPlay() }
                countedIsrc = track.isrc
                lastPositionMs = positionMs
            }
    }
    val context = LocalContext.current
    var resumeAfterShazam by remember { mutableStateOf(false) }
    var askedNotifications by rememberSaveable { mutableStateOf(false) }
    val startShazam = {
        // The microphone would hear this app itself, so the music pauses while listening.
        resumeAfterShazam = playback is PlaybackState.Playing
        if (resumeAfterShazam) player.pause()
        shazamVm.start()   // the sheet shows "Listening" at once
        // The foreground service keeps listening / identifying when the app goes to the background and posts the result notification.
        try { ShazamListenService.start(context) } catch (e: Exception) { /* still listens while the app is open */ }
    }
    // Android 13+: the result notification needs this permission. Asked once before listening; listening works either way.
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> startShazam() }
    // Mic is granted: ask for notifications once (Android 13+), then start.
    val beginAfterMic = {
        val needsNotif = android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsNotif && !askedNotifications) {
            askedNotifications = true
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else startShazam()
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) beginAfterMic() else shazamVm.permissionDenied()
    }
    val onShazam = {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (granted) beginAfterMic() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }
    // The microphone is only needed while listening: the music comes back as soon as that is over.
    LaunchedEffect(shazamState) {
        if (shazamState !is ShazamState.Listening && resumeAfterShazam) {
            resumeAfterShazam = false
            player.resume()
        }
    }
    // One palette and ONE flowing background for the whole app: it lives behind the NavHost, so Search and
    // Library are drawn on the same canvas (same colors, same animation state) and navigating never restarts it.
    val artUrl = remember(loadedTrack?.albumArtUrl) { artFor(loadedTrack?.albumArtUrl, 200) }
    // Adaptive: the cover's palette, forced into the chosen Light/Dark (Auto = cover decides). Otherwise a fixed preset.
    val palette = rememberAppPalette(artUrl, appearance)
    val neutralPalette = appNeutralPalette(appearance)
    // Light/dark of the whole app, for Material surfaces outside CoverTheme (sheets, dialogs, menus).
    val appDark = !palette.isLight
    // Keep the flowing background alive until the player has finished sliding in over it.
    var backgroundVisible by remember { mutableStateOf(true) }
    LaunchedEffect(playerCovering, createCovering) {
        // The player and the New playlist screen draw their own opaque background.
        backgroundVisible = !(playerCovering || createCovering)
    }
    // When the player slides away, the flowing background fades in under it instead of popping in behind a transparent screen.
    val bgAlpha by animateFloatAsState(
        targetValue = if (backgroundVisible) 1f else 0f,
        animationSpec = tween(1),   // the player is opaque while it covers the background, so switching is instant and invisible
        label = "background-alpha",
    )
    // The app settles in (a slight scale-up) while the loading screen leaves.
    val appReveal by animateFloatAsState(
        targetValue = if (showLoading) 0f else 1f,
        animationSpec = tween(520, easing = GamutMotion.Standard),
        label = "app-reveal",
    )
    // Keep the window behind everything in step with the setting, so overscroll / transitions never show the old look.
    val activity = LocalContext.current as? ComponentActivity
    SideEffect { activity?.window?.setBackgroundDrawable(ColorDrawable(neutralPalette.base.toArgb())) }
    GamutTheme(dark = appDark) {
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier.fillMaxSize().graphicsLayer {
                    val scale = 0.95f + 0.05f * appReveal
                    scaleX = scale
                    scaleY = scale
                },
            ) {
            GamutBackdrop {
                // One shared acrylic state for the whole app. The flowing background is registered as a source on it
                // (beneath everything, zIndex -1), so any glass in the app (the mini player) frosts that same background.
                AcrylicHost(Modifier.fillMaxSize()) {
                    FlowingBackground(
                        palette = palette,
                        isPlaying = isPlayingNow,
                        visible = backgroundVisible,
                        modifier = Modifier.fillMaxSize().graphicsLayer { alpha = bgAlpha }.acrylicSource(zIndex = -1f),
                    )
                    val removeFromPlaylist = remember(appLibraryVm) {
                        { t: Track, id: String -> id.toLongOrNull()?.let { appLibraryVm.removeTrack(it, t) }; Unit }
                    }
                    CompositionLocalProvider(LocalCoverPalette provides palette, LocalSupportNudge provides supportNudge, LocalNotifier provides notifier, LocalArtistPhotoResolver provides photoResolver, LocalPlaylistRemove provides removeFromPlaylist) {
                        // The strip docks onto the bottom bar of Search and Library (their `nowPlaying` slot).
                        val openArtist: (String) -> Unit = { artist -> nav.navigate("artist-name/${Uri.encode(artist)}") }
                        val openConnect = { showConnect = true }
                        val nowPlaying: @Composable () -> Unit = { NowPlayingStrip(player, docked = false, onOpen = openPlayer, onDevicesClick = openConnect) }
                        // Screens without a bottom bar (playlist, artist): the strip sits alone at the bottom edge.
                        val nowPlayingDocked: @Composable () -> Unit = { NowPlayingStrip(player, docked = true, onOpen = openPlayer, onDevicesClick = openConnect) }
                        // [queue] is the list the track was picked from (so next / shuffle / the queue sheet have something
                        // to work with); empty = just this one track.
                        val playTrackWithQueue: (Track, List<Track>) -> Unit = tap@{ track, queue ->
                            // Same song as the one already loaded (playing or paused): nothing to fetch. Resume it if it
                            // was paused and just open the player, instead of re-resolving and restarting it.
                            if (loadedTrack?.isrc == track.isrc) {
                                if (!isPlayingNow) player.resume()
                                openPlayer()
                                return@tap
                            }
                            // Same song is still being resolved from an earlier tap: let that one finish.
                            if (resolvingIsrc == track.isrc && resolveJob?.isActive == true) return@tap
                            selectedTrack = track
                            resolvingIsrc = track.isrc
                            // Tapping another song while one is still resolving must cancel it: otherwise the slower
                            // (older) tap finishes last and starts playing the WRONG song.
                            resolveJob?.cancel()
                            resolveJob = scope.launch {
                                // A song stored on the phone (MediaStore content:// URI): play it directly, no network, no resolving.
                                val deviceUri = track.localUri()
                                if (deviceUri != null) {
                                    val startIndex = queue.indexOfFirst { it.isrc == track.isrc }
                                    if (startIndex >= 0) player.setQueue(queue, startIndex)
                                    com.gamut.feature.playerui.components.WaveformSource.register(track, deviceUri.toString())
                                    player.play(track, deviceUri.toString())
                                    resolvingIsrc = null
                                    openPlayer()
                                    return@launch
                                }
                                // Downloaded: play the file. First, so it also works with no network at all.
                                val localFile = downloadIndex.pathFor(track.isrc)?.let(::File)?.takeIf { it.exists() }
                                if (localFile != null) {
                                    val startIndex = queue.indexOfFirst { it.isrc == track.isrc }
                                    if (startIndex >= 0) player.setQueue(queue, startIndex)
                                    com.gamut.feature.playerui.components.WaveformSource.register(track, localFile.absolutePath)
                                    player.play(track, Uri.fromFile(localFile).toString())
                                    resolvingIsrc = null
                                    openPlayer()
                                    return@launch
                                }
                                // Instant feedback on the tap: the pill stays until the song starts or fails. A newer tap
                                // replaces it (same key), and a stale dismiss(id) can never close the newer one.
                                val noticeId = notifier.loading("Loading ${track.title}\u2026")
                                resolveTrackSource(track).fold(
                                    onSuccess = { source ->
                                        notifier.update(noticeId, "Getting audio\u2026")
                                        audioSources.audioUrl(source.youtubeVideoId).fold(
                                            onSuccess = { audioUrl ->
                                                val startIndex = queue.indexOfFirst { it.isrc == track.isrc }
                                                if (startIndex >= 0) player.setQueue(queue, startIndex)
                                                com.gamut.feature.playerui.components.WaveformSource.register(track, audioUrl)
                                                player.play(track, audioUrl)
                                                notifier.dismiss(noticeId)
                                                openPlayer()
                                            },
                                            onFailure = { notifier.error("Couldn\u2019t play this song") },
                                        )
                                    },
                                    onFailure = { error ->
                                        notifier.error(
                                            when (error) {
                                                is ResolveFailure.Timeout -> "Connection was slow. Try again."
                                                is ResolveFailure.NoCandidates -> "No playable source was found."
                                                is ResolveFailure.LowScore -> "No reliable match was found."
                                                else -> "Couldn\u2019t play this song"
                                            },
                                        )
                                    },
                                )
                                resolvingIsrc = null
                            }
                        }
                        HomeTabs(
                            active = (currentRoute == null || currentRoute == "home") && !createOpen,
                            homeAlpha = homeShift,
                            // Under the (opaque) player the bar + strip would keep blurring for nothing: drop them once it has settled.
                            showBar = !(playerCovering || createCovering),
                            searchVm = searchVm,
                            libraryVm = appLibraryVm,
                            resolvingIsrc = resolvingIsrc,
                            selectedTrack = selectedTrack,
                            // Follows the PLAYER (queue advances, skips, other screens), not the last tapped row.
                            nowPlayingIsrc = loadedTrack?.isrc,
                            onTrackSelected = playTrackWithQueue,
                            onArtistSelected = { artistId -> nav.navigate("artist-real/$artistId") },
                            onAlbumSelected = { item -> nav.navigate(albumRoute(item)) },
                            onTrackArtist = { t -> openArtist(t.artist) },
                            onOpenPlaylist = { id -> nav.navigate(if (id == "liked") "favorites" else "playlist/$id") },
                            onCreatePlaylist = { openCreate() },
                            onOpenLocal = { nav.navigate("local") },
                            openSearchTick = openSearchTick,
                            openLibraryTick = openLibraryTick,
                            onProfile = { nav.navigate("settings") { launchSingleTop = true } },
                            onShazam = onShazam,
                            nowPlaying = nowPlaying,
                        )
                        // Settings: pre-composed layer above HomeTabs, below its sub-pages and the NavHost.
                        SettingsLayer(
                            composed = settingsComposed || currentRoute == "settings",
                            active = currentRoute == "settings",
                            x = settingsX,
                            subPagePos = layerPos,
                        ) {
                            SettingsScreen(
                                onBack = { nav.popBackStack() },
                                onLikedSongs = { nav.navigate("favorites") },
                                onPlaylists = { openLibraryTick++; nav.popBackStack("home", false) },
                                onRecentlyPlayed = { openLayer(SettingsPage.Recent) },
                                onProfile = { openLayer(SettingsPage.Profile) },
                                onAppearance = { openLayer(SettingsPage.Appearance) },
                                onPlaybackAudio = { openLayer(SettingsPage.Playback) },
                                onStorage = { openLayer(SettingsPage.Storage) },
                                onFeedback = { openLayer(SettingsPage.Feedback) },
                                onAbout = { openLayer(SettingsPage.About) },
                                nowPlaying = nowPlayingDocked,
                            )
                        }
                        // Settings sub-pages: BELOW the NavHost (so the player still slides over them), above HomeTabs.
                        SlideLayer(composed = SettingsPage.Recent in composedPages, selected = layerPage == SettingsPage.Recent, open = layerOpen, position = layerPos, onClose = closeLayer) {
                            val recent by recentVm.tracks.collectAsState()
                            RecentlyPlayedScreen(tracks = recent, onBack = closeLayer, onTrackSelected = playTrackWithQueue, nowPlaying = nowPlayingDocked)
                        }
                        SlideLayer(composed = SettingsPage.Appearance in composedPages, selected = layerPage == SettingsPage.Appearance, open = layerOpen, position = layerPos, onClose = closeLayer) {
                            AppearanceScreen(onBack = closeLayer, nowPlaying = nowPlayingDocked)
                        }
                        SlideLayer(composed = SettingsPage.Playback in composedPages, selected = layerPage == SettingsPage.Playback, open = layerOpen, position = layerPos, onClose = closeLayer) {
                            PlaybackSettingsScreen(onBack = closeLayer, nowPlaying = nowPlayingDocked)
                        }
                        SlideLayer(composed = SettingsPage.Storage in composedPages, selected = layerPage == SettingsPage.Storage, open = layerOpen, position = layerPos, onClose = closeLayer) {
                            StorageSettingsScreen(onBack = closeLayer, nowPlaying = nowPlayingDocked)
                        }
                        SlideLayer(composed = SettingsPage.Profile in composedPages, selected = layerPage == SettingsPage.Profile, open = layerOpen, position = layerPos, onClose = closeLayer) {
                            ProfileScreen(onBack = closeLayer, nowPlaying = nowPlayingDocked)
                        }
                        SlideLayer(composed = SettingsPage.Feedback in composedPages, selected = layerPage == SettingsPage.Feedback, open = layerOpen, position = layerPos, onClose = closeLayer) {
                            FeedbackScreen(onBack = closeLayer, nowPlaying = nowPlayingDocked)
                        }
                        SlideLayer(composed = SettingsPage.About in composedPages, selected = layerPage == SettingsPage.About, open = layerOpen, position = layerPos, onClose = closeLayer) {
                            AboutScreen(onBack = closeLayer, nowPlaying = nowPlayingDocked)
                        }
                        // New playlist: same mechanism as the Settings pages (own position, Discover / Library shift with it).
                        SlideLayer(composed = createComposed, selected = true, open = createOpen, position = createPos, onClose = closeCreate) {
                            key(createSession) {
                                CreatePlaylistScreen(
                                    viewModel = appLibraryVm,
                                    selectedTrack = selectedTrack,
                                    onBack = closeCreate,
                                    active = LocalPageActive.current,
                                )
                            }
                        }
                        NavHost(
                            navController = nav,
                            startDestination = "home",
                            // Detail screens form ONE STRIP like the tabs (see GamutMotion.navPushEnter): translation only, no alpha.
                            enterTransition = { GamutMotion.navPushEnter },
                            exitTransition = { GamutMotion.navPushExit },
                            popEnterTransition = { GamutMotion.navPopEnter },
                            popExitTransition = { GamutMotion.navPopExit },
                        ) {
                            // "home" is drawn by HomeTabs above and never leaves composition, so this destination is empty.
                            composable("home") {}
                            // Drawn by SettingsLayer (above); the route only owns the back stack entry, so no transitions here.
                            composable(
                                "settings",
                                enterTransition = { EnterTransition.None },
                                exitTransition = { ExitTransition.None },
                                popEnterTransition = { EnterTransition.None },
                                popExitTransition = { ExitTransition.None },
                            ) {}
                            composable("local") {
                                val localVm = hiltViewModel<LocalMusicViewModel>()
                                Settled {
                                    LocalMusicScreen(
                                        libraryVm = appLibraryVm,
                                        onBack = { nav.popBackStack() },
                                        onTrackSelected = playTrackWithQueue,
                                        nowPlayingIsrc = loadedTrack?.isrc,
                                        viewModel = localVm,
                                        nowPlaying = nowPlayingDocked,
                                    )
                                }
                            }
                            composable("favorites") {
                                val favorites by appLibraryVm.favorites.collectAsState()
                                val downloadsVm = hiltViewModel<PlaylistDownloadsViewModel>()
                                Settled {
                                    FavoritesScreen(
                                        tracks = favorites,
                                        onBack = { nav.popBackStack() },
                                        onTrackSelected = playTrackWithQueue,
                                        onShuffle = { list ->
                                            player.setShuffle(true)
                                            list.randomOrNull()?.let { playTrackWithQueue(it, list) }
                                        },
                                        onRemove = { appLibraryVm.toggleFavorite(it) },
                                        onReorder = { appLibraryVm.setFavoriteOrder(it) },
                                        onTrackArtist = { t -> openArtist(t.artist) },
                                        nowPlayingIsrc = loadedTrack?.isrc,
                                        downloads = downloadsVm,
                                        nowPlaying = nowPlayingDocked,
                                    )
                                }
                            }
                            composable("playlist/{id}") { entry ->
                                // ViewModels are created at once (their loading starts now); only the UI is deferred.
                                val playlistVm = hiltViewModel<com.gamut.feature.library.PlaylistViewModel>()
                                val downloadsVm = hiltViewModel<PlaylistDownloadsViewModel>()
                                val storedPlaylistTracks by playlistVm.tracks.collectAsState()
                                val existingLocal by localAvailabilityVm.existing.collectAsState()
                                val playlistTracks = remember(storedPlaylistTracks, existingLocal) { storedPlaylistTracks.withoutMissingLocal(existingLocal) }
                                val allPlaylists by appLibraryVm.playlists.collectAsState()
                                val favoriteIsrcs by appLibraryVm.favoriteIsrcs.collectAsState()
                                val id = entry.arguments?.getString("id").orEmpty()
                                Settled {
                                    PlaylistScreen(
                                        playlistId = id,
                                        title = allPlaylists.firstOrNull { it.id.toString() == id }?.name ?: "Playlist",
                                        onBack = { nav.popBackStack() },
                                        onPlay = { list ->
                                            player.setShuffle(false)
                                            list.firstOrNull()?.let { playTrackWithQueue(it, list) }
                                        },
                                        onShuffle = { list ->
                                            player.setShuffle(true)
                                            list.randomOrNull()?.let { playTrackWithQueue(it, list) }
                                        },
                                        onTrackSelected = { track -> playTrackWithQueue(track, playlistTracks) },
                                        onAddSongs = { openSearchTick++; nav.popBackStack("home", false) },
                                        nowPlayingIsrc = loadedTrack?.isrc,
                                        favoriteIsrcs = favoriteIsrcs,
                                        existingLocalIsrcs = existingLocal,
                                        onToggleFavorite = appLibraryVm::toggleFavorite,
                                        onTrackArtist = { t -> openArtist(t.artist) },
                                        viewModel = playlistVm,
                                        downloads = downloadsVm,
                                        nowPlaying = nowPlayingDocked,
                                    )
                                }
                            }
                            composable("artist/{artistId}") {
                                val vm = hiltViewModel<ArtistViewModel>()
                                Settled {
                                    ArtistRoute(
                                        viewModel = vm,
                                        libraryVm = appLibraryVm,
                                        onOpenArtist = openArtist,
                                        onBack = { nav.popBackStack() },
                                        onTrackSelected = playTrackWithQueue,
                                        onAlbumSelected = { item -> nav.navigate(albumRoute(item)) },
                                        nowPlayingIsrc = loadedTrack?.isrc,
                                        nowPlaying = nowPlayingDocked,
                                    )
                                }
                            }
                            composable("artist-real/{artistId}") {
                                val vm = hiltViewModel<ArtistViewModel>()
                                Settled {
                                    ArtistRoute(
                                        viewModel = vm,
                                        libraryVm = appLibraryVm,
                                        onOpenArtist = openArtist,
                                        onBack = { nav.popBackStack() },
                                        onTrackSelected = playTrackWithQueue,
                                        onAlbumSelected = { item -> nav.navigate(albumRoute(item)) },
                                        nowPlayingIsrc = loadedTrack?.isrc,
                                        nowPlaying = nowPlayingDocked,
                                    )
                                }
                            }
                            composable("artist-name/{artistName}") {
                                val vm = hiltViewModel<ArtistViewModel>()
                                Settled {
                                    ArtistRoute(
                                        viewModel = vm,
                                        libraryVm = appLibraryVm,
                                        onOpenArtist = openArtist,
                                        onBack = { nav.popBackStack() },
                                        onTrackSelected = playTrackWithQueue,
                                        onAlbumSelected = { item -> nav.navigate(albumRoute(item)) },
                                        nowPlayingIsrc = loadedTrack?.isrc,
                                        nowPlaying = nowPlayingDocked,
                                    )
                                }
                            }
                            composable(
                                "album/{id}?title={title}&subtitle={subtitle}&art={art}",
                                arguments = listOf("title", "subtitle", "art").map { navArgument(it) { defaultValue = "" } },
                            ) {
                                val vm = hiltViewModel<AlbumViewModel>()
                                Settled {
                                    AlbumRoute(
                                        viewModel = vm,
                                        libraryVm = appLibraryVm,
                                        onOpenArtist = openArtist,
                                        onBack = { nav.popBackStack() },
                                        onTrackSelected = playTrackWithQueue,
                                        nowPlayingIsrc = loadedTrack?.isrc,
                                        nowPlaying = nowPlayingDocked,
                                    )
                                }
                            }
                        }
                        // Above the NavHost, below the notices / sheets. Pre-composed once, slid with playerPos.
                        PlayerLayer(composed = playerComposed, open = playerOpen, position = playerPos) { active ->
                            val libraryVm = appLibraryVm
                            val favoriteIsrcs by libraryVm.favoriteIsrcs.collectAsState()
                            val playlists by libraryVm.playlists.collectAsState()
                            val tracksByPlaylist by libraryVm.tracksByPlaylist.collectAsState()
                            val playlistInfo by libraryVm.playlistInfo.collectAsState()
                            PlayerScreen(
                                player,
                                onBack = closePlayer,
                                active = active,
                                favoriteIsrcs = favoriteIsrcs,
                                onToggleFavorite = { t ->
                                    libraryVm.toggleFavorite(t)
                                    if (t.isrc in favoriteIsrcs) notifier.info("Removed from Favorites") else notifier.success("Added to Favorites")
                                },
                                playlists = playlists.map { PlaylistPickerItem(id = it.id.toString(), name = it.name, coverUrl = it.coverUri ?: playlistInfo[it.id]?.coverUrl) },
                                playlistIdsWithTrack = loadedTrack?.let { playlistIdsContaining(tracksByPlaylist, it) } ?: emptySet(),
                                onAddToPlaylist = { t, playlistId ->
                                    playlists.firstOrNull { it.id.toString() == playlistId }?.let { libraryVm.addTrack(it.id, t) }
                                },
                                onRemoveFromPlaylist = { t, playlistId ->
                                    playlists.firstOrNull { it.id.toString() == playlistId }?.let { libraryVm.removeTrack(it.id, t) }
                                },
                                // The artist page slides in (NavHost) while the player drops away: two independent translations.
                                onGoToArtist = { t ->
                                    closePlayer()
                                    nav.navigate("artist-name/${Uri.encode(t.artist)}")
                                },
                            )
                        }
                        // Safety net for every NavHost screen (artist, album, playlist, favorites, settings): the system back
                        // gesture always pops one screen. Registered BEFORE the layer handlers below, which therefore win
                        // while a settings sub-page or "New playlist" is open; the player has its own handling (!playerOpen).
                        BackHandler(enabled = !homeShowing && !playerOpen && !layerOpen && !createOpen) { nav.popBackStack() }
                        BackHandler(enabled = layerOpen && currentRoute == "settings" && !playerOpen) { closeLayer() }
                        BackHandler(enabled = createOpen && !playerOpen) { closeCreate() }
                        if (shazamState !is ShazamState.Idle) {
                            val shazamLevel by shazamVm.level.collectAsState()
                            val favoriteIsrcs by appLibraryVm.favoriteIsrcs.collectAsState()
                            val libraryPlaylists by appLibraryVm.playlists.collectAsState()
                            val playlistInfo by appLibraryVm.playlistInfo.collectAsState()
                            val tracksByPlaylist by appLibraryVm.tracksByPlaylist.collectAsState()
                            val playlists = remember(libraryPlaylists, playlistInfo, tracksByPlaylist) {
                                libraryPlaylists.map { TrackPlaylistOption(id = it.id.toString(), name = it.name, coverUrl = it.coverUri ?: playlistInfo[it.id]?.coverUrl, trackIsrcs = tracksByPlaylist[it.id].orEmpty().mapTo(HashSet()) { t -> t.isrc }) }
                            }
                            // Sheets sit outside the screens' CoverTheme, so they get their own: light/dark applies here too.
                            CoverTheme(palette) {
                                ShazamSheet(
                                    state = shazamState,
                                    level = shazamLevel,
                                    favoriteIsrcs = favoriteIsrcs,
                                    playlists = playlists,
                                    onDismiss = shazamVm::dismiss,
                                    onRetry = onShazam,
                                    onOpenSettings = {
                                        context.startActivity(
                                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                                        )
                                    },
                                    onPlay = { track -> shazamVm.dismiss(); playTrackWithQueue(track, emptyList()) },
                                    onToggleFavorite = appLibraryVm::toggleFavorite,
                                    onAddToQueue = { track -> player.addToQueue(track) },
                                    onAddToPlaylist = { track, id -> appLibraryVm.addTrack(id.toLong(), track) },
                                    onTrackArtist = { track -> shazamVm.dismiss(); openArtist(track.artist) },
                                )
                            }
                        }
                        // Support sheet: after the sheets above, before the notices. Never while playing / player open / loading / cold start.
                        val supportSafe = !showLoading && coldStartQuiet && !isPlayingNow && !playerOpen && !createOpen &&
                            shazamState is ShazamState.Idle && !showConnect
                        CoverTheme(palette) { SupportSheetHost(vm = supportVm, safeMoment = supportSafe) }
                        // Last child = drawn above every screen. Needs its own CoverTheme (it is outside the screens').
                        CoverTheme(if (playerOpen || createOpen) neutralPalette else palette) { NoticeHost() }
                        // Connect sheet (Bluetooth + output devices), opened from the mini player's devices icon.
                        if (showConnect) {
                            CoverTheme(palette) {
                                ConnectSheet(accent = MaterialTheme.colorScheme.primary, onDismiss = { showConnect = false })
                            }
                        }
                    }
                }
            }
            }
            AnimatedVisibility(
                showLoading,
                exit = fadeOut(tween(450, easing = GamutMotion.Standard)) +
                    scaleOut(tween(450, easing = GamutMotion.Standard), targetScale = 1.05f),
            ) {
                GamutLoadingScreen(dataReady = dataReady, onFinished = { showLoading = false })
            }
        }
    }
}

/** Reads the player state and feeds [MiniPlayerStrip]; slides open when a track is loaded, closed when not. */
@Composable
private fun NowPlayingStrip(player: MusicPlayer, docked: Boolean, onOpen: () -> Unit, onDevicesClick: () -> Unit) {
    val outputs = rememberOutputDevices()
    val state by player.playbackState.collectAsState()
    val trackState by remember { derivedStateOf { (state as? PlaybackState.Playing)?.track ?: (state as? PlaybackState.Paused)?.track } }
    val track: Track? = trackState
    val isPlaying by remember { derivedStateOf { state is PlaybackState.Playing } }
    val isLoading by remember { derivedStateOf { state is PlaybackState.Loading } }
    val loadProgress by player.loadProgress.collectAsState()
    val streamDuration by player.streamDurationMs.collectAsState()
    val position = (state as? PlaybackState.Playing)?.positionMs ?: (state as? PlaybackState.Paused)?.positionMs ?: 0L
    // Keep the last track around so the strip keeps its content while it slides away.
    var last by remember { mutableStateOf(track) }
    if (track != null && track != last) last = track
    val shown = track ?: last
    // Position ticks about once a second; a linear tween of the same length makes the line glide.
    val progress by animateFloatAsState(
        targetValue = if (shown == null) 0f else (position.toFloat() / (if (streamDuration > 0L) streamDuration else shown.durationMs).coerceAtLeast(1L)).coerceIn(0f, 1f),
        animationSpec = tween(500, easing = LinearEasing),
        label = "strip-progress",
    )
    AnimatedVisibility(
        // While the next song loads (skip / track end) the state has no track: keep the strip, so it does not collapse
        // and pop back on every skip.
        visible = track != null || (isLoading && last != null),
        enter = expandVertically(tween(280)) + fadeIn(tween(220)),
        exit = shrinkVertically(tween(220)) + fadeOut(tween(160)),
    ) {
        if (shown != null) {
            MiniPlayerStrip(
                title = shown.title,
                artist = shown.artist,
                artUrl = remember(shown.albumArtUrl) { artFor(shown.albumArtUrl, 200) },
                isPlaying = isPlaying,
                isLoading = isLoading,
                loadProgress = loadProgress,
                progress = progress,
                onOpen = onOpen,
                onTogglePlay = { if (isPlaying) player.pause() else player.resume() },
                onNext = player::skipNext,
                onPrevious = player::skipPrevious,
                onDevicesClick = onDevicesClick,
                devicesActive = outputs.any { it.isActive && it.isBluetooth },
                dockedToEdge = docked,
            )
        }
    }
}
/** Art of a song stored on the phone (content: / file: URI) is used as is; only remote art is resized. */
private fun artFor(url: String?, size: Int) =
    if (url != null && (url.startsWith("file:") || url.startsWith("content:"))) url else squareArtUrl(url, size)

private fun albumRoute(item: DiscoverItem) =
    "album/${Uri.encode(item.id)}?title=${Uri.encode(item.title)}&subtitle=${Uri.encode(item.subtitle)}&art=${Uri.encode(item.imageUrl)}"

/** Length of the Discover <-> Library slide, and the fling speed (px/s) that counts as "go to the other tab". */
private const val TAB_SWITCH_MS = GamutMotion.TAB_MS
private const val TAB_FLING_VELOCITY = 700f

/**
 * For the screens that are expensive to compose (artist, album, playlist, favorites): provides [LocalNavSettled], which is
 * false while the strip slide is running and true once it has finished. The screen shows a skeleton until then and
 * composes its real content afterwards, so the motion itself only ever draws a cheap placeholder.
 */
@Composable
private fun AnimatedVisibilityScope.Settled(content: @Composable () -> Unit) {
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(transition.currentState) { if (transition.currentState == EnterExitState.Visible) settled = true }
    LaunchedEffect(Unit) { delay(700); settled = true }   // never trap the user behind a skeleton
    CompositionLocalProvider(LocalNavSettled provides settled) { content() }
}

/**
 * The two top-level tabs, both always composed and living for the whole session (they are NOT a NavHost destination,
 * so opening an album and coming back never rebuilds them). Switching slides the tabs sideways, by tap on the bar or
 * by swiping (a swipe that starts on a horizontal carousel belongs to the carousel). The bottom bar and the mini
 * player strip live here ONCE. While a detail screen covers this layer it is faded out, blocks touches and is hidden
 * from accessibility.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeTabs(
    active: Boolean,
    homeAlpha: State<Float>,
    showBar: Boolean,
    searchVm: SearchViewModel,
    libraryVm: LibraryViewModel,
    resolvingIsrc: String?,
    selectedTrack: Track?,
    nowPlayingIsrc: String?,
    onTrackSelected: (Track, List<Track>) -> Unit,
    onArtistSelected: (Long) -> Unit,
    onAlbumSelected: (DiscoverItem) -> Unit,
    onTrackArtist: (Track) -> Unit,
    onOpenPlaylist: (String) -> Unit,
    onCreatePlaylist: () -> Unit,
    onOpenLocal: () -> Unit,
    openSearchTick: Int,
    openLibraryTick: Int,
    onProfile: () -> Unit,
    onShazam: () -> Unit,
    nowPlaying: @Composable () -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // Followed artists for the Library tab (null until the database has answered).
    val followVm = hiltViewModel<com.gamut.core.sync.FollowViewModel>()
    val followedEntities by followVm.artists.collectAsState()
    val followedArtists = remember(followedEntities) {
        followedEntities.orEmpty().map { com.gamut.feature.library.LibraryArtist(it.artistKey, it.name, it.imageUrl) }
    }
    // Feeds the generated profile circle in the Discover header.
    val accountVm = hiltViewModel<AccountViewModel>()
    val profileName by accountVm.displayName.collectAsState()
    val profileId by accountVm.userId.collectAsState()
    val avatar by accountVm.avatar.collectAsState()
    val favoriteIsrcs by libraryVm.favoriteIsrcs.collectAsState()
    val libraryPlaylists by libraryVm.playlists.collectAsState()
    val playlistInfo by libraryVm.playlistInfo.collectAsState()
    val tracksByPlaylist by libraryVm.tracksByPlaylist.collectAsState()
    val playlistOptions = remember(libraryPlaylists, playlistInfo, tracksByPlaylist) {
        libraryPlaylists.map { TrackPlaylistOption(id = it.id.toString(), name = it.name, coverUrl = it.coverUri ?: playlistInfo[it.id]?.coverUrl, trackIsrcs = tracksByPlaylist[it.id].orEmpty().mapTo(HashSet()) { t -> t.isrc }) }
    }
    // Bumped to ask SearchScreen to focus its field and open the keyboard (after the slide to Discover).
    var focusSearchTick by remember { mutableIntStateOf(0) }
    // 0f = Discover, 1f = Library, anything between while sliding or dragging. Only read in draw/layout lambdas.
    val position = remember { Animatable(tab.toFloat()) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var barHeight by remember { mutableStateOf(0.dp) }
    var widthPx by remember { mutableFloatStateOf(1f) }
    val palette = LocalCoverPalette.current
    // Which tab the bar highlights: flips once, halfway, so it does not recompose every frame of the slide.
    val shownTab by remember { derivedStateOf { if (position.value > 0.5f) 1 else 0 } }
    val barComposed by remember { derivedStateOf { homeAlpha.value > 0f } }

    fun goTo(target: Int) {
        tab = target
        scope.launch { position.animateTo(target.toFloat(), tween(TAB_SWITCH_MS, easing = GamutMotion.Standard)) }
    }

    // Empty-state buttons ("Find artists", "Find songs") ask for the search tab.
    LaunchedEffect(openSearchTick) { if (openSearchTick > 0) { goTo(0); focusSearchTick++ } }
    LaunchedEffect(openLibraryTick) { if (openLibraryTick > 0) goTo(1) }
    BackHandler(enabled = active && tab == 1) { goTo(0) }
    // Only the visible layer may set the status bar icons; a hidden Library recomposing must not override an album's.
    if (active) TransparentStatusBar(darkIcons = palette.isLight)

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                // Slides out to the left in step with the detail screen (one strip, no alpha, so no offscreen layer).
                // Fully hidden: parked off-screen and not drawn. Discover's list is a Haze blur source on the app-wide
                // state, so a hidden layer must not stay where glass on the screen above could frost it.
                val shown = homeAlpha.value
                translationX = -(1f - shown) * size.width
                if (shown <= 0f) alpha = 0f
            }
            .then(if (active) Modifier else Modifier.consumeTouches().clearAndSetSemantics {}),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
                .draggable(
                    orientation = Orientation.Horizontal,
                    enabled = active,
                    state = rememberDraggableState { dx ->
                        scope.launch { position.snapTo((position.value - dx / widthPx).coerceIn(0f, 1f)) }
                    },
                    onDragStarted = { position.stop() },
                    onDragStopped = { velocity ->
                        goTo(
                            when {
                                velocity < -TAB_FLING_VELOCITY -> 1
                                velocity > TAB_FLING_VELOCITY -> 0
                                position.value > 0.5f -> 1
                                else -> 0
                            },
                        )
                    },
                ),
        ) {
            TabLayer(page = 0, position = { position.value }, active = shownTab == 0) {
                SearchScreen(
                    viewModel = searchVm,
                    resolvingIsrc = resolvingIsrc,
                    onTrackSelected = onTrackSelected,
                    onArtistSelected = onArtistSelected,
                    onAlbumSelected = onAlbumSelected,
                    onShazam = onShazam,
                    onProfile = onProfile,
                    profileName = profileName,
                    profileSeed = profileId,
                    profileImage = avatar,
                    profileModifier = Modifier.supportNudgeCorona(),   // soft coral glow around the profile circle after the sheet was dismissed
                    nowPlayingIsrc = nowPlayingIsrc,
                    favoriteIsrcs = favoriteIsrcs,
                    onToggleFavorite = libraryVm::toggleFavorite,
                    playlists = playlistOptions,
                    onAddToPlaylist = { track, playlistId ->
                        libraryPlaylists.firstOrNull { it.id.toString() == playlistId }?.let { libraryVm.addTrack(it.id, track) }
                    },
                    onTrackArtist = onTrackArtist,
                    barHeight = barHeight,
                    focusSearchTick = focusSearchTick,
                )
            }
            TabLayer(page = 1, position = { position.value }, active = shownTab == 1) {
                LibraryScreen(
                    artists = followedArtists,
                    artistsLoading = followedEntities == null,
                    onOpenArtist = { key ->
                        // Follow keys look like "deezer:123"; the artist screen route takes the numeric id.
                        key.removePrefix("deezer:").toLongOrNull()?.let(onArtistSelected)
                    },
                    viewModel = libraryVm,
                    selectedTrack = selectedTrack,
                    onOpenPlaylist = onOpenPlaylist,
                    onCreatePlaylist = onCreatePlaylist,
                    onOpenLocal = onOpenLocal,
                    onOpenSearch = { goTo(0); focusSearchTick++ },
                    barHeight = barHeight,
                )
            }
        }
        if (showBar && barComposed) {
            CoverTheme(palette) {
                GamutBottomBar(
                    items = listOf(
                        BottomBarItem("Discover", PhosphorIcons.Bold.MagnifyingGlass, selected = shownTab == 0, onClick = if (shownTab == 0) null else ({ goTo(0) })),
                        BottomBarItem("Library", PhosphorIcons.Bold.BookmarkSimple, selected = shownTab == 1, onClick = if (shownTab == 1) null else ({ goTo(1) })),
                    ),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .onSizeChanged { barHeight = with(density) { it.height.toDp() } },
                    nowPlaying = nowPlaying,
                )
            }
        }
    }
}

/**
 * The Settings screen as a persistent layer: composed once while idle, parked off-screen, and translated by [x]
 * (1 = parked right, 0 = open, -1 = pushed left) minus [subPagePos], so it travels left when a sub-page opens.
 */
@Composable
private fun SettingsLayer(
    composed: Boolean,
    active: Boolean,
    x: Animatable<Float, AnimationVector1D>,
    subPagePos: Animatable<Float, AnimationVector1D>,
    content: @Composable () -> Unit,
) {
    if (!composed) return
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                translationX = (x.value - subPagePos.value) * size.width
                if (x.value >= 1f) alpha = 0f
            }
            .then(if (active) Modifier else Modifier.clearAndSetSemantics {}),
    ) { CompositionLocalProvider(LocalPageActive provides active) { content() } }
}

/** The Settings sub-pages that live as persistent slide layers (see [SlideLayer]). Order = pre-composition order. */
private enum class SettingsPage { Recent, Appearance, Playback, Storage, Profile, Feedback, About }

/**
 * A screen that is composed once and slid with a shared [position] (0 = closed, 1 = open), exactly like [TabLayer].
 * Not [selected] or fully closed: parked off-screen with alpha 0 (not drawn, not hit-testable, hidden from accessibility).
 * Its translation is applied in the draw phase, and Settings travels with it. Swiping right closes it, with the same
 * fling rule as the tabs. While it is not live (not selected, or closing), [LocalPageActive] is false, so screens can
 * pause anything animated (see AppearanceScreen's preview) and do not touch the status bar.
 */
@Composable
private fun SlideLayer(
    composed: Boolean,
    selected: Boolean,
    open: Boolean,
    position: Animatable<Float, AnimationVector1D>,
    onClose: () -> Unit,
    content: @Composable () -> Unit,
) {
    if (!composed) return
    val scope = rememberCoroutineScope()
    var widthPx by remember { mutableFloatStateOf(1f) }
    val live = selected && open
    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
            .graphicsLayer {
                val rel = if (selected) 1f - position.value else 1f
                translationX = rel * size.width
                if (rel >= 1f) alpha = 0f
            }
            .then(if (live) Modifier else Modifier.clearAndSetSemantics {})
            .draggable(
                orientation = Orientation.Horizontal,
                enabled = live,
                state = rememberDraggableState { dx ->
                    scope.launch { position.snapTo((position.value - dx / widthPx).coerceIn(0f, 1f)) }
                },
                onDragStarted = { position.stop() },
                onDragStopped = { velocity ->
                    val close = velocity > TAB_FLING_VELOCITY || (velocity > -TAB_FLING_VELOCITY && position.value < 0.5f)
                    if (close) onClose() else position.animateTo(1f, tween(TAB_SWITCH_MS, easing = GamutMotion.Standard))
                },
            ),
    ) {
        CompositionLocalProvider(LocalPageActive provides live) { content() }
    }
}

/**
 * The player as a persistent layer: composed once, parked below the screen (alpha 0: not drawn, not hit-testable, hidden from
 * accessibility) and slid vertically with [position] (0 = parked, 1 = open). The position is read only inside the graphicsLayer
 * lambda, so opening and closing never recompose or lay out anything. While open it swallows touches so nothing underneath can
 * be tapped through it. [content] receives `active` (open and not closing), which the player uses to pause everything costly.
 */
@Composable
private fun PlayerLayer(
    composed: Boolean,
    open: Boolean,
    position: Animatable<Float, AnimationVector1D>,
    content: @Composable (active: Boolean) -> Unit,
) {
    if (!composed) return
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                val rel = 1f - position.value
                translationY = rel * size.height
                if (rel >= 1f) alpha = 0f
            }
            .then(
                if (open) Modifier.pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
                else Modifier.clearAndSetSemantics {},
            ),
    ) { content(open) }
}

/**
 * One page of the tab strip. Its position is applied in the draw phase (graphicsLayer), so sliding or dragging never
 * recomposes the screens. A page that is fully off-screen is not drawn and is hidden from accessibility.
 */
@Composable
private fun TabLayer(page: Int, position: () -> Float, active: Boolean, content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                val rel = page - position()
                translationX = rel * size.width
                if (abs(rel) >= 1f) alpha = 0f
            }
            .then(if (active) Modifier else Modifier.clearAndSetSemantics {}),
    ) { content() }
}

/** Swallows every touch before the children see it (Initial pass), so a hidden tab can never be tapped through. */
private fun Modifier.consumeTouches(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
    }
}

/**
 * The artist page plus the library wiring its three-dots sheet needs (favorites, playlists). Collected here, not in the
 * NavHost, so a favorite toggle only recomposes this screen.
 */
@Composable
private fun ArtistRoute(
    viewModel: ArtistViewModel,
    libraryVm: LibraryViewModel,
    onBack: () -> Unit,
    onTrackSelected: (Track, List<Track>) -> Unit,
    onAlbumSelected: (DiscoverItem) -> Unit,
    onOpenArtist: (String) -> Unit,
    nowPlayingIsrc: String?,
    nowPlaying: @Composable () -> Unit,
) {
    val favoriteIsrcs by libraryVm.favoriteIsrcs.collectAsState()
    val libraryPlaylists by libraryVm.playlists.collectAsState()
    val playlistInfo by libraryVm.playlistInfo.collectAsState()
    val tracksByPlaylist by libraryVm.tracksByPlaylist.collectAsState()
    val playlistOptions = remember(libraryPlaylists, playlistInfo, tracksByPlaylist) {
        libraryPlaylists.map { TrackPlaylistOption(id = it.id.toString(), name = it.name, coverUrl = it.coverUri ?: playlistInfo[it.id]?.coverUrl, trackIsrcs = tracksByPlaylist[it.id].orEmpty().mapTo(HashSet()) { t -> t.isrc }) }
    }
    val followVm = hiltViewModel<com.gamut.core.sync.FollowViewModel>()
    val followedKeys by followVm.followedKeys.collectAsState()
    DiscoverArtistScreen(
        followedKeys = followedKeys,
        onToggleFollow = followVm::toggle,
        viewModel = viewModel,
        onBack = onBack,
        onTrackSelected = onTrackSelected,
        onAlbumSelected = onAlbumSelected,
        onSimilarArtist = onOpenArtist,
        onTrackArtist = { t -> onOpenArtist(t.artist) },
        favoriteIsrcs = favoriteIsrcs,
        onToggleFavorite = libraryVm::toggleFavorite,
        playlists = playlistOptions,
        onAddToPlaylist = { track, playlistId ->
            libraryPlaylists.firstOrNull { it.id.toString() == playlistId }?.let { libraryVm.addTrack(it.id, track) }
        },
        nowPlayingIsrc = nowPlayingIsrc,
        nowPlaying = nowPlaying,
    )
}

/** The album page plus the library wiring its three-dots sheet needs (favorites, playlists). Same idea as [ArtistRoute]. */
@Composable
private fun AlbumRoute(
    viewModel: AlbumViewModel,
    libraryVm: LibraryViewModel,
    onBack: () -> Unit,
    onTrackSelected: (Track, List<Track>) -> Unit,
    onOpenArtist: (String) -> Unit,
    nowPlayingIsrc: String?,
    nowPlaying: @Composable () -> Unit,
) {
    val favoriteIsrcs by libraryVm.favoriteIsrcs.collectAsState()
    val libraryPlaylists by libraryVm.playlists.collectAsState()
    val playlistInfo by libraryVm.playlistInfo.collectAsState()
    val tracksByPlaylist by libraryVm.tracksByPlaylist.collectAsState()
    val playlistOptions = remember(libraryPlaylists, playlistInfo, tracksByPlaylist) {
        libraryPlaylists.map { TrackPlaylistOption(id = it.id.toString(), name = it.name, coverUrl = it.coverUri ?: playlistInfo[it.id]?.coverUrl, trackIsrcs = tracksByPlaylist[it.id].orEmpty().mapTo(HashSet()) { t -> t.isrc }) }
    }
    AlbumScreen(
        viewModel = viewModel,
        onBack = onBack,
        onTrackSelected = onTrackSelected,
        onTrackArtist = { t -> onOpenArtist(t.artist) },
        favoriteIsrcs = favoriteIsrcs,
        onToggleFavorite = libraryVm::toggleFavorite,
        playlists = playlistOptions,
        onAddToPlaylist = { track, playlistId ->
            libraryPlaylists.firstOrNull { it.id.toString() == playlistId }?.let { libraryVm.addTrack(it.id, track) }
        },
        nowPlayingIsrc = nowPlayingIsrc,
        nowPlaying = nowPlaying,
    )
}