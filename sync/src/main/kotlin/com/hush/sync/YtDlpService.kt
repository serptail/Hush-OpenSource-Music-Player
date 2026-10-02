package com.hush.sync

import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.ExperimentalSerializationApi

@Serializable
data class YoutubeSearchResponse(val candidates: List<YoutubeCandidate> = emptyList())

@Serializable
data class YoutubeCandidate(
    val videoId: String,
    val title: String,
    val channelName: String,
    val channelIsTopicChannel: Boolean,
    val durationMs: Long,
    val viewCount: Long,
    val category: String = "songs",
    val isMusicCatalog: Boolean = false,
)

@Serializable
data class AudioStreamResponse(val url: String, val expiresAtEpochSeconds: Long)

@Serializable
private data class YtDlpSearchResult(val entries: List<YtDlpEntry> = emptyList())

@Serializable
private data class YtDlpEntry(
    val id: String = "",
    val title: String = "",
    val uploader: String? = null,
    val channel: String? = null,
    val duration: Double? = null,
    @SerialName("view_count") val viewCount: Long? = null,
)

@OptIn(ExperimentalSerializationApi::class)
private val parser = Json { ignoreUnknownKeys = true; explicitNulls = false }

class YtDlpService(
    private val executable: String = System.getenv("YTDLP_PATH")?.takeIf { it.isNotBlank() } ?: "yt-dlp",
) {
    private val streamCache = ConcurrentHashMap<String, CachedStream>()
    private var lastUpdateAtMs = 0L

    suspend fun warmUp() = withContext(Dispatchers.IO) { ensureUpdated() }

    /**
     * Tries YouTube Music's "Songs" catalog first (matches what YouTube itself badges as
     * a music track); falls back to a broad ytsearch only if that comes back empty, so
     * intentionally obscure edits/bootlegs stay reachable without polluting the common case.
     */
    suspend fun search(query: String): YoutubeSearchResponse = withContext(Dispatchers.IO) {
        val songs = searchMusicCatalog(query)
        if (songs.candidates.isNotEmpty()) return@withContext songs
        searchBroad(query)
    }

    private fun searchMusicCatalog(query: String): YoutubeSearchResponse {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val result = runCatching {
            runCommand(
                "--dump-single-json",
                "--flat-playlist",
                "--skip-download",
                "--no-warnings",
                "--playlist-end", "15",
                "https://music.youtube.com/search?q=$encoded$SONGS_FILTER",
            )
        }.getOrNull() ?: return YoutubeSearchResponse(emptyList())

        val parsed = runCatching { parser.decodeFromString<YtDlpSearchResult>(result) }.getOrNull() ?: return YoutubeSearchResponse(emptyList())
        return YoutubeSearchResponse(
            parsed.entries.mapNotNull { it.toCandidate(isMusicCatalog = true) },
        )
    }

    private fun searchBroad(query: String): YoutubeSearchResponse {
        val result = runCommand(
            "--dump-single-json",
            "--flat-playlist",
            "--skip-download",
            "--no-warnings",
            "--playlist-end", "8",
            "--extractor-args", "youtube:player_client=web_safari",
            "ytsearch8:$query",
        )
        val parsed = parser.decodeFromString<YtDlpSearchResult>(result)
        return YoutubeSearchResponse(
            parsed.entries.mapNotNull { it.toCandidate(isMusicCatalog = false) },
        )
    }

    private fun YtDlpEntry.toCandidate(isMusicCatalog: Boolean): YoutubeCandidate? {
        if (!VIDEO_ID.matcher(id).matches()) return null
        val channelOrUploader = channel ?: uploader
        return YoutubeCandidate(
            videoId = id,
            title = title,
            channelName = channelOrUploader.orEmpty(),
            channelIsTopicChannel = channelOrUploader?.trim()?.endsWith("- Topic", ignoreCase = true) == true,
            durationMs = ((duration ?: 0.0) * 1_000).toLong(),
            viewCount = viewCount ?: 0L,
            isMusicCatalog = isMusicCatalog,
        )
    }

    suspend fun audioStream(videoId: String): AudioStreamResponse = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        streamCache[videoId]?.takeIf { it.expiresAtMs > now }?.let { cached ->
            return@withContext AudioStreamResponse(cached.url, cached.expiresAtMs / 1_000)
        }
        val url = runCommand(
            "--get-url",
            "--format", "bestaudio/best",
            "--no-playlist",
            "--no-warnings",
            "https://www.youtube.com/watch?v=$videoId",
        ).lineSequence().firstOrNull { it.startsWith("https://") }
            ?: error("yt-dlp returned no playable audio URL")
        val expiresAtMs = now + STREAM_CACHE_TTL_MS
        streamCache[videoId] = CachedStream(url, expiresAtMs)
        AudioStreamResponse(url, expiresAtMs / 1_000)
    }

    @Synchronized
    private fun ensureUpdated() {
        val now = System.currentTimeMillis()
        if (now - lastUpdateAtMs < UPDATE_INTERVAL_MS) return
        runCommand("-U")
        lastUpdateAtMs = now
    }

    private fun runCommand(vararg arguments: String): String {
        val process = try {
            ProcessBuilder(listOf(executable) + arguments.toList())
                .redirectErrorStream(true)
                .start()
        } catch (error: IOException) {
            throw IllegalStateException("yt-dlp is not installed or YTDLP_PATH is invalid", error)
        }
        val output = StringBuilder()
        val reader = Thread {
            process.inputStream.bufferedReader().useLines { lines -> lines.forEach { output.appendLine(it) } }
        }
        reader.start()
        val finished = process.waitFor(COMMAND_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            reader.join(1_000)
            error("yt-dlp command timed out after ${COMMAND_TIMEOUT_SECONDS}s")
        }
        reader.join(1_000)
        val exitCode = process.exitValue()
        if (exitCode != 0) error("yt-dlp failed with exit code $exitCode")
        return output.toString().trim()
    }

    private data class CachedStream(val url: String, val expiresAtMs: Long)

    private companion object {
        const val SONGS_FILTER = "&sp=EgWKAQIIAWoKEAoQAxAEEAkQBQ%3D%3D"
        val VIDEO_ID: Pattern = Pattern.compile("^[A-Za-z0-9_-]{11}$")
        const val UPDATE_INTERVAL_MS = 86_400_000L
        const val STREAM_CACHE_TTL_MS = 240_000L
        const val COMMAND_TIMEOUT_SECONDS = 30L
    }
}
