package com.hush.feature.search

import com.hush.core.model.Track
import com.hush.core.network.CoverArtApi
import com.hush.core.network.MusicBrainzApi
import com.hush.core.network.MusicBrainzRecordingDto
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

interface SearchRepository {
    suspend fun searchTracks(query: String): Result<List<Track>>
    suspend fun loadArtwork(track: Track): Track
}

class SearchRepositoryImpl @Inject constructor(
    private val musicBrainzApi: MusicBrainzApi,
    private val coverArtApi: CoverArtApi,
) : SearchRepository {
    private val releaseIdsByRecording = ConcurrentHashMap<String, List<String>>()
    private val artworkByRelease = ConcurrentHashMap<String, String>()
    private val artworkSemaphore = Semaphore(4)

    override suspend fun searchTracks(query: String): Result<List<Track>> = runCatching {
        musicBrainzApi.searchRecordings(query).recordings.map { recording ->
            releaseIdsByRecording[recording.id] = recording.releases.map { it.id }
            recording.toDomain()
        }
    }

    override suspend fun loadArtwork(track: Track): Track {
        val releaseIds = releaseIdsByRecording[track.musicBrainzId].orEmpty()
        for (releaseId in releaseIds.take(1)) {
            artworkByRelease[releaseId]?.let { return track.copy(albumArtUrl = it) }
            val response = artworkSemaphore.withPermit {
                runCatching { coverArtApi.getCoverArt(releaseId) }.getOrNull()
            } ?: continue
            if (!response.isSuccessful) continue
            val front = response.body()?.images?.firstOrNull { it.front } ?: continue
            val url = front.thumbnails?.large ?: front.thumbnails?.medium ?: front.image
            if (!url.isNullOrBlank()) artworkByRelease[releaseId] = url
            return track.copy(albumArtUrl = url)
        }
        return track
    }
}

private fun MusicBrainzRecordingDto.toDomain(): Track {
    val artist = artistCredit.mapNotNull { it.name ?: it.artist?.name }.joinToString().ifBlank { "Unknown artist" }
    val stableIsrc = isrcs.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        ?: "fallback:${fallbackKey(artist, title, length ?: 0L)}"
    return Track(
        isrc = stableIsrc,
        musicBrainzId = id,
        title = title,
        artist = artist,
        album = releases.firstOrNull()?.title,
        durationMs = length ?: 0L,
        albumArtUrl = null,
    )
}

private fun fallbackKey(artist: String, title: String, durationMs: Long): String =
    listOf(artist, title, durationMs.toString()).joinToString("|") { value ->
        value.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class SearchRepositoryModule { @Binds abstract fun bindSearchRepository(impl: SearchRepositoryImpl): SearchRepository }
