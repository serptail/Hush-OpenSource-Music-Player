package com.gamut.feature.resolver.potoken

// Ported from BitChord (https://github.com/kushagrasinghx/BitChord, GPL-3.0): the BotGuard PoToken
// minter that lets InnerTubeX use the web/TV clients for music-video streams.
class PoTokenException(message: String) : Exception(message)

// to be thrown if the WebView provided by the system is broken
class BadWebViewException(message: String) : Exception(message)

fun buildExceptionForJsError(error: String): Exception {
    return if (error.contains("SyntaxError"))
        BadWebViewException(error)
    else
        PoTokenException(error)
}
