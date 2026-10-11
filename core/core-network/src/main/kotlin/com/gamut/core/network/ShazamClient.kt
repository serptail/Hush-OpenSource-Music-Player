package com.gamut.core.network

import android.util.Log
import com.gamut.core.model.Track
import com.gamut.core.model.TrackSource
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import org.json.JSONObject

sealed interface ShazamResult {
    /** The song, already shaped as a regular [Track] (cover, album, length when known), ready to save or play. */
    data class Match(val track: Track, val shazamUrl: String?) : ShazamResult
    data object NoMatch : ShazamResult
    data class Failure(val message: String) : ShazamResult
}

/**
 * Recognizes a song fully on the device: the audio is fingerprinted locally ([ShazamSignature]) and only the
 * fingerprint (not the audio) is sent to Shazam's public discovery endpoint. No server and no API key are involved.
 * The same request ShazamIO makes, so the same caveat applies: it is an unofficial, undocumented endpoint.
 *
 * Plain HttpURLConnection + org.json on purpose: no extra dependencies.
 */
@Singleton
class ShazamClient @Inject constructor(private val configs: ShazamConfigStore) {

    /** One try: the outcome, and whether the failure looks like the request itself is outdated (a fresh config may fix it). */
    private class Sent(val result: ShazamResult, val configProblem: Boolean = false)

    /** [pcm] = raw 16 kHz mono 16-bit little-endian PCM (what MicRecorder produces), ideally 5 to 12 seconds. */
    suspend fun recognize(pcm: ByteArray): ShazamResult = withContext(Dispatchers.IO) {
        val signature = try {
            ShazamSignature.fromPcm16(pcm)
        } catch (e: Exception) {
            return@withContext ShazamResult.Failure("Couldn't analyse the audio")
        }
        configs.refreshIfDue()   // at most one short lookup every few hours
        val first = send(signature, configs.current())
        if (first.configProblem && configs.refreshNow()) {
            Log.i(TAG, "retrying with the newer request config")
            return@withContext send(signature, configs.current()).result
        }
        first.result
    }

    private suspend fun send(signature: ShazamSignature.Result, config: ShazamConfig): Sent {
        val body = JSONObject()
            .put("timezone", config.timezone)
            .put("signature", JSONObject().put("uri", signature.uri).put("samplems", signature.sampleMs))
            .put("timestamp", System.currentTimeMillis())
            .put("context", JSONObject())
            .put("geolocation", JSONObject())
            .toString()
            .toByteArray()

        val url = config.endpoint
            .replace("{uuid1}", UUID.randomUUID().toString().uppercase())
            .replace("{uuid2}", UUID.randomUUID().toString().uppercase())

        val connection = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            return Sent(ShazamResult.Failure("Recognition failed"), configProblem = true)
        }
        // A blocking socket read can't be interrupted, but closing the connection from here makes it throw.
        val onCancel = currentCoroutineContext().job.invokeOnCompletion { cause -> if (cause != null) connection.disconnect() }
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("X-Shazam-Platform", config.platform)
            connection.setRequestProperty("X-Shazam-AppVersion", config.appVersion)
            connection.setRequestProperty("Accept", "*/*")
            connection.setRequestProperty("Accept-Language", config.language)
            connection.setRequestProperty("User-Agent", config.userAgent)
            config.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }

            when (val code = connection.responseCode) {
                200 -> Sent(parse(connection.inputStream.bufferedReader().use { it.readText() }))
                204 -> Sent(ShazamResult.NoMatch)
                429 -> Sent(ShazamResult.Failure("Too many tries, wait a moment and try again"))
                else -> {
                    val snippet = connection.errorStream?.bufferedReader()?.use { it.readText().take(200) }.orEmpty()
                    Log.w(TAG, "Shazam answered HTTP $code: $snippet")
                    Sent(ShazamResult.Failure("Shazam had a problem ($code)"), configProblem = true)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Sent(ShazamResult.Failure("Couldn't reach Shazam"))
        } catch (e: Exception) {
            Sent(ShazamResult.Failure("Recognition failed"))
        } finally {
            onCancel.dispose()
            connection.disconnect()
        }
    }

    private suspend fun parse(text: String): ShazamResult {
        if (text.isBlank()) return ShazamResult.NoMatch
        val track = JSONObject(text).optJSONObject("track") ?: return ShazamResult.NoMatch
        val title = track.str("title") ?: return ShazamResult.NoMatch
        val artist = track.str("subtitle").orEmpty()

        var album: String? = null
        track.optJSONArray("sections")?.let { sections ->
            for (i in 0 until sections.length()) {
                val section = sections.optJSONObject(i) ?: continue
                if (section.optString("type") != "SONG") continue
                val metadata = section.optJSONArray("metadata") ?: continue
                for (j in 0 until metadata.length()) {
                    val item = metadata.optJSONObject(j) ?: continue
                    if (item.optString("title") == "Album") album = item.str("text")
                }
            }
        }
        val images = track.optJSONObject("images")
        val cover = images?.str("coverarthq") ?: images?.str("coverart")

        // Shazam doesn't know the track length; iTunes does (best effort, never fails the recognition).
        val extra = itunes(title, artist)

        // Shazam almost always has an ISRC; if it doesn't, a stable id of our own keeps the library key unique.
        val isrc = track.str("isrc") ?: "shazam:${track.str("key") ?: "$artist-$title".lowercase()}"
        return ShazamResult.Match(
            track = Track(
                isrc = isrc,
                title = title,
                artist = artist,
                album = album ?: extra?.album,
                durationMs = extra?.durationMs ?: 0L,   // 0 = unknown; the player learns it from the stream
                albumArtUrl = cover ?: extra?.artworkUrl,
                source = TrackSource.ITUNES,
            ),
            shazamUrl = track.str("url"),
        )
    }

    private class ItunesInfo(val durationMs: Long?, val album: String?, val artworkUrl: String?)

    /** Length / album / cover of the same song from the iTunes catalog. Returns null if not found (never throws). */
    private suspend fun itunes(title: String, artist: String): ItunesInfo? {
        val wantedTitle = norm(title)
        val wantedArtist = norm(artist)
        if (wantedTitle.isEmpty()) return null
        val results = try {
            val term = URLEncoder.encode("$artist $title", "UTF-8")
            val connection = URL("https://itunes.apple.com/search?term=$term&entity=song&limit=5")
                .openConnection() as HttpURLConnection
            val onCancel = currentCoroutineContext().job.invokeOnCompletion { cause -> if (cause != null) connection.disconnect() }
            try {
                connection.connectTimeout = ITUNES_TIMEOUT_MS
                connection.readTimeout = ITUNES_TIMEOUT_MS
                if (connection.responseCode != 200) return null
                JSONObject(connection.inputStream.bufferedReader().use { it.readText() }).optJSONArray("results")
            } finally {
                onCancel.dispose()
                connection.disconnect()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        } ?: return null

        for (i in 0 until results.length()) {
            val item = results.optJSONObject(i) ?: continue
            val foundTitle = norm(item.optString("trackName"))
            val foundArtist = norm(item.optString("artistName"))
            val sameArtist = wantedArtist.isNotEmpty() && foundArtist.isNotEmpty() &&
                (foundArtist.contains(wantedArtist) || wantedArtist.contains(foundArtist))
            if (foundTitle == wantedTitle && sameArtist) {
                return ItunesInfo(
                    durationMs = item.optLong("trackTimeMillis", 0L).takeIf { it > 0 },
                    album = item.str("collectionName"),
                    artworkUrl = item.str("artworkUrl100")?.replace("100x100bb", "600x600bb"),
                )
            }
        }
        return null
    }

    /** Lower-case letters and digits only, without "(feat. ...)" / "[Remastered]" parts. Works for any script. */
    private fun norm(text: String): String =
        text.replace(BRACKETS, "").filter { it.isLetterOrDigit() }.lowercase()

    /** A string field, or null when it is missing, JSON null, or blank (org.json would give "null" for a JSON null). */
    private fun JSONObject.str(name: String): String? =
        if (isNull(name)) null else optString(name).takeIf { it.isNotBlank() }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 8_000
        const val READ_TIMEOUT_MS = 20_000
        const val ITUNES_TIMEOUT_MS = 2_000
        const val TAG = "ShazamClient"
        val BRACKETS = Regex("[(\\[].*?[)\\]]")
    }
}