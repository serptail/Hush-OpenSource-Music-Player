package com.gamut.feature.resolver.potoken

import android.util.Log

/** Stand-in for BitChord's TrackLog: the PoToken code logs through logcat under the video tag. */
internal object PotLog {
    /** Verbose JS-failure details only in debug builds; flip on when diagnosing the minter. */
    @Volatile
    var debug: Boolean = false

    private const val PREFIX = "GamutVideo/"

    fun d(tag: String, message: String) {
        Log.d(PREFIX + tag, message)
    }

    fun w(tag: String, message: String) {
        Log.w(PREFIX + tag, message)
    }

    fun e(tag: String, message: String) {
        Log.e(PREFIX + tag, message)
    }
}
