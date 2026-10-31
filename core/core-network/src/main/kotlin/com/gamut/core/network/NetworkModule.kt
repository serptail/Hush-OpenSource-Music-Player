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
import okhttp3.Dispatcher
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
        // OkHttp allows only 5 parallel calls per host by default, and a call waiting for its rate-limit slot keeps its place.
        // The feed and the artist photos fire many Deezer calls at once, so give them room instead of queueing behind that.
        .dispatcher(Dispatcher().apply { maxRequestsPerHost = 16 })
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
    fun deezerChartsApi(@Named("deezer") client: OkHttpClient): DeezerChartsApi =
        retrofit("https://api.deezer.com/", client).create(DeezerChartsApi::class.java)

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

/**
 * MusicBrainz requires "AppName/version ( contact )" where the contact is a URL or an e-mail address they can reach you on
 * if the app misbehaves. Anonymous or generic agents get throttled or blocked. Switch to the repo URL once it is public.
 */
private const val MUSICBRAINZ_USER_AGENT = "Gamut/1.0 ( https://github.com/serptail )"

private object UserAgentInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response = chain.proceed(
        chain.request().newBuilder().header("User-Agent", MUSICBRAINZ_USER_AGENT).build(),
    )
}

/**
 * At most 40 network requests per 5 s window (Deezer's documented limit is 50). Cache hits never get here.
 *
 * Each request reserves a time slot under the lock (instant), then waits for its slot OUTSIDE the lock, in short steps so a
 * cancelled call stops waiting at once. The old version slept while holding the lock, so one throttled request froze every
 * other Deezer call behind it (logcat showed "Long monitor contention ... DeezerRateLimitInterceptor" for 3 s at a time).
 * Slots are handed out in order and never earlier than the previous one, so the 40-per-window rule still holds.
 */
private object DeezerRateLimitInterceptor : Interceptor {
    private const val WINDOW_MS = 5_000L
    private const val MAX_REQUESTS = 40
    private val lock = Any()
    private val slots = ArrayDeque<Long>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val waitMs = synchronized(lock) {
            val now = System.currentTimeMillis()
            while (slots.isNotEmpty() && slots.first() <= now - WINDOW_MS) slots.removeFirst()
            var slot = now
            if (slots.isNotEmpty()) slot = maxOf(slot, slots.last())
            if (slots.size >= MAX_REQUESTS) slot = maxOf(slot, slots[slots.size - MAX_REQUESTS] + WINDOW_MS)
            slots.addLast(slot)
            slot - now
        }
        var remaining = waitMs
        while (remaining > 0) {
            if (chain.call().isCanceled()) throw java.io.IOException("Canceled")
            val step = minOf(remaining, 100L)
            try {
                Thread.sleep(step)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw java.io.IOException("Interrupted while waiting for the Deezer rate limit")
            }
            remaining -= step
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
 * Backoff for iTunes, which signals its soft rate limit with 403 (not 429/5xx like most APIs) and keeps it up for a while.
 * Retries 403, 429, 5xx and I/O errors up to [maxAttempts] times. If the server sends Retry-After (seconds) that wait is
 * honoured, capped at [MAX_WAIT_MS] so an artist page never hangs on it; otherwise it waits 1 s, 2.5 s, 5 s (+ jitter).
 * Stops at once when the call was cancelled (the user left the screen). Kept separate from BackoffInterceptor so a 403 from
 * an authenticated client elsewhere in the app is never retried by accident.
 */
private class ItunesBackoffInterceptor(private val maxAttempts: Int = 4) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        var attempt = 0
        while (true) {
            if (chain.call().isCanceled()) throw java.io.IOException("Canceled")
            var response: Response? = null
            var error: java.io.IOException? = null
            try {
                response = chain.proceed(chain.request())
            } catch (e: java.io.IOException) {
                error = e
            }
            val code = response?.code
            val retryable = error != null || code == 403 || code == 429 || (code != null && code >= 500)
            if (!retryable || attempt >= maxAttempts - 1) {
                response?.let { return it }
                throw error ?: java.io.IOException("Request failed after $maxAttempts attempts")
            }
            val waitMs = waitMs(response, attempt)
            response?.close()
            attempt++
            try {
                Thread.sleep(waitMs)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw java.io.IOException("Interrupted while backing off")
            }
        }
    }

    private fun waitMs(response: Response?, attempt: Int): Long {
        val retryAfter = response?.header("Retry-After")?.trim()?.toLongOrNull()
        if (retryAfter != null && retryAfter > 0) return (retryAfter * 1_000L).coerceAtMost(MAX_WAIT_MS)
        return STEPS_MS[attempt.coerceAtMost(STEPS_MS.size - 1)] + (0..300).random()
    }

    private companion object {
        const val MAX_WAIT_MS = 8_000L
        val STEPS_MS = longArrayOf(1_000L, 2_500L, 5_000L)
    }
}