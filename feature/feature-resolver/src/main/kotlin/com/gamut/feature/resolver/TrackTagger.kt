package com.gamut.feature.resolver

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.gamut.core.model.Track
import com.gamut.feature.resolver.tagging.AudioTags
import com.gamut.feature.resolver.tagging.Mp4TagWriter
import com.gamut.feature.resolver.tagging.OpusRemuxer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

interface LyricsSource {
    suspend fun lrcFor(track: Track): String?

    fun forget(isrc: String)
}

class PreparedExtras(
    val lyrics: String?,
    val cover: ByteArray?,
) {
    companion object {
        val NONE = PreparedExtras(null, null)
    }
}

class TaggedAudio(
    val file: File,
    val extension: String,
    val tagged: Boolean = false,
)

@Singleton
class TrackTagger
    @Inject
    constructor(
        private val storage: DownloadStorage,
        private val lyrics: LyricsSource,
        private val taggedSongs: TaggedSongs,
    ) {
        private val http =
            OkHttpClient
                .Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .callTimeout(25, TimeUnit.SECONDS)
                .build()

        suspend fun prepare(track: Track): PreparedExtras =
            coroutineScope {
                val lrc = async { attempt { lyrics.lrcFor(track) }?.takeIf { it.isNotBlank() } }
                val cover = async(Dispatchers.IO) { attempt { fetchCover(track) } }
                PreparedExtras(lrc.await(), cover.await())
            }

        fun apply(
            track: Track,
            audio: File,
            extension: String,
            extras: PreparedExtras,
        ): TaggedAudio {
            val tags =
                AudioTags(
                    title = track.title,
                    artist = track.artist,
                    album = track.album,
                    isrc = track.isrc.takeIf { it.isNotBlank() },
                    lyrics = extras.lyrics?.let(::withoutWordStamps),
                    cover = extras.cover,
                )
            return try {
                when (extension) {
                    "m4a", "mp4" -> {
                        val ok = Mp4TagWriter.write(audio, tags)
                        if (ok) taggedSongs.add(track.isrc)
                        TaggedAudio(audio, extension, ok)
                    }

                    "webm" -> {
                        val out = File(audio.parentFile, audio.nameWithoutExtension + ".tag.part")
                        if (OpusRemuxer.remux(audio, out, tags)) {
                            audio.delete()
                            taggedSongs.add(track.isrc)
                            TaggedAudio(out, "opus", tagged = true)
                        } else {
                            out.delete()
                            TaggedAudio(audio, extension)
                        }
                    }

                    else -> {
                        TaggedAudio(audio, extension)
                    }
                }
            } catch (_: Exception) {
                TaggedAudio(audio, extension)
            }
        }

        fun forget(isrc: String) {
            storage.coverFile(isrc).delete()
            lyrics.forget(isrc)
            taggedSongs.remove(isrc)
        }

        fun needsTags(
            isrc: String,
            extension: String,
        ): Boolean =
            when (extension) {
                "webm" -> true
                "m4a", "mp4" -> !taggedSongs.contains(isrc)
                else -> false
            }

        private fun fetchCover(track: Track): ByteArray? {
            val candidates = listOfNotNull(largerArt(track.albumArtUrl), track.albumArtUrl).distinct()
            for (url in candidates) {
                val raw = download(url) ?: continue
                val processed = process(raw, track.isrc) ?: continue
                return processed
            }
            return null
        }

        private fun process(
            raw: ByteArray,
            isrc: String,
        ): ByteArray? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            if (longest <= 0) return null
            var sample = 1
            while (longest / (sample * 2) >= EMBED_PX) sample *= 2
            val decoded =
                BitmapFactory.decodeByteArray(raw, 0, raw.size, BitmapFactory.Options().apply { inSampleSize = sample })
                    ?: return null
            val embedBitmap = fit(decoded, EMBED_PX)
            val embed = jpeg(embedBitmap, 90)
            val thumbBitmap = fit(embedBitmap, THUMB_PX)
            runCatching {
                val file = storage.coverFile(isrc)
                file.parentFile?.mkdirs()
                file.writeBytes(jpeg(thumbBitmap, 85))
            }
            return embed
        }

        private fun fit(
            bitmap: Bitmap,
            maxSide: Int,
        ): Bitmap {
            val longest = maxOf(bitmap.width, bitmap.height)
            if (longest <= maxSide) return bitmap
            val scale = maxSide.toFloat() / longest
            return Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        }

        private fun jpeg(
            bitmap: Bitmap,
            quality: Int,
        ): ByteArray = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()

        private fun download(url: String): ByteArray? =
            try {
                http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (!response.isSuccessful) null else response.body?.bytes()?.takeIf { it.size in 1..MAX_DOWNLOAD_BYTES }
                }
            } catch (_: IOException) {
                null
            }

        private fun largerArt(url: String?): String? {
            if (url.isNullOrBlank()) return null
            return when {
                "mzstatic.com" in url -> {
                    url.replace(
                        Regex("""/\d{2,4}x\d{2,4}(bb|cc|sr)?(-\d+)?\.(jpg|jpeg|png|webp)$""", RegexOption.IGNORE_CASE),
                    ) { "/${EMBED_PX}x${EMBED_PX}bb.${it.groupValues[3]}" }
                }

                "dzcdn.net" in url -> {
                    url.replace(Regex("""/\d{2,4}x\d{2,4}-"""), "/${EMBED_PX}x$EMBED_PX-")
                }

                "coverartarchive.org" in url -> {
                    url.replace(Regex("""-(250|500)(\.\w+)$"""), "-1200$2")
 
                }

                else -> {
                    url
                }
            }
        }

        private fun withoutWordStamps(lrc: String) =
            lrc.replace(Regex("""<\d{1,3}:\d{2}(?:[.:]\d{1,3})?>"""), "").replace(Regex("""[ \t]{2,}"""), " ")

        private suspend fun <T> attempt(block: suspend () -> T): T? =
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }

        private companion object {
            const val EMBED_PX = 1000
            const val THUMB_PX = 512
            const val MAX_DOWNLOAD_BYTES = 12 * 1024 * 1024
        }
    }
