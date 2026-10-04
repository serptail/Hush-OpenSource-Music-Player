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
    override fun onCreate() { super.onCreate(); session = MediaSession.Builder(this, (musicPlayer as Media3MusicPlayer).mediaSessionPlayer).build() }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session
    override fun onDestroy() { session?.release(); session = null; super.onDestroy() }
}