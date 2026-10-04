package com.gamut.feature.resolver

import com.gamut.core.model.ResolutionMethod
import com.gamut.core.model.ResolvedSource
import com.gamut.core.model.Track
import com.gamut.core.model.TrackSource
import com.gamut.core.network.YoutubeCandidate
import com.gamut.core.network.YoutubeMusicSearchSource
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

sealed class ResolveFailure(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class NoCandidates(val query: String) : ResolveFailure("No candidates for $query")
    class LowScore(val best: Double, val bestTitle: String) : ResolveFailure("Best candidate score: $best")
    class Timeout(val stage: String) : ResolveFailure("Timed out at $stage")
}

interface YoutubeCandidateRepository {
    suspend fun songs(track: Track): List<YoutubeCandidate>
    suspend fun broad(track: Track): List<YoutubeCandidate>
}

class YoutubeCandidateRepositoryImpl @Inject constructor(private val source: YoutubeMusicSearchSource) : YoutubeCandidateRepository {
    override suspend fun songs(track: Track) = source.searchSongs("${track.artist} ${track.title}")
    override suspend fun broad(track: Track) = source.searchBroad("${track.artist} ${track.title}")
}

interface AudioSourceRepository {
    /** [forceRefresh] drops any cached stream URL first (YouTube URLs expire and are tied to the client's IP). */
    suspend fun audioUrl(videoId: String, forceRefresh: Boolean = false): Result<String>
}

class AudioSourceRepositoryImpl @Inject constructor(
    private val engine: AndroidYtDlpEngine,
    private val newPipe: NewPipeYoutubeSource,
    @ApplicationScope private val appScope: CoroutineScope,
) : AudioSourceRepository {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, CachedAudio>()
    private val inFlight = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Deferred<Result<String>>>()

    override suspend fun audioUrl(videoId: String, forceRefresh: Boolean): Result<String> {
        if (forceRefresh) {
            cache.remove(videoId)
            engine.forget(videoId)
        }
        cache[videoId]?.takeIf { it.expiresAtMs > System.currentTimeMillis() }?.let {
            return Result.success(it.url)
        }
        val job = inFlight.computeIfAbsent(videoId) {
            appScope.async {
                val result = kotlinx.coroutines.withTimeoutOrNull(AUDIO_PRIMARY_TIMEOUT_MS) { newPipe.audioUrl(videoId) }
                    ?.fold(
                        onSuccess = { Result.success(it) },
                        onFailure = { engine.audioUrl(videoId) },
                    )
                    ?: engine.audioUrl(videoId)
                result.onSuccess { url -> cache[videoId] = CachedAudio(url, expiryOf(url)) }
                result
            }
        }
        return try {
            job.await()
        } finally {
            if (job.isCompleted) inFlight.remove(videoId, job)
        }
    }

    private fun expiryOf(url: String): Long = Regex("[?&]expire=(\\d+)").find(url)?.groupValues?.get(1)
        ?.toLongOrNull()?.times(1_000L)?.minus(120_000L)
        ?: System.currentTimeMillis() + 20 * 60_000L

    private data class CachedAudio(val url: String, val expiresAtMs: Long)

    private companion object { const val AUDIO_PRIMARY_TIMEOUT_MS = 8_000L }
}

class ResolveTrackSourceUseCase @Inject constructor(
    private val sources: ResolvedSourceRepository,
    private val candidates: YoutubeCandidateRepository,
    private val scorer: CandidateScorer,
    @ApplicationScope private val appScope: CoroutineScope,
) {
    suspend operator fun invoke(track: Track): Result<ResolvedSource> = runCatching {
        val resolutionKey = track.resolutionKey()
        sources.getLocal(resolutionKey)?.let { return@runCatching it }
        sources.getRemote(resolutionKey)?.let { remote -> sources.saveLocal(remote); return@runCatching remote }

        val youtubeVideoId = track.youtubeVideoId
        if (track.source == TrackSource.YOUTUBE && !youtubeVideoId.isNullOrBlank()) {
            val direct = ResolvedSource(resolutionKey, youtubeVideoId, ResolutionMethod.DIRECT, 1.0f, 0)
            sources.saveLocal(direct)
            appScope.launch { sources.submitRemote(direct) }
            return@runCatching direct
        }

        // yt-dlp's search can occasionally hang or get slow-walked by YouTube instead of
        // failing outright -- a hard ceiling here means a click on a track surfaces "no
        // verified source found" within a bounded time instead of spinning indefinitely.
        val songs = withTimeoutOrNull(SEARCH_TIMEOUT_MS) { candidates.songs(track) }
            ?: throw ResolveFailure.Timeout("songs search")
        var pool = songs
        var selected = scorer.selectBest(pool, track)
        if (selected == null || selected.confidenceScore < CandidateScorer.ACCEPT) {
            val broad = withTimeoutOrNull(BROAD_TIMEOUT_MS) { candidates.broad(track) }
                ?: throw ResolveFailure.Timeout("broad search")
            pool = songs + broad.filter { strictBroadOk(it, track) }
            selected = scorer.selectBest(pool, track)
        }
        val result = selected?.copy(isrc = resolutionKey)
            ?: if (pool.isEmpty()) throw ResolveFailure.NoCandidates(track.title)
            else throw ResolveFailure.LowScore(scorer.rank(pool, track).first().score, pool.first().title)
        if (result.confidenceScore >= CandidateScorer.ACCEPT) {
            sources.saveLocal(result)
            appScope.launch { sources.submitRemote(result) }
        }
        result
    }

    private fun strictBroadOk(candidate: YoutubeCandidate, track: Track): Boolean {
        val artist = com.gamut.core.common.TextMatch.toks(track.artist)
        val candidateText = com.gamut.core.common.TextMatch.toks(candidate.title + " " + candidate.channelName)
        val artistHit = artist.any { it in candidateText }
        val durationOk = candidate.durationMs <= 0L || track.durationMs <= 0L ||
            kotlin.math.abs(candidate.durationMs - track.durationMs) <= 8_000L
        return artistHit && durationOk
    }

    private companion object {
        const val SEARCH_TIMEOUT_MS = 8_000L
        const val BROAD_TIMEOUT_MS = 6_000L
    }
}

private fun Track.resolutionKey(): String = isrc.takeIf { it.isNotBlank() } ?:
    "fallback:${listOf(artist, title, durationMs.toString()).joinToString("|") { value -> value.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim() }}"