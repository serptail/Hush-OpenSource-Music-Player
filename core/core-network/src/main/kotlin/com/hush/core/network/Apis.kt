package com.hush.core.network

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface MusicBrainzApi {
    @GET("recording")
    suspend fun searchRecordings(
        @Query("query") query: String,
        @Query("fmt") format: String = "json",
        @Query("limit") limit: Int = 25,
        @Query("inc") includes: String = "isrcs",
    ): MusicBrainzSearchResponse
}

interface CoverArtApi {
    @GET("release/{mbid}")
    suspend fun getCoverArt(@Path("mbid") releaseMbid: String): Response<CoverArtResponse>
}

interface ItunesApi {
    @GET("search")
    suspend fun search(
        @Query("term") term: String,
        @Query("media") media: String = "music",
        @Query("entity") entity: String = "song",
        @Query("limit") limit: Int = 25,
        @Query("country") country: String = "US",
    ): Response<ItunesSearchResponse>
}

interface YoutubeMusicApi {
    @POST("search") suspend fun search(@Body request: YoutubeSearchRequest): YoutubeSearchResponse
}

interface ResolverSyncApi {
    @GET("resolve/{isrc}") suspend fun get(@Path("isrc") isrc: String): Response<ResolvedSourceDto>
    @POST("resolve") suspend fun save(@Body source: ResolvedSourceDto): Response<Unit>
    @GET("youtube/search") suspend fun searchYoutube(@Query("query") query: String): YoutubeSearchResponse
    @GET("youtube/stream/{videoId}") suspend fun youtubeStream(@Path("videoId") videoId: String): Response<AudioStreamDto>
}

