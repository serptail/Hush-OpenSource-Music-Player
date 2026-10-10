package com.gamut.core.player

import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import javax.inject.Inject
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class GamutMediaSessionService : MediaSessionService() {
    @Inject lateinit var musicPlayer: MusicPlayer
    private var session: MediaSession? = null
    // mediaSessionPlayer (not sessionPlayer): routes notification / lock-screen / Bluetooth next + previous
    // through the queue logic in Media3MusicPlayer instead of ExoPlayer's single-item playlist.
    override fun onCreate() {
        super.onCreate()
        val player = musicPlayer as Media3MusicPlayer
        session = MediaSession.Builder(this, player.mediaSessionPlayer).build()
        // Crossfade hands playback over to a second ExoPlayer: keep the notification / lock screen pointed at the current one.
        player.onSessionPlayerChanged = { session?.player = it }
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    // While the next track is being resolved the player is briefly paused. Media3 would drop the foreground state
    // right then, and Android then freezes the app on the lock screen: playback never resumes. Stay foreground
    // for that gap.
    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        super.onUpdateNotification(session, startInForegroundRequired || (musicPlayer as Media3MusicPlayer).keepForeground)
    }
    override fun onDestroy() {
        (musicPlayer as Media3MusicPlayer).onSessionPlayerChanged = null
        session?.release(); session = null; super.onDestroy()
    }
}