package com.gamut.core.network

import android.content.Context
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.Cache
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
        .addInterceptor(BackoffInterceptor())
        .build()

    @Provides
    @Singleton
    @Named("coverart")
    fun coverArtClient(): OkHttpClient = baseClient()

    /**
     * One on-disk HTTP cache shared by the catalogue clients (iTunes, Deezer, Apple charts). It survives restarts, so
     * the same artist / album / photo lookups are not fetched again. Never used for YouTube: stream URLs expire.
     */
    @Provides
    @Singleton
    @Named("catalog-cache")
    fun catalogCache(@ApplicationContext context: Context): Cache =
        Cache(File(context.cacheDir, "http-catalog"), 20L * 1024 * 1024)

    @Provides
    @Singleton
    @Named("itunes")
    fun itunesClient(@Named("catalog-cache") cache: Cache): OkHttpClient = baseClient().newBuilder()
        // iTunes has no documented hard limit but empirically throttles around
        // ~20 requests/minute/IP and signals it with 403 rather than 429/5xx.
        // SearchRepositoryImpl's Semaphore(1) + 10-min result cache already keep us well
        // under that in normal typing; this interceptor is the safety net for the rare
        // case we do trip it, since plain BackoffInterceptor ignores 403 by design.
        .addInterceptor(ItunesBackoffInterceptor())
        .cached(cache)
        .build()

    @Provides
    @Singleton
    @Named("deezer")
    fun deezerClient(@Named("catalog-cache") cache: Cache): OkHttpClient = baseClient().newBuilder()
        // Deezer answers HTTP 200 with {"error":{"code":4,...}} once ~50 requests / 5 s are exceeded; those used to
        // be read as "no results" (and cached). Stay under the limit, and never cache an error body (see cached()).
        .addNetworkInterceptor(DeezerRateLimitInterceptor)
        .cached(cache)
        .build()

    @Provides
    @Singleton
    @Named("apple")
    fun appleClient(@Named("catalog-cache") cache: Cache): OkHttpClient = baseClient().newBuilder().cached(cache).build()

    @Provides
    @Singleton
    @Named("billboard")
    fun billboardClient(@Named("catalog-cache") cache: Cache): OkHttpClient = baseClient().newBuilder().cached(cache).build()

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
    fun itunesApi(@Named("itunes") client: OkHttpClient): ItunesApi =
        retrofit("https://itunes.apple.com/", client).create(ItunesApi::class.java)

    @Provides
    @Singleton
    fun deezerApi(@Named("deezer") client: OkHttpClient): DeezerApi =
        retrofit("https://api.deezer.com/", client).create(DeezerApi::class.java)

    @Provides
    @Singleton
    fun appleChartsApi(@Named("apple") client: OkHttpClient): AppleChartsApi =
        retrofit("https://rss.marketingtools.apple.com/api/v2/us/", client).create(AppleChartsApi::class.java)

    @Provides
    @Singleton
    fun billboardApi(@Named("billboard") client: OkHttpClient): BillboardApi =
        retrofit("https://raw.githubusercontent.com/", client).create(BillboardApi::class.java)

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
        connectTimeout(8, TimeUnit.SECONDS)
        readTimeout(15, TimeUnit.SECONDS)
        writeTimeout(15, TimeUnit.SECONDS)
        if (com.gamut.core.network.BuildConfig.DEBUG) {
            addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
        }
    }.build()
}

/** How long a catalogue answer (artist, album list, chart, photo lookup) is reused without asking again. */
private const val CATALOG_TTL_SECONDS = 3_600

/**
 * Caches successful GET answers on disk for [CATALOG_TTL_SECONDS], whatever the server's own cache headers say:
 * iTunes and Deezer send none that are usable. Vary is dropped because these answers do not depend on request
 * headers, and OkHttp refuses to cache a response that says "Vary: *". Failures (e.g. iTunes' 403) are never cached.
 */
private fun OkHttpClient.Builder.cached(cache: Cache): OkHttpClient.Builder = cache(cache)
    .addNetworkInterceptor { chain ->
        val response = chain.proceed(chain.request())
        // Deezer reports quota errors as 200 + {"error":...}: caching that would serve "no results" for an hour.
        val apiError = response.isSuccessful && response.peekBody(256).string().trimStart().startsWith("{\"error\"")
        if (chain.request().method == "GET" && response.isSuccessful && !apiError) {
            response.newBuilder()
                .removeHeader("Pragma")
                .removeHeader("Vary")
                .header("Cache-Control", "public, max-age=$CATALOG_TTL_SECONDS")
                .build()
        } else {
            response
        }
    }

private object UserAgentInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response = chain.proceed(
        chain.request().newBuilder().header("User-Agent", "Gamut/1.0 (https://github.com/yourname/gamut)").build(),
    )
}

/** At most 40 network requests per 5 s window (Deezer's documented limit is 50). Cache hits never get here. */
private object DeezerRateLimitInterceptor : Interceptor {
    private const val WINDOW_MS = 5_000L
    private const val MAX_REQUESTS = 40
    private val lock = Any()
    private val times = ArrayDeque<Long>()

    override fun intercept(chain: Interceptor.Chain): Response {
        synchronized(lock) {
            while (true) {
                val now = System.currentTimeMillis()
                while (times.isNotEmpty() && now - times.first() >= WINDOW_MS) times.removeFirst()
                if (times.size < MAX_REQUESTS) { times.addLast(now); break }
                Thread.sleep(WINDOW_MS - (now - times.first()))
            }
        }
        return chain.proceed(chain.request())
    }
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

/**
 * Retries on 5xx AND on a slow/hanging response (timeout), not just non-2xx codes.
 * Shared by MusicBrainz and sync clients.
 */
private class BackoffInterceptor(private val maxAttempts: Int = 3) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        var lastResponse: Response? = null
        var lastError: java.io.IOException? = null
        repeat(maxAttempts) { attempt ->
            lastResponse?.close()
            try {
                lastResponse = chain.proceed(chain.request())
                lastError = null
                if (lastResponse!!.isSuccessful || lastResponse!!.code < 500) return lastResponse!!
            } catch (error: java.io.IOException) {
                lastError = error
            }
            if (attempt < maxAttempts - 1) Thread.sleep(150L * (1L shl attempt))
        }
        lastResponse?.let { return it }
        throw lastError ?: java.io.IOException("Request failed after $maxAttempts attempts")
    }
}

/**
 * Same shape as BackoffInterceptor, but also retries on 403 -- the code iTunes returns
 * when its soft rate limit is tripped, rather than 429/5xx like most APIs. Kept as a
 * separate class rather than a flag on BackoffInterceptor so a 403 from an *authenticated*
 * client elsewhere in the app is never silently retried by accident.
 */
private class ItunesBackoffInterceptor(private val maxAttempts: Int = 3) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        var lastResponse: Response? = null
        var lastError: java.io.IOException? = null
        repeat(maxAttempts) { attempt ->
            lastResponse?.close()
            try {
                lastResponse = chain.proceed(chain.request())
                lastError = null
                val code = lastResponse!!.code
                if (lastResponse!!.isSuccessful || (code < 500 && code != 403)) return lastResponse!!
            } catch (error: java.io.IOException) {
                lastError = error
            }
            if (attempt < maxAttempts - 1) Thread.sleep(300L * (1L shl attempt))
        }
        lastResponse?.let { return it }
        throw lastError ?: java.io.IOException("Request failed after $maxAttempts attempts")
    }
}