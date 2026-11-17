package com.gamut.onboarding

import android.content.Context
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamut.account.ProfileStore
import com.gamut.core.sync.AuthRepository
import com.gamut.core.sync.SyncConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class Gate { Loading, Setup, Welcome, Ready }

data class WelcomeUi(val busy: Boolean = false, val error: String? = null)

@HiltViewModel
class WelcomeViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val auth: AuthRepository,
    private val store: OnboardingStore,
    private val profile: ProfileStore,
) : ViewModel() {
    val gate: StateFlow<Gate> = combine(store.localMode, store.setupDone, store.setupStarted, auth.authState) { local, setupDone, started, signedIn ->
        android.util.Log.d("GamutGate", "local=$local setupDone=$setupDone started=$started signedIn=$signedIn")
        when {
            local -> Gate.Ready
            started && !setupDone -> Gate.Setup
            SyncConfig.enabled && signedIn == null -> Gate.Loading
            signedIn == true -> Gate.Ready
            !setupDone -> Gate.Setup
            !SyncConfig.enabled -> Gate.Ready
            else -> Gate.Welcome
        }
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        when {
            store.localMode.value -> Gate.Ready
            store.setupStarted.value && !store.setupDone.value -> Gate.Setup
            SyncConfig.enabled -> Gate.Loading
            !store.setupDone.value -> Gate.Setup
            else -> Gate.Ready
        },
    )

    private val _ui = MutableStateFlow(WelcomeUi())
    val ui: StateFlow<WelcomeUi> = _ui

    init {
        viewModelScope.launch(Dispatchers.IO) {
            combine(store.localMode, auth.authState, profile.autoBackup) { local, signedIn, enabled -> enabled && (local || !SyncConfig.enabled) && signedIn != true }
                .distinctUntilChanged()
                .collect { if (it) AutoBackup.schedule(appContext) else AutoBackup.cancel(appContext) }
        }
    }

    fun continueLocally() = store.chooseLocal()

    fun signIn(activityContext: Context) {
        if (_ui.value.busy) return
        viewModelScope.launch {
            _ui.value = WelcomeUi(busy = true)
            auth.signInWithGoogle(activityContext).fold(
                onSuccess = {
                    profile.adoptLocalName(auth.currentUserId())
                    _ui.value = WelcomeUi()
                },
                onFailure = {
                    _ui.value = WelcomeUi(
                        error = if (it is GetCredentialCancellationException) {
                            null
                        } else {
                            "Couldn\u2019t sign in. Check your connection and try again."
                        },
                    )
                },
            )
        }
    }
}
