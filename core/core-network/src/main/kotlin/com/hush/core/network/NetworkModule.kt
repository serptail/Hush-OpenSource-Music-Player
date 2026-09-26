package com.hush.core.network

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit

private val json = Json { ignoreUnknownKeys = true }
private val contentType = "application/json".toMediaType()

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides
    @Singleton
    @Named("musicbrainz")
    fun musicBrainzClient(): OkHttpClient = baseClient().newBuilder()
        .addInterceptor(UserAgentInterceptor)
        .addInterceptor(MusicBrainzRateLimitInterceptor)
        .build()

    @Provides
    @Singleton
    @Named("coverart")
    fun coverArtClient(): OkHttpClient = baseClient()

    @Provides
    @Singleton
    @Named("youtube")
    fun youtubeClient(): OkHttpClient = baseClient()

    @Provides
    @Singleton
    @Named("sync")
    fun syncClient(): OkHttpClient = baseClient().newBuilder().addInterceptor(BackoffInterceptor()).build()

    @Provides
    @Singleton
    fun musicBrainzApi(@Named("musicbrainz") client: OkHttpClient): MusicBrainzApi =
        retrofit("https://musicbrainz.org/ws/2/", client).create(MusicBrainzApi::class.java)

    @Provides
    @Singleton
    fun coverArtApi(@Named("coverart") client: OkHttpClient): CoverArtApi =
        retrofit("https://coverartarchive.org/", client).create(CoverArtApi::class.java)

    @Provides
    @Singleton
    fun youtubeApi(@Named("youtube") client: OkHttpClient): YoutubeMusicApi =
        retrofit("https://music.youtube.com/", client).create(YoutubeMusicApi::class.java)


    @Provides
    @Singleton
    fun resolverSyncApi(@Named("sync") client: OkHttpClient): ResolverSyncApi =
        retrofit(BuildConfig.SYNC_BASE_URL, client).create(ResolverSyncApi::class.java)

    private fun retrofit(baseUrl: String, client: OkHttpClient) = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(client)
        .addConverterFactory(json.asConverterFactory(contentType))
        .build()

    private fun baseClient() = OkHttpClient.Builder().apply {
        if (com.hush.core.network.BuildConfig.DEBUG) {
            addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
        }
    }.build()
}

private object UserAgentInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response = chain.proceed(
        chain.request().newBuilder().header("User-Agent", "Hush/1.0 (https://github.com/yourname/hush)").build(),
    )
}

private object MusicBrainzRateLimitInterceptor : Interceptor {
    private val lock = Any()
    private var lastRequestAtMs = 0L

    override fun intercept(chain: Interceptor.Chain): Response {
        synchronized(lock) {
            val waitMs = 1_000L - (System.currentTimeMillis() - lastRequestAtMs)
            if (waitMs > 0) Thread.sleep(waitMs)
            lastRequestAtMs = System.currentTimeMillis()
        }
        return chain.proceed(chain.request())
    }
}

private class BackoffInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        var last: Response? = null
        repeat(3) { attempt ->
            last?.close()
            last = chain.proceed(chain.request())
            if (last!!.isSuccessful || last!!.code < 500) return last!!
            Thread.sleep(150L * (1L shl attempt))
        }
        return last!!
    }
}
