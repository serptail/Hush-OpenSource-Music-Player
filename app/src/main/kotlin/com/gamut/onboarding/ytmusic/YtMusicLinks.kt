package com.gamut.onboarding.ytmusic

object YtMusicLinks {
    private val URL = Regex("""https?://(?:[\w-]+\.)?(?:youtube\.com|youtu\.be)/[^\s<>"']+""", RegexOption.IGNORE_CASE)

    private val LIST_PARAM = Regex("""[?&]list=([A-Za-z0-9_-]{10,})""")

    private val BROWSE_PATH = Regex("""/browse/VL([A-Za-z0-9_-]{10,})""")

    fun parse(text: String): List<String> {
        val found = LinkedHashSet<String>()
        URL.findAll(text).forEach { match ->
            val url = match.value
            val id = LIST_PARAM.find(url)?.groupValues?.get(1) ?: BROWSE_PATH.find(url)?.groupValues?.get(1)
            if (id != null && isReadable(id)) found += id
        }
        return found.toList()
    }

    private fun isReadable(id: String): Boolean = !id.startsWith("RD") || id.startsWith("RDCLAK")
}
