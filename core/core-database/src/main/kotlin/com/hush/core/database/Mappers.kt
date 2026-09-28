package com.hush.core.database

import com.hush.core.model.DownloadedTrack
import com.hush.core.model.ResolutionMethod
import com.hush.core.model.ResolvedSource
import com.hush.core.model.Track
import com.hush.core.model.TrackSource

fun TrackEntity.toDomain() = Track(
    isrc = isrc,
    musicBrainzId = musicBrainzId,
    title = title,
    artist = artist,
    album = album,
    durationMs = durationMs,
    albumArtUrl = albumArtUrl,
    source = runCatching { TrackSource.valueOf(source) }.getOrDefault(TrackSource.MUSICBRAINZ),
    youtubeVideoId = youtubeVideoId,
)

fun Track.toEntity() = TrackEntity(
    isrc = isrc,
    musicBrainzId = musicBrainzId,
    title = title,
    artist = artist,
    album = album,
    durationMs = durationMs,
    albumArtUrl = albumArtUrl,
    source = source.name,
    youtubeVideoId = youtubeVideoId,
)

fun ResolvedSourceEntity.toDomain() = ResolvedSource(isrc, youtubeVideoId, ResolutionMethod.valueOf(resolutionMethod), confidenceScore, verifiedCount)
fun ResolvedSource.toEntity() = ResolvedSourceEntity(isrc, youtubeVideoId, resolutionMethod.name, confidenceScore, verifiedCount)
fun DownloadEntity.toDomain() = DownloadedTrack(isrc, localFilePath, downloadedAtEpochMs, fileSizeBytes)
fun DownloadedTrack.toEntity() = DownloadEntity(isrc, localFilePath, downloadedAtEpochMs, fileSizeBytes)
