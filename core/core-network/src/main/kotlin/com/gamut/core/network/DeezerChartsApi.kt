package com.gamut.core.network

import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * The keyless Deezer endpoints that feed the Discover feed: charts (worldwide and per genre), editorial new releases,
 * and per-artist top tracks / related artists. Uses the shared "deezer" client, so it gets the same on-disk HTTP cache
 * and the same rate-limit interceptor as [DeezerApi]. Genre id 0 means "all genres".
 */
interface DeezerChartsApi {
    @GET("chart/{genre}/tracks")
    suspend fun chartTracks(@Path("genre") genre: Int, @Query("limit") limit: Int): DeezerTrackResponse

    @GET("chart/{genre}/albums")
    suspend fun chartAlbums(@Path("genre") genre: Int, @Query("limit") limit: Int): DeezerAlbumResponse

    @GET("chart/{genre}/artists")
    suspend fun chartArtists(@Path("genre") genre: Int, @Query("limit") limit: Int): DeezerArtistSearchResponse

    /** Editorial new releases (albums, EPs, singles) with their release date. */
    @GET("editorial/{genre}/releases")
    suspend fun newReleases(@Path("genre") genre: Int, @Query("limit") limit: Int): DeezerAlbumResponse

    @GET("search/artist")
    suspend fun searchArtists(@Query("q") query: String, @Query("limit") limit: Int): DeezerArtistSearchResponse

    @GET("artist/{id}/top")
    suspend fun artistTop(@Path("id") id: Long, @Query("limit") limit: Int): DeezerTrackResponse

    @GET("artist/{id}/related")
    suspend fun relatedArtists(@Path("id") id: Long, @Query("limit") limit: Int): DeezerArtistSearchResponse

    /** Track rows carry no album object here (see [DeezerTrackDto.album]); the caller supplies title and cover. */
    @GET("album/{id}/tracks")
    suspend fun albumTracks(@Path("id") id: Long, @Query("limit") limit: Int): DeezerTrackResponse
}
