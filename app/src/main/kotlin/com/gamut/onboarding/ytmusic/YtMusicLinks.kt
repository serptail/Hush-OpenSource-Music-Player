package com.gamut.onboarding.ytmusic

/**
 * Importing straight from YouTube Music playlist links: finds the playlist ids in whatever text was pasted. No
 * account is involved. [YtMusicLibraryReader.readPlaylist] reads public and unlisted playlists anonymously, the way
 * a signed-out visitor to music.youtube.com sees them. Private playlists can't be read this way.
 */
object YtMusicLinks {
    private val URL = Regex("""https?://(?:[\w-]+\.)?(?:youtube\.com|youtu\.be)/[^\s<>"']+""", RegexOption.IGNORE_CASE)

    /** `?list=PL…` or `&list=PL…`, which covers /playlist, /watch and share links alike. */
    private val LIST_PARAM = Regex("""[?&]list=([A-Za-z0-9_-]{10,})""")

    /** The `/browse/VL<id>` form some share links use. */
    private val BROWSE_PATH = Regex("""/browse/VL([A-Za-z0-9_-]{10,})""")

    /** Every playlist id in [text], in order, without repeats. */
    fun parse(text: String): List<String> {
        val found = LinkedHashSet<String>()
        URL.findAll(text).forEach { match ->
            val url = match.value
            val id = LIST_PARAM.find(url)?.groupValues?.get(1) ?: BROWSE_PATH.find(url)?.groupValues?.get(1)
            if (id != null && isReadable(id)) found += id
        }
        return found.toList()
    }

    /**
     * Radio mixes ("RD\u2026") are generated per listener and have no page to read. YouTube Music's own curated
     * "RDCLAK\u2026" playlists do.
     */
    private fun isReadable(id: String): Boolean = !id.startsWith("RD") || id.startsWith("RDCLAK")
}
