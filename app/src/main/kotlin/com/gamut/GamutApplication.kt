package com.gamut

import android.app.Activity
import android.app.Application
import android.net.Uri
import android.os.Bundle
import androidx.work.Configuration
import com.gamut.account.ProfileStore
import com.gamut.core.database.DownloadIndex
import com.gamut.core.player.MusicPlayer
import com.gamut.core.sync.SyncManager
import com.gamut.feature.resolver.AndroidYtDlpEngine
import com.gamut.feature.resolver.AudioSourceRepository
import com.gamut.feature.resolver.ResolveTrackSourceUseCase
import com.gamut.feature.resolver.YtDlpUpdateWorker
import com.gamut.onboarding.AutoBackup
import dagger.hilt.android.HiltAndroidApp
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class GamutApplication : Application(), Configuration.Provider {
	// WorkManager's auto-initializer is disabled in this project, so it is configured here (used by the daily auto-backup).
	override val workManagerConfiguration: Configuration get() = Configuration.Builder().build()

	@Inject lateinit var ytDlp: AndroidYtDlpEngine
	@Inject lateinit var player: MusicPlayer
	@Inject lateinit var resolveTrackSource: ResolveTrackSourceUseCase
	@Inject lateinit var audioSources: AudioSourceRepository
	@Inject lateinit var downloadIndex: DownloadIndex
	@Inject lateinit var syncManager: SyncManager
	@Inject lateinit var profileStore: ProfileStore

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
		CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
			// Brings yt-dlp up to date if it has been a while, then keeps it fresh in the background (every ~12 h).
			ytDlp.warmUp()
			YtDlpUpdateWorker.schedule(applicationContext)
		}
		// Local-first sync: does nothing until a Supabase project is configured and the user signs in.
		syncManager.start(CoroutineScope(SupervisorJob() + Dispatchers.Default))
		// Pull other devices' changes (and notice a revoked session) as soon as the app is opened, not up to 15 min later.
		registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
			private var started = 0
			private var lastSyncAt = 0L
			override fun onActivityStarted(activity: Activity) {
				if (started++ == 0 && System.currentTimeMillis() - lastSyncAt > 30_000) {   // not on every rotation
					lastSyncAt = System.currentTimeMillis()
					syncManager.syncNow()
				}
			}
			override fun onActivityStopped(activity: Activity) { started-- }
			override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
			override fun onActivityResumed(activity: Activity) {}
			override fun onActivityPaused(activity: Activity) {}
			override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
			override fun onActivityDestroyed(activity: Activity) {}
		})
		// Daily on-device backup follows the Profile switch and is applied at every launch, so "on" and "off" both stick.
		CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
			profileStore.autoBackup.collect { AutoBackup.apply(applicationContext, it) }
		}
	}
}