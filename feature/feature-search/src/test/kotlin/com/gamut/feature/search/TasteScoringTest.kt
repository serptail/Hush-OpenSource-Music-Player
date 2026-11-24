package com.gamut.feature.search

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class TasteScoringTest {
    private val day = 86_400_000L

    @Test
    fun aPlayFromNowIsWorthOne() {
        assertEquals(1.0, TasteRepository.decay(0L), 1e-9)
    }

    @Test
    fun aPlayHalvesEveryThreeWeeks() {
        assertEquals(0.5, TasteRepository.decay(21 * day), 1e-9)
        assertEquals(0.25, TasteRepository.decay(42 * day), 1e-9)
    }

    @Test
    fun aClockSetBackCountsAsNow() {
        assertEquals(1.0, TasteRepository.decay(-5 * day), 1e-9)
    }

    @Test
    fun laterPlaysAddLessThanEarlierOnes() {
        val first = TasteRepository.playScore(1.0, followed = true)
        val tenth = TasteRepository.playScore(10.0, followed = true) - TasteRepository.playScore(9.0, followed = true)
        assertTrue(tenth < first)
    }

    @Test
    fun playsAloneNeverReachAFollow() {
        val binge = TasteRepository.playScore(10_000.0, followed = false)
        assertTrue("$binge should stay under a follow's 3.0", binge < 3.0)
    }

    @Test
    fun aFollowedArtistKeepsAddingPlays() {
        assertTrue(TasteRepository.playScore(10_000.0, followed = true) > 3.0)
    }

    @Test
    fun weightedSampleNeverRepeatsAndStopsAtTheList() {
        val ranked = (1..5).toList()
        repeat(50) { seed ->
            val picked = TasteRepository.weightedSample(ranked, 10, Random(seed))
            assertEquals(ranked.toSet(), picked.toSet())
            assertEquals(picked.size, picked.distinct().size)
        }
    }

    @Test
    fun weightedSampleFavoursTheTop() {
        val ranked = (1..10).toList()
        val firstPicks = (0 until 2000).map { TasteRepository.weightedSample(ranked, 1, Random(it)).single() }
        assertTrue(firstPicks.count { it == 1 } > firstPicks.count { it == 10 } * 3)
    }

    @Test
    fun leadNameDropsFeaturedArtists() {
        assertEquals("Drake", TasteRepository.leadName("Drake feat. Rihanna"))
        assertEquals("Drake", TasteRepository.leadName("Drake, Future"))
    }

    @Test
    fun cacheKeepsAnAnswerUntilItExpires() =
        runBlocking {
            var clock = 0L
            val cache = TtlCache<String, Int>(1_000L) { clock }
            var loads = 0
            assertEquals(1, cache.getOrLoad("a") { ++loads })
            clock = 999L
            assertEquals(1, cache.getOrLoad("a") { ++loads })
            clock = 1_000L
            assertEquals(2, cache.getOrLoad("a") { ++loads })
        }

    @Test
    fun cacheDoesNotRememberAFailureButServesTheOldAnswerThroughOne() =
        runBlocking {
            var clock = 0L
            val cache = TtlCache<String, Int>(1_000L) { clock }
            assertNull(cache.getOrLoad("a") { null })
            assertEquals(7, cache.getOrLoad("a") { 7 })
            clock = 5_000L
            assertEquals(7, cache.getOrLoad("a") { null })
        }

    @Test
    fun stationIdsNameTheirKind() {
        assertEquals(StationId.MOOD, StationId.kindOf("station:mood:chill"))
        assertEquals(StationId.MIX, StationId.kindOf("station:mix:1,2,3"))
        assertNull(StationId.kindOf("deezer-album:5"))
    }
}
