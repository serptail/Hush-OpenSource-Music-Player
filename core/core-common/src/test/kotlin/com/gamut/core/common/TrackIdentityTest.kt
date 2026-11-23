package com.gamut.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackIdentityTest {
    private fun same(
        a: Triple<String, String, Long>,
        b: Triple<String, String, Long>,
    ) = TrackIdentity.sameRecording(a.first, a.second, a.third, b.first, b.second, b.third)

    @Test
    fun packagingInTitleAndExtraSingersDoNotSplitARecording() {
        assertTrue(
            same(
                Triple("Paniyon Sa (From \"Satyamev Jayate\")", "Atif Aslam", 245_000),
                Triple("Paniyon Sa", "Atif Aslam, Tulsi Kumar", 246_000),
            ),
        )
    }

    @Test
    fun liveAndStudioAreDifferentRecordings() {
        assertFalse(same(Triple("Song (Live)", "Artist", 200_000), Triple("Song", "Artist", 200_000)))
    }

    @Test
    fun remixAndOriginalAreDifferentRecordings() {
        assertFalse(same(Triple("Halo - Remix", "Beyonce", 200_000), Triple("Halo", "Beyonce", 200_000)))
    }

    @Test
    fun rerecordedVersionIsNotTheOriginal() {
        assertFalse(same(Triple("Love Story (Taylor's Version)", "Taylor Swift", 235_000), Triple("Love Story", "Taylor Swift", 235_000)))
    }

    @Test
    fun remasterTagIsOnlyPackaging() {
        assertTrue(same(Triple("Song - Remastered 2011", "Artist", 0), Triple("Song", "Artist", 0)))
    }

    @Test
    fun youtubeArtistPrefixAndOfficialTagAreStripped() {
        assertTrue(same(Triple("Beyonc\u00E9 - Halo (Official Video)", "Beyonc\u00E9", 261_000), Triple("Halo", "Beyonc\u00E9", 261_000)))
        assertEquals("Halo (Official Video)", TrackIdentity.displayTitle("Beyonc\u00E9 - Halo (Official Video)", "Beyonc\u00E9"))
    }

    @Test
    fun differentArtistsSameTitleStaySeparate() {
        assertFalse(same(Triple("Halo", "Beyonce", 261_000), Triple("Halo", "Haim", 261_000)))
    }

    @Test
    fun farApartRuntimesAreNotTheSameRecording() {
        assertFalse(same(Triple("Song", "Artist", 200_000), Triple("Song", "Artist", 3_600_000)))
    }

    @Test
    fun topicChannelCountsAsTheArtist() {
        assertTrue(same(Triple("Song", "Artist - Topic", 200_000), Triple("Song", "Artist", 201_000)))
    }

    @Test
    fun nonLatinTitlesStillMatch() {
        assertTrue(same(Triple("\u5922\u306E\u4E2D\u3078", "\u9670\u967D", 0), Triple("\u5922\u306E\u4E2D\u3078", "\u9670\u967D", 0)))
    }
}
