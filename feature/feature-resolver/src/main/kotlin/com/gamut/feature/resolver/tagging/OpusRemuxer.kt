package com.gamut.feature.resolver.tagging

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

object OpusRemuxer {
    private const val ID_EBML = 0x1A45DFA3L
    private const val ID_SEGMENT = 0x18538067L
    private const val ID_TRACKS = 0x1654AE6BL
    private const val ID_TRACK_ENTRY = 0xAEL
    private const val ID_TRACK_NUMBER = 0xD7L
    private const val ID_CODEC_ID = 0x86L
    private const val ID_CODEC_PRIVATE = 0x63A2L
    private const val ID_CLUSTER = 0x1F43B675L
    private const val ID_SIMPLE_BLOCK = 0xA3L
    private const val ID_BLOCK_GROUP = 0xA0L
    private const val ID_BLOCK = 0xA1L

    private const val MAX_PACKETS_PER_PAGE = 50
    private const val MAX_PICTURE_BYTES = 3 * 1024 * 1024

    private class Parsed(
        val head: ByteArray,
        val packets: List<ByteArray>,
    )

    fun remux(
        src: File,
        dst: File,
        tags: AudioTags,
    ): Boolean =
        try {
            val parsed = parse(src)
            if (parsed == null) {
                false
            } else {
                val samples = parsed.packets.map { opusSamples(it) }
                if (samples.any { it <= 0 }) {
                    false
                } else {
                    FileOutputStream(dst).buffered().use { write(it, parsed, samples, tags) }
                    if (verify(dst, parsed.packets.size)) {
                        true
                    } else {
                        dst.delete()
                        false
                    }
                }
            }
        } catch (_: Exception) {
            dst.delete()
            false
        }

    private class Reader(
        private val input: InputStream,
    ) {
        var pos = 0L
            private set

        fun read(): Int {
            val b = input.read()
            if (b >= 0) pos++
            return b
        }

        fun byte(): Int = read().also { if (it < 0) throw EOFException() }

        fun bytes(n: Long): ByteArray {
            if (n < 0 || n > Int.MAX_VALUE) throw IOException("bad length")
            val out = ByteArray(n.toInt())
            var done = 0
            while (done < out.size) {
                val got = input.read(out, done, out.size - done)
                if (got < 0) throw EOFException()
                done += got
            }
            pos += n
            return out
        }

        fun skip(n: Long) {
            var left = n
            while (left > 0) {
                val s = input.skip(left)
                if (s <= 0) {
                    if (read() < 0) throw EOFException()
                    left--
                } else {
                    left -= s
                    pos += s
                }
            }
        }

        fun id(): Long {
            val first = read()
            if (first < 0) return -1
            var len = 1
            var mask = 0x80
            while (len <= 4 && (first and mask) == 0) {
                len++
                mask = mask shr 1
            }
            if (len > 4) throw IOException("bad element id")
            var v = first.toLong()
            repeat(len - 1) { v = (v shl 8) or byte().toLong() }
            return v
        }

        fun size(): Long {
            val first = byte()
            var len = 1
            var mask = 0x80
            while (len <= 8 && (first and mask) == 0) {
                len++
                mask = mask shr 1
            }
            if (len > 8) throw IOException("bad element size")
            val firstBits = first and (mask - 1)
            var allOnes = firstBits == mask - 1
            var v = firstBits.toLong()
            repeat(len - 1) {
                val b = byte()
                if (b != 0xFF) allOnes = false
                v = (v shl 8) or b.toLong()
            }
            return if (allOnes) -1 else v
        }
    }

    private fun parse(file: File): Parsed? =
        FileInputStream(file).use { stream -> parseStream(Reader(BufferedInputStream(stream, 1 shl 16))) }

    private fun parseStream(r: Reader): Parsed? {
        run {
            if (r.id() != ID_EBML) return null
            r.skip(r.size().also { if (it < 0) return null })
            if (r.id() != ID_SEGMENT) return null
            val segmentSize = r.size()
            val segmentEnd = if (segmentSize >= 0) r.pos + segmentSize else Long.MAX_VALUE

            var trackNumber = -1L
            var head: ByteArray? = null
            val packets = ArrayList<ByteArray>()

            fun readBlock(size: Long) {
                val start = r.pos
                val track = r.size()
                r.bytes(2)
                val flags = r.byte()
                if (((flags shr 1) and 3) != 0) throw IOException("laced blocks are not supported")
                val payload = r.bytes(size - (r.pos - start))
                if (track == trackNumber && payload.isNotEmpty()) packets += payload
            }

            while (r.pos < segmentEnd) {
                val id = r.id()
                if (id < 0) break
                val size = r.size()
                if (size < 0) return null
                val end = r.pos + size
                when (id) {
                    ID_TRACKS -> while (r.pos < end) {
                        val childId = r.id()
                        val childSize = r.size()
                        if (childSize < 0) return null
                        if (childId != ID_TRACK_ENTRY) {
                            r.skip(childSize)
                            continue
                        }
                        val entryEnd = r.pos + childSize
                        var number = -1L
                        var codec = ""
                        var priv: ByteArray? = null
                        while (r.pos < entryEnd) {
                            val fieldId = r.id()
                            val fieldSize = r.size()
                            if (fieldSize < 0) return null
                            when (fieldId) {
                                ID_TRACK_NUMBER -> number = r.bytes(fieldSize).fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
                                ID_CODEC_ID -> codec = String(r.bytes(fieldSize), Charsets.US_ASCII).trimEnd('\u0000')
                                ID_CODEC_PRIVATE -> priv = r.bytes(fieldSize)
                                else -> r.skip(fieldSize)
                            }
                        }
                        if (codec == "A_OPUS" && head == null) {
                            trackNumber = number
                            head = priv
                        }
                    }

                    ID_CLUSTER -> while (r.pos < end) {
                        val childId = r.id()
                        val childSize = r.size()
                        if (childSize < 0) return null
                        when (childId) {
                            ID_SIMPLE_BLOCK -> {
                                readBlock(childSize)
                            }

                            ID_BLOCK_GROUP -> {
                                val groupEnd = r.pos + childSize
                                while (r.pos < groupEnd) {
                                    val blockId = r.id()
                                    val blockSize = r.size()
                                    if (blockSize < 0) return null
                                    if (blockId == ID_BLOCK) readBlock(blockSize) else r.skip(blockSize)
                                }
                            }

                            else -> {
                                r.skip(childSize)
                            }
                        }
                    }

                    else -> r.skip(size)
                }
            }

            val opusHead = head ?: return null
            if (opusHead.size < 19 || String(opusHead, 0, 8, Charsets.US_ASCII) != "OpusHead") return null
            if (packets.isEmpty()) return null
            return Parsed(opusHead, packets)
        }
    }

    private fun opusSamples(packet: ByteArray): Int {
        if (packet.isEmpty()) return 0
        val toc = packet[0].toInt() and 0xFF
        val config = toc shr 3
        val frameSamples =
            when {
                config < 12 -> intArrayOf(480, 960, 1920, 2880)[config and 3]
                config < 16 -> if ((config and 1) == 0) 480 else 960
                else -> intArrayOf(120, 240, 480, 960)[config and 3]
            }
        val frames =
            when (toc and 3) {
                0 -> {
                    1
                }

                1, 2 -> {
                    2
                }

                else -> {
                    if (packet.size < 2) return 0
                    packet[1].toInt() and 0x3F
                }
            }
        return frames * frameSamples
    }

    private fun write(
        out: OutputStream,
        parsed: Parsed,
        samples: List<Int>,
        tags: AudioTags,
    ) {
        val writer = OggWriter(out, (System.nanoTime() xor 0x5DEECE66DL).toInt())
        writer.packet(parsed.head, 0)
        writer.endPage(bos = true)
        writer.packet(opusTags(tags), 0)
        writer.endPage()
        var granule = 0L
        parsed.packets.forEachIndexed { i, packet ->
            granule += samples[i]
            writer.packet(packet, granule)
            when {
                i == parsed.packets.lastIndex -> writer.endPage(eos = true)
                writer.packetsOnPage >= MAX_PACKETS_PER_PAGE || writer.segments > 220 -> writer.endPage()
            }
        }
    }

    private fun opusTags(tags: AudioTags): ByteArray {
        val comments = ArrayList<String>()

        fun add(
            key: String,
            value: String?,
        ) {
            if (!value.isNullOrBlank()) comments += "$key=$value"
        }
        add("TITLE", tags.title)
        add("ARTIST", tags.artist)
        add("ALBUMARTIST", tags.artist)
        add("ALBUM", tags.album)
        add("ISRC", tags.isrc)
        add("LYRICS", tags.lyrics)
        tags.cover?.takeIf { it.isNotEmpty() && it.size <= MAX_PICTURE_BYTES }?.let { cover ->
            add("METADATA_BLOCK_PICTURE", Base64.getEncoder().encodeToString(flacPicture(cover, tags.coverMime)))
        }
        val vendor = "Gamut".toByteArray()
        val out = ByteArrayOutputStream()
        out.write("OpusTags".toByteArray())
        out.write(le32(vendor.size))
        out.write(vendor)
        out.write(le32(comments.size))
        comments.forEach { c ->
            val bytes = c.toByteArray(Charsets.UTF_8)
            out.write(le32(bytes.size))
            out.write(bytes)
        }
        return out.toByteArray()
    }

    private fun flacPicture(
        image: ByteArray,
        mime: String,
    ): ByteArray {
        val m = mime.toByteArray()
        val bb = ByteBuffer.allocate(32 + m.size + image.size).order(ByteOrder.BIG_ENDIAN)
        bb.putInt(3)
        bb.putInt(m.size)
        bb.put(m)
        bb.putInt(0)
        bb.putInt(0)
        bb.putInt(0)
        bb.putInt(24)
        bb.putInt(0)
        bb.putInt(image.size)
        bb.put(image)
        return bb.array().copyOf(bb.position())
    }

    private fun le32(v: Int): ByteArray =
        ByteBuffer
            .allocate(4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(v)
            .array()

    private class OggWriter(
        private val out: OutputStream,
        private val serial: Int,
    ) {
        private var sequence = 0
        private val table = ByteArray(255)
        private val body = ByteArrayOutputStream()
        private var continued = false
        private var granule = -1L

        var segments = 0
            private set
        var packetsOnPage = 0
            private set

        fun packet(
            data: ByteArray,
            granuleAfter: Long,
        ) {
            var offset = 0
            var remaining = data.size
            var started = false
            while (true) {
                if (segments == 255) flush(flags = 0, midPacket = started)
                val segment = minOf(remaining, 255)
                table[segments++] = segment.toByte()
                body.write(data, offset, segment)
                started = true
                offset += segment
                remaining -= segment
                if (segment < 255) break
            }
            granule = granuleAfter
            packetsOnPage++
        }

        fun endPage(
            bos: Boolean = false,
            eos: Boolean = false,
        ) {
            if (segments > 0) flush(flags = (if (bos) 0x02 else 0) or (if (eos) 0x04 else 0), midPacket = false)
        }

        private fun flush(
            flags: Int,
            midPacket: Boolean,
        ) {
            val header = ByteArray(27 + segments)
            val hb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            hb.put("OggS".toByteArray())
            hb.put(0)
            hb.put(((if (continued) 0x01 else 0) or flags).toByte())
            hb.putLong(if (packetsOnPage > 0) granule else -1L)
            hb.putInt(serial)
            hb.putInt(sequence)
            hb.putInt(0)
            hb.put(segments.toByte())
            hb.put(table, 0, segments)
            val bodyBytes = body.toByteArray()
            var crc = crc(0, header)
            crc = crc(crc, bodyBytes)
            hb.putInt(22, crc)
            out.write(header)
            out.write(bodyBytes)
            sequence++
            segments = 0
            packetsOnPage = 0
            granule = -1L
            body.reset()
            continued = midPacket
        }
    }

    private val CRC_TABLE =
        IntArray(256).also { t ->
            for (i in 0 until 256) {
                var r = i shl 24
                repeat(8) { r = if (r < 0) (r shl 1) xor 0x04C11DB7 else r shl 1 }
                t[i] = r
            }
        }

    private fun crc(
        start: Int,
        bytes: ByteArray,
    ): Int {
        var c = start
        for (b in bytes) c = (c shl 8) xor CRC_TABLE[((c ushr 24) xor (b.toInt() and 0xFF)) and 0xFF]
        return c
    }

    private fun verify(
        file: File,
        audioPackets: Int,
    ): Boolean {
        return try {
            val bytes = file.readBytes()
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            var p = 0
            var packets = 0
            var expectedSequence = 0
            var eos = false
            while (p < bytes.size) {
                if (eos || p + 27 > bytes.size) return false
                if (String(bytes, p, 4, Charsets.US_ASCII) != "OggS") return false
                val segs = bytes[p + 26].toInt() and 0xFF
                var bodyLength = 0
                for (i in 0 until segs) {
                    val s = bytes[p + 27 + i].toInt() and 0xFF
                    bodyLength += s
                    if (s < 255) packets++
                }
                val total = 27 + segs + bodyLength
                if (p + total > bytes.size) return false
                if (bb.getInt(p + 18) != expectedSequence++) return false
                val stored = bb.getInt(p + 22)
                val page = bytes.copyOfRange(p, p + total)
                page[22] = 0
                page[23] = 0
                page[24] = 0
                page[25] = 0
                if (crc(0, page) != stored) return false
                eos = (bytes[p + 5].toInt() and 0x04) != 0
                p += total
            }
            eos && packets == audioPackets + 2
        } catch (_: Exception) {
            false
        }
    }
}
