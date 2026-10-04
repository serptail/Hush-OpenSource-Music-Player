package com.gamut

import android.app.Application
import com.gamut.core.player.MusicPlayer
import com.gamut.feature.resolver.AndroidYtDlpEngine
import com.gamut.feature.resolver.AudioSourceRepository
import com.gamut.feature.resolver.ResolveTrackSourceUseCase
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class GamutApplication : Application() {
	@Inject lateinit var ytDlp: AndroidYtDlpEngine
	@Inject lateinit var player: MusicPlayer
	@Inject lateinit var resolveTrackSource: ResolveTrackSourceUseCase
	@Inject lateinit var audioSources: AudioSourceRepository

	override fun onCreate() {
		super.onCreate()
		// The player owns the queue, so for skip / auto-advance / the notification buttons it needs to turn the
		// next Track into a stream URL itself: same two steps MainActivity uses when a track is tapped.
		player.setSourceResolver { track, forceRefresh, onProgress ->
			val source = resolveTrackSource(track).getOrNull() ?: return@setSourceResolver null
			onProgress(0.5f)   // the track is identified; what is left is fetching its audio URL
			audioSources.audioUrl(source.youtubeVideoId, forceRefresh).getOrNull()
		}
		CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { ytDlp.warmUp() }
	}
}