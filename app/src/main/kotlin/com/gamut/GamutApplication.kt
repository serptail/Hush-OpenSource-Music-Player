package com.gamut

import android.app.Application
import android.net.Uri
import com.gamut.core.database.DownloadIndex
import com.gamut.core.player.MusicPlayer
import com.gamut.feature.resolver.AndroidYtDlpEngine
import com.gamut.feature.resolver.AudioSourceRepository
import com.gamut.feature.resolver.ResolveTrackSourceUseCase
import dagger.hilt.android.HiltAndroidApp
import java.io.File
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
	@Inject lateinit var downloadIndex: DownloadIndex

	override fun onCreate() {
		super.onCreate()
		// The player owns the queue, so for skip / auto-advance / the notification buttons it needs to turn the
		// next Track into a stream URL itself: same steps MainActivity uses when a track is tapped.
		player.setSourceResolver { track, forceRefresh, onProgress ->
			// A downloaded song plays from disk (works offline). forceRefresh means the player already failed on
			// the URL it had, so a broken file falls back to streaming instead of looping on the same path.
			if (!forceRefresh) {
				downloadIndex.pathFor(track.isrc)?.let(::File)?.takeIf { it.exists() }?.let {
					return@setSourceResolver Uri.fromFile(it).toString()
				}
			}
			val source = resolveTrackSource(track).getOrNull() ?: return@setSourceResolver null
			onProgress(0.5f)   // the track is identified; what is left is fetching its audio URL
			audioSources.audioUrl(source.youtubeVideoId, forceRefresh).getOrNull()
		}
		CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { ytDlp.warmUp() }
	}
}