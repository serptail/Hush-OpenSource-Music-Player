package com.gamut.feature.resolver

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.net.ConnectivityManager
import android.os.Build
import android.util.Log
import com.gamut.core.model.Track
import com.gamut.core.network.YoutubeCandidate
import com.gamut.core.network.isValidYoutubeVideoId
import com.gamut.core.player.VideoStream
import com.gamut.core.player.VideoStreamProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.log10

/**
 * Resolves the best music-video rendition for a track: finds the official video, then picks the
 * highest-quality video-only stream this device can actually decode.
 *
 * Playback follows the app's audio clock, so a video whose length differs from the track is
 * rejected rather than played out of sync.
 */
@Singleton
class VideoStreamRepository
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val newPipe: NewPipeYoutubeSource,
        private val innerTubeX: InnerTubeXVideoSource,
        private val scorer: CandidateScorer,
    ) : VideoStreamProvider {
        private val cache = ConcurrentHashMap<String, Cached>()
        private val locks = ConcurrentHashMap<String, Mutex>()

        /** Clients googlevideo turned away, by video: the next resolve skips them, as BitChord's player does. */
        private val refused = ConcurrentHashMap<String, MutableSet<String>>()

        init {
            innerTubeX.warmUp()
        }

        private data class Cached(
            val stream: VideoStream?,
            val expiresAtMs: Long,
        )

        override suspend fun resolve(
            track: Track,
            forceRefresh: Boolean,
        ): VideoStream? {
            val key = track.isrc.ifBlank { "${track.title}|${track.artist}" }
            if (!forceRefresh) cache[key]?.takeIf { it.expiresAtMs > now() }?.let { return it.stream }
            return locks.getOrPut(key) { Mutex() }.withLock {
                if (!forceRefresh) cache[key]?.takeIf { it.expiresAtMs > now() }?.let { return@withLock it.stream }
                val cachedStream = cache[key]?.stream
                val cachedId = cachedStream?.videoId
                // A forced refresh means the stream just failed. Remember which client minted it, so the
                // next ask goes to a different one instead of getting the same refused URL back.
                val failedProfile = cachedStream?.profileId
                if (forceRefresh && cachedStream != null && failedProfile != null) {
                    if (refused.size > MAX_REMEMBERED_REFUSALS) refused.clear()
                    refused.getOrPut(cachedStream.videoId) { ConcurrentHashMap.newKeySet<String>() }.add(failedProfile)
                    innerTubeX.onVideoRefused()
                }
                val result =
                    try {
                        withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
                            find(track, reuseVideoId = if (forceRefresh) cachedId else null, safe = forceRefresh)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "resolve failed for ${track.artist} - ${track.title}: ${e.message}")
                        null
                    }
                if (result == null) Log.w(TAG, "no video for ${track.artist} - ${track.title}")
                // A timeout or network error is not "this track has no video": don't remember it for long.
                val ttl = if (result != null) (result.expiresAtMs - now()).coerceAtLeast(60_000L) else NEGATIVE_TTL_MS
                cache[key] = Cached(result, now() + ttl)
                result
            }
        }

        /**
         * [safe] is the retry after a failure: H.264 up to 1080p only, which every phone decodes,
         * because above that YouTube serves VP9/AV1 and the failure was likely the decoder.
         */
        private suspend fun find(
            track: Track,
            reuseVideoId: String?,
            safe: Boolean,
        ): VideoStream? {
            val ids = if (reuseVideoId != null) listOf(reuseVideoId) else candidateIds(track)
            Log.d(TAG, "trying ${ids.size} video id(s) for ${track.artist} - ${track.title}: $ids")
            for (videoId in ids.take(MAX_ATTEMPTS)) {
                val stream = viaInnerTubeX(videoId, safe) ?: viaNewPipe(videoId, safe)
                if (stream != null) return stream
            }
            return null
        }

        /**
         * The primary path: InnerTubeX tries its client catalog in order, with the clients googlevideo
         * already refused for [videoId] skipped. Null when it found nothing usable, which sends the
         * caller to [viaNewPipe].
         */
        private suspend fun viaInnerTubeX(
            videoId: String,
            safe: Boolean,
        ): VideoStream? {
            val ceiling = if (safe) SAFE_MAX_HEIGHT else if (isMetered()) METERED_MAX_HEIGHT else AUTO_MAX_HEIGHT
            val video =
                try {
                    innerTubeX.resolve(videoId, ceiling, refused[videoId].orEmpty().toSet())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "$videoId: InnerTubeX failed: ${e.message}")
                    null
                } ?: return null
            val aspect = if (video.height > 0) video.width.toFloat() / video.height else 0f
            if (aspect < MIN_VIDEO_ASPECT) {
                Log.w(TAG, "$videoId: InnerTubeX ${video.width}x${video.height} is a still image, skipping")
                return null
            }
            // Above 1080p YouTube serves VP9/AV1, which not every phone decodes. An unrecognised codec
            // string is let through; the player's own failure path steps down from there.
            val option = VideoOption(video.url, video.width, video.height, 0, video.codecs, 0)
            if (mimeOf(video.codecs) != null && !canDecode(option)) {
                Log.w(TAG, "$videoId: InnerTubeX ${video.height}p ${video.codecs} not decodable here")
                refused.getOrPut(videoId) { ConcurrentHashMap.newKeySet<String>() }.add(video.profileId)
                return null
            }
            Log.d(TAG, "$videoId: using ${video.width}x${video.height} ${video.codecs} via InnerTubeX ${video.profileId}")
            return VideoStream(
                videoId = videoId,
                url = video.url,
                width = video.width,
                height = video.height,
                fps = 0,
                codec = video.codecs.ifBlank { null },
                expiresAtMs = expiryOf(video.url),
                headers = video.headers,
                profileId = video.profileId,
            )
        }

        /** The fallback: NewPipe's progressive video-only renditions, filtered to what this device decodes. */
        private suspend fun viaNewPipe(
            videoId: String,
            safe: Boolean,
        ): VideoStream? {
            val options =
                newPipe.videoOnlyStreams(videoId).getOrElse {
                    Log.w(TAG, "$videoId: stream extraction failed: ${it.message}")
                    emptyList()
                }
            Log.d(TAG, "$videoId: ${options.size} video-only streams: " + options.joinToString { "${it.height}p/${it.codec.take(4)}" })
            val best = pick(options, safe)
            if (best == null) {
                Log.w(TAG, "$videoId: nothing decodable here")
                return null
            }
            // A catalogue "video" is its cover held still (square, or a still on black): nothing to show.
            val aspect = if (best.height > 0) best.width.toFloat() / best.height else 0f
            if (aspect < MIN_VIDEO_ASPECT) {
                Log.w(TAG, "$videoId: ${best.width}x${best.height} is a still image, skipping")
                return null
            }
            Log.d(
                TAG,
                "$videoId: using ${best.width}x${best.height} ${best.fps}fps ${best.codec} via NewPipe " +
                    "client=${Regex("[?&]c=([^&]+)").find(best.url)?.groupValues?.get(1)} " +
                    "clen=${Regex("[?&]clen=(\\d+)").find(best.url)?.groupValues?.get(1)}",
            )
            return VideoStream(
                videoId = videoId,
                url = best.url,
                width = best.width,
                height = best.height,
                fps = best.fps,
                codec = best.codec.ifBlank { null },
                expiresAtMs = expiryOf(best.url),
                headers = headersFor(best.url),
            )
        }

        /**
         * Which YouTube videos might be this track's picture, best first.
         *
         * The track's own source goes first: when it was resolved to the official upload, picture and
         * audio are the same file and stay in sync by construction. Search results follow, kept only
         * when their length is close enough to the track's that the audio clock still lines up.
         */
        private suspend fun candidateIds(track: Track): List<String> {
            val ids = LinkedHashSet<String>()
            track.youtubeVideoId?.takeIf(::isValidYoutubeVideoId)?.let { ids += it }
            val candidates = LinkedHashMap<String, YoutubeCandidate>()
            // Every source is asked, always: stopping after the first query that returned a few rows
            // meant the "official video" search never ran and only lyric uploads were ever ranked.
            val query = "${track.artist} ${track.title}"
            val found =
                coroutineScope {
                    listOf(
                        async { newPipe.searchMusicVideos(query) },
                        async { newPipe.searchMusicVideos("$query official video") },
                        async { newPipe.searchVideos("$query official video") },
                    ).awaitAll()
                }
            found.forEach { list -> list.forEach { candidates.putIfAbsent(it.videoId, it) } }
            val ranked =
                scorer
                    .rank(candidates.values.toList(), track)
                    .sortedByDescending { it.score.toDouble() + videoBias(it.candidate, track) }
            ranked.take(5).forEach {
                Log.d(TAG, "candidate ${it.candidate.videoId} \"${it.candidate.title}\" score=${"%.2f".format(it.score)} " +
                    "dur=${it.candidate.durationMs / 1000}s (track ${track.durationMs / 1000}s) views=${it.candidate.viewCount}")
            }
            ranked
                .filter { it.score >= CandidateScorer.LOW_CONFIDENCE && staysInSync(it.candidate, track) }
                .forEach { ids += it.candidate.videoId }
            return ids.toList()
        }

        /**
         * What the title scorer can't see: the official video is the one with a real picture, from the
         * artist's own (or a VEVO) channel, with the views to show it. Lyric cards, covers, sheet music
         * and edits are text on a still or somebody else's take.
         */
        private fun videoBias(
            c: YoutubeCandidate,
            track: Track,
        ): Double {
            val title = c.title.lowercase()
            val channel = c.channelName.lowercase()
            var bias = 0.0
            if (JUNK_TITLE_WORDS.any { it in title }) bias -= 0.6
            if ("official" in title && "video" in title) bias += 0.4
            if (channel.endsWith("vevo")) bias += 0.3
            val artist = track.artist.lowercase().split(',', '&', ';').first().trim()
            if (artist.isNotBlank() && artist in channel) bias += 0.3
            bias += log10(c.viewCount.toDouble().coerceAtLeast(1.0)) / 20.0
            return bias
        }

        private fun staysInSync(
            candidate: YoutubeCandidate,
            track: Track,
        ): Boolean {
            if (candidate.durationMs <= 0L) return false
            // Track length unknown (e.g. not resolved yet): nothing to compare against, so trust the scorer.
            if (track.durationMs <= 0L) return true
            return abs(candidate.durationMs - track.durationMs) <= MAX_DURATION_DRIFT_MS
        }

        /** The headers googlevideo expects for [url]: the agent of the client that minted it. */
        private fun headersFor(url: String): Map<String, String> {
            val client = Regex("[?&]c=([^&]+)").find(url)?.groupValues?.get(1)?.uppercase().orEmpty()
            return runCatching {
                val locale = NewPipe.getPreferredLocalization()
                val agent =
                    when {
                        client.startsWith("ANDROID") -> ANDROID_VR_USER_AGENT
                        client == "VISIONOS" -> YoutubeParsingHelper.getVisionOsUserAgent(locale)
                        client.startsWith("TVHTML5") -> TV_USER_AGENT
                        client.startsWith("WEB") || client == "MWEB" -> WEB_USER_AGENT
                        else -> WEB_USER_AGENT
                    }
                buildMap {
                    put("User-Agent", agent)
                    if (client.startsWith("WEB") || client == "MWEB" || client.startsWith("TVHTML5")) {
                        put("Origin", "https://www.youtube.com")
                        put("Referer", "https://www.youtube.com/")
                    }
                }
            }.getOrElse {
                Log.w(TAG, "couldn't build headers for client $client: ${it.message}")
                emptyMap()
            }
        }

        /** Highest resolution this device can decode, capped at 1080p (480p on metered data): above that YouTube serves VP9/AV1 and phones stall. */
        private fun pick(
            options: List<VideoOption>,
            safe: Boolean,
        ): VideoOption? {
            val ceiling = if (safe) SAFE_MAX_HEIGHT else if (isMetered()) METERED_MAX_HEIGHT else AUTO_MAX_HEIGHT
            val decodable =
                options.filter { canDecode(it) && (!safe || mimeOf(it.codec) == "video/avc") }
            val pool = decodable.filter { it.height <= ceiling }.ifEmpty { decodable }
            return pool.maxWithOrNull(
                compareBy<VideoOption> { it.height }
                    .thenBy { it.fps.coerceAtLeast(0) }
                    // Equal quality: H.264 is the safest to decode smoothly, then VP9, then AV1.
                    .thenBy { codecPreference(it.codec) }
                    .thenBy { it.bitrate },
            )
        }

        private fun codecPreference(codec: String): Int =
            when (mimeOf(codec)) {
                "video/avc" -> 3
                "video/x-vnd.on2.vp9" -> 2
                "video/hevc" -> 1
                else -> 0
            }

        private fun canDecode(option: VideoOption): Boolean {
            val mime = mimeOf(option.codec) ?: return false
            if (option.width <= 0 || option.height <= 0) return false
            val fps = option.fps.coerceAtLeast(1)
            return decoderInfos.any { info ->
                if (!info.supportedTypes.any { it.equals(mime, ignoreCase = true) }) return@any false
                val caps = runCatching { info.getCapabilitiesForType(mime).videoCapabilities }.getOrNull() ?: return@any false
                caps.areSizeAndRateSupported(option.width, option.height, fps.toDouble()) &&
                    // Software decoders can't keep up with high resolutions in real time.
                    (isHardware(info) || option.height <= SOFTWARE_MAX_HEIGHT)
            }
        }

        private fun isHardware(info: MediaCodecInfo): Boolean =
            if (Build.VERSION.SDK_INT >= 29) {
                info.isHardwareAccelerated
            } else {
                !info.name.startsWith("OMX.google.") && !info.name.startsWith("c2.android.")
            }

        private val decoderInfos: List<MediaCodecInfo> by lazy {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder }
        }

        private fun mimeOf(codec: String): String? {
            val c = codec.lowercase()
            return when {
                c.startsWith("avc1") || c.startsWith("avc3") -> "video/avc"
                c.startsWith("vp09") || c.startsWith("vp9") -> "video/x-vnd.on2.vp9"
                c.startsWith("av01") -> "video/av01"
                c.startsWith("hev1") || c.startsWith("hvc1") -> "video/hevc"
                else -> null
            }
        }

        private fun isMetered(): Boolean =
            runCatching {
                context.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered
            }.getOrDefault(false)

        private fun expiryOf(url: String): Long =
            Regex("[?&]expire=(\\d+)")
                .find(url)
                ?.groupValues
                ?.get(1)
                ?.toLongOrNull()
                ?.times(1_000L)
                ?.minus(120_000L)
                ?: (now() + 20 * 60_000L)

        private fun now() = System.currentTimeMillis()

        private companion object {
            const val TAG = "GamutVideo"
            const val MAX_ATTEMPTS = 3
            const val MAX_REMEMBERED_REFUSALS = 64
            const val MIN_VIDEO_ASPECT = 1.2f
            const val SAFE_MAX_HEIGHT = 1080
            const val TV_USER_AGENT =
                "Mozilla/5.0(SMART-TV; Linux; Tizen 4.0.0.2) AppleWebkit/605.1.15 (KHTML, like Gecko) SamsungBrowser/9.2 TV Safari/605.1.15"
            const val WEB_USER_AGENT =
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
            const val ANDROID_VR_USER_AGENT =
                "com.google.android.apps.youtube.vr.oculus/1.62.27 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"
            const val RESOLVE_TIMEOUT_MS = 25_000L
            const val NEGATIVE_TTL_MS = 5 * 60_000L
            val JUNK_TITLE_WORDS =
                listOf(
                    "lyric", "sheet music", "karaoke", "cover", "slowed", "reverb", "sped up", "speed up",
                    "8d", "nightcore", "instrumental", "acapella", "a cappella", "tutorial", "piano",
                    "reaction", "remix", "rock version",
                )
            const val MAX_DURATION_DRIFT_MS = 8_000L
            const val AUTO_MAX_HEIGHT = 1080
            const val METERED_MAX_HEIGHT = 480
            const val SOFTWARE_MAX_HEIGHT = 720
        }
    }