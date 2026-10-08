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
import androidx.activity.compose.setContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.Animatable
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
import com.adamglin.phosphoricons.Light
import com.adamglin.phosphoricons.light.MagnifyingGlass
import com.adamglin.phosphoricons.light.VinylRecord
import com.gamut.core.designsystem.BottomBarItem
import com.gamut.core.designsystem.GamutBottomBar
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.layout.fillMaxSize
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
import com.gamut.core.designsystem.GamutTheme
import com.gamut.core.designsystem.LocalCoverPalette
import com.gamut.core.designsystem.rememberCoverPalette
import com.gamut.core.designsystem.squareArtUrl
import com.gamut.core.model.PlaybackState
import com.gamut.core.model.Track
import com.gamut.core.player.MusicPlayer
import com.gamut.feature.library.FavoritesScreen
import com.gamut.feature.library.EmptyStateCovers
import com.gamut.feature.library.LibraryScreen
import com.gamut.core.database.DownloadIndex
import com.gamut.feature.library.LibraryViewModel
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
import kotlinx.coroutines.launch
import kotlin.math.abs

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var player: MusicPlayer
    @Inject lateinit var resolveTrackSource: ResolveTrackSourceUseCase
    @Inject lateinit var audioSources: AudioSourceRepository
    @Inject lateinit var newPipe: NewPipeYoutubeSource
    @Inject lateinit var downloadIndex: DownloadIndex
    @Inject lateinit var downloadManager: TrackDownloadManager
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        com.gamut.feature.playerui.components.WaveformSource.init(applicationContext)
        // Configure the window ONCE, up front. Doing this lazily when the player opens re-lays out the window
        // and briefly shows the default (white) window background.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false
        window.setBackgroundDrawable(ColorDrawable(CoverPalette.Neutral.base.toArgb()))
        setContent { GamutApp(player, resolveTrackSource, audioSources, newPipe, downloadIndex, downloadManager) }
    }
}

@Composable
private fun GamutApp(player: MusicPlayer, resolveTrackSource: ResolveTrackSourceUseCase, audioSources: AudioSourceRepository, newPipe: NewPipeYoutubeSource, downloadIndex: DownloadIndex, downloadManager: TrackDownloadManager) {
    val nav = rememberNavController()
    // App-wide toast pill (top). Screens reach it with LocalNotifier.current.
    val notifier = rememberNotifier()
    // One toast when a run of downloads ends: the song (if one was queued) or the playlist (if all of it was).
    LaunchedEffect(downloadManager) {
        downloadManager.summaries.collect { s ->
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
    // Bumped by the empty-state "Find ..." buttons: HomeTabs reacts by sliding to the Discover (search) tab.
    var openSearchTick by remember { mutableIntStateOf(0) }
    var showLoading by remember { mutableStateOf(true) }
    var forceReady by remember { mutableStateOf(false) }
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
    val homeShowing = currentRoute == null || currentRoute == "home" ||
        (currentRoute == "player" && nav.previousBackStackEntry?.destination?.route == "home")
    // Fade-through: home fades out fast (90 ms), and only then does a detail screen fade in (see the NavHost), so
    // two transparent screens never overlap. Coming back, the order is reversed.
    val homeAlpha = animateFloatAsState(
        targetValue = if (homeShowing) 1f else 0f,
        animationSpec = if (homeShowing) tween(210, delayMillis = 90, easing = FastOutSlowInEasing) else tween(90),
        label = "home-alpha",
    )
    val scope = rememberCoroutineScope()
    var selectedTrack by remember { mutableStateOf<Track?>(null) }
    var showConnect by remember { mutableStateOf(false) }
    var resolvingIsrc by remember { mutableStateOf<String?>(null) }
    var resolveJob by remember { mutableStateOf<Job?>(null) }
    // Whatever is loaded in the player (playing or paused) colors the search background.
    val playback by player.playbackState.collectAsState()
    // The root composable must not read `playback` directly: it changes every position tick and would recompose
    // the whole app (NavHost included) twice a second while music plays.
    val loadedTrack by remember { derivedStateOf { (playback as? PlaybackState.Playing)?.track ?: (playback as? PlaybackState.Paused)?.track } }
    val isPlayingNow by remember { derivedStateOf { playback is PlaybackState.Playing } }

    // --- "What's this song?": listens to the microphone, then offers to save / play what it found ---
    val shazamVm = hiltViewModel<ShazamViewModel>()
    val shazamState by shazamVm.state.collectAsState()
    val appLibraryVm = hiltViewModel<LibraryViewModel>()
    val context = LocalContext.current
    var resumeAfterShazam by remember { mutableStateOf(false) }
    val startShazam = {
        // The microphone would hear this app itself, so the music pauses while listening.
        resumeAfterShazam = playback is PlaybackState.Playing
        if (resumeAfterShazam) player.pause()
        shazamVm.start()
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startShazam() else shazamVm.permissionDenied()
    }
    val onShazam = {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (granted) startShazam() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
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
    val artUrl = remember(loadedTrack?.albumArtUrl) { squareArtUrl(loadedTrack?.albumArtUrl, 200) }
    val palette = rememberCoverPalette(artUrl)
    // Keep the flowing background alive until the player has finished sliding in over it.
    var backgroundVisible by remember { mutableStateOf(true) }
    LaunchedEffect(currentRoute) {
        // The player and the New playlist screen draw their own opaque background.
        if (currentRoute == "player" || currentRoute == "create-playlist") { delay(360); backgroundVisible = false } else backgroundVisible = true
    }
    GamutTheme {
        Box(Modifier.fillMaxSize()) {
            GamutBackdrop {
                // One shared acrylic state for the whole app. The flowing background is registered as a source on it
                // (beneath everything, zIndex -1), so any glass in the app (the mini player) frosts that same background.
                AcrylicHost(Modifier.fillMaxSize()) {
                    FlowingBackground(
                        palette = palette,
                        isPlaying = isPlayingNow,
                        visible = backgroundVisible,
                        modifier = Modifier.fillMaxSize().acrylicSource(zIndex = -1f),
                    )
                    CompositionLocalProvider(LocalCoverPalette provides palette, LocalNotifier provides notifier) {
                        // The strip docks onto the bottom bar of Search and Library (their `nowPlaying` slot).
                        val openPlayer = { nav.navigate("player") { launchSingleTop = true }; Unit }
                        val openArtist: (String) -> Unit = { artist -> nav.navigate("artist-name/${Uri.encode(artist)}") }
                        val openConnect = { showConnect = true }
                        val nowPlaying: @Composable () -> Unit = { NowPlayingStrip(player, docked = false, onOpen = openPlayer, onArtistClick = openArtist, onDevicesClick = openConnect) }
                        // Screens without a bottom bar (playlist, artist): the strip sits alone at the bottom edge.
                        val nowPlayingDocked: @Composable () -> Unit = { NowPlayingStrip(player, docked = true, onOpen = openPlayer, onArtistClick = openArtist, onDevicesClick = openConnect) }
                        // [queue] is the list the track was picked from (so next / shuffle / the queue sheet have something
                        // to work with); empty = just this one track.
                        val playTrackWithQueue: (Track, List<Track>) -> Unit = tap@{ track, queue ->
                            // Same song as the one already loaded (playing or paused): nothing to fetch. Resume it if it
                            // was paused and just open the player, instead of re-resolving and restarting it.
                            if (loadedTrack?.isrc == track.isrc) {
                                if (!isPlayingNow) player.resume()
                                nav.navigate("player") { launchSingleTop = true }
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
                                // Downloaded: play the file. First, so it also works with no network at all.
                                val localFile = downloadIndex.pathFor(track.isrc)?.let(::File)?.takeIf { it.exists() }
                                if (localFile != null) {
                                    val startIndex = queue.indexOfFirst { it.isrc == track.isrc }
                                    if (startIndex >= 0) player.setQueue(queue, startIndex)
                                    com.gamut.feature.playerui.components.WaveformSource.register(track, localFile.absolutePath)
                                    player.play(track, Uri.fromFile(localFile).toString())
                                    resolvingIsrc = null
                                    nav.navigate("player") { launchSingleTop = true }
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
                                                nav.navigate("player") { launchSingleTop = true }
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
                            active = currentRoute == null || currentRoute == "home",
                            homeAlpha = homeAlpha,
                            // Under the (opaque) player the bar + strip would keep blurring for nothing: drop them once it has settled.
                            showBar = currentRoute != "player" || backgroundVisible,
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
                            onCreatePlaylist = { nav.navigate("create-playlist") { launchSingleTop = true } },
                            openSearchTick = openSearchTick,
                            onShazam = onShazam,
                            nowPlaying = nowPlaying,
                        )
                        NavHost(
                            navController = nav,
                            startDestination = "home",
                            // Fade-through with a small slide. The incoming screen starts fading in only after the old one is
                            // gone, which is also exactly when its heavy first composition (see AfterEnter) has finished.
                            enterTransition = {
                                fadeIn(tween(210, delayMillis = 90, easing = FastOutSlowInEasing)) +
                                    slideInHorizontally(tween(300, easing = FastOutSlowInEasing)) { it / 12 }
                            },
                            exitTransition = {
                                if (targetState.destination.route == "player") ExitTransition.None else fadeOut(tween(90))
                            },
                            popEnterTransition = {
                                if (initialState.destination.route == "player") EnterTransition.None
                                else fadeIn(tween(210, delayMillis = 90, easing = FastOutSlowInEasing))
                            },
                            popExitTransition = {
                                fadeOut(tween(90)) + slideOutHorizontally(tween(200, easing = FastOutSlowInEasing)) { it / 12 }
                            },
                        ) {
                            // "home" is drawn by HomeTabs above and never leaves composition, so this destination is empty.
                            composable("home") {}
                            composable("favorites") {
                                val favorites by appLibraryVm.favorites.collectAsState()
                                val downloadsVm = hiltViewModel<PlaylistDownloadsViewModel>()
                                AfterEnter {
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
                                        downloads = downloadsVm,
                                        nowPlaying = nowPlayingDocked,
                                    )
                                }
                            }
                            composable("create-playlist") {
                                AfterEnter {
                                    CreatePlaylistScreen(
                                        viewModel = appLibraryVm,
                                        selectedTrack = selectedTrack,
                                        onBack = { nav.popBackStack() },
                                    )
                                }
                            }
                            composable("playlist/{id}") { entry ->
                                // ViewModels are created at once (their loading starts now); only the UI is deferred.
                                val playlistVm = hiltViewModel<com.gamut.feature.library.PlaylistViewModel>()
                                val downloadsVm = hiltViewModel<PlaylistDownloadsViewModel>()
                                val playlistTracks by playlistVm.tracks.collectAsState()
                                val allPlaylists by appLibraryVm.playlists.collectAsState()
                                val favoriteIsrcs by appLibraryVm.favoriteIsrcs.collectAsState()
                                val id = entry.arguments?.getString("id").orEmpty()
                                AfterEnter {
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
                                AfterEnter {
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
                                AfterEnter {
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
                                AfterEnter {
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
                                AfterEnter {
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
                            composable(
                                "player",
                                enterTransition = { slideInVertically(tween(200, easing = LinearOutSlowInEasing)) { it } },
                                // Plain slide, opaque, no fade: what is revealed underneath is already drawn (see backgroundVisible).
                                popExitTransition = { slideOutVertically(tween(160, easing = FastOutLinearInEasing)) { it } },
                            ) {
                                val libraryVm = appLibraryVm
                                val favoriteIsrcs by libraryVm.favoriteIsrcs.collectAsState()
                                val playlists by libraryVm.playlists.collectAsState()
                                PlayerScreen(
                                    player,
                                    onBack = { nav.popBackStack() },
                                    favoriteIsrcs = favoriteIsrcs,
                                    onToggleFavorite = { t ->
                                        libraryVm.toggleFavorite(t)
                                        if (t.isrc in favoriteIsrcs) notifier.info("Removed from Favorites") else notifier.success("Added to Favorites")
                                    },
                                    playlists = playlists.map { PlaylistPickerItem(id = it.id.toString(), name = it.name, coverUrl = it.coverUri) },
                                    onAddToPlaylist = { t, playlistId ->
                                        playlists.firstOrNull { it.id.toString() == playlistId }?.let { libraryVm.addTrack(it.id, t) }
                                    },
                                )
                            }
                        }
                        if (shazamState !is ShazamState.Idle) {
                            val shazamLevel by shazamVm.level.collectAsState()
                            val favoriteIsrcs by appLibraryVm.favoriteIsrcs.collectAsState()
                            val playlists by appLibraryVm.playlists.collectAsState()
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
                                onAddToPlaylist = { playlist, track -> appLibraryVm.addTrack(playlist.id, track) },
                                onCreatePlaylist = { name, track -> appLibraryVm.createPlaylistAndAdd(name, track) },
                            )
                        }
                        // Last child = drawn above every screen. Needs its own CoverTheme (it is outside the screens').
                        CoverTheme(if (currentRoute == "player" || currentRoute == "create-playlist") CoverPalette.Neutral else palette) { NoticeHost() }
                        // Connect sheet (Bluetooth + output devices), opened from the mini player's devices icon.
                        if (showConnect) {
                            CoverTheme(palette) {
                                ConnectSheet(accent = MaterialTheme.colorScheme.primary, onDismiss = { showConnect = false })
                            }
                        }
                    }
                }
            }
            AnimatedVisibility(showLoading, exit = fadeOut(tween(400))) {
                GamutLoadingScreen(dataReady = dataReady, onFinished = { showLoading = false })
            }
        }
    }
}

/** Reads the player state and feeds [MiniPlayerStrip]; slides open when a track is loaded, closed when not. */
@Composable
private fun NowPlayingStrip(player: MusicPlayer, docked: Boolean, onOpen: () -> Unit, onArtistClick: (String) -> Unit, onDevicesClick: () -> Unit) {
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
                artUrl = remember(shown.albumArtUrl) { squareArtUrl(shown.albumArtUrl, 200) },
                isPlaying = isPlaying,
                isLoading = isLoading,
                loadProgress = loadProgress,
                progress = progress,
                onOpen = onOpen,
                onTogglePlay = { if (isPlaying) player.pause() else player.resume() },
                onNext = player::skipNext,
                onArtistClick = { shown?.artist?.let(onArtistClick) },
                onDevicesClick = onDevicesClick,
                devicesActive = outputs.any { it.isActive && it.isBluetooth },
                dockedToEdge = docked,
            )
        }
    }
}
private fun albumRoute(item: DiscoverItem) =
    "album/${Uri.encode(item.id)}?title=${Uri.encode(item.title)}&subtitle=${Uri.encode(item.subtitle)}&art=${Uri.encode(item.imageUrl)}"

/** Length of the Discover <-> Library slide, and the fling speed (px/s) that counts as "go to the other tab". */
private const val TAB_SWITCH_MS = 240
private const val TAB_FLING_VELOCITY = 700f

/**
 * Composes [content] two frames AFTER the screen was entered, instead of in the very frame of the navigation.
 * The enter transition keeps the new screen invisible for its first 90 ms, so its heavy first composition (lists,
 * images, blur) lands in that invisible window instead of as a hitch on top of the old screen. The ViewModel is
 * created by the caller, so data loading still starts immediately.
 */
@Composable
private fun AfterEnter(content: @Composable () -> Unit) {
    var ready by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        withFrameNanos { }
        ready = true
    }
    if (ready) content()
}

/**
 * The two top-level tabs, both always composed and living for the whole session (they are NOT a NavHost destination,
 * so opening an album and coming back never rebuilds them). Switching slides the tabs sideways, by tap on the bar or
 * by swiping (a swipe that starts on a horizontal carousel belongs to the carousel). The bottom bar and the mini
 * player strip live here ONCE. While a detail screen covers this layer it is faded out, blocks touches and is hidden
 * from accessibility.
 */
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
    openSearchTick: Int,
    onShazam: () -> Unit,
    nowPlaying: @Composable () -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val favoriteIsrcs by libraryVm.favoriteIsrcs.collectAsState()
    val libraryPlaylists by libraryVm.playlists.collectAsState()
    val playlistOptions = remember(libraryPlaylists) {
        libraryPlaylists.map { TrackPlaylistOption(id = it.id.toString(), name = it.name, coverUrl = it.coverUri) }
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
        scope.launch { position.animateTo(target.toFloat(), tween(TAB_SWITCH_MS, easing = FastOutSlowInEasing)) }
    }

    // Empty-state buttons ("Find artists", "Find songs") ask for the search tab.
    LaunchedEffect(openSearchTick) { if (openSearchTick > 0) { goTo(0); focusSearchTick++ } }
    BackHandler(enabled = active && tab == 1) { goTo(0) }
    // Only the visible layer may set the status bar icons; a hidden Library recomposing must not override an album's.
    if (active) TransparentStatusBar(darkIcons = palette.isLight)

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                alpha = homeAlpha.value
                // Fully hidden: also park the layer off-screen. Discover's list is a Haze blur source on the app-wide
                // state, so at alpha 0 it could still be frosted through glass on the screen above (Favorites, Artist).
                if (alpha == 0f) translationX = size.width
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
                    viewModel = libraryVm,
                    selectedTrack = selectedTrack,
                    onOpenPlaylist = onOpenPlaylist,
                    onCreatePlaylist = onCreatePlaylist,
                    onOpenSearch = { goTo(0); focusSearchTick++ },
                    barHeight = barHeight,
                )
            }
        }
        if (showBar && barComposed) {
            CoverTheme(palette) {
                GamutBottomBar(
                    items = listOf(
                        BottomBarItem("Discover", PhosphorIcons.Light.MagnifyingGlass, selected = shownTab == 0, onClick = if (shownTab == 0) null else ({ goTo(0) })),
                        BottomBarItem("Library", PhosphorIcons.Light.VinylRecord, selected = shownTab == 1, onClick = if (shownTab == 1) null else ({ goTo(1) })),
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
    val playlistOptions = remember(libraryPlaylists) {
        libraryPlaylists.map { TrackPlaylistOption(id = it.id.toString(), name = it.name, coverUrl = it.coverUri) }
    }
    DiscoverArtistScreen(
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
    val playlistOptions = remember(libraryPlaylists) {
        libraryPlaylists.map { TrackPlaylistOption(id = it.id.toString(), name = it.name, coverUrl = it.coverUri) }
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