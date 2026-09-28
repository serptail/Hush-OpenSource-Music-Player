package com.hush.feature.resolver

import com.hush.core.common.AppFailure
import com.hush.core.model.ResolutionMethod
import com.hush.core.model.ResolvedSource
import com.hush.core.model.Track
import com.hush.core.model.TrackSource
import com.hush.core.network.YoutubeCandidate
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

interface YoutubeCandidateRepository { suspend fun candidates(track: Track): List<YoutubeCandidate> }

class YoutubeCandidateRepositoryImpl @Inject constructor(private val engine: AndroidYtDlpEngine) : YoutubeCandidateRepository {
    override suspend fun candidates(track: Track) = engine.search(track)
}

interface AudioSourceRepository { suspend fun audioUrl(videoId: String): Result<String> }

class AudioSourceRepositoryImpl @Inject constructor(private val engine: AndroidYtDlpEngine) : AudioSourceRepository {
    override suspend fun audioUrl(videoId: String): Result<String> = engine.audioUrl(videoId)
}

class ResolveTrackSourceUseCase @Inject constructor(
    private val sources: ResolvedSourceRepository,
    private val candidates: YoutubeCandidateRepository,
    private val scorer: CandidateScorer,
) {
    suspend operator fun invoke(track: Track): Result<ResolvedSource> = runCatching {
        val resolutionKey = track.resolutionKey()
        sources.getLocal(resolutionKey)?.let { return@runCatching it }
        sources.getRemote(resolutionKey)?.let { remote -> sources.saveLocal(remote); return@runCatching remote }

        val youtubeVideoId = track.youtubeVideoId
        if (track.source == TrackSource.YOUTUBE && !youtubeVideoId.isNullOrBlank()) {
            val direct = ResolvedSource(resolutionKey, youtubeVideoId, ResolutionMethod.DIRECT, 1.0f, 0)
            sources.saveLocal(direct)
            CoroutineScope(Dispatchers.IO).launch { sources.submitRemote(direct) }
            return@runCatching direct
        }

        // yt-dlp's search can occasionally hang or get slow-walked by YouTube instead of
        // failing outright -- a hard ceiling here means a click on a track surfaces "no
        // verified source found" within a bounded time instead of spinning indefinitely.
        val foundCandidates = withTimeoutOrNull(CANDIDATE_SEARCH_TIMEOUT_MS) { candidates.candidates(track) }
            ?: throw AppFailure.NoMatch()

        val selected = scorer.selectBest(foundCandidates, track)?.copy(isrc = resolutionKey) ?: throw AppFailure.NoMatch()
        sources.saveLocal(selected)
        CoroutineScope(Dispatchers.IO).launch { sources.submitRemote(selected) }
        selected
    }

    private companion object {
        const val CANDIDATE_SEARCH_TIMEOUT_MS = 15_000L
    }
}

private fun Track.resolutionKey(): String = isrc.takeIf { it.isNotBlank() } ?:
    "fallback:${listOf(artist, title, durationMs.toString()).joinToString("|") { value -> value.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim() }}"