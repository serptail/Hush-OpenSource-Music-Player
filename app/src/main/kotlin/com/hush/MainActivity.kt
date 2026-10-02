package com.hush

import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import com.hush.core.designsystem.MiniPlayerStrip
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import kotlinx.coroutines.delay
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowCompat
import com.hush.core.designsystem.CoverPalette
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.hilt.navigation.compose.hiltViewModel
import com.hush.core.designsystem.HushBackdrop
import com.hush.core.designsystem.FlowingBackground
import com.hush.core.designsystem.HushTheme
import com.hush.core.designsystem.LocalCoverPalette
import com.hush.core.designsystem.rememberCoverPalette
import com.hush.core.designsystem.squareArtUrl
import com.hush.core.model.PlaybackState
import com.hush.core.model.Track
import com.hush.core.player.MusicPlayer
import com.hush.feature.library.FavoritesScreen
import com.hush.feature.library.LibraryScreen
import com.hush.feature.library.LibraryViewModel
import com.hush.feature.library.PlaylistScreen
import com.hush.feature.playerui.PlayerScreen
import com.hush.feature.resolver.ResolveTrackSourceUseCase
import com.hush.feature.resolver.ResolveFailure
import com.hush.feature.resolver.AudioSourceRepository
import com.hush.feature.search.SearchScreen
import com.hush.feature.search.SearchViewModel
import com.hush.feature.search.ArtistScreen as DiscoverArtistScreen
import com.hush.feature.search.ArtistViewModel
import com.hush.feature.search.AlbumScreen
import com.hush.feature.search.AlbumViewModel
import com.hush.feature.search.DiscoverItem
import androidx.navigation.navArgument
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var player: MusicPlayer
    @Inject lateinit var resolveTrackSource: ResolveTrackSourceUseCase
    @Inject lateinit var audioSources: AudioSourceRepository
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Configure the window ONCE, up front. Doing this lazily when the player opens re-lays out the window
        // and briefly shows the default (white) window background.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false
        window.setBackgroundDrawable(ColorDrawable(CoverPalette.Neutral.base.toArgb()))
        setContent { HushApp(player, resolveTrackSource, audioSources) }
    }
}

@Composable
private fun HushApp(player: MusicPlayer, resolveTrackSource: ResolveTrackSourceUseCase, audioSources: AudioSourceRepository) {
    val nav = rememberNavController()
    // The player draws its own opaque background, so the flowing one underneath is paused while it is open.
    val currentRoute = nav.currentBackStackEntryAsState().value?.destination?.route
    val scope = rememberCoroutineScope()
    var selectedTrack by remember { mutableStateOf<Track?>(null) }
    var resolvingIsrc by remember { mutableStateOf<String?>(null) }
    var resolutionMessage by remember { mutableStateOf<String?>(null) }
    // Whatever is loaded in the player (playing or paused) colors the search background.
    val playback by player.playbackState.collectAsState()
    val loadedTrack = (playback as? PlaybackState.Playing)?.track ?: (playback as? PlaybackState.Paused)?.track
    // One palette and ONE flowing background for the whole app: it lives behind the NavHost, so Search and
    // Library are drawn on the same canvas (same colors, same animation state) and navigating never restarts it.
    val artUrl = remember(loadedTrack?.albumArtUrl) { squareArtUrl(loadedTrack?.albumArtUrl, 200) }
    val palette = rememberCoverPalette(artUrl)
    // Keep the flowing background alive until the player has finished sliding in over it.
    var backgroundVisible by remember { mutableStateOf(true) }
    LaunchedEffect(currentRoute) {
        if (currentRoute == "player") { delay(360); backgroundVisible = false } else backgroundVisible = true
    }
    HushTheme {
        HushBackdrop {
            FlowingBackground(palette = palette, isPlaying = playback is PlaybackState.Playing, visible = backgroundVisible)
            CompositionLocalProvider(LocalCoverPalette provides palette) {
                // The strip docks onto the bottom bar of Search and Library (their `nowPlaying` slot).
                val openPlayer = { nav.navigate("player") { launchSingleTop = true }; Unit }
                val openArtist: (String) -> Unit = { artist -> nav.navigate("artist-name/${Uri.encode(artist)}") }
                val nowPlaying: @Composable () -> Unit = { NowPlayingStrip(player, docked = false, onOpen = openPlayer, onArtistClick = openArtist) }
                // Screens without a bottom bar (playlist, artist): the strip sits alone at the bottom edge.
                val nowPlayingDocked: @Composable () -> Unit = { NowPlayingStrip(player, docked = true, onOpen = openPlayer, onArtistClick = openArtist) }
                val playTrack: (Track) -> Unit = { track ->
                    selectedTrack = track
                    resolvingIsrc = track.isrc
                    resolutionMessage = null
                    scope.launch {
                        resolveTrackSource(track).fold(
                            onSuccess = { source ->
                                audioSources.audioUrl(source.youtubeVideoId).fold(
                                    onSuccess = { audioUrl -> player.play(track, audioUrl); resolutionMessage = null; nav.navigate("player") },
                                    onFailure = { error -> resolutionMessage = error.message ?: "No playable audio stream was found" },
                                )
                            },
                            onFailure = { error ->
                                resolutionMessage = when (error) {
                                    is ResolveFailure.Timeout -> "Connection was slow. Try again."
                                    is ResolveFailure.NoCandidates -> "No playable source was found."
                                    is ResolveFailure.LowScore -> "No reliable match was found."
                                    else -> error.message ?: "Could not verify a match"
                                }
                            },
                        )
                        resolvingIsrc = null
                    }
                }
                NavHost(
                    navController = nav,
                    startDestination = "search",
                    enterTransition = { EnterTransition.None },
                    exitTransition = { ExitTransition.None },
                    popEnterTransition = { EnterTransition.None },
                    popExitTransition = { ExitTransition.None },
                ) {
                    composable("search") {
                        SearchScreen(
                            viewModel = hiltViewModel<SearchViewModel>(),
                            resolvingIsrc = resolvingIsrc,
                            resolutionMessage = resolutionMessage,
                            onTrackSelected = playTrack,
                            onArtistSelected = { artistId -> nav.navigate("artist-real/$artistId") },
                            onAlbumSelected = { item -> nav.navigate(albumRoute(item)) },
                            onLibrary = { nav.navigate("library") },
                            nowPlaying = nowPlaying,
                        )
                    }
                    composable("library") {
                        LibraryScreen(
                            viewModel = hiltViewModel<LibraryViewModel>(),
                            selectedTrack = selectedTrack,
                            onBack = { nav.navigate("search") { popUpTo("search") { inclusive = false }; launchSingleTop = true } },
                            nowPlaying = nowPlaying,
                            onOpenPlaylist = { id -> nav.navigate(if (id == "liked") "favorites" else "playlist/$id") },
                            onOpenArtist = { id -> nav.navigate("artist/$id") },
                        )
                    }
                    composable("favorites") {
                        FavoritesScreen(
                            onBack = { nav.popBackStack() },
                            onPlayer = { nav.navigate("player") },
                            nowPlaying = nowPlayingDocked,
                        )
                    }
                    composable("playlist/{id}") { entry ->
                        PlaylistScreen(
                            playlistId = entry.arguments?.getString("id").orEmpty(),
                            onBack = { nav.popBackStack() },
                            onTrackSelected = playTrack,
                            viewModel = hiltViewModel<com.hush.feature.library.PlaylistViewModel>(),
                            nowPlaying = nowPlayingDocked,
                        )
                    }
                    composable("artist/{artistId}") {
                        DiscoverArtistScreen(
                            viewModel = hiltViewModel<ArtistViewModel>(),
                            onBack = { nav.popBackStack() },
                            onTrackSelected = playTrack,
                            onAlbumSelected = { item -> nav.navigate(albumRoute(item)) },
                            nowPlaying = nowPlayingDocked,
                        )
                    }
                    composable("artist-real/{artistId}") {
                        DiscoverArtistScreen(
                            viewModel = hiltViewModel<ArtistViewModel>(),
                            onBack = { nav.popBackStack() },
                            onTrackSelected = playTrack,
                            onAlbumSelected = { item -> nav.navigate(albumRoute(item)) },
                            nowPlaying = nowPlayingDocked,
                        )
                    }
                    composable("artist-name/{artistName}") {
                        DiscoverArtistScreen(
                            viewModel = hiltViewModel<ArtistViewModel>(),
                            onBack = { nav.popBackStack() },
                            onTrackSelected = playTrack,
                            onAlbumSelected = { item -> nav.navigate(albumRoute(item)) },
                            nowPlaying = nowPlayingDocked,
                        )
                    }
                    composable(
                        "album/{id}?title={title}&subtitle={subtitle}&art={art}",
                        arguments = listOf("title", "subtitle", "art").map { navArgument(it) { defaultValue = "" } },
                    ) {
                        AlbumScreen(
                            viewModel = hiltViewModel<AlbumViewModel>(),
                            onBack = { nav.popBackStack() },
                            onTrackSelected = playTrack,
                            nowPlaying = nowPlayingDocked,
                        )
                    }
                    composable(
                        "player",
                        enterTransition = { slideInVertically(tween(320, easing = FastOutSlowInEasing)) { it } },
                        popExitTransition = { slideOutVertically(tween(280, easing = FastOutSlowInEasing)) { it } },
                    ) { PlayerScreen(player, onBack = { nav.popBackStack() }) }
                }
            }
        }
    }
}

/** Reads the player state and feeds [MiniPlayerStrip]; slides open when a track is loaded, closed when not. */
@Composable
private fun NowPlayingStrip(player: MusicPlayer, docked: Boolean, onOpen: () -> Unit, onArtistClick: (String) -> Unit) {
    val state by player.playbackState.collectAsState()
    val track = (state as? PlaybackState.Playing)?.track ?: (state as? PlaybackState.Paused)?.track
    val isPlaying = state is PlaybackState.Playing
    val position = (state as? PlaybackState.Playing)?.positionMs ?: (state as? PlaybackState.Paused)?.positionMs ?: 0L
    // Keep the last track around so the strip keeps its content while it slides away.
    var last by remember { mutableStateOf(track) }
    if (track != null && track != last) last = track
    val shown = track ?: last
    // Position ticks about once a second; a linear tween of the same length makes the line glide.
    val progress by animateFloatAsState(
        targetValue = if (shown == null) 0f else (position.toFloat() / shown.durationMs.coerceAtLeast(1L)).coerceIn(0f, 1f),
        animationSpec = tween(1000, easing = LinearEasing),
        label = "strip-progress",
    )
    AnimatedVisibility(
        visible = track != null,
        enter = expandVertically(tween(280)) + fadeIn(tween(220)),
        exit = shrinkVertically(tween(220)) + fadeOut(tween(160)),
    ) {
        if (shown != null) {
            MiniPlayerStrip(
                title = shown.title,
                artist = shown.artist,
                artUrl = remember(shown.albumArtUrl) { squareArtUrl(shown.albumArtUrl, 200) },
                isPlaying = isPlaying,
                progress = progress,
                onOpen = onOpen,
                onTogglePlay = { if (isPlaying) player.pause() else player.resume() },
                onNext = player::skipNext,
                onArtistClick = { shown?.artist?.let(onArtistClick) },
                dockedToEdge = docked,
            )
        }
    }
}
private fun albumRoute(item: DiscoverItem) =
    "album/${Uri.encode(item.id)}?title=${Uri.encode(item.title)}&subtitle=${Uri.encode(item.subtitle)}&art=${Uri.encode(item.imageUrl)}"