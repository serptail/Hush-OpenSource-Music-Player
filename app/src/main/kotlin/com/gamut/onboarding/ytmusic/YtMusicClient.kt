package com.gamut.onboarding.ytmusic

import com.gamut.onboarding.importer.ImportLibrary
import com.gamut.onboarding.importer.ImportPlaylist
import com.gamut.onboarding.importer.ImportTrack
import com.gamut.onboarding.importer.MiniJson
import java.io.IOException
import java.io.InterruptedIOException
import java.net.URLEncoder
import java.util.Locale
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class YtAuthException(message: String) : IOException(message)

class YtMusicClient(private val http: OkHttpClient, private val session: YtSession) {
    @Volatile private var visitorData: String? = session.visitorData

    fun browse(browseId: String): Any? = post("browse", emptyMap()) { put("browseId", browseId) }

    fun continuation(token: String): Any? = post(
        "browse",
        mapOf("ctoken" to token, "continuation" to token, "type" to "next"),
    ) { put("continuation", token) }

    private fun post(endpoint: String, query: Map<String, String>, extras: JSONObject.() -> Unit): Any? {
        val params = buildString {
            append("?prettyPrint=false&hl=en")
            query.forEach { (key, value) -> append('&').append(key).append('=').append(URLEncoder.encode(value, "UTF-8")) }
        }
        val version = session.clientVersion ?: FALLBACK_VERSION
        val body = JSONObject().apply {
            put(
                "context",
                JSONObject()
                    .put(
                        "client",
                        JSONObject().put("clientName", "WEB_REMIX").put("clientVersion", version).put("hl", "en")
                            .put("gl", Locale.getDefault().country.ifEmpty { "US" })
                            .also { client -> visitorData?.let { client.put("visitorData", it) } },
                    )
                    .put(
                        "user",
                        JSONObject().put("lockedSafetyMode", false)
                            .also { user -> session.dataSyncId?.let { user.put("onBehalfOfUser", it) } },
                    )
                    .put("request", JSONObject().put("useSsl", true)),
            )
            extras()
        }.toString().toRequestBody(JSON)

        val request = Request.Builder()
            .url("$BASE/$endpoint$params")
            .post(body)
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("X-Origin", ORIGIN)
            .header("Origin", ORIGIN)
            .header("Referer", "$ORIGIN/")
            .header("X-YouTube-Client-Name", "67")
            .header("X-YouTube-Client-Version", version)
            .apply {
                if (session.cookie.isNotBlank()) {
                    header("Cookie", session.cookie)
                    header("X-Goog-AuthUser", session.authUser ?: "0")
                }
                visitorData?.let { header("X-Goog-Visitor-Id", it) }
                session.pageId?.let { header("X-Goog-PageId", it) }
                YtSigning.sapisidFrom(session.cookie)?.let { header("Authorization", YtSigning.sapisidHash(it, ORIGIN)) }
            }
            .build()

        var last: IOException? = null
        repeat(ATTEMPTS) { attempt ->
            if (attempt > 0) Thread.sleep(BACKOFF_MS shl (attempt - 1))
            try {
                http.newCall(request).execute().use { response ->
                    if (response.code == 401 || response.code == 403) throw YtAuthException("YouTube Music rejected the sign-in (${response.code})")
                    if (!response.isSuccessful) throw IOException("YouTube Music answered ${response.code}")
                    val parsed = MiniJson.parse(response.body?.string().orEmpty())
                    if (visitorData == null) {
                        visitorData = ((parsed as? Map<*, *>)?.get("responseContext") as? Map<*, *>)?.get("visitorData") as? String
                    }
                    return parsed
                }
            } catch (e: YtAuthException) {
                throw e
            } catch (e: InterruptedIOException) {
                throw e
            } catch (e: IOException) {
                last = e
            }
        }
        throw last ?: IOException("YouTube Music didn\u2019t answer")
    }

    private companion object {
        const val BASE = "https://music.youtube.com/youtubei/v1"
        const val ORIGIN = "https://music.youtube.com"
        const val FALLBACK_VERSION = "1.20250101.01.00"
        const val ATTEMPTS = 3
        const val BACKOFF_MS = 500L
        val JSON = "application/json".toMediaType()
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"
    }
}

class YtMusicLibraryReader(private val client: YtMusicClient) {
    suspend fun read(onProgress: (String) -> Unit): ImportLibrary {
        var failure: IOException? = null

        onProgress("Liked songs")
        val liked = try {
            tracksOf("VLLM")
        } catch (e: YtAuthException) {
            throw e
        } catch (e: IOException) {
            failure = e
            emptyList()
        }

        val refs = try {
            playlistRefs()
        } catch (e: YtAuthException) {
            if (liked.isEmpty()) throw e
            emptyList()
        } catch (e: IOException) {
            failure = failure ?: e
            emptyList()
        }

        val playlists = ArrayList<ImportPlaylist>()
        for (ref in refs.take(MAX_PLAYLISTS)) {
            onProgress(ref.title)
            val tracks = try {
                tracksOf(ref.browseId)
            } catch (e: YtAuthException) {
                if (liked.isEmpty() && playlists.isEmpty()) throw e
                break
            } catch (_: IOException) {
                continue
            }
            if (tracks.isNotEmpty()) playlists += ImportPlaylist(ref.title, tracks)
        }

        val library = ImportLibrary(playlists, liked)
        if (library.isEmpty && failure != null) throw failure
        return library
    }

    suspend fun readPlaylist(playlistId: String): ImportPlaylist? {
        val (title, tracks) = readPages("VL$playlistId")
        return if (tracks.isEmpty()) null else ImportPlaylist(title ?: DEFAULT_TITLE, tracks)
    }

    private suspend fun tracksOf(browseId: String): List<ImportTrack> = readPages(browseId).second

    private suspend fun readPages(browseId: String): Pair<String?, List<ImportTrack>> {
        val out = ArrayList<ImportTrack>()
        coroutineContext.ensureActive()
        val first = client.browse(browseId)
        val title = YtMusicParser.parsePlaylistTitle(first)
        var page = YtMusicParser.parseTrackPage(first)
        out += page.tracks
        val seen = HashSet<String>()
        var pages = 1
        while (pages < MAX_PAGES) {
            val token = page.continuation ?: break
            if (!seen.add(token)) break
            coroutineContext.ensureActive()
            page = try {
                YtMusicParser.parseTrackPage(client.continuation(token))
            } catch (_: IOException) {
                break
            }
            out += page.tracks
            pages++
        }
        return title to out
    }

    private suspend fun playlistRefs(): List<YtMusicParser.PlaylistRef> {
        val out = LinkedHashMap<String, YtMusicParser.PlaylistRef>()
        coroutineContext.ensureActive()
        var page = YtMusicParser.parsePlaylistPage(client.browse("FEmusic_liked_playlists"))
        page.playlists.forEach { out.putIfAbsent(it.browseId, it) }
        val seen = HashSet<String>()
        var pages = 1
        while (pages < MAX_PAGES) {
            val token = page.continuation ?: break
            if (!seen.add(token)) break
            coroutineContext.ensureActive()
            page = try {
                YtMusicParser.parsePlaylistPage(client.continuation(token))
            } catch (_: IOException) {
                break
            }
            page.playlists.forEach { out.putIfAbsent(it.browseId, it) }
            pages++
        }
        return out.values.toList()
    }

    private companion object {
        const val MAX_PAGES = 300
        const val MAX_PLAYLISTS = 150
        const val DEFAULT_TITLE = "YouTube Music playlist"
    }
}
