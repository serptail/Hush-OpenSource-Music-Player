package com.gamut.core.player

import android.app.PendingIntent
import android.os.Bundle
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.gamut.core.model.RepeatMode
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Owns the MediaSession: the notification-shade player ("This phone"), the lock-screen controls and Bluetooth
 * buttons all come from it. Besides previous / play / next and the seek bar (which Media3 draws by itself) it adds
 * a shuffle and a repeat button, a Gamut small icon, and opens the app when the notification is tapped.
 */
@OptIn(UnstableApi::class)
@AndroidEntryPoint
class GamutMediaSessionService : MediaSessionService() {
    @Inject lateinit var musicPlayer: MusicPlayer
    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val repeatCommand = SessionCommand(ACTION_REPEAT, Bundle.EMPTY)
    private val shuffleCommand = SessionCommand(ACTION_SHUFFLE, Bundle.EMPTY)

    // mediaSessionPlayer (not sessionPlayer): routes notification / lock-screen / Bluetooth next + previous
    // through the queue logic in Media3MusicPlayer instead of ExoPlayer's single-item playlist.
    override fun onCreate() {
        super.onCreate()
        val player = musicPlayer as Media3MusicPlayer

        // Small status-bar icon: drop a white-on-transparent vector named ic_stat_gamut into any module's res/drawable
        // and it is used; without it Media3's generic icon stays.
        val icon = resources.getIdentifier("ic_stat_gamut", "drawable", packageName)
        if (icon != 0) {
            setMediaNotificationProvider(DefaultMediaNotificationProvider.Builder(this).build().also { it.setSmallIcon(icon) })
        }

        val builder = MediaSession.Builder(this, player.mediaSessionPlayer).setCallback(SessionCallback())
        packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            builder.setSessionActivity(
                PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
        }
        session = builder.build()
        refreshButtons()
        // The buttons show the real state: repeat off / all / one, shuffle on / off.
        scope.launch {
            combine(musicPlayer.repeatMode, musicPlayer.shuffleEnabled) { _, _ -> }.collect { refreshButtons() }
        }
        // Crossfade hands playback over to a second ExoPlayer: keep the notification / lock screen pointed at the current one.
        player.onSessionPlayerChanged = { session?.player = it }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    @Suppress("DEPRECATION")   // setCustomLayout / CommandButton.Builder() exist in every Media3 1.x, only newer ones prefer other names
    private fun refreshButtons() {
        val shuffleOn = musicPlayer.shuffleEnabled.value
        val repeat = musicPlayer.repeatMode.value
        val shuffleButton = CommandButton.Builder()
            .setDisplayName(if (shuffleOn) "Shuffle on" else "Shuffle off")
            .setSessionCommand(shuffleCommand)
            .setIconResId(if (shuffleOn) R.drawable.gamut_ic_shuffle_on else R.drawable.gamut_ic_shuffle_off)
            .build()
        val repeatButton = CommandButton.Builder()
            .setDisplayName(
                when (repeat) {
                    RepeatMode.OFF -> "Repeat off"
                    RepeatMode.ALL -> "Repeat all"
                    RepeatMode.ONE -> "Repeat one"
                },
            )
            .setSessionCommand(repeatCommand)
            .setIconResId(
                when (repeat) {
                    RepeatMode.OFF -> R.drawable.gamut_ic_repeat_off
                    RepeatMode.ALL -> R.drawable.gamut_ic_repeat_all
                    RepeatMode.ONE -> R.drawable.gamut_ic_repeat_one
                },
            )
            .build()
        session?.setCustomLayout(ImmutableList.of(shuffleButton, repeatButton))
    }

    private inner class SessionCallback : MediaSession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(repeatCommand)
                .add(shuffleCommand)
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                ACTION_SHUFFLE -> musicPlayer.setShuffle(!musicPlayer.shuffleEnabled.value)
                ACTION_REPEAT -> musicPlayer.setRepeatMode(
                    when (musicPlayer.repeatMode.value) {
                        RepeatMode.OFF -> RepeatMode.ALL
                        RepeatMode.ALL -> RepeatMode.ONE
                        RepeatMode.ONE -> RepeatMode.OFF
                    },
                )
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    // While the next track is being resolved the player is briefly paused. Media3 would drop the foreground state
    // right then, and Android then freezes the app on the lock screen: playback never resumes. Stay foreground
    // for that gap.
    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        super.onUpdateNotification(session, startInForegroundRequired || (musicPlayer as Media3MusicPlayer).keepForeground)
    }

    override fun onDestroy() {
        (musicPlayer as Media3MusicPlayer).onSessionPlayerChanged = null
        scope.cancel()
        session?.release(); session = null; super.onDestroy()
    }

    private companion object {
        const val ACTION_REPEAT = "gamut.action.REPEAT"
        const val ACTION_SHUFFLE = "gamut.action.SHUFFLE"
    }
}