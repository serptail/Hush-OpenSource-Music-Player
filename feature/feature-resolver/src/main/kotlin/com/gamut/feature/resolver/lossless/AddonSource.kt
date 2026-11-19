/*
 * Adapted from BitChord (https://github.com/kushagrasinghx/BitChord), GPL-3.0.
 * BitChord's device-verification handshake (bitchordAuth) is not ported: it exists only for addons that
 * serve the genuine BitChord app.
 */
package com.gamut.feature.resolver.lossless

import android.util.Log
import com.gamut.core.player.audio.DeviceCodecs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * A [MusicSource] backed by one addon server: plain HTTP, `GET /manifest.json`, `/search?q=` and
 * `/stream/{id}`, JSON both ways. Nothing is downloaded or run.
 *
 * Every parameter the addon's manifest declares goes back as a query parameter with its default, and
 * `quality` is set from the addon's own option list, because an addon declaring `high|normal|low` does not
 * understand "lossless". `?atmos=auto` is sent only when this device and the listener both want Atmos.
 */
class AddonSource(
    val config: SourceConfig,
) : MusicSource {
    override val configId: String get() = config.id
    override val displayName: String get() = config.displayName

    private val base: String = config.baseUrl.trim().removeSuffix("/manifest.json").trimEnd('/')

    @Volatile private var manifest: JSONObject? = null

    @Volatile private var rows: Map<String, JSONObject> = emptyMap()

    override suspend fun health(): SourceHealth =
        withContext(Dispatchers.IO) {
            try {
                val m = loadManifest()
                val resources = m.optJSONArray("resources")?.toStrings().orEmpty()
                if (resources.isNotEmpty() && resources.none { it.equals("search", true) }) {
                    SourceHealth.Rejected("This addon does not declare search")
                } else {
                    SourceHealth.Ok(m.optString("name").ifBlank { null })
                }
            } catch (e: Exception) {
                SourceHealth.Unreachable(e.message ?: "unreachable")
            }
        }

    override suspend fun search(
        query: String,
        limit: Int,
        waitForAll: Boolean,
        request: StreamRequest?,
    ): List<LosslessTrack> =
        withContext(Dispatchers.IO) {
            val body = get(endpoint(listOf("search"), settings(request) + ("q" to query.trim())))
            val arr = JSONObject(body).optJSONArray("tracks") ?: return@withContext emptyList()
            val out = ArrayList<LosslessTrack>()
            val seen = HashMap<String, JSONObject>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id")
                if (id.isBlank()) continue
                seen[id] = o
                out +=
                    LosslessTrack(
                        id = id,
                        title = o.optString("title"),
                        artist = o.optString("artist"),
                        album = o.optString("album").ifBlank { null },
                        coverUrl = o.optString("artworkURL").ifBlank { o.optString("albumArtworkURL") }.ifBlank { null },
                        durationSec = o.optDouble("duration", 0.0).takeIf { it > 0 }?.toInt(),
                        sourceQuality = o.optString("audioQuality").ifBlank { o.optString("format") }.ifBlank { null },
                    )
                if (out.size >= limit) break
            }
            rows = seen
            out
        }

    override suspend fun stream(
        trackId: String,
        request: StreamRequest,
    ): SourceStream? =
        withContext(Dispatchers.IO) {
            // A search row may carry its own URL, in which case the spec skips /stream altogether.
            val row = rows[trackId]
            val rowUrl = row?.optString("streamURL").orEmpty()
            val json =
                if (rowUrl.isNotBlank()) {
                    JSONObject().put("url", rowUrl).put("format", row?.optString("format").orEmpty())
                } else {
                    JSONObject(get(endpoint(listOf("stream", trackId), settings(request))))
                }
            json.optString("error").takeIf { it.isNotBlank() }?.let {
                Log.w(TAG, "${config.displayName}: no stream for $trackId — $it")
                return@withContext null
            }
            val url = json.optString("url")
            if (url.isBlank()) return@withContext null
            val encrypted = json.opt("encrypted")
            if (encrypted != null && encrypted != JSONObject.NULL && encrypted != false && encrypted.toString() != "false") {
                Log.w(TAG, "${config.displayName}: stream is encrypted ($encrypted), which Gamut cannot play")
                return@withContext null
            }
            val manifestKind = json.optString("manifest").ifBlank { json.optString("mediaType") }.lowercase(Locale.ROOT)
            if (manifestKind in setOf("hls", "dash") && !url.contains(".m3u8") && !url.contains(".mpd")) {
                Log.w(TAG, "${config.displayName}: $manifestKind stream without a recognisable URL; skipping")
                return@withContext null
            }
            val text = "${json.optString("format")} ${json.optString("quality")} ${json.optString("audioQuality")}"
            val atmos =
                json.optBoolean("atmos", false) ||
                    ATMOS.containsMatchIn("$text ${json.optString("audioMode")} ${json.optJSONArray("audioModes")}")
            val codec =
                when {
                    atmos -> "eac3-joc"
                    else ->
                        (json.optString("codec").ifBlank { json.optString("fileCodec") }.ifBlank { json.optString("format") })
                            .lowercase(Locale.ROOT)
                            .let(::normaliseCodec)
                }
            val bitrate = json.optDouble("bitrate", 0.0)
            SourceStream(
                url = url,
                format =
                    StreamFormat(
                        codec = codec,
                        kbps = bitrate.takeIf { it > 0 }?.let { if (it > 10_000) (it / 1000).toInt() else it.toInt() },
                        sampleRateHz = json.optDouble("sampleRate", 0.0).takeIf { it > 0 }?.toInt(),
                        bitDepth = json.optDouble("bitDepth", 0.0).takeIf { it > 0 }?.toInt(),
                    ),
                durationSec = row?.optDouble("duration", 0.0)?.takeIf { it > 0 }?.toInt(),
            )
        }

    // ── Settings passthrough ──────────────────────────────────────────────

    private suspend fun settings(request: StreamRequest?): Map<String, String> {
        val declared = runCatching { loadManifest().optJSONArray("settings") }.getOrNull()
        val params = LinkedHashMap<String, String>()
        var qualityOptions = emptyList<String>()
        if (declared != null) {
            for (i in 0 until declared.length()) {
                val s = declared.optJSONObject(i) ?: continue
                val key = s.optString("key")
                if (key.isBlank()) continue
                s.opt("default")?.takeIf { it != JSONObject.NULL }?.let { params[key] = it.toString() }
                if (key == "quality") {
                    val opts = s.optJSONArray("options")
                    qualityOptions =
                        (0 until (opts?.length() ?: 0)).mapNotNull { j ->
                            val o = opts!!.opt(j)
                            (if (o is JSONObject) o.opt("value") else o)?.takeIf { it != JSONObject.NULL }?.toString()
                        }
                }
            }
        }
        when (request) {
            is StreamRequest.Lossless -> params["quality"] = qualityOptions.firstOrNull() ?: "LOSSLESS"
            is StreamRequest.Capped -> params["quality"] = qualityOptions.lastOrNull() ?: "LOW"
            is StreamRequest.Best, null -> if (qualityOptions.isNotEmpty()) params["quality"] = qualityOptions.first()
        }
        // `auto` is try-Atmos-then-stereo. `1` would be strict mode, where a track without an Atmos mix is an error.
        if (DeviceCodecs.atmosWanted) params.putIfAbsent("atmos", "auto")
        return params
    }

    private suspend fun loadManifest(): JSONObject {
        manifest?.let { return it }
        val parsed = JSONObject(get("$base/manifest.json"))
        manifest = parsed
        return parsed
    }

    private fun endpoint(
        segments: List<String>,
        params: Map<String, String>,
    ): String {
        val builder = base.toHttpUrlOrNull()?.newBuilder() ?: throw IllegalArgumentException("That is not a usable address")
        segments.forEach(builder::addPathSegment)
        params.forEach { (k, v) -> builder.addQueryParameter(k, v) }
        return builder.build().toString()
    }

    private fun get(url: String): String {
        val req = Request.Builder().url(url).header("Accept", "application/json").build()
        Http.client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            return resp.body?.string().orEmpty()
        }
    }

    private fun JSONArray.toStrings(): List<String> = (0 until length()).map { optString(it) }

    private fun normaliseCodec(raw: String): String =
        when {
            raw.isBlank() -> ""
            "flac" in raw -> "flac"
            "alac" in raw -> "alac"
            raw.startsWith("wav") -> "wav"
            raw in setOf("m4a", "mp4", "aac") -> "aac"
            else -> raw
        }

    private companion object {
        const val TAG = "Gamut"
        val ATMOS = Regex("""atmos|dolby|eac3[_-]?joc|ec-?3""", RegexOption.IGNORE_CASE)
    }
}
