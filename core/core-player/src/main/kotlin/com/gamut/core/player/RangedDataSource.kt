package com.gamut.core.player

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.InterruptedIOException
import java.util.Locale

/**
 * Fetches a googlevideo stream as a run of bounded ranges instead of one open-ended read
 * (ported from Auris' ChunkedDataSource).
 *
 * googlevideo paces a continuous response down to roughly playback speed, which is fatal for
 * high-bitrate video: there is never spare bandwidth to build a buffer. The same bytes as
 * bounded ranges are served at line rate. Transparent to everything above: the caller sees one
 * continuous stream of the length it asked for.
 *
 * Needs the total size, which every progressive googlevideo URL carries as `clen`; anything
 * without it is forwarded untouched.
 */
@UnstableApi
class RangedDataSource(
    private val upstream: DataSource,
    private val chunkBytes: Long,
) : DataSource {
    private var baseSpec: DataSpec? = null
    private var position = 0L
    private var bytesRemaining = 0L
    private var chunkRemaining = 0L
    private var chunkOpen = false
    private var rangeBytes = chunkBytes
    private var passthrough = false
    private var chunksLogged = 0

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        baseSpec = dataSpec
        position = dataSpec.position
        chunksLogged = 0
        if (!dataSpec.uri.isGoogleVideo()) {
            passthrough = true
            chunkOpen = true
            return upstream.open(dataSpec)
        }
        passthrough = false
        rangeBytes = minOf(chunkBytes, rangeBytesFor(dataSpec.uri))
        var total = dataSpec.uri.getQueryParameter("clen")?.toLongOrNull()
        val hadClen = total != null
        if (total == null) {
            // No `clen` in the URL. Sending one open-ended request is what googlevideo throttles to
            // playback speed, so learn the size from the first bounded response instead.
            val first = if (dataSpec.length == C.LENGTH_UNSET.toLong()) rangeBytes else minOf(rangeBytes, dataSpec.length)
            val spec = dataSpec.buildUpon().setLength(first).build()
            upstream.open(spec)
            total = totalFromContentRange(upstream.responseHeaders)
            if (total == null) {
                Log.w(TAG, "ranged: no clen and no Content-Range, falling back to a plain read")
                upstream.close()
                passthrough = true
                chunkOpen = true
                return upstream.open(dataSpec)
            }
            val end = if (dataSpec.length == C.LENGTH_UNSET.toLong()) total else minOf(total, position + dataSpec.length)
            bytesRemaining = (end - position).coerceAtLeast(0L)
            chunkRemaining = minOf(first, bytesRemaining)
            chunkOpen = true
            if (position == 0L) Log.d(TAG, "ranged: total=$total learned from Content-Range (no clen), chunk=$rangeBytes")
            return bytesRemaining
        }
        val end = if (dataSpec.length == C.LENGTH_UNSET.toLong()) total else minOf(total, position + dataSpec.length)
        bytesRemaining = (end - position).coerceAtLeast(0L)
        if (position == 0L) Log.d(TAG, "ranged: total=$total from clen=$hadClen, chunk=$rangeBytes")
        if (bytesRemaining > 0) openChunk()
        return bytesRemaining
    }

    private fun totalFromContentRange(headers: Map<String, List<String>>): Long? =
        headers.entries
            .firstOrNull { it.key.equals("Content-Range", ignoreCase = true) }
            ?.value
            ?.firstOrNull()
            ?.substringAfterLast('/')
            ?.trim()
            ?.toLongOrNull()

    private fun openChunk() {
        val length = minOf(rangeBytes, bytesRemaining)
        val spec = requireNotNull(baseSpec).buildUpon().setPosition(position).setLength(length).build()
        val startedAt = SystemClock.elapsedRealtime()
        upstream.open(spec)
        if (chunksLogged < LOGGED_CHUNKS) {
            chunksLogged++
            Log.d(TAG, "ranged: chunk at $position (${length / 1024} kB) answered in ${SystemClock.elapsedRealtime() - startedAt}ms")
        }
        chunkRemaining = length
        chunkOpen = true
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        if (passthrough) return upstream.read(buffer, offset, length)
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        // A range that ends early is re-opened for the part that didn't arrive, which is also
        // how the step to the next range happens. The attempt limit stops a server that has
        // decided to send nothing from spinning here forever.
        repeat(MAX_EMPTY_RANGES) {
            if (chunkRemaining == 0L) {
                closeChunk()
                openChunk()
            }
            val read = upstream.read(buffer, offset, minOf(length.toLong(), chunkRemaining).toInt())
            if (read != C.RESULT_END_OF_INPUT) {
                position += read
                chunkRemaining -= read
                bytesRemaining -= read
                return read
            }
            chunkRemaining = 0L
        }
        return C.RESULT_END_OF_INPUT
    }

    private fun closeChunk() {
        if (chunkOpen) {
            try {
                upstream.close()
            } catch (_: InterruptedIOException) {
            }
            chunkOpen = false
        }
    }

    override fun getUri(): Uri? = upstream.uri ?: baseSpec?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        closeChunk()
        baseSpec = null
        bytesRemaining = 0L
        chunkRemaining = 0L
    }

    class Factory(
        private val upstream: DataSource.Factory,
        private val chunkBytes: Long = DEFAULT_CHUNK_BYTES,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = RangedDataSource(upstream.createDataSource(), chunkBytes)
    }

    private companion object {
        const val TAG = "GamutVideo"
        const val LOGGED_CHUNKS = 4
        const val MAX_EMPTY_RANGES = 3
        const val DEFAULT_CHUNK_BYTES = 1L * 1024 * 1024
        const val NARROW_RANGE_BYTES = 512L * 1024

        fun Uri.isGoogleVideo(): Boolean = host?.endsWith("googlevideo.com") == true

        /** Largest single range googlevideo reliably serves for the client that minted [uri]. */
        fun rangeBytesFor(uri: Uri): Long {
            val client = uri.getQueryParameter("c")?.uppercase(Locale.ROOT)
            return if (client == "ANDROID_VR" || client?.startsWith("TVHTML5_SIMPLY") == true) {
                NARROW_RANGE_BYTES
            } else {
                DEFAULT_CHUNK_BYTES
            }
        }
    }
}