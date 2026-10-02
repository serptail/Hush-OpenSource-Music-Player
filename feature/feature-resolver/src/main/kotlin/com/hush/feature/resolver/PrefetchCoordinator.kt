package com.hush.feature.resolver

import com.hush.core.model.Track
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

@Singleton
class PrefetchCoordinator @Inject constructor(
    private val resolve: ResolveTrackSourceUseCase,
    private val audio: AudioSourceRepository,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val gate = Semaphore(2)
    private var activeJob: Job? = null

    fun prefetch(tracks: List<Track>) {
        activeJob?.cancel()
        activeJob = scope.launch {
            tracks.take(PREFETCH_COUNT).forEach { track ->
                launch {
                    gate.withPermit {
                        resolve(track).onSuccess { source -> audio.audioUrl(source.youtubeVideoId) }
                    }
                }
            }
        }
    }

    private companion object { const val PREFETCH_COUNT = 3 }
}
