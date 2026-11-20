package com.gamut.feature.resolver.potoken

// Ported from BitChord (https://github.com/kushagrasinghx/BitChord, GPL-3.0): the BotGuard PoToken
// minter that lets InnerTubeX use the web/TV clients for music-video streams.
class PoTokenResult(
    val playerRequestPoToken: String,
    val streamingDataPoToken: String,
)
