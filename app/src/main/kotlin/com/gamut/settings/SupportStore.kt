package com.gamut.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Everything you might want to tune lives here. */
object SupportConfig {
    /** The one place the Ko-fi link is defined. */
    const val KOFI_URL = "https://ko-fi.com/serptail"

    const val QUIET_DAYS = 3                                  // nothing at all in the first days of use
    val SHEET_GAP_DAYS = longArrayOf(7, 30, 90)               // wait before sheet #1 (from first launch), #2, #3 (from the previous sheet)
    const val MAX_SHEETS = 3
    const val COLD_START_QUIET_MS = 20_000L                   // after the loading screen, stay quiet this long
    const val PLAYLIST_MILESTONE = 10                         // songs in one playlist
    val PLAY_MILESTONES = intArrayOf(100, 300, 1000)          // songs played
}

/** The "good moments" that can ask for the sheet. */
enum class SupportTrigger { FirstDownload, PlaylistMilestone, PlayMilestone }

/**
 * All support-sheet state, kept on the phone (SharedPreferences): no network, no account.
 * Moments call [recordDownloadSuccess] / [recordPlay] / [recordPlaylistSize]; they only leave a *pending* request.
 * The host (SupportSheetHost) decides when it is actually a good time to show it.
 */
@Singleton
class SupportStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("support", Context.MODE_PRIVATE)

    init {
        if (!prefs.contains(K_FIRST_LAUNCH)) prefs.edit().putLong(K_FIRST_LAUNCH, System.currentTimeMillis()).apply()
    }

    private val _nudge = MutableStateFlow(prefs.getBoolean(K_NUDGE, false))
    /** True after a sheet was dismissed: the profile circle gets a heart badge, the Support row pulses once. */
    val nudge: StateFlow<Boolean> = _nudge.asStateFlow()

    private val _pending = MutableStateFlow(readPending())
    /** A good moment happened and the sheet has not been shown for it yet. */
    val pending: StateFlow<SupportTrigger?> = _pending.asStateFlow()

    val plays: Int get() = prefs.getInt(K_PLAYS, 0)
    private val shownCount: Int get() = prefs.getInt(K_SHOWN, 0)
    private val dismissedForever: Boolean get() = prefs.getBoolean(K_FOREVER, false)

    // ---- moments -------------------------------------------------------------------------------------------------

    fun recordDownloadSuccess() {
        if (prefs.getBoolean(K_DOWNLOAD_DONE, false)) return
        prefs.edit().putBoolean(K_DOWNLOAD_DONE, true).apply()
        request(SupportTrigger.FirstDownload)
    }

    fun recordPlay() {
        val n = plays + 1
        prefs.edit().putInt(K_PLAYS, n).apply()
        if (n in SupportConfig.PLAY_MILESTONES) request(SupportTrigger.PlayMilestone)
    }

    fun recordPlaylistSize(largest: Int) {
        if (largest < SupportConfig.PLAYLIST_MILESTONE || prefs.getBoolean(K_PLAYLIST_DONE, false)) return
        prefs.edit().putBoolean(K_PLAYLIST_DONE, true).apply()
        request(SupportTrigger.PlaylistMilestone)
    }

    private fun request(trigger: SupportTrigger) {
        if (dismissedForever || _pending.value != null) return
        prefs.edit().putString(K_PENDING, trigger.name).apply()
        _pending.value = trigger
    }

    // ---- rules ---------------------------------------------------------------------------------------------------

    /** The timing rules. (The "not while playing / player open / loading / cold start" rules live in MainActivity.) */
    fun canShowNow(): Boolean {
        if (dismissedForever) return false
        val count = shownCount
        if (count >= SupportConfig.MAX_SHEETS) return false
        val now = System.currentTimeMillis()
        val first = prefs.getLong(K_FIRST_LAUNCH, now)
        if (now - first < days(SupportConfig.QUIET_DAYS.toLong())) return false
        val anchor = if (count == 0) first else prefs.getLong(K_LAST_SHOWN, now)
        return now - anchor >= days(SupportConfig.SHEET_GAP_DAYS[count.coerceAtMost(SupportConfig.SHEET_GAP_DAYS.lastIndex)])
    }

    // ---- what the person did -------------------------------------------------------------------------------------

    /** The sheet appeared. Counts as one of the three, even if the app is killed with it open. */
    fun onSheetShown() {
        prefs.edit().putInt(K_SHOWN, shownCount + 1).putLong(K_LAST_SHOWN, System.currentTimeMillis()).remove(K_PENDING).apply()
        _pending.value = null
    }

    /** Swipe, scrim tap, back, or "Maybe later": ask again much later, and point at Settings. */
    fun onLater() = setNudge(!dismissedForever)

    /** "Buy me a coffee": they went to Ko-fi, no badge needed. */
    fun onSupported() = setNudge(false)

    /** "I already did / don't ask again": never show anything proactive again. Settings and About stay. */
    fun onNeverAgain() {
        prefs.edit().putBoolean(K_FOREVER, true).remove(K_PENDING).apply()
        _pending.value = null
        setNudge(false)
    }

    fun clearNudge() = setNudge(false)

    private fun setNudge(on: Boolean) {
        prefs.edit().putBoolean(K_NUDGE, on).apply()
        _nudge.value = on
    }

    private fun readPending() = prefs.getString(K_PENDING, null)?.let { runCatching { SupportTrigger.valueOf(it) }.getOrNull() }
    private fun days(n: Long) = n * 24L * 60L * 60L * 1000L

    private companion object {
        const val K_FIRST_LAUNCH = "first_launch"
        const val K_SHOWN = "shown_count"
        const val K_LAST_SHOWN = "last_shown"
        const val K_FOREVER = "dismissed_forever"
        const val K_NUDGE = "nudge"
        const val K_PENDING = "pending"
        const val K_PLAYS = "plays"
        const val K_DOWNLOAD_DONE = "download_done"
        const val K_PLAYLIST_DONE = "playlist_done"
    }
}

/** Gives composables (Settings, About, the host in MainActivity) the same singleton store, the way the rest of the app does. */
@HiltViewModel
class SupportViewModel @Inject constructor(val store: SupportStore) : ViewModel()