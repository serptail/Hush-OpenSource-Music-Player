package com.gamut.core.network

import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface DeezerChartsApi {
    @GET("chart/{genre}/tracks")
    suspend fun chartTracks(
        @Path("genre") genre: Int,
        @Query("limit") limit: Int,
    ): DeezerTrackResponse

    @GET("chart/{genre}/albums")
    suspend fun chartAlbums(
        @Path("genre") genre: Int,
        @Query("limit") limit: Int,
    ): DeezerAlbumResponse

    @GET("chart/{genre}/artists")
    suspend fun chartArtists(
        @Path("genre") genre: Int,
        @Query("limit") limit: Int,
    ): DeezerArtistSearchResponse

    @GET("editorial/{genre}/releases")
    suspend fun newReleases(
        @Path("genre") genre: Int,
        @Query("limit") limit: Int,
    ): DeezerAlbumResponse

    @GET("search/artist")
    suspend fun searchArtists(
        @Query("q") query: String,
        @Query("limit") limit: Int,
    ): DeezerArtistSearchResponse

    @GET("search/track")
    suspend fun searchTracks(
        @Query("q") query: String,
        @Query("limit") limit: Int,
    ): DeezerTrackResponse

    @GET("search/album")
    suspend fun searchAlbums(
        @Query("q") query: String,
        @Query("limit") limit: Int,
    ): DeezerAlbumResponse

    @GET("artist/{id}/top")
    suspend fun artistTop(
        @Path("id") id: Long,
        @Query("limit") limit: Int,
    ): DeezerTrackResponse

    @GET("artist/{id}/related")
    suspend fun relatedArtists(
        @Path("id") id: Long,
        @Query("limit") limit: Int,
    ): DeezerArtistSearchResponse

    @GET("search/playlist")
    suspend fun searchPlaylists(
        @Query("q") query: String,
        @Query("limit") limit: Int,
    ): DeezerPlaylistResponse

    @GET("playlist/{id}/tracks")
    suspend fun playlistTracks(
        @Path("id") id: Long,
        @Query("limit") limit: Int,
    ): DeezerTrackResponse

    @GET("artist/{id}/albums")
    suspend fun artistAlbums(
        @Path("id") id: Long,
        @Query("limit") limit: Int,
    ): DeezerAlbumResponse

    @GET("album/{id}/tracks")
    suspend fun albumTracks(
        @Path("id") id: Long,
        @Query("limit") limit: Int,
    ): DeezerTrackResponse
}
