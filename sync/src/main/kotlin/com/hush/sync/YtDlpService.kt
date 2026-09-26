package com.hush.sync

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
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

    suspend fun search(query: String): YoutubeSearchResponse = withContext(Dispatchers.IO) {
        ensureUpdated()
        val result = runCommand(
            "--dump-single-json",
            "--flat-playlist",
            "--skip-download",
            "--no-warnings",
            "--playlist-end", "5",
            "--extractor-args", "youtube:player_client=web_safari",
            "ytsearch5:$query",
        )
        val parsed = parser.decodeFromString<YtDlpSearchResult>(result)
        YoutubeSearchResponse(parsed.entries.filter { it.id.isNotBlank() }.map { entry ->
            YoutubeCandidate(
                videoId = entry.id,
                title = entry.title,
                channelName = entry.channel ?: entry.uploader.orEmpty(),
                channelIsTopicChannel = false,
                durationMs = ((entry.duration ?: 0.0) * 1_000).toLong(),
                viewCount = entry.viewCount ?: 0L,
            )
        })
    }

    suspend fun audioStream(videoId: String): AudioStreamResponse = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        streamCache[videoId]?.takeIf { it.expiresAtMs > now }?.let { cached ->
            return@withContext AudioStreamResponse(cached.url, cached.expiresAtMs / 1_000)
        }
        ensureUpdated()
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
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        if (exitCode != 0) error("yt-dlp failed with exit code $exitCode")
        return output.trim()
    }

    private data class CachedStream(val url: String, val expiresAtMs: Long)

    private companion object {
        const val UPDATE_INTERVAL_MS = 86_400_000L
        const val STREAM_CACHE_TTL_MS = 240_000L
    }
}
