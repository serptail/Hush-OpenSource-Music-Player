package com.gamut.feature.resolver.tagging

class AudioTags(
    val title: String,
    val artist: String,
    val album: String? = null,
    val isrc: String? = null,
    val lyrics: String? = null,
    val cover: ByteArray? = null,
    val coverMime: String = "image/jpeg",
)
