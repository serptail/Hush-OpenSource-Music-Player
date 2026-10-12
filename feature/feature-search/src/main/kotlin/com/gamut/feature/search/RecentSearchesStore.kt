package com.gamut.feature.search

import android.content.Context

/**
 * Last few searches, newest first, in SharedPreferences. A plain class (no DI) so the screen can create it from a
 * Context. Only queries the user acted on are stored (a result was opened, or the keyboard's Search key was pressed),
 * so half-typed words never end up here.
 */
class RecentSearchesStore(context: Context) {
    private val prefs = context.getSharedPreferences("recent_searches", Context.MODE_PRIVATE)

    fun all(): List<String> =
        prefs.getString(KEY, "").orEmpty().split('\n').filter { it.isNotBlank() }

    fun add(query: String) {
        val q = query.trim()
        if (q.length < 2) return
        val next = (listOf(q) + all().filterNot { it.equals(q, ignoreCase = true) }).take(MAX)
        prefs.edit().putString(KEY, next.joinToString("\n")).apply()
    }

    fun remove(query: String) {
        prefs.edit().putString(KEY, all().filterNot { it == query }.joinToString("\n")).apply()
    }

    fun clear() = prefs.edit().remove(KEY).apply()

    private companion object {
        const val KEY = "queries"
        const val MAX = 8
    }
}
