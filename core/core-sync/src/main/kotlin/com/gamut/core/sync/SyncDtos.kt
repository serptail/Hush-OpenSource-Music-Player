package com.gamut.core.sync

import com.gamut.core.model.Track
import com.gamut.core.model.TrackSource
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ---- resolve-metadata ----
@Serializable data class ResolveItem(val provider: String, @SerialName("provider_id") val providerId: String)
@Serializable data class ResolveRequest(val items: List<ResolveItem>)
@Serializable data class SongRef(val id: String, val provider: String, @SerialName("provider_song_id") val providerSongId: String)
@Serializable data class ResolveResponse(val songs: List<SongRef> = emptyList())

// ---- push rows ----
@Serializable
data class FavoriteRow(
    @SerialName("user_id") val userId: String,
    @SerialName("song_id") val songId: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
)

@Serializable
data class PlaylistRow(
    val id: String,
    @SerialName("user_id") val userId: String,
    val name: String,
    val tags: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
)

@Serializable
data class PlaylistSongRow(
    @SerialName("playlist_id") val playlistId: String,
    @SerialName("song_id") val songId: String,
    val position: Int,
    @SerialName("deleted_at") val deletedAt: String? = null,
)

// ---- pull rows ----
@Serializable data class ArtistNameDto(val name: String? = null)
@Serializable data class AlbumTitleDto(val title: String? = null)

@Serializable
data class SongDto(
    val provider: String,
    @SerialName("provider_song_id") val providerSongId: String,
    val title: String,
    @SerialName("duration_ms") val durationMs: Long = 0,
    @SerialName("artwork_url") val artworkUrl: String? = null,
    val artist: ArtistNameDto? = null,
    val album: AlbumTitleDto? = null,
)

@Serializable
data class FavoritePull(
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
    val songs: SongDto? = null,
)

@Serializable
data class PlaylistPull(
    val id: String,
    val name: String,
    val tags: String = "",
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
)

@Serializable
data class PlaylistSongPull(
    @SerialName("playlist_id") val playlistId: String,
    val position: Int,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
    val songs: SongDto? = null,
)

// ---- artist follows ----
@Serializable
data class FollowRow(
    @SerialName("user_id") val userId: String,
    val provider: String,
    @SerialName("provider_artist_id") val providerArtistId: String,
    val name: String,
    @SerialName("artwork_url") val artworkUrl: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
)

@Serializable
data class FollowPull(
    val provider: String,
    @SerialName("provider_artist_id") val providerArtistId: String,
    val name: String,
    @SerialName("artwork_url") val artworkUrl: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
)

/** Lightweight playlist listing used to adopt same-named server playlists on first sign-in. */
@Serializable
data class RemotePlaylistRef(
    val id: String,
    val name: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
)

internal const val SONG_EMBED =
    "songs(provider,provider_song_id,title,duration_ms,artwork_url,artist:artists(name),album:albums(title))"

internal fun SongDto.isrc() = "$provider:$providerSongId"

internal fun SongDto.toTrack(): Track {
    val yt = provider == "youtube"
    return Track(
        isrc = isrc(),
        title = title,
        artist = artist?.name.orEmpty().ifBlank { "Unknown artist" },
        album = album?.title,
        durationMs = durationMs,
        albumArtUrl = artworkUrl,
        source = if (yt) TrackSource.YOUTUBE else TrackSource.ITUNES,
        youtubeVideoId = if (yt) providerSongId else null,
    )
}

/** "itunes:123" -> ("itunes", "123"). Null for anything the server cannot resolve (e.g. a bare MusicBrainz ISRC). */
internal fun parseIsrc(isrc: String): Pair<String, String>? {
    val provider = isrc.substringBefore(':', "")
    val id = isrc.substringAfter(':', "")
    return if (provider in PROVIDERS && Regex("^[A-Za-z0-9_-]{1,32}$").matches(id)) provider to id else null
}
private val PROVIDERS = setOf("deezer", "itunes", "apple", "youtube")

/** "deezer:123" -> ("deezer", "123"). Same shape and validation as a song key. */
internal fun parseArtistKey(key: String): Pair<String, String>? = parseIsrc(key)