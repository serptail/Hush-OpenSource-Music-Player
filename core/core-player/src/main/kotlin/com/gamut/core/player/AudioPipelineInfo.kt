package com.gamut.core.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.Locale

@OptIn(UnstableApi::class)
object AudioPipelineInfo {
    private val _state = MutableStateFlow(SignalPath())
    val state: StateFlow<SignalPath> = _state.asStateFlow()

    private val _details = MutableStateFlow(PipelineDetails())
    val details: StateFlow<PipelineDetails> = _details.asStateFlow()

    /** True while a stats panel is open, so the player only polls the buffer when somebody is looking. */
    @Volatile var watching: Boolean = false

    private val losslessMimes =
        setOf(MimeTypes.AUDIO_FLAC, MimeTypes.AUDIO_ALAC, MimeTypes.AUDIO_RAW, MimeTypes.AUDIO_TRUEHD)

    internal fun onSource(format: Format?) {
        if (format == null) {
            _state.update { it.copy(codec = "", bitrateKbps = 0, sampleRateHz = 0, bitDepth = 0, channels = 0, lossless = false) }
            _details.update { it.copy(container = "", atmos = false) }
            return
        }
        val mime = format.sampleMimeType.orEmpty()
        _details.update {
            it.copy(
                container = containerLabel(format.containerMimeType.orEmpty()),
                atmos = mime == MimeTypes.AUDIO_E_AC3_JOC,
            )
        }
        val bitrate =
            listOf(format.averageBitrate, format.bitrate, format.peakBitrate)
                .firstOrNull { it != Format.NO_VALUE && it > 0 }
                ?.let { (it + 500) / 1000 } ?: 0
        val depth =
            when (format.pcmEncoding) {
                C.ENCODING_PCM_8BIT -> 8
                C.ENCODING_PCM_16BIT -> 16
                C.ENCODING_PCM_24BIT -> 24
                C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 32
                else -> 0
            }
        _state.update {
            it.copy(
                codec = codecLabel(mime),
                bitrateKbps = bitrate,
                sampleRateHz = format.sampleRate.takeIf { r -> r != Format.NO_VALUE } ?: 0,
                bitDepth = depth,
                channels = format.channelCount.takeIf { c -> c != Format.NO_VALUE } ?: 0,
                lossless = mime in losslessMimes,
            )
        }
    }

    internal fun onOrigin(
        origin: String,
        qualitySetting: String,
        qualityEffective: String,
    ) {
        _details.update { it.copy(origin = origin, qualitySetting = qualitySetting, qualityEffective = qualityEffective) }
    }

    internal fun onUpgraded(from: String) {
        _details.update { it.copy(upgradedFrom = from) }
    }

    internal fun onDecoder(name: String) {
        _details.update { it.copy(decoder = name) }
    }

    /** The PCM the decoder hands to the sound engine, reported every time the output configures. */
    internal fun onPcm(
        rateHz: Int,
        channels: Int,
        encoding: Int,
    ) {
        val bits =
            when (encoding) {
                C.ENCODING_PCM_8BIT -> 8
                C.ENCODING_PCM_16BIT -> 16
                C.ENCODING_PCM_24BIT -> 24
                C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 32
                else -> 0
            }
        _details.update {
            it.copy(pcmRateHz = rateHz, pcmChannels = channels, pcmBits = bits, pcmFloat = encoding == C.ENCODING_PCM_FLOAT)
        }
    }

    internal fun onDsp(dsp: DspState) {
        _details.update { it.copy(dsp = dsp) }
    }

    internal fun onEnvironment(
        mixerRateHz: Int,
        sdk: Int,
    ) {
        _details.update { it.copy(mixerRateHz = mixerRateHz, androidSdk = sdk) }
    }

    internal fun onBandwidth(kbps: Long) {
        _details.update { it.copy(bandwidthKbps = kbps) }
    }

    internal fun onBuffer(aheadMs: Long) {
        _details.update { it.copy(bufferAheadMs = aheadMs) }
    }

    internal fun onUnderrun() {
        _details.update { it.copy(underruns = it.underruns + 1) }
    }

    internal fun onRoute(
        name: String,
        kind: RouteKind,
    ) {
        _state.update { it.copy(routeName = name, routeKind = kind) }
    }

    internal fun onBitPerfect(
        status: BitPerfectStatus,
        note: String,
    ) {
        _state.update { it.copy(bitPerfect = status, bitPerfectNote = note) }
    }

    private fun containerLabel(mime: String): String =
        when (mime) {
            MimeTypes.AUDIO_WEBM, MimeTypes.VIDEO_WEBM -> "WebM"
            MimeTypes.AUDIO_MP4, MimeTypes.VIDEO_MP4 -> "MP4 / M4A"
            MimeTypes.AUDIO_OGG -> "Ogg"
            MimeTypes.AUDIO_FLAC -> "FLAC"
            MimeTypes.AUDIO_MPEG -> "MP3"
            MimeTypes.APPLICATION_M3U8 -> "HLS"
            "" -> ""
            else -> mime.substringAfter('/').uppercase(Locale.US)
        }

    private fun codecLabel(mime: String): String =
        when (mime) {
            MimeTypes.AUDIO_FLAC -> "FLAC"
            MimeTypes.AUDIO_ALAC -> "ALAC"
            MimeTypes.AUDIO_RAW -> "PCM"
            MimeTypes.AUDIO_AAC -> "AAC"
            MimeTypes.AUDIO_MPEG -> "MP3"
            MimeTypes.AUDIO_OPUS -> "Opus"
            MimeTypes.AUDIO_VORBIS -> "Vorbis"
            MimeTypes.AUDIO_AC3 -> "AC-3"
            MimeTypes.AUDIO_E_AC3 -> "E-AC-3"
            MimeTypes.AUDIO_E_AC3_JOC -> "E-AC-3 JOC"
            MimeTypes.AUDIO_AC4 -> "AC-4"
            MimeTypes.AUDIO_TRUEHD -> "TrueHD"
            "" -> ""
            else -> mime.substringAfter('/').uppercase(Locale.US)
        }
}