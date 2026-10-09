package com.gamut.core.sync

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Access + refresh tokens live in Android-Keystore-backed encrypted preferences, not plain SharedPreferences. */
class EncryptedSessionManager(private val context: Context) : SessionManager {
    private val json = Json { ignoreUnknownKeys = true }
    private val prefs by lazy {
        EncryptedSharedPreferences.create(
            context, "gamut_session",
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override suspend fun saveSession(session: UserSession) = withContext(Dispatchers.IO) {
        prefs.edit().putString(KEY, json.encodeToString(session)).apply()
    }

    override suspend fun loadSession(): UserSession? = withContext(Dispatchers.IO) {
        runCatching { prefs.getString(KEY, null)?.let { json.decodeFromString<UserSession>(it) } }.getOrNull()
    }

    override suspend fun deleteSession() = withContext(Dispatchers.IO) { prefs.edit().remove(KEY).apply() }

    private companion object { const val KEY = "session" }
}
