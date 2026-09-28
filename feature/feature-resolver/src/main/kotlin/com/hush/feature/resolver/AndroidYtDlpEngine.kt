package com.hush.feature.resolver

import android.content.Context
import com.hush.core.model.Track
import com.hush.core.network.YoutubeCandidate
import com.hush.core.network.YoutubeMusicSearchSource
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// --- music.youtube.com flat-playlist JSON shape -----------------------------------------

@Serializable
private data class YtMusicSearchResultJson(val entries: List<YtMusicEntryJson> = emptyList())

@Serializable
private data class YtMusicEntryJson(
    val id: String = "",
    val title: String = "",
    val uploader: String? = null,
    val channel: String? = null,
    val duration: Double? = null,
    @SerialName("view_count") val viewCount: Long? = null,
)

// --- plain ytsearch flat-playlist JSON shape (broad fallback) ---------------------------

@Serializable
private data class SearchResultJson(val entries: List<SearchEntryJson> = emptyList())

@Serializable
private data class SearchEntryJson(
    val id: String = "",
    val title: String = "",
    val uploader: String? = null,
    val channel: String? = null,
    val duration: Double? = null,
    @SerialName("view_count") val viewCount: Long? = null,
)

@Singleton
class AndroidYtDlpEngine @Inject constructor(
    @ApplicationContext private val context: Context,
) : YoutubeMusicSearchSource {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    @Volatile private var initialized = false
    @Volatile private var lastUpdateAtMs = 0L
    // A forced yt-dlp update is expensive (network round trip) -- only ever do it once per
    // process lifetime as a bot-check recovery attempt, not on every single failure.
    @Volatile private var hasForcedUpdateThisSession = false

    /**
     * Scoped to YouTube Music's own "Songs" catalog. --flat-playlist search rarely triggers
     * YouTube's bot check the way a full video-page fetch does, but we still pin a client
     * type for consistency and because YouTube tightens this without warning.
     */
    override suspend fun searchSongs(query: String): List<YoutubeCandidate> = withContext(Dispatchers.IO) {
        ensureReady()
        val encoded = URLEncoder.encode(query, "UTF-8")
        val request = YoutubeDLRequest("https://music.youtube.com/search?q=$encoded")
            .addOption("--flat-playlist")
            .addOption("--dump-single-json")
            .addOption("--skip-download")
            .addOption("--no-warnings")
            .addOption("--playlist-end", "10")
            .addOption("--extractor-args", CLIENT_ARGS)
        val output = runCatching { YoutubeDL.getInstance().execute(request).out }.getOrNull() ?: return@withContext emptyList()
        runCatching { json.decodeFromString<YtMusicSearchResultJson>(output) }
            .getOrNull()
            ?.entries
            ?.mapNotNull { it.toCandidate(isMusicCatalog = true) }
            ?: emptyList()
    }

    override suspend fun searchBroad(query: String): List<YoutubeCandidate> = withContext(Dispatchers.IO) {
        ensureReady()
        val request = YoutubeDLRequest("ytsearch8:$query")
            .addOption("--flat-playlist")
            .addOption("--dump-single-json")
            .addOption("--skip-download")
            .addOption("--no-warnings")
            .addOption("--extractor-args", CLIENT_ARGS)
        val output = runCatching { YoutubeDL.getInstance().execute(request).out }.getOrNull() ?: return@withContext emptyList()
        runCatching { json.decodeFromString<SearchResultJson>(output) }
            .getOrNull()
            ?.entries
            ?.mapNotNull { entry ->
                entry.id.takeIf { it.isNotBlank() }?.let {
                    YoutubeCandidate(
                        videoId = it,
                        title = entry.title,
                        channelName = entry.channel ?: entry.uploader.orEmpty(),
                        channelIsTopicChannel = isTopicChannel(entry.channel ?: entry.uploader),
                        durationMs = entry.durationMsOrZero(),
                        viewCount = entry.viewCount ?: 0L,
                        category = "songs",
                        isMusicCatalog = false,
                    )
                }
            }
            ?: emptyList()
    }

    suspend fun search(track: Track): List<YoutubeCandidate> {
        val query = "${track.artist} ${track.title}"
        val songs = searchSongs(query)
        if (songs.isNotEmpty()) return songs
        return searchBroad(query)
    }

    /**
     * The step that was actually failing in your log: getInfo() does a FULL video-page
     * fetch (not --flat-playlist), which is exactly what triggers YouTube's "Sign in to
     * confirm you're not a bot" / 429 response on the default "web" client. ios/android
     * historically don't require a PO token for this, so we pin those first.
     *
     * A 12s overall timeout also means a stuck/blocked resolution fails fast with a clear
     * error instead of leaving the UI hanging indefinitely.
     */
    suspend fun audioUrl(videoId: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            ensureReady()
            withTimeoutOrNull(AUDIO_RESOLVE_TIMEOUT_MS) { resolveAudioUrl(videoId) }
                ?: error("Timed out resolving a playable source for this track")
        }.recoverCatching { error ->
            // YouTube changes its bot-check/signature scheme often enough that a stale
            // yt-dlp build is frequently the actual cause -- force one update (once per
            // session) and retry before surfacing failure to the user.
            if (error.looksLikeBotCheck() && !hasForcedUpdateThisSession) {
                hasForcedUpdateThisSession = true
                forceUpdate()
                withTimeoutOrNull(AUDIO_RESOLVE_TIMEOUT_MS) { resolveAudioUrl(videoId) }
                    ?: throw error
            } else {
                throw error
            }
        }
    }

    private fun resolveAudioUrl(videoId: String): String {
        val request = YoutubeDLRequest("https://www.youtube.com/watch?v=$videoId")
            // Prefer a genuine audio-only stream (m4a first, since ExoPlayer decodes it
            // natively without extra container demuxing); only fall through to a muxed
            // video+audio "best" as a last resort if YouTube truly offers no audio-only
            // format for this video, rather than "best" winning by default and silently
            // pulling down (and decoding) a video track we never render.
            .addOption("-f", "bestaudio[ext=m4a]/bestaudio/best")
            .addOption("--no-playlist")
            .addOption("--no-warnings")
            .addOption("--extractor-args", CLIENT_ARGS)
            .addOption("--retries", "2")
        return YoutubeDL.getInstance().getInfo(request).url?.takeIf { it.isNotBlank() }
            ?: error("yt-dlp returned no playable audio URL")
    }

    private fun Throwable.looksLikeBotCheck(): Boolean {
        val text = message.orEmpty()
        return text.contains("Sign in to confirm", ignoreCase = true) ||
            text.contains("429", ignoreCase = true) ||
            text.contains("Too Many Requests", ignoreCase = true)
    }

    private fun YtMusicEntryJson.toCandidate(isMusicCatalog: Boolean): YoutubeCandidate? {
        val videoId = id.takeIf { it.isNotBlank() } ?: return null
        val channelOrUploader = channel ?: uploader
        return YoutubeCandidate(
            videoId = videoId,
            title = title,
            channelName = channelOrUploader.orEmpty(),
            channelIsTopicChannel = isTopicChannel(channelOrUploader),
            durationMs = durationMsOrZero(),
            viewCount = viewCount ?: 0L,
            category = "songs",
            isMusicCatalog = isMusicCatalog,
        )
    }

    private fun YtMusicEntryJson.durationMsOrZero(): Long = ((duration ?: 0.0) * 1_000).toLong()
    private fun SearchEntryJson.durationMsOrZero(): Long = ((duration ?: 0.0) * 1_000).toLong()

    private fun isTopicChannel(channelName: String?): Boolean =
        channelName?.trim()?.endsWith("- Topic", ignoreCase = true) == true

    private fun ensureReady() {
        if (!initialized) {
            synchronized(this) {
                if (!initialized) {
                    YoutubeDL.getInstance().init(context)
                    initialized = true
                }
            }
        }
        val now = System.currentTimeMillis()
        if (now - lastUpdateAtMs >= UPDATE_INTERVAL_MS) {
            synchronized(this) {
                if (now - lastUpdateAtMs >= UPDATE_INTERVAL_MS) {
                    runCatching { YoutubeDL.getInstance().updateYoutubeDL(context, YoutubeDL.UpdateChannel.STABLE) }
                    lastUpdateAtMs = now
                }
            }
        }
    }

    private fun forceUpdate() {
        synchronized(this) {
            runCatching { YoutubeDL.getInstance().updateYoutubeDL(context, YoutubeDL.UpdateChannel.STABLE) }
            lastUpdateAtMs = System.currentTimeMillis()
        }
    }

    private companion object {
        const val UPDATE_INTERVAL_MS = 86_400_000L
        const val AUDIO_RESOLVE_TIMEOUT_MS = 12_000L
        // ios/android clients generally don't demand a PO token the way "web" now does;
        // web_safari is kept as a third option since it occasionally succeeds when the
        // other two are rate-limited independently by YouTube.
        const val CLIENT_ARGS = "youtube:player_client=ios,android,web_safari"
    }
}