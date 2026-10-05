package com.gamut.feature.resolver

import com.gamut.core.network.YoutubeCandidate
import com.gamut.core.network.YoutubeMusicSearchSource
import com.gamut.core.network.isValidYoutubeVideoId
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    // One extraction (visitor_id, reel_item_watch, player x2, next: ~7 requests, ~2 s) per video, not one per caller.
    // It runs in its own scope so that a caller that gives up (the user tapped another song) does not abort it for the
    // next caller, and the finished URL is cached.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cache = StreamUrlCache()
    private val inFlight = ConcurrentHashMap<String, Deferred<String>>()

    /**
     * A direct audio URL for [videoId].
     *  - Cached for a while (never past the URL's own `expire`), so verifying a match and then playing it, or tapping the
     *    same song twice, extracts only once.
     *  - Concurrent calls for the same video share one extraction.
     *  - [forceRefresh]: drop the cached URL first (the player says the one it has stopped working).
     * Cancelling the caller cancels only its wait, and is rethrown instead of being turned into a failed Result.
     */
    suspend fun audioUrl(videoId: String, forceRefresh: Boolean = false): Result<String> {
        if (forceRefresh) cache.remove(videoId) else cache.get(videoId)?.let { return Result.success(it) }
        val created = scope.async(start = CoroutineStart.LAZY) {
            try {
                extractAudioUrl(videoId).also { cache.put(videoId, it) }
            } finally {
                inFlight.remove(videoId)
            }
        }
        val job = inFlight.putIfAbsent(videoId, created) ?: created.also { it.start() }
        if (job !== created) created.cancel()
        return try {
            Result.success(job.await())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Fire and forget: warms the cache (e.g. for the next song in the queue) so the tap or skip finds the URL ready. */
    fun prefetchAudioUrl(videoId: String) {
        if (cache.get(videoId) != null || inFlight.containsKey(videoId)) return
        scope.launch { audioUrl(videoId) }
    }

    private fun extractAudioUrl(videoId: String): String {
        ensureInitialized()
        return StreamInfo.getInfo("https://www.youtube.com/watch?v=$videoId")
            .audioStreams
            .asSequence()
            .filter { it.isUrl }
            // AAC/m4a first (same preference as the yt-dlp path: cheaper to decode, plays natively in ExoPlayer),
            // then the highest bitrate within that format.
            .sortedWith(compareByDescending<AudioStream> { it.format == MediaFormat.M4A }.thenByDescending { it.averageBitrate })
            .map { it.content }
            .firstOrNull { it.isNotBlank() }
            ?: error("NewPipe returned no direct audio stream")
    }

    override suspend fun searchSongs(query: String): List<YoutubeCandidate> =
        withContext(Dispatchers.IO) {
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
                // runCatching used to swallow this: a cancelled search came back as "no results", and HybridYoutubeMusicSearchSource
                // then started a slow yt-dlp search for a query nobody is waiting for any more.
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

/**
 * Small in-memory cache of extracted stream URLs. googlevideo URLs carry an `expire` (epoch seconds, usually ~6 h);
 * an entry lives for at most [TTL_MS] and never past that expiry minus a safety margin.
 */
private class StreamUrlCache {
    private class Entry(val url: String, val expiresAt: Long)

    private val map = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>) = size > MAX_ENTRIES
    }

    fun get(videoId: String): String? = synchronized(map) {
        val e = map[videoId] ?: return null
        if (System.currentTimeMillis() >= e.expiresAt) { map.remove(videoId); null } else e.url
    }

    fun put(videoId: String, url: String) {
        val now = System.currentTimeMillis()
        val hardExpiry = EXPIRE_PARAM.find(url)?.groupValues?.get(1)?.toLongOrNull()?.times(1000L)?.minus(SAFETY_MARGIN_MS)
        val expiresAt = minOf(now + TTL_MS, hardExpiry ?: Long.MAX_VALUE)
        if (expiresAt <= now) return
        synchronized(map) { map[videoId] = Entry(url, expiresAt) }
    }

    fun remove(videoId: String) { synchronized(map) { map.remove(videoId) } }

    private companion object {
        const val TTL_MS = 20 * 60_000L
        const val SAFETY_MARGIN_MS = 2 * 60_000L
        const val MAX_ENTRIES = 32
        val EXPIRE_PARAM = Regex("[?&]expire=(\\d+)")
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