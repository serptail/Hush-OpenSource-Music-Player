package com.gamut.feature.resolver

import com.gamut.core.network.YoutubeCandidate
import com.gamut.core.network.YoutubeMusicSearchSource
import com.gamut.core.network.isValidYoutubeVideoId
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request as OkHttpRequest
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamInfo
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class NewPipeYoutubeSource @Inject constructor(
    @Named("youtube") private val client: OkHttpClient,
) : YoutubeMusicSearchSource {
    suspend fun audioUrl(videoId: String): Result<String> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            ensureInitialized()
            StreamInfo.getInfo("https://www.youtube.com/watch?v=$videoId")
                .audioStreams
                .asSequence()
                .filter { it.isUrl }
                .sortedByDescending { it.averageBitrate }
                .map { it.content }
                .firstOrNull { it.isNotBlank() }
                ?: error("NewPipe returned no direct audio stream")
        }
    }

    override suspend fun searchSongs(query: String): List<YoutubeCandidate> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
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
            }.getOrDefault(emptyList())
        }

    override suspend fun searchBroad(query: String): List<YoutubeCandidate> = emptyList()

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
