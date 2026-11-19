/*
 * Adapted from BitChord (https://github.com/kushagrasinghx/BitChord), GPL-3.0.
 */
package com.gamut.feature.resolver.lossless

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONObject
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * JioSaavn as a [MusicSource]: one endpoint, no account, AAC up to 320 kbps.
 *
 * Its catalogue holds different recordings under the same title and artist, so the resolver's strict
 * matcher (exact title, artist overlap, duration within 4 s) still decides whether a row is accepted.
 */
class JioSaavnSource(
    val config: SourceConfig,
) : MusicSource {
    override val configId: String get() = config.id
    override val displayName: String get() = config.label.ifBlank { SourceKind.JIOSAAVN.label }

    override suspend fun health(): SourceHealth = SourceHealth.Ok()

    override suspend fun search(
        query: String,
        limit: Int,
        waitForAll: Boolean,
        request: StreamRequest?,
    ): List<LosslessTrack> =
        withContext(Dispatchers.IO) {
            val root =
                call(
                    "search.getResults",
                    "q" to query, "p" to "1", "n" to "10",
                )?.let { JSONObject(it) } ?: return@withContext emptyList()
            val results = root.optJSONArray("results") ?: return@withContext emptyList()
            (0 until results.length())
                .mapNotNull { results.optJSONObject(it) }
                .sortedByDescending { it.optString("explicit_content") == "1" }
                .take(limit)
                .mapNotNull { o ->
                    val info = o.optJSONObject("more_info") ?: JSONObject()
                    val artists = info.optJSONObject("artistMap")?.optJSONArray("primary_artists")
                    val artist =
                        (0 until (artists?.length() ?: 0))
                            .mapNotNull { artists?.optJSONObject(it)?.optString("name") }
                            .joinToString(", ")
                    val id = o.optString("id").ifBlank { return@mapNotNull null }
                    LosslessTrack(
                        id = id,
                        title = o.optString("title"),
                        artist = artist,
                        album = info.optString("album").ifBlank { null },
                        coverUrl =
                            o
                                .optString("image")
                                .replace(Regex("150x150|50x50"), "500x500")
                                .replace(Regex("^http://"), "https://")
                                .ifBlank { null },
                        durationSec = info.optString("duration").toIntOrNull(),
                        sourceQuality = "HIGH",
                    )
                }
        }

    override suspend fun stream(
        trackId: String,
        request: StreamRequest,
    ): SourceStream? =
        withContext(Dispatchers.IO) {
            val body = call("song.getDetails", "pids" to trackId) ?: return@withContext null
            val root = JSONObject(body)
            // song.getDetails answers with a map keyed by the id asked for, not a {"songs": [...]} envelope.
            val song =
                root.optJSONArray("songs")?.optJSONObject(0)
                    ?: root.keys().asSequence().mapNotNull { root.optJSONObject(it) }.firstOrNull()
                    ?: return@withContext null
            val info = song.optJSONObject("more_info") ?: return@withContext null
            val url = decrypt(info.optString("encrypted_media_url"))
            if (url.isBlank()) return@withContext null
            val has320 = info.optString("320kbps").equals("true", ignoreCase = true)
            val best = selectBest(url, has320) ?: return@withContext null
            // 48 and 96 kbps are below what YouTube already serves; taking them would be a downgrade.
            if (best.second != null && best.second!! <= MIN_USABLE_KBPS) {
                Log.i(TAG, "JioSaavn only offered ${best.second} kbps for $trackId; not worth playing")
                return@withContext null
            }
            SourceStream(best.first, StreamFormat(codec = "aac", kbps = best.second))
        }

    private fun call(
        method: String,
        vararg params: Pair<String, String>,
    ): String? {
        val url =
            API.toHttpUrl().newBuilder()
                .addQueryParameter("__call", method)
                .addQueryParameter("_format", "json")
                .addQueryParameter("_marker", "0")
                .addQueryParameter("api_version", "4")
                .addQueryParameter("ctx", "android")
                .apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }
                .build()
        val req =
            Request.Builder().url(url)
                .header("Accept", "application/json")
                .header("User-Agent", UA)
                .header("Accept-Language", "en-IN,en;q=0.9")
                .header("Cookie", "explicit_content=1")
                .build()
        return try {
            Http.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "JioSaavn $method: HTTP ${resp.code}")
                    null
                } else {
                    resp.body?.string()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "JioSaavn $method: ${e.message}")
            null
        }
    }

    private fun decrypt(encrypted: String): String {
        if (encrypted.isBlank()) return ""
        return try {
            val cipher = Cipher.getInstance("DES/ECB/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(DES_KEY.toByteArray(Charsets.UTF_8), "DES"))
            String(cipher.doFinal(Base64.decode(encrypted, Base64.DEFAULT)), Charsets.UTF_8).trim()
        } catch (e: Exception) {
            Log.w(TAG, "JioSaavn URL decryption failed: ${e.message}")
            ""
        }
    }

    /** The highest rendition the track really has, and its bitrate. The CDN has no 320 file when the row says false. */
    private fun selectBest(
        url: String,
        has320: Boolean,
    ): Pair<String, Int?>? {
        val m = RENDITION.find(url) ?: return url to null
        val offered = m.groupValues[1].toInt()
        if (!has320) return url to offered
        return url.replaceRange(m.range, "_320.${m.groupValues[2]}") to 320
    }

    private companion object {
        const val TAG = "Gamut"
        const val MIN_USABLE_KBPS = 96
        const val DES_KEY = "38346591"
        val API = String(Base64.decode("aHR0cHM6Ly93d3cuamlvc2Fhdm4uY29tL2FwaS5waHA=", Base64.DEFAULT), Charsets.UTF_8)
        const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"
        val RENDITION = Regex("_(48|96|160|320)\\.(mp4|aac|mp3)(?=[?#]|$)", RegexOption.IGNORE_CASE)
    }
}
