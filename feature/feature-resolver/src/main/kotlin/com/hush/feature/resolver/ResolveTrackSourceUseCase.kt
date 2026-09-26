package com.hush.feature.resolver

import com.hush.core.common.AppFailure
import com.hush.core.network.YoutubeCandidate
import com.hush.core.model.ResolvedSource
import com.hush.core.model.Track
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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
        val selected = scorer.selectBest(candidates.candidates(track), track)?.copy(isrc = resolutionKey) ?: throw AppFailure.NoMatch()
        sources.saveLocal(selected)
        CoroutineScope(Dispatchers.IO).launch { sources.submitRemote(selected) }
        selected
    }
}

private fun Track.resolutionKey(): String = isrc.takeIf { it.isNotBlank() } ?:
    "fallback:${listOf(artist, title, durationMs.toString()).joinToString("|") { value -> value.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim() }}"

