package com.gamut.feature.search

import androidx.lifecycle.ViewModel
import com.gamut.core.model.Track
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

sealed interface ShazamState {
    /** Nothing going on: the sheet is closed. */
    data object Idle : ShazamState
    /** [searching] = a recording is currently being checked by the server (listening carries on meanwhile). */
    data class Listening(val searching: Boolean) : ShazamState
    data class Found(val track: Track) : ShazamState
    data object NotFound : ShazamState
    data class Failed(val message: String) : ShazamState
    data object PermissionDenied : ShazamState
}

/**
 * Thin UI handle on [ShazamSession]. The listening itself no longer lives here: a ViewModel dies with the Activity, but
 * the session (kept alive by the microphone foreground service) keeps going in the background, and its state is simply
 * shown again when the app is reopened or the result notification is tapped.
 */
@HiltViewModel
class ShazamViewModel @Inject constructor(
    private val session: ShazamSession,
) : ViewModel() {

    val state: StateFlow<ShazamState> = session.state
    val level: StateFlow<Float> = session.level

    /** The caller makes sure the RECORD_AUDIO permission is granted first (see [permissionDenied]). */
    fun start() = session.start()

    /** Stops listening (if it was) and closes the sheet. */
    fun dismiss() = session.dismiss()

    fun permissionDenied() = session.permissionDenied()
}