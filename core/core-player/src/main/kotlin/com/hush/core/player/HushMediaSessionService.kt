package com.hush.core.player

import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import javax.inject.Inject
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class HushMediaSessionService : MediaSessionService() {
    @Inject lateinit var musicPlayer: MusicPlayer
    private var session: MediaSession? = null
    override fun onCreate() { super.onCreate(); session = MediaSession.Builder(this, (musicPlayer as Media3MusicPlayer).sessionPlayer).build() }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session
    override fun onDestroy() { session?.release(); session = null; super.onDestroy() }
}