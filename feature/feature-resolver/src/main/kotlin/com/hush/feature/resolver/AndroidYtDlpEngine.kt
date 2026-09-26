package com.hush.feature.resolver

import android.content.Context
import com.hush.core.model.Track
import com.hush.core.network.YoutubeCandidate
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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
class AndroidYtDlpEngine @Inject constructor(@ApplicationContext private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    @Volatile private var initialized = false
    @Volatile private var lastUpdateAtMs = 0L

    suspend fun search(track: Track): List<YoutubeCandidate> = withContext(Dispatchers.IO) {
        ensureReady()
        val request = YoutubeDLRequest("ytsearch5:${track.artist} ${track.title}")
            .addOption("--flat-playlist")
            .addOption("--dump-single-json")
            .addOption("--skip-download")
            .addOption("--no-warnings")
        val output = YoutubeDL.getInstance().execute(request).out
        json.decodeFromString<SearchResultJson>(output).entries.mapNotNull { entry ->
            entry.id.takeIf { it.isNotBlank() }?.let {
                YoutubeCandidate(it, entry.title, entry.channel ?: entry.uploader.orEmpty(), false, ((entry.duration ?: 0.0) * 1_000).toLong(), entry.viewCount ?: 0L, "songs")
            }
        }
    }

    suspend fun audioUrl(videoId: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            ensureReady()
            val request = YoutubeDLRequest("https://www.youtube.com/watch?v=$videoId")
                .addOption("-f", "bestaudio/best")
                .addOption("--no-playlist")
            YoutubeDL.getInstance().getInfo(request).url?.takeIf { it.isNotBlank() }
                ?: error("yt-dlp returned no playable audio URL")
        }
    }

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

    private companion object { const val UPDATE_INTERVAL_MS = 86_400_000L }
}
