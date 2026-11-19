package com.gamut.feature.resolver.lossless

import okhttp3.OkHttpClient
import java.util.Locale
import java.util.concurrent.TimeUnit

/** The HTTP client module indexes and module scripts are fetched with. */
internal object Http {
    val client: OkHttpClient by lazy {
        OkHttpClient
            .Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }
}

/** What a stream is, as far as its source said. */
data class StreamFormat(
    val codec: String? = null,
    val kbps: Int? = null,
    val sampleRateHz: Int? = null,
    val bitDepth: Int? = null,
    val kbpsInferred: Boolean = false,
) {
    val isDolbyAtmos: Boolean get() = codec?.lowercase(Locale.ROOT)?.let { it.startsWith("eac3") || it == "ec3" } == true

    val isLossless: Boolean get() = codec?.lowercase(Locale.ROOT) in LOSSLESS_CODECS

    /** For example "flac · 24-bit / 96 kHz" or "mp3 · 320 kbps". */
    val summary: String
        get() {
            val c = codec?.uppercase(Locale.ROOT) ?: return "unknown format"
            val detail =
                if (isLossless) {
                    listOfNotNull(
                        bitDepth?.let { "$it-bit" },
                        sampleRateHz?.let { String.format(Locale.US, "%.1f kHz", it / 1000f).replace(".0 kHz", " kHz") },
                    ).joinToString(" / ")
                } else {
                    kbps?.let { "$it kbps" }.orEmpty()
                }
            return if (detail.isEmpty()) c else "$c · $detail"
        }

    private companion object {
        val LOSSLESS_CODECS = setOf("flac", "alac", "wav", "aiff", "x-flac")
    }
}

data class SourceStream(
    val url: String,
    val format: StreamFormat = StreamFormat(),
    val headers: Map<String, String> = emptyMap(),
    val durationSec: Int? = null,
)

sealed interface StreamRequest {
    /** Bit-exact audio, or nothing. */
    data object Lossless : StreamRequest

    /** The best the source has, at no more than [maxKbps]. */
    data class Capped(val maxKbps: Int) : StreamRequest

    data object Best : StreamRequest
}

sealed interface SourceHealth {
    data class Ok(val detail: String? = null) : SourceHealth

    data class Unreachable(val reason: String) : SourceHealth

    data class Rejected(val reason: String) : SourceHealth

    val isOk: Boolean get() = this is Ok
}

/** The kinds of source Gamut can ask for a better stream. Persisted by name. */
enum class SourceKind(
    val label: String,
    val detail: String,
    val needsUrl: Boolean,
) {
    ADDON("Addon", "An addon server you host or were given a link to. Plain HTTP, nothing is downloaded or run.", true),
    MODULE("Module index", "A URL to a compatible module-index JSON. Its JS plugins run in a sandbox.", true),
    JIOSAAVN("JioSaavn", "Up to 320 kbps AAC. Catalogue matching is strict but can still miss.", false),
}

/** One source the listener added. For [SourceKind.ADDON] and [SourceKind.MODULE], [baseUrl] is its URL. */
data class SourceConfig(
    val id: String,
    val label: String = "",
    val baseUrl: String = "",
    val kind: SourceKind = SourceKind.MODULE,
) {
    val displayName: String
        get() = label.ifBlank { baseUrl.substringAfter("://").substringBefore('/').ifBlank { kind.label } }
}

/** A row a module returned. [id] is `<moduleId>::<upstreamId>`, which [MusicSource.stream] routes on. */
data class LosslessTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val coverUrl: String? = null,
    val durationSec: Int? = null,
    val sourceQuality: String? = null,
)

interface MusicSource {
    val configId: String
    val displayName: String

    suspend fun health(): SourceHealth

    suspend fun search(
        query: String,
        limit: Int = 25,
        waitForAll: Boolean = false,
        request: StreamRequest? = null,
    ): List<LosslessTrack>

    suspend fun stream(
        trackId: String,
        request: StreamRequest,
    ): SourceStream?
}
