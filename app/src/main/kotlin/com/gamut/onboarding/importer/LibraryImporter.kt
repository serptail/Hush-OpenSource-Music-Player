package com.gamut.onboarding.importer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.gamut.core.model.Track
import com.gamut.feature.library.LibraryRepository
import com.gamut.feature.resolver.ApplicationScope
import com.gamut.feature.search.SearchRepository
import com.gamut.onboarding.applemusic.AppleMusicLinks
import com.gamut.onboarding.spotify.ExportFile
import com.gamut.onboarding.spotify.SpotifyExportParser
import com.gamut.onboarding.spotify.SpotifyLinks
import com.gamut.onboarding.spotify.SpotifyPaging
import com.gamut.onboarding.ytmusic.YtAuthException
import com.gamut.onboarding.ytmusic.YtMusicClient
import com.gamut.onboarding.ytmusic.YtMusicLibraryReader
import com.gamut.onboarding.ytmusic.YtMusicLinks
import com.gamut.onboarding.ytmusic.YtSession
import com.gamut.onboarding.ytmusic.clearYouTubeLogin
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/** Where an import is coming from. The Library's import card shows the matching logo. */
enum class ImportSource { Spotify, YouTubeMusic, AppleMusic }

sealed interface ImportState {
    data object Idle : ImportState

    /** [what] says what is being read, like "your YouTube Music library" or "Road Trip". */
    data class Reading(val what: String, val source: ImportSource? = null) : ImportState

    /** [playlist] names the playlist being filled, or null while liked songs are matched. */
    data class Matching(
        val done: Int,
        val total: Int,
        val current: String?,
        val playlist: String? = null,
        val source: ImportSource? = null,
    ) : ImportState

    /** [unreadable] counts pasted links that couldn't be read at all (private, removed, or not a playlist). */
    data class Done(
        val playlists: Int,
        val songs: Int,
        val missed: Int,
        val cancelled: Boolean,
        val unreadable: Int = 0,
        val source: ImportSource? = null,
    ) : ImportState

    data class Failed(val message: String, val source: ImportSource? = null) : ImportState
}

/**
 * Brings playlists and liked songs from another service into Gamut: from Spotify's export files, pasted Spotify,
 * YouTube Music and Apple Music links, or straight from a signed-in YouTube Music account.
 *
 * Every song is searched in Gamut's own catalog and judged by [TrackMatcher], ported from BitChord. A search with
 * results but no confident match falls back to its first result (see [FALLBACK_TO_FIRST_RESULT]); only a search that
 * returns nothing counts as missed. Playlists are created as soon as their first songs match and then filled a
 * batch at a time, so they appear in the Library while the import is still running. It runs in the application
 * scope, so leaving the screen doesn't stop it.
 */
@Singleton
class LibraryImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val search: SearchRepository,
    private val catalog: ImportCatalog,
    private val library: LibraryRepository,
    @ApplicationScope private val appScope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow<ImportState>(ImportState.Idle)
    val state: StateFlow<ImportState> = mutableState

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    @Volatile private var job: Job? = null

    /** Spotify's data download (.zip, or its .json files) and Exportify's .csv files. */
    fun startFromFiles(uris: List<Uri>) {
        if (uris.isEmpty() || job?.isActive == true) return
        mutableState.value = ImportState.Reading("your files", ImportSource.Spotify)
        job = appScope.launch(Dispatchers.IO) {
            val found = try {
                val files = uris.flatMap { readFiles(it) }
                withContext(Dispatchers.Default) { SpotifyExportParser.parse(files) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                mutableState.value = ImportState.Failed("Couldn\u2019t read those files.", ImportSource.Spotify)
                return@launch
            }
            run(found, ImportSource.Spotify, emptyMessage = "No songs found in those files. Pick the Spotify .zip, the Playlist .json files, or Exportify .csv files.")
        }.also(::resetReadingOnCancel)
    }

    /** Playlist and album links pasted from Spotify (open.spotify.com, spotify: URIs, spotify.link). Public ones only. */
    fun startFromSpotifyLinks(text: String) {
        if (job?.isActive == true) return
        val direct = SpotifyLinks.parse(text)
        val short = SpotifyLinks.shortLinks(text)
        if (direct.isEmpty() && short.isEmpty()) {
            mutableState.value = ImportState.Failed("No Spotify links found. Paste links that look like open.spotify.com/playlist/\u2026", ImportSource.Spotify)
            return
        }
        mutableState.value = ImportState.Reading("your Spotify links", ImportSource.Spotify)
        job = appScope.launch(Dispatchers.IO) {
            val refs = LinkedHashMap<String, SpotifyLinks.Ref>()
            direct.forEach { refs.putIfAbsent(it.kind.path + it.id, it) }
            var unreadable = 0
            for (link in short) {
                ensureActive()
                val ref = resolveShortLink(link)
                if (ref == null) unreadable++ else refs.putIfAbsent(ref.kind.path + ref.id, ref)
            }
            val playlists = ArrayList<ImportPlaylist>()
            refs.values.forEachIndexed { index, ref ->
                ensureActive()
                mutableState.value = ImportState.Reading(if (refs.size == 1) "your playlist" else "playlist ${index + 1} of ${refs.size}", ImportSource.Spotify)
                val playlist = fetchSpotifyPage(ref)
                if (playlist == null) unreadable++ else playlists += playlist
            }
            if (playlists.isEmpty()) {
                mutableState.value = ImportState.Failed(
                    "Couldn\u2019t read ${if (unreadable == 1) "that link" else "those links"}. " +
                        "Only public playlists and albums can be imported from a link. For private ones, use your Spotify data download.",
                    ImportSource.Spotify,
                )
                return@launch
            }
            run(ImportLibrary(playlists, emptyList()), ImportSource.Spotify, emptyMessage = "Those playlists are empty.", unreadable = unreadable)
        }.also(::resetReadingOnCancel)
    }

    /**
     * Playlist links pasted from YouTube Music (music.youtube.com/playlist?list=\u2026, youtube.com/playlist and watch
     * links with a list). No sign-in: they are read as a signed-out visitor would, so only public and unlisted
     * playlists work. Private ones need [startFromYouTubeMusic].
     */
    fun startFromYouTubeLinks(text: String) {
        if (job?.isActive == true) return
        val ids = YtMusicLinks.parse(text)
        if (ids.isEmpty()) {
            mutableState.value = ImportState.Failed("No YouTube Music playlist links found. Paste links that look like music.youtube.com/playlist?list=\u2026", ImportSource.YouTubeMusic)
            return
        }
        mutableState.value = ImportState.Reading("your YouTube Music links", ImportSource.YouTubeMusic)
        job = appScope.launch(Dispatchers.IO) {
            val reader = YtMusicLibraryReader(YtMusicClient(http, YtSession.anonymous()))
            val playlists = ArrayList<ImportPlaylist>()
            var unreadable = 0
            ids.forEachIndexed { index, id ->
                ensureActive()
                mutableState.value = ImportState.Reading(if (ids.size == 1) "your playlist" else "playlist ${index + 1} of ${ids.size}", ImportSource.YouTubeMusic)
                val playlist = try {
                    reader.readPlaylist(id)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "couldn't read YouTube Music playlist: ${e::class.java.name}: ${e.message}")
                    null
                }
                if (playlist == null) unreadable++ else playlists += playlist
            }
            if (playlists.isEmpty()) {
                mutableState.value = ImportState.Failed(
                    "Couldn\u2019t read ${if (unreadable == 1) "that link" else "those links"}. " +
                        "Only public or unlisted playlists can be imported from a link, and you need to be online.",
                    ImportSource.YouTubeMusic,
                )
                return@launch
            }
            run(ImportLibrary(playlists, emptyList()), ImportSource.YouTubeMusic, emptyMessage = "Those playlists are empty.", unreadable = unreadable)
        }.also(::resetReadingOnCancel)
    }

    /**
     * Playlist and album links pasted from Apple Music (music.apple.com/\u2026/playlist/\u2026). No sign-in: they are read
     * as a signed-out visitor would see them, so only public playlists and albums work.
     */
    fun startFromAppleMusicLinks(text: String) {
        if (job?.isActive == true) return
        val refs = AppleMusicLinks.parse(text)
        if (refs.isEmpty()) {
            mutableState.value = ImportState.Failed("No Apple Music links found. Paste links that look like music.apple.com/us/playlist/\u2026", ImportSource.AppleMusic)
            return
        }
        mutableState.value = ImportState.Reading("your Apple Music links", ImportSource.AppleMusic)
        job = appScope.launch(Dispatchers.IO) {
            val playlists = ArrayList<ImportPlaylist>()
            var unreadable = 0
            refs.forEachIndexed { index, ref ->
                ensureActive()
                mutableState.value = ImportState.Reading(if (refs.size == 1) "your playlist" else "playlist ${index + 1} of ${refs.size}", ImportSource.AppleMusic)
                val playlist = fetchAppleMusicPage(ref)
                if (playlist == null) unreadable++ else playlists += playlist
            }
            if (playlists.isEmpty()) {
                mutableState.value = ImportState.Failed(
                    "Couldn\u2019t read ${if (unreadable == 1) "that link" else "those links"}. " +
                        "Only public playlists and albums can be imported from a link, and you need to be online.",
                    ImportSource.AppleMusic,
                )
                return@launch
            }
            run(ImportLibrary(playlists, emptyList()), ImportSource.AppleMusic, emptyMessage = "Those playlists are empty.", unreadable = unreadable)
        }.also(::resetReadingOnCancel)
    }

    /** The signed-in user's Liked Music and playlists. The session is used for this import only and never stored. */
    fun startFromYouTubeMusic(session: YtSession) {
        if (job?.isActive == true) return
        mutableState.value = ImportState.Reading("your YouTube Music library", ImportSource.YouTubeMusic)
        job = appScope.launch(Dispatchers.IO) {
            val found = try {
                YtMusicLibraryReader(YtMusicClient(http, session)).read { what ->
                    mutableState.value = ImportState.Reading(what, ImportSource.YouTubeMusic)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: YtAuthException) {
                mutableState.value = ImportState.Failed("YouTube Music didn\u2019t accept that sign-in. Try again and wait for YouTube Music to load before tapping Import.", ImportSource.YouTubeMusic)
                return@launch
            } catch (e: IOException) {
                mutableState.value = ImportState.Failed("Couldn\u2019t reach YouTube Music. Check your connection and try again.", ImportSource.YouTubeMusic)
                return@launch
            } catch (e: Exception) {
                mutableState.value = ImportState.Failed("Couldn\u2019t read your YouTube Music library.", ImportSource.YouTubeMusic)
                return@launch
            } finally {
                // The login was only ever for this import.
                clearYouTubeLogin()
            }
            run(found, ImportSource.YouTubeMusic, emptyMessage = "Your YouTube Music library looks empty. Liked songs and playlists are what get imported.")
        }.also(::resetReadingOnCancel)
    }

    fun cancel() {
        job?.cancel()
    }

    /**
     * A cancel while still reading never reaches [run], which is what turns a cancel into a result, so nothing else
     * would clear the card: it would sit on "Reading\u2026" with a Cancel button that does nothing. Reading a YouTube Music
     * library takes long enough for people to hit it. Done once the job has actually finished, so a last progress update
     * from a request still in flight can't bring the card back.
     */
    private fun resetReadingOnCancel(launched: Job) {
        launched.invokeOnCompletion { cause ->
            if (cause is CancellationException) mutableState.update { if (it is ImportState.Reading) ImportState.Idle else it }
        }
    }

    /** Call once a Done or Failed result has been shown. */
    fun acknowledge() {
        mutableState.update { if (it is ImportState.Done || it is ImportState.Failed) ImportState.Idle else it }
    }

    private suspend fun run(found: ImportLibrary, source: ImportSource, emptyMessage: String, unreadable: Int = 0) {
        if (found.isEmpty) {
            mutableState.value = ImportState.Failed(emptyMessage, source)
            return
        }
        val progress = AtomicInteger()
        val results = ConcurrentHashMap<String, List<Track>>()
        val stats = MatchStats()
        var playlists = 0
        var songs = 0
        var missed = 0
        mutableState.value = ImportState.Matching(0, found.trackCount, null, source = source)
        try {
            for (playlist in found.playlists) {
                val seen = HashSet<String>()
                var playlistId: Long? = null
                for (batch in playlist.tracks.chunked(BATCH)) {
                    val matched = matchAll(batch, found.trackCount, progress, results, stats, playlist.name)
                    missed += matched.count { it == null }
                    val fresh = matched.filterNotNull().filter { seen.add(keyOf(it)) }
                    if (fresh.isEmpty()) continue
                    // Created with its first songs rather than up front, so a playlist nothing matched for is never left empty.
                    val id = playlistId ?: library.createPlaylist(playlist.name).also {
                        playlistId = it
                        playlists++
                    }
                    // appendTrack saves the track itself as well as its place in the playlist. replaceTracks only links
                    // by id, so tracks that aren't in the track table yet never showed up in the playlist.
                    fresh.forEach { library.appendTrack(id, it) }
                    songs += fresh.size
                }
            }
            if (found.liked.isNotEmpty()) {
                val now = System.currentTimeMillis()
                var position = 0
                for (batch in found.liked.chunked(BATCH)) {
                    val matched = matchAll(batch, found.trackCount, progress, results, stats, null)
                    matched.forEach { track ->
                        if (track == null) {
                            missed++
                        } else {
                            library.addFavorite(track, now - position * 1_000L)
                            songs++
                        }
                        position++
                    }
                }
            }
            Log.d(TAG, "import finished: ${found.trackCount} read, ${stats.matched.get()} matched, ${stats.fallback.get()} by first result, ${stats.none.get()} with no search results, $songs added to $playlists playlists")
            mutableState.value = ImportState.Done(playlists, songs, missed, cancelled = false, unreadable = unreadable, source = source)
        } catch (e: CancellationException) {
            mutableState.value = ImportState.Done(playlists, songs, missed, cancelled = true, unreadable = unreadable, source = source)
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "import stopped: ${e::class.java.name}: ${e.message}", e)
            mutableState.value = if (songs > 0) {
                ImportState.Done(playlists, songs, missed, cancelled = true, unreadable = unreadable, source = source)
            } else {
                ImportState.Failed("Couldn\u2019t add the songs to your library.", source)
            }
        }
    }

    private class MatchStats {
        val matched = AtomicInteger()
        val fallback = AtomicInteger()
        val none = AtomicInteger()
    }

    private fun keyOf(track: Track): String = "${track.isrc}".ifBlank { "${track.title}|${track.artist}".lowercase() }

    private suspend fun matchAll(
        tracks: List<ImportTrack>,
        total: Int,
        progress: AtomicInteger,
        results: ConcurrentHashMap<String, List<Track>>,
        stats: MatchStats,
        playlist: String?,
    ): List<Track?> = coroutineScope {
        val gate = Semaphore(PARALLEL)
        tracks.map { wanted ->
            async {
                gate.withPermit {
                    val track = find(wanted, results, stats)
                    val done = progress.incrementAndGet()
                    mutableState.update { if (it is ImportState.Matching) it.copy(done = done, total = total, current = wanted.title, playlist = playlist) else it }
                    track
                }
            }
        }.awaitAll()
    }

    /**
     * BitChord's way of finding a song: one fast search for "title artist", let [TrackMatcher] pick the best candidate, and when it
     * rejects them all take the search's first result rather than give up.
     */
    private suspend fun find(wanted: ImportTrack, results: ConcurrentHashMap<String, List<Track>>, stats: MatchStats): Track? {
        val query = listOf(wanted.title, wanted.artist).filter { it.isNotBlank() }.joinToString(" ").take(120)
        val key = query.lowercase()
        val candidates = (
            results[key] ?: run {
                // One Deezer request first; the app's full search (slow, rate-limited by iTunes) only for songs that found nothing.
                val quick = catalog.search(query)
                val found = quick.ifEmpty {
                    withTimeoutOrNull(SEARCH_TIMEOUT_MS) { search.search(query).lastOrNull()?.tracks }.orEmpty()
                }
                if (found.isNotEmpty()) results[key] = found
                found
            }
            ).take(CANDIDATES)
        if (candidates.isEmpty()) {
            stats.none.incrementAndGet()
            return null
        }
        // The album is what tells apart the same recording on the album, the single and a compilation, which Deezer
        // returns as separate rows with different cover art. Without it the first of them wins.
        val rows = candidates.map { MatchSong(it.title, it.artist, durationText(it.durationMs), albumName = it.album) }
        val target = TrackMatcher.Target(
            title = wanted.title,
            artist = wanted.artist,
            durationSec = wanted.durationMs?.takeIf { it > 0 }?.div(1000)?.toInt(),
            album = wanted.album,
        )
        val best = TrackMatcher.best(rows, target)
        if (best != null) {
            stats.matched.incrementAndGet()
            return candidates[rows.indexOf(best)]
        }
        if (!FALLBACK_TO_FIRST_RESULT) {
            stats.none.incrementAndGet()
            return null
        }
        stats.fallback.incrementAndGet()
        return candidates.first()
    }

    /** "3:45" for a runtime in milliseconds; null when the catalog row has none. */
    private fun durationText(ms: Long): String? {
        if (ms <= 0) return null
        val seconds = ms / 1000
        return "%d:%02d".format(seconds / 60, seconds % 60)
    }

    /**
     * The songs on one Spotify playlist or album page, or null when it can't be read. Blocking; called off the main
     * thread. A playlist the embed page cut off at 100 songs is read on through [SpotifyPaging]; if that is refused,
     * the 100 already read stand.
     */
    private fun fetchSpotifyPage(ref: SpotifyLinks.Ref): ImportPlaylist? {
        repeat(2) {
            try {
                val html = http.newCall(browserRequest(ref.embedUrl)).execute().use { response ->
                    if (response.isSuccessful) response.body?.string() else null
                }
                if (html != null) {
                    val page = SpotifyLinks.parseEmbedPage(html) ?: return null
                    if (ref.kind != SpotifyLinks.Kind.Playlist || page.tracks.size < SpotifyPaging.EMBED_LIMIT) return page
                    val token = SpotifyLinks.embedToken(html) ?: return page
                    val rest = try {
                        SpotifyPaging.fetchRemaining(http, ref.id, token, page.tracks.size, BROWSER_USER_AGENT)
                    } catch (e: Exception) {
                        Log.w(TAG, "couldn't read past the first ${page.tracks.size} songs: ${e.message}")
                        emptyList()
                    }
                    return page.copy(tracks = page.tracks + rest)
                }
            } catch (_: IOException) {
            }
        }
        return null
    }

    /**
     * The songs on one public Apple Music playlist or album page, or null when it can't be read. Blocking.
     *
     * The page's own track data is read first. The share-preview meta tags list every song's catalog id regardless
     * of layout, so when they name more songs than that read found (or it found none), the ids are resolved through
     * the public iTunes lookup instead, and whichever way got more songs wins.
     */
    private fun fetchAppleMusicPage(ref: AppleMusicLinks.Ref): ImportPlaylist? {
        repeat(2) {
            try {
                val html = http.newCall(browserRequest(ref.pageUrl)).execute().use { response ->
                    if (response.isSuccessful) response.body?.string() else null
                }
                if (html != null) {
                    val page = AppleMusicLinks.parsePage(html, ref.kind)
                    val ids = AppleMusicLinks.songIds(html)
                    if (ids.isNotEmpty() && (page == null || ids.size > page.tracks.size)) {
                        val looked = lookupAppleSongs(ids, ref.storefront)
                        if (looked.size > (page?.tracks?.size ?: 0)) {
                            return ImportPlaylist(page?.name ?: AppleMusicLinks.pageTitle(html, ref.kind), looked)
                        }
                    }
                    return page
                }
            } catch (_: IOException) {
            }
        }
        return null
    }

    /** Titles and artists for Apple Music song ids, in the order given. Songs that can't be resolved are left out. */
    private fun lookupAppleSongs(ids: List<String>, storefront: String): List<ImportTrack> {
        val byId = HashMap<String, ImportTrack>()
        // The page's own storefront first; a song that storefront doesn't carry may still resolve in the US one.
        for (country in listOf(storefront, "us").distinct()) {
            val missing = ids.distinct().filter { it !in byId }
            if (missing.isEmpty()) break
            for (chunk in missing.chunked(APPLE_LOOKUP_CHUNK)) {
                try {
                    val body = http.newCall(browserRequest(AppleMusicLinks.lookupUrl(chunk, country))).execute().use { response ->
                        if (response.isSuccessful) response.body?.string() else null
                    } ?: continue
                    AppleMusicLinks.parseLookup(body).forEach { (id, track) -> byId[id] = track }
                } catch (e: IOException) {
                    Log.w(TAG, "Apple Music song lookup failed: ${e.message}")
                }
            }
        }
        return ids.mapNotNull { byId[it] }
    }

    /** Follows a spotify.link share link to the playlist or album it points at. */
    private fun resolveShortLink(link: String): SpotifyLinks.Ref? = try {
        http.newCall(browserRequest(link)).execute().use { response ->
            SpotifyLinks.parse(response.request.url.toString()).firstOrNull()
                ?: SpotifyLinks.parse(response.body?.string().orEmpty()).firstOrNull()
        }
    } catch (_: IOException) {
        null
    }

    private fun browserRequest(url: String) = Request.Builder()
        .url(url)
        .header("User-Agent", BROWSER_USER_AGENT)
        .header("Accept-Language", "en-US,en;q=0.9")
        .build()

    /** The files behind one picked URI. A zip is streamed and yields its json/csv entries; anything else is one file. */
    private fun readFiles(uri: Uri): List<ExportFile> {
        val name = displayName(uri)
        val input = context.contentResolver.openInputStream(uri) ?: return emptyList()
        return input.use { stream ->
            if (name.lowercase().endsWith(".zip")) {
                SpotifyExportParser.unzip(stream)
            } else {
                listOf(ExportFile(name, readLimited(stream)))
            }
        }
    }

    /** Reads at most [MAX_FILE_BYTES]; a longer file is cut off rather than risking running out of memory. */
    private fun readLimited(stream: java.io.InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(16 * 1024)
        var total = 0
        while (total < MAX_FILE_BYTES) {
            val n = stream.read(chunk)
            if (n < 0) break
            out.write(chunk, 0, n)
            total += n
        }
        return out.toByteArray()
    }

    private fun displayName(uri: Uri): String {
        val fromProvider = try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (_: Exception) {
            null
        }
        return fromProvider ?: uri.lastPathSegment.orEmpty()
    }

    private companion object {
        const val TAG = "GamutImport"

        /** Searches in flight at once, as in BitChord. */
        const val PARALLEL = 4

        /** Songs matched between writes to the library, so a playlist fills in visibly as the import goes. */
        const val BATCH = 24

        /** BitChord's behaviour: a search with results is never written off, its first result is the last resort. Set false to leave unsure songs out instead. */
        const val FALLBACK_TO_FIRST_RESULT = true
        const val CANDIDATES = 15

        /** Song ids per iTunes lookup request (the API takes up to 200). */
        const val APPLE_LOOKUP_CHUNK = 150
        const val SEARCH_TIMEOUT_MS = 12_000L
        const val MAX_FILE_BYTES = 64 * 1024 * 1024
        const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"
    }
}