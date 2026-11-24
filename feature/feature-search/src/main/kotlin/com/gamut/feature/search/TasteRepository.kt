package com.gamut.feature.search

import com.gamut.core.database.FollowDao
import com.gamut.core.database.RecentDao
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/** One artist the listener leans toward, with how strongly. [followed] artists always outrank play history alone. */
data class TasteArtist(
    val key: String,
    val name: String,
    val score: Double,
    val followed: Boolean,
)

/**
 * What the app knows about the listener's taste, from its own data only: the artists they follow and what they
 * have played lately.
 *
 * A follow is worth more than any amount of play history, and newer follows more than older ones. A play is
 * worth less the longer ago it was (it halves every [PLAY_HALF_LIFE_DAYS] days), and plays of one artist add up
 * with diminishing returns, so ten tracks outrank one but a single binge does not bury everything else.
 */
@Singleton
class TasteRepository
    @Inject
    constructor(
        private val follows: FollowDao,
        private val recents: RecentDao,
    ) {
        @Volatile private var cache: List<TasteArtist>? = null

        fun clear() {
            cache = null
        }

        suspend fun artists(): List<TasteArtist> {
            cache?.let { return it }
            val built =
                try {
                    build()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    return emptyList()
                }
            cache = built
            return built
        }

        private suspend fun build(now: Long = System.currentTimeMillis()): List<TasteArtist> {
            val follow = HashMap<String, Double>()
            val played = HashMap<String, Double>()
            val names = HashMap<String, String>()

            val followList = follows.all().sortedByDescending { it.followedAtEpochMs }
            followList.forEachIndexed { index, f ->
                val key = artistKey(f.name)
                if (key.isBlank()) return@forEachIndexed
                val recency = 1.0 - index.toDouble() / followList.size.coerceAtLeast(1)
                follow.merge(key, FOLLOW_WEIGHT + recency, Double::plus)
                names.putIfAbsent(key, f.name)
            }

            recents.plays(PLAY_SCAN).forEach { play ->
                val name = leadName(play.track.artist)
                val key = artistKey(name)
                if (key.isBlank() || name.equals("Various Artists", ignoreCase = true)) return@forEach
                played.merge(key, decay(now - play.playedAtEpochMs), Double::plus)
                names.putIfAbsent(key, name)
            }

            return (follow.keys + played.keys)
                .map { key -> key to (follow[key] ?: 0.0) + playScore(played[key] ?: 0.0, followed = key in follow) }
                .sortedByDescending { it.second }
                .take(MAX_ARTISTS)
                .map { (key, score) -> TasteArtist(key, names.getValue(key), score, key in follow) }
        }

        companion object {
            private const val FOLLOW_WEIGHT = 3.0
            private const val PLAY_SCAN = 200
            private const val MAX_ARTISTS = 24
            private const val PLAY_HALF_LIFE_DAYS = 21.0
            private const val PLAY_GAIN = 1.6
            private const val MS_PER_DAY = 86_400_000.0

            /** Play history alone never reaches a follow's worth, so a followed artist always ranks above it. */
            private const val PLAY_CAP = FOLLOW_WEIGHT - 0.1

            /** One play's worth after [ageMs]: 1 today, 0.5 after the half-life, and so on. A clock set back counts as now. */
            internal fun decay(ageMs: Long): Double = 0.5.pow(ageMs.coerceAtLeast(0L) / MS_PER_DAY / PLAY_HALF_LIFE_DAYS)

            /**
             * [weight], the decayed plays of an artist added up, as a score: logarithmic, so the tenth play counts for
             * much less than the first. Capped below a follow unless the artist is [followed], where it adds on top.
             */
            internal fun playScore(
                weight: Double,
                followed: Boolean,
            ): Double {
                val raw = PLAY_GAIN * ln(1.0 + weight)
                return if (followed) raw else min(raw, PLAY_CAP)
            }

            private val LEAD_SPLIT = Regex("(?i)\\s+(featuring|feat\\.?|ft\\.?|with|x|&|and)\\s+|,|/")

            internal fun leadName(artist: String): String = artist.split(LEAD_SPLIT).first().trim()

            /**
             * [count] of [ranked] chosen at random, the higher an artist sits the likelier. The top of the list turns up
             * most of the time and the rest often enough that what is shown changes from one visit to the next.
             */
            fun <T> weightedSample(
                ranked: List<T>,
                count: Int,
                random: Random,
            ): List<T> {
                val pool = ranked.withIndex().toMutableList()
                val picked = ArrayList<T>()
                while (picked.size < count && pool.isNotEmpty()) {
                    val weights = pool.map { (ranked.size - it.index).toDouble() }
                    var roll = random.nextDouble() * weights.sum()
                    var chosen = 0
                    for (i in weights.indices) {
                        roll -= weights[i]
                        if (roll <= 0) {
                            chosen = i
                            break
                        }
                    }
                    picked += pool.removeAt(chosen).value
                }
                return picked
            }
        }
    }
