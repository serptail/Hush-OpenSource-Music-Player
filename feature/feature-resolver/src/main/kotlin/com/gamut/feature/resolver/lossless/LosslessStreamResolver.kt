package com.gamut.feature.resolver.lossless

import android.util.Log
import com.gamut.core.model.Track
import com.gamut.core.player.audio.DeviceCodecs
import com.gamut.core.player.audio.LosslessOutput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.text.Normalizer
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Finds a better stream than YouTube's for a track, in the sources the listener added: addon servers,
 * module indexes and JioSaavn, tried in the order they are listed.
 *
 * A stream is taken only when it is clearly better: lossless (when the output can carry it and the switch is
 * on), a Dolby Atmos rendition (when this device and Settings both want one), or a lossy stream at 256 kbps
 * or more, which beats YouTube's Opus at about 160. Anything else, and every failure, returns null and the
 * caller falls back to YouTube. Each time this returns null the reason is logged under the `Gamut` tag.
 */
@Singleton
class LosslessStreamResolver
    @Inject
    constructor(
        private val settings: LosslessSettings,
    ) {
        private val sources = java.util.concurrent.ConcurrentHashMap<String, MusicSource>()

        private class Hit(
            val url: String?,
            val at: Long,
        )

        private val cache = java.util.concurrent.ConcurrentHashMap<String, Hit>()

        /**
         * The stream URL, or null for "use YouTube". Answers are remembered (20 minutes for a hit, 5 for a miss)
         * so a replay, a prefetch and the in-playback upgrade do not repeat the same lookups.
         *
         * [fastOnly] asks only the sources that answer in about a second (JioSaavn), for the moment a track
         * starts; slow sources are left to [urlFor] with it off, which runs while the track already plays.
         */
        suspend fun urlFor(
            track: Track,
            fastOnly: Boolean = false,
        ): String? {
            val state = settings.state.value
            val key = "${track.isrc}|${state.sources.hashCode()}|${state.preferLossless}|${LosslessOutput.capable}|${DeviceCodecs.atmosWanted}|$fastOnly"
            val now = System.currentTimeMillis()
            cache[key]?.let { hit -> if (now - hit.at < (if (hit.url != null) HIT_TTL_MS else MISS_TTL_MS)) return hit.url }
            val result = lookup(track, state, fastOnly)
            if (!fastOnly || result != null) cache[key] = Hit(result, System.currentTimeMillis())
            return result
        }

        private suspend fun lookup(
            track: Track,
            state: LosslessSettings.State,
            fastOnly: Boolean,
        ): String? {
            if (state.sources.isEmpty()) {
                if (!fastOnly) skip(track, "no sources added (Settings > Playback > Sources)")
                return null
            }
            val wantLossless = state.preferLossless && LosslessOutput.capable
            val atmos = DeviceCodecs.atmosWanted
            if (state.preferLossless && !LosslessOutput.capable) {
                Log.i(TAG, "lossless requested but the current output cannot carry it; looking for lossy upgrades only")
            }
            val request = if (wantLossless) StreamRequest.Lossless else StreamRequest.Best
            val query = "${track.artist} ${track.title}".trim()
            for (config in state.sources) {
                if (fastOnly && config.kind != SourceKind.JIOSAAVN) continue
                val source = sources.getOrPut(config.id + "|" + config.baseUrl) { build(config) }
                try {
                    val stream =
                        withTimeoutOrNull(if (fastOnly) FAST_BUDGET_MS else PER_SOURCE_BUDGET_MS) {
                            val rows = source.search(query, limit = 12, request = request)
                            if (rows.isEmpty()) {
                                Log.i(TAG, "${config.displayName}: no results for \"$query\"")
                                return@withTimeoutOrNull null
                            }
                            val match = rows.firstOrNull { sameRecording(it, track) }
                            if (match == null) {
                                Log.i(TAG, "${config.displayName}: ${rows.size} results, none the same recording as \"${track.title}\"")
                                return@withTimeoutOrNull null
                            }
                            source.stream(match.id, request)
                        }
                    if (stream == null) {
                        Log.i(TAG, "${config.displayName}: no stream (timed out, or the source has no usable file)")
                        continue
                    }
                    val f = stream.format
                    val verdict =
                        when {
                            f.isLossless && wantLossless -> "lossless"
                            f.isDolbyAtmos && atmos -> "Dolby Atmos"
                            f.isDolbyAtmos -> {
                                Log.i(TAG, "${config.displayName}: Atmos rendition ignored (${DeviceCodecs.atmosRefusal()})")
                                null
                            }
                            f.isLossless -> {
                                Log.i(TAG, "${config.displayName}: lossless ignored (switch off or output not capable)")
                                null
                            }
                            (f.kbps ?: 0) >= MIN_LOSSY_UPGRADE_KBPS -> "${f.kbps} kbps"
                            else -> {
                                Log.i(TAG, "${config.displayName}: ${f.summary} is not better than YouTube — skipping")
                                null
                            }
                        }
                    if (verdict != null) {
                        Log.i(TAG, "$verdict from ${config.displayName}: ${f.summary}")
                        return stream.url
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "${config.displayName}: ${e.message}")
                }
            }
            if (!fastOnly) skip(track, "no source had a better stream")
            return null
        }

        private fun skip(
            track: Track,
            why: String,
        ) = Log.i(TAG, "using YouTube for \"${track.title}\": $why")

        private fun build(config: SourceConfig): MusicSource =
            when (config.kind) {
                SourceKind.ADDON -> AddonSource(config)
                SourceKind.MODULE -> ModuleSource(config)
                SourceKind.JIOSAAVN -> JioSaavnSource(config)
            }

        internal companion object {
            private const val TAG = "Gamut"
            private const val PER_SOURCE_BUDGET_MS = 12_000L
            private const val FAST_BUDGET_MS = 2_500L
            private const val HIT_TTL_MS = 20 * 60_000L
            private const val MISS_TTL_MS = 5 * 60_000L
            private const val DURATION_TOLERANCE_S = 4
            private const val MIN_LOSSY_UPGRADE_KBPS = 256

            internal fun sameRecording(
                row: LosslessTrack,
                track: Track,
            ): Boolean {
                val title = fold(track.title)
                val rowTitle = fold(row.title)
                if (title.isEmpty() || rowTitle.isEmpty()) return false
                if (rowTitle != title) return false
                val artists = tokens(track.artist)
                val rowArtists = tokens(row.artist)
                if (artists.isEmpty() || artists.none { it in rowArtists }) return false
                val wanted = (track.durationMs / 1000).toInt()
                val got = row.durationSec
                return wanted <= 0 || got == null || abs(wanted - got) <= DURATION_TOLERANCE_S
            }

            private fun fold(text: String): String =
                Normalizer
                    .normalize(text, Normalizer.Form.NFD)
                    .replace(Regex("\\p{M}+"), "")
                    .lowercase(Locale.ROOT)
                    .replace(Regex("[^a-z0-9]+"), " ")
                    .trim()

            private fun tokens(text: String): Set<String> = fold(text).split(' ').filter { it.length > 1 }.toSet()
        }
    }
