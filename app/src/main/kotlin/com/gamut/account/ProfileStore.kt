package com.gamut.account

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProfileStore
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) {
        private val prefs = context.getSharedPreferences("gamut_profile", Context.MODE_PRIVATE)
        private val tick = MutableStateFlow(0)

        fun nameOverride(userId: String?): Flow<String?> = tick.map { prefs.getString(nameKey(userId), null) }

        fun setName(
            userId: String?,
            name: String?,
        ) {
            prefs.edit().apply { if (name.isNullOrBlank()) remove(nameKey(userId)) else putString(nameKey(userId), name) }.apply()
            tick.value++
        }

        fun localName(): String? = prefs.getString(nameKey(null), null)?.takeIf { it.isNotBlank() }

        fun adoptLocalName(userId: String?) {
            if (userId == null) return
            val chosen = localName() ?: return
            if (prefs.getString(nameKey(userId), null).isNullOrBlank()) setName(userId, chosen)
        }

        private val _autoBackup = MutableStateFlow(prefs.getBoolean(KEY_AUTO_BACKUP, true))
        val autoBackup: StateFlow<Boolean> = _autoBackup

        fun setAutoBackup(enabled: Boolean) {
            prefs.edit().putBoolean(KEY_AUTO_BACKUP, enabled).apply()
            _autoBackup.value = enabled
        }

        private fun nameKey(userId: String?) = "name_${userId ?: "local"}"

        private companion object {
            const val KEY_AUTO_BACKUP = "auto_backup"
        }
    }
