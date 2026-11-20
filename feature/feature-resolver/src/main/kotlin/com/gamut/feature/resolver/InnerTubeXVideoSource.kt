package com.gamut.feature.resolver

import android.content.Context
import android.webkit.CookieManager
import android.util.Log
import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.InnerTubeLogLevel
import com.metrolist.innertubex.InnerTubeLogger
import com.metrolist.innertubex.cipher.PlayerConfigRepository
import com.metrolist.innertubex.cipher.RemotePlayerConfigStore
import com.metrolist.innertubex.cipher.YouTubeCipherService
import com.metrolist.innertubex.extraction.AudioQuality
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.InnerTubeExtractor
import com.metrolist.innertubex.extraction.StreamResolveException
import com.metrolist.innertubex.extraction.YtConfigParserImpl
import com.metrolist.innertubex.extraction.generateClientPlaybackNonce
import com.gamut.feature.resolver.potoken.PoTokenGenerator
import com.gamut.feature.resolver.potoken.PoTokenWebView
import com.metrolist.innertubex.models.YouTubeLocale
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.io.File
import java.util.Locale
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/** A video-only rendition InnerTubeX resolved, with the identity its fetch has to wear. */
data class ResolvedVideo(
    val url: String,
    val width: Int,
    val height: Int,
    val mimeType: String,
    val codecs: String,
    /** Headers the media fetch must carry: googlevideo wants the agent of the client that minted [url]. */
    val headers: Map<String, String>,
    /** Every height this video is offered at, largest first. */
    val heights: List<Int>,
    /** InnerTubeX's catalog id for the client that minted [url]; what a refusal skips. */
    val profileId: String,
)

/**
 * YouTube video-only stream resolution through InnerTubeX, the way BitChord does it.
 *
 * InnerTubeX owns what NewPipe's single-shot extraction doesn't: a catalog of player clients
 * (TV, VR, iOS, web) tried in order, the signature / `n` cipher, the headers each client's URLs
 * need, and an exclusion list so a client googlevideo refused can be skipped on the next ask.
 *
 * Two things BitChord does that this has to do as well, or most videos resolve and then never play:
 *  - a BotGuard PoToken provider (a hidden WebView): direct video comes from the web / TV clients,
 *    which googlevideo only serves with a token. Without one the catalog is down to Android VR,
 *    whose URLs hand out the first range and then 403 the next.
 *  - a stable visitor id on every request. A `player` call without one gets URLs that answer a
 *    byte and then refuse every real read, which looks exactly like "loads, never plays".
 */
@Singleton
class InnerTubeXVideoSource
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        @Named("youtube") private val okHttp: OkHttpClient,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private var warmup: Job? = null

        init {
            PoTokenWebView.httpClient = okHttp
        }

        private val playerDir: File by lazy { File(context.filesDir, "innertubex_players").apply { mkdirs() } }
        private val configPrefs by lazy { context.getSharedPreferences("innertubex_config", Context.MODE_PRIVATE) }

        private val http =
            HttpClient(OkHttp) {
                engine { preconfigured = okHttp }
                install(ContentNegotiation) {
                    json(
                        Json {
                            ignoreUnknownKeys = true
                            explicitNulls = false
                            encodeDefaults = true
                        },
                    )
                }
                install(HttpTimeout) {
                    requestTimeoutMillis = 30_000
                    connectTimeoutMillis = 15_000
                    socketTimeoutMillis = 20_000
                }
                expectSuccess = false
            }

        private val logger =
            InnerTubeLogger { event ->
                val details =
                    if (event.details.isEmpty()) {
                        ""
                    } else {
                        event.details.entries.joinToString(prefix = " [", postfix = "]") { "${it.key}=${it.value}" }
                    }
                val line = "ITX ${event.tag}: ${event.message}$details"
                when (event.level) {
                    InnerTubeLogLevel.DEBUG -> Log.v(TAG, line)
                    InnerTubeLogLevel.INFO -> Log.d(TAG, line)
                    InnerTubeLogLevel.WARN -> Log.w(TAG, line)
                    InnerTubeLogLevel.ERROR -> Log.e(TAG, line)
                }
            }

        /** The remote player config (cipher hints per player build), remembered between runs. */
        private val repository =
            object : PlayerConfigRepository {
                override val enabled: Boolean = true
                override val sourceUrl: String = PLAYER_CONFIG_URL
                override val defaultSourceUrl: String = PLAYER_CONFIG_URL
                override var cachedJson: String
                    get() = configPrefs.getString("json", "").orEmpty()
                    set(value) = configPrefs.edit().putString("json", value).apply()
                override var cachedAtMs: Long
                    get() = configPrefs.getLong("cached_at_ms", 0L)
                    set(value) = configPrefs.edit().putLong("cached_at_ms", value).apply()
                override var cachedSourceUrl: String
                    get() = configPrefs.getString("source_url", "").orEmpty()
                    set(value) = configPrefs.edit().putString("source_url", value).apply()
                override var cachedEtag: String
                    get() = configPrefs.getString("etag", "").orEmpty()
                    set(value) = configPrefs.edit().putString("etag", value).apply()
            }

        private val innerTube = InnerTube(http, logger = logger)

        private val webViewSupported by lazy { runCatching { CookieManager.getInstance() }.isSuccess }

        /** BotGuard PoTokens minted in a hidden WebView; one page is kept per visitor id. */
        private val poTokens =
            PoTokenGenerator(
                createMinter = { PoTokenWebView.getNewPoTokenGenerator(context) },
                available = { webViewSupported },
            )

        private val visitorLock = Mutex()
        private val remoteStore = RemotePlayerConfigStore(http, repository, logger)
        private val cipherService = YouTubeCipherService(http, remoteStore, logger)
        private val extractor =
            InnerTubeExtractor(
                configParser = YtConfigParserImpl(http, innerTube, remoteStore, logger),
                cipherService = cipherService,
                innerTube = innerTube,
                tokenProvider = poTokens.asTokenProvider(),
                logger = logger,
            )

        /**
         * Pays the cold costs (player config, cipher solver) before the first video is asked for.
         * Safe to call more than once; only the first call does anything.
         */
        fun warmUp() {
            if (warmup != null) return
            warmup =
                scope.launch {
                    delay(WARM_DELAY_MS)
                    runCatching {
                        cipherService.setPreprocessedPlayerCache(::readPlayer, ::writePlayer)
                        applySession()
                        extractor.prewarm()
                    }.onFailure { if (it is CancellationException) throw it }
                        .onFailure { Log.w(TAG, "InnerTubeX warm-up failed: ${it.message}") }
                }
        }

        /** Tells InnerTubeX a client's URL was refused mid-playback, so a stale cipher config is refreshed. */
        fun onVideoRefused() {
            scope.launch { runCatching { cipherService.refreshAfterStreamRejection() } }
        }

        /**
         * The picture for [videoId], at most [maxHeight] tall.
         *
         * [ageRestricted] matters more for video than audio: asked for video, InnerTubeX ranks TV,
         * VR and iOS clients first, several of which can't serve an age-gated video, so the request
         * budget goes on `LOGIN_REQUIRED`s. Saying so drops them. A plain ask that finds nothing is
         * therefore repeated once as age-restricted.
         *
         * @param skipClients profile ids googlevideo already refused for this video.
         * @return null when InnerTubeX found no video-only stream.
         * @throws StreamResolveException when every client was turned away.
         */
        suspend fun resolve(
            videoId: String,
            maxHeight: Int,
            skipClients: Set<String> = emptySet(),
        ): ResolvedVideo? {
            if (videoId !in ageGated) {
                val plain =
                    try {
                        extract(videoId, maxHeight, skipClients, ageRestricted = false)
                    } catch (e: StreamResolveException) {
                        if (e.reason !in ESCALATED_REASONS) throw e
                        null
                    }
                if (plain != null) return plain
                Log.d(TAG, "$videoId: nothing asked plainly; asking as age-restricted")
            }
            return extract(videoId, maxHeight, skipClients, ageRestricted = true)?.also { ageGated += videoId }
        }

        private suspend fun extract(
            videoId: String,
            maxHeight: Int,
            skipClients: Set<String>,
            ageRestricted: Boolean,
        ): ResolvedVideo? =
            withContext(Dispatchers.IO) {
                applySession()
                val stream =
                    extractor.extract(
                        videoId = videoId,
                        hints =
                            ContentHints(
                                isAgeRestricted = true.takeIf { ageRestricted },
                                wantVideo = true,
                                maxVideoHeight = maxHeight,
                            ).withStreamCapabilities(allowHls = false, allowSabr = false, allowBoundedRange = true),
                        excludedClients = skipClients,
                        // The audio this resolves alongside is ignored: the app has its own.
                        audioQuality = AudioQuality.LOW,
                        clientPlaybackNonce = generateClientPlaybackNonce(),
                    ) ?: return@withContext null

                val url = stream.videoUrl?.takeIf { it.isNotBlank() && LIVE_MARKER !in it } ?: return@withContext null
                val height = stream.videoHeight ?: 0
                Log.d(
                    TAG,
                    "$videoId: ${stream.videoWidth ?: 0}x$height ${stream.videoMimeType.orEmpty()} " +
                        "${stream.videoCodecs.orEmpty()} via ${stream.profileId}" +
                        if (ageRestricted) " (age-restricted)" else "",
                )
                ResolvedVideo(
                    url = url,
                    width = stream.videoWidth ?: 0,
                    height = height,
                    mimeType = stream.videoMimeType.orEmpty(),
                    codecs = stream.videoCodecs.orEmpty(),
                    headers = stream.headers,
                    heights =
                        (stream.availableVideoHeights + height)
                            .filter { it > 0 }
                            .distinct()
                            .sortedDescending(),
                    profileId = stream.profileId,
                )
            }

        /** Locale plus the visitor id every request has to carry. */
        private suspend fun applySession() {
            applyLocale()
            innerTube.visitorData = ensureVisitorData()
        }

        /**
         * One visitor id, kept between launches. It is what the PoToken is bound to, so a new id per
         * ask would also mean a new BotGuard page per ask. Refreshed every few days rather than kept
         * forever, since an id can get burned.
         */
        private suspend fun ensureVisitorData(): String? =
            visitorLock.withLock {
                val stored = configPrefs.getString(KEY_VISITOR, null)?.takeIf { it.isNotBlank() }
                val storedAt = configPrefs.getLong(KEY_VISITOR_AT, 0L)
                if (stored != null && System.currentTimeMillis() - storedAt < VISITOR_TTL_MS) return@withLock stored
                val fresh =
                    runCatching { innerTube.fetchFreshVisitorData() }
                        .onFailure { if (it is CancellationException) throw it }
                        .onFailure { Log.w(TAG, "couldn't mint a visitor id: ${it.message}") }
                        .getOrNull()
                        ?.takeIf { it.isNotBlank() }
                if (fresh == null) return@withLock stored
                configPrefs.edit().putString(KEY_VISITOR, fresh).putLong(KEY_VISITOR_AT, System.currentTimeMillis()).apply()
                fresh
            }

        private fun applyLocale() {
            val locale = Locale.getDefault()
            innerTube.locale =
                YouTubeLocale(
                    gl = locale.country.ifBlank { "US" },
                    hl = locale.language.ifBlank { "en" },
                )
        }

        /** EJS-preprocessed players, kept across launches: solving one from scratch is the slow step. */
        private suspend fun readPlayer(key: String): String? =
            withContext(Dispatchers.IO) { File(playerDir, key).takeIf { it.isFile }?.readText() }

        private suspend fun writePlayer(
            key: String,
            value: String?,
        ) = withContext(Dispatchers.IO) {
            val file = File(playerDir, key)
            if (value == null) {
                file.delete()
                return@withContext
            }
            val tmp = File(playerDir, "$key.tmp")
            tmp.writeText(value)
            tmp.renameTo(file)
            // Players rotate every few days; only the newest are worth their megabytes.
            playerDir
                .listFiles()
                ?.filter { !it.name.endsWith(".tmp") }
                ?.sortedByDescending { it.lastModified() }
                ?.drop(KEPT_PLAYERS)
                ?.forEach { it.delete() }
        }

        /** Videos the age gate stopped, asked as age-restricted from the start. */
        private val ageGated: MutableSet<String> = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())

        private companion object {
            const val TAG = "GamutVideo"
            const val PLAYER_CONFIG_URL =
                "https://raw.githubusercontent.com/ZemerTeam/zemer-cipher/master/library/src/main/assets/player_configs.json"
            const val LIVE_MARKER = "yt_live_broadcast"
            const val WARM_DELAY_MS = 2_000L
            const val KEPT_PLAYERS = 3
            const val KEY_VISITOR = "visitor_data"
            const val KEY_VISITOR_AT = "visitor_data_at_ms"
            const val VISITOR_TTL_MS = 3L * 24 * 60 * 60 * 1000

            /** What a plain ask fails with when the age gate is why. */
            val ESCALATED_REASONS =
                setOf(
                    StreamResolveException.Reason.AGE_RESTRICTED,
                    StreamResolveException.Reason.NO_PLAYABLE_STREAM,
                )
        }
    }
