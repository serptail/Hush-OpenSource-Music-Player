package com.hush

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.hilt.navigation.compose.hiltViewModel
import com.hush.core.designsystem.HushBackdrop
import com.hush.core.designsystem.HushTheme
import com.hush.core.model.Track
import com.hush.core.player.MusicPlayer
import com.hush.feature.library.LibraryScreen
import com.hush.feature.library.LibraryViewModel
import com.hush.feature.playerui.PlayerScreen
import com.hush.feature.resolver.ResolveTrackSourceUseCase
import com.hush.feature.resolver.AudioSourceRepository
import com.hush.feature.search.SearchScreen
import com.hush.feature.search.SearchViewModel
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
        setContent { HushApp(player, resolveTrackSource, audioSources) }
    }
}

@Composable
private fun HushApp(player: MusicPlayer, resolveTrackSource: ResolveTrackSourceUseCase, audioSources: AudioSourceRepository) {
    val nav = rememberNavController()
    val scope = rememberCoroutineScope()
    var selectedTrack by remember { mutableStateOf<Track?>(null) }
    var resolvingIsrc by remember { mutableStateOf<String?>(null) }
    var resolutionMessage by remember { mutableStateOf<String?>(null) }
    HushTheme {
        HushBackdrop {
            NavHost(navController = nav, startDestination = "search") {
            composable("search") {
                SearchScreen(
                    viewModel = hiltViewModel<SearchViewModel>(),
                    resolvingIsrc = resolvingIsrc,
                    resolutionMessage = resolutionMessage,
                    onTrackSelected = { track ->
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
                                onFailure = { error -> resolutionMessage = error.message ?: "Could not verify a match" },
                            )
                            resolvingIsrc = null
                        }
                    },
                    onLibrary = { nav.navigate("library") },
                    onPlayer = { nav.navigate("player") },
                )
            }
            composable("library") { LibraryScreen(hiltViewModel<LibraryViewModel>(), selectedTrack, { nav.navigate("search") }, { nav.navigate("player") }) }
                composable("player") { PlayerScreen(player, onBack = { nav.popBackStack() }) }
            }
        }
    }
}