package com.gamut.core.model

object LocalCovers {
    const val DIR = "covers"

    fun fileName(isrc: String): String = isrc.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".jpg"
}
