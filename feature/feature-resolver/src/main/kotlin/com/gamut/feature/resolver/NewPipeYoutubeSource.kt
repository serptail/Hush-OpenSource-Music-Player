package com.gamut.feature.resolver

import com.gamut.core.network.YoutubeCandidate
import com.gamut.core.network.YoutubeMusicSearchSource
import com.gamut.core.network.isValidYoutubeVideoId
import kotlinx.coroutines.CancellationException
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request as OkHttpRequest
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamInfo
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class NewPipeYoutubeSource @Inject constructor(
    @Named("youtube") private val client: OkHttpClient,
) : YoutubeMusicSearchSource {
    /**
     * Extracts a direct audio URL. No caching here: [AudioSourceRepositoryImpl] owns the cache, the in-flight sharing and
     * forceRefresh (a cache down here would hand a dead URL back after a refresh).
     * Cancellation is rethrown, not turned into a failed Result (runCatching swallowed it).
     */
    suspend fun audioUrl(videoId: String): Result<String> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            ensureInitialized()
            Result.success(
                StreamInfo.getInfo("https://www.youtube.com/watch?v=$videoId")
                    .audioStreams
                    .asSequence()
                    .filter { it.isUrl }
                    // AAC/m4a first (same preference as the yt-dlp path: cheaper to decode, plays natively in ExoPlayer),
                    // then the highest bitrate within that format.
                    .sortedWith(compareByDescending<AudioStream> { it.format == MediaFormat.M4A }.thenByDescending { it.averageBitrate })
                    .map { it.content }
                    .firstOrNull { it.isNotBlank() }
                    ?: error("NewPipe returned no direct audio stream"),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Highest-bitrate direct audio stream of ANY codec (Opus/WebM ~160 kbps beats AAC ~128 kbps). Used by downloads,
     * which save the stream untouched; playback keeps preferring m4a in [audioUrl].
     */
    suspend fun bestAudioUrl(videoId: String): Result<String> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            ensureInitialized()
            Result.success(
                StreamInfo.getInfo("https://www.youtube.com/watch?v=$videoId")
                    .audioStreams
                    .asSequence()
                    .filter { it.isUrl }
                    .sortedByDescending { it.averageBitrate }
                    .map { it.content }
                    .firstOrNull { it.isNotBlank() }
                    ?: error("NewPipe returned no direct audio stream"),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun searchSongs(query: String): List<YoutubeCandidate> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                ensureInitialized()
                val service = NewPipe.getService("YouTube")
                val handler = service.getSearchQHFactory().fromQuery(
                    query,
                    listOf(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS),
                    "",
                )
                SearchInfo.getInfo(service, handler).relatedItems
                    .filterIsInstance<StreamInfoItem>()
                    .mapNotNull { it.toCandidate() }
                    .take(25)
            } catch (e: CancellationException) {
                // runCatching used to swallow this: a cancelled search came back as "no results", and
                // HybridYoutubeMusicSearchSource then started a slow yt-dlp search nobody was waiting for.
                throw e
            } catch (e: Exception) {
                emptyList()
            }
        }

    override suspend fun searchBroad(query: String): List<YoutubeCandidate> = emptyList()

    /**
     * Pays the one-off cold-start cost (downloader init, visitor_id, ...) before the first tap, so the first play
     * does not sit near [AudioSourceRepositoryImpl]'s 8 s NewPipe ceiling and fall back to the slow yt-dlp path.
     * Failures are ignored: this is only a warm-up.
     */
    suspend fun warmUp() {
        runCatching { searchSongs("music") }
    }

    private fun StreamInfoItem.toCandidate(): YoutubeCandidate? {
        val videoId = url.substringAfter("v=", missingDelimiterValue = "")
            .substringBefore('&')
            .takeIf(::isValidYoutubeVideoId) ?: return null
        val uploader = uploaderName.orEmpty()
        return YoutubeCandidate(
            videoId = videoId,
            title = name,
            channelName = uploader,
            channelIsTopicChannel = uploader.endsWith("- Topic", ignoreCase = true),
            durationMs = duration.coerceAtLeast(0) * 1_000L,
            viewCount = viewCount.coerceAtLeast(0),
            category = "songs",
            isMusicCatalog = true,
        )
    }

    @Synchronized
    private fun ensureInitialized() {
        if (NewPipe.getDownloader() == null) NewPipe.init(OkHttpDownloader(client))
    }
}

@Singleton
class HybridYoutubeMusicSearchSource @Inject constructor(
    private val newPipe: NewPipeYoutubeSource,
    private val ytDlp: AndroidYtDlpEngine,
) : YoutubeMusicSearchSource {
    override suspend fun searchSongs(query: String): List<YoutubeCandidate> {
        val extracted = newPipe.searchSongs(query)
        return extracted.ifEmpty { ytDlp.searchSongs(query) }
    }

    override suspend fun searchBroad(query: String): List<YoutubeCandidate> = ytDlp.searchBroad(query)
}

private class OkHttpDownloader(private val client: OkHttpClient) : Downloader() {
    override fun execute(request: Request): Response {
        val headers = Headers.Builder().apply {
            request.headers().forEach { (name, values) -> values.forEach { value -> add(name, value) } }
        }.build()
        val body = request.dataToSend()?.let { okhttp3.RequestBody.create(null, it) }
        val call = OkHttpRequest.Builder()
            .url(request.url())
            .headers(headers)
            .method(request.httpMethod(), body)
            .build()
        client.newCall(call).execute().use { response ->
            return Response(
                response.code,
                response.message,
                response.headers.toMultimap(),
                response.body?.string().orEmpty(),
                response.request.url.toString(),
            )
        }
    }
}