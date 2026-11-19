package com.gamut.core.player.audio

import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi

/** How Dolby Atmos is described anywhere the signal path is shown. */
@UnstableApi
object AtmosLabel {
    const val SHORT = "Dolby Atmos"
    const val FULL = "Dolby Atmos (E-AC-3 JOC) — immersive, not lossless"

    fun isAtmos(sampleMimeType: String?): Boolean = sampleMimeType == MimeTypes.AUDIO_E_AC3_JOC
}
