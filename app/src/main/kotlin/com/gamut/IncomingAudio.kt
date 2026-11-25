package com.gamut

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import com.gamut.core.model.Track
import java.io.File
import java.security.MessageDigest

/** An audio file another app handed to Gamut through "Open with" or the share sheet, ready to play. */
data class IncomingAudio(
    val track: Track,
    val uri: String,
)

private const val ISRC_PREFIX = "ext:"

/** The audio file an intent points at, or null when it isn't an "open this audio" or "share this audio" intent. */
internal fun audioUriFrom(intent: Intent?): Uri? {
    intent ?: return null
    val uri: Uri? =
        when (intent.action) {
            Intent.ACTION_VIEW -> {
                intent.data
            }

            Intent.ACTION_SEND -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
            }

            else -> {
                null
            }
        }
    if (uri == null || (uri.scheme != "content" && uri.scheme != "file")) return null
    val type = intent.type.orEmpty()
    val audio = type.isEmpty() || type.startsWith("audio/") || type == "application/ogg" || type == "application/x-ogg"
    return uri.takeIf { audio }
}

/** Reads the tags of [uri] into a [Track]. Blocking: call it off the main thread. */
internal fun readIncomingAudio(
    context: Context,
    uri: Uri,
): IncomingAudio {
    var title: String? = null
    var artist: String? = null
    var album: String? = null
    var durationMs = 0L
    var cover: ByteArray? = null
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(context, uri)
        title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
        artist =
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
        album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
        durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        cover = retriever.embeddedPicture
    } catch (_: Exception) {
        // Unreadable tags still play: the file name stands in for the title.
    } finally {
        runCatching { retriever.release() }
    }

    val id = sha1(uri.toString()).take(16)
    val artUrl =
        cover?.let { bytes ->
            runCatching {
                val dir = File(context.cacheDir, "incoming-art").apply { mkdirs() }
                val file = File(dir, "$id.jpg")
                file.writeBytes(bytes)
                Uri.fromFile(file).toString()
            }.getOrNull()
        }

    val track =
        Track(
            isrc = ISRC_PREFIX + id,
            title = title?.takeIf { it.isNotBlank() } ?: displayName(context, uri),
            artist = artist?.takeIf { it.isNotBlank() } ?: "Unknown artist",
            album = album?.takeIf { it.isNotBlank() },
            durationMs = durationMs,
            albumArtUrl = artUrl,
        )
    return IncomingAudio(track, uri.toString())
}

private fun displayName(
    context: Context,
    uri: Uri,
): String {
    val fromProvider =
        if (uri.scheme == "content") {
            runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            }.getOrNull()
        } else {
            null
        }
    val raw = fromProvider ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Audio file"
    return raw.substringBeforeLast('.', raw).ifBlank { "Audio file" }
}

private fun sha1(text: String): String =
    MessageDigest
        .getInstance("SHA-1")
        .digest(text.toByteArray())
        .joinToString("") { "%02x".format(it) }
