package com.hush.core.database

import com.hush.core.model.DownloadedTrack
import com.hush.core.model.ResolvedSource
import com.hush.core.model.ResolutionMethod
import com.hush.core.model.Track

fun TrackEntity.toDomain() = Track(isrc, musicBrainzId, title, artist, album, durationMs, albumArtUrl)
fun Track.toEntity() = TrackEntity(isrc, musicBrainzId, title, artist, album, durationMs, albumArtUrl)
fun ResolvedSourceEntity.toDomain() = ResolvedSource(isrc, youtubeVideoId, ResolutionMethod.valueOf(resolutionMethod), confidenceScore, verifiedCount)
fun ResolvedSource.toEntity() = ResolvedSourceEntity(isrc, youtubeVideoId, resolutionMethod.name, confidenceScore, verifiedCount)
fun DownloadEntity.toDomain() = DownloadedTrack(isrc, localFilePath, downloadedAtEpochMs, fileSizeBytes)
fun DownloadedTrack.toEntity() = DownloadEntity(isrc, localFilePath, downloadedAtEpochMs, fileSizeBytes)