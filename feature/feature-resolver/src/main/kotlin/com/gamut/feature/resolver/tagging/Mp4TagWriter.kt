package com.gamut.feature.resolver.tagging

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer

object Mp4TagWriter {
    private class Box(
        val type: String,
        val start: Long,
        val size: Long,
        val header: Int,
    )

    private val CONTAINERS = setOf("moov", "trak", "mdia", "minf", "stbl")

    fun write(
        file: File,
        tags: AudioTags,
    ): Boolean {
        val tmp = File(file.path + ".tagged")
        return try {
            if (!rewrite(file, tmp, tags) || !verify(tmp)) {
                tmp.delete()
                false
            } else {
                if (!tmp.renameTo(file)) {
                    tmp.copyTo(file, overwrite = true)
                    tmp.delete()
                }
                true
            }
        } catch (_: Exception) {
            tmp.delete()
            false
        }
    }

    private fun rewrite(
        src: File,
        dst: File,
        tags: AudioTags,
    ): Boolean {
        RandomAccessFile(src, "r").use { raf ->
            val boxes = topLevel(raf) ?: return false
            val moov = boxes.firstOrNull { it.type == "moov" } ?: return false
            if (moov.header != 8 || moov.size > MAX_MOOV) return false
            val mdat = boxes.firstOrNull { it.type == "mdat" }
            val firstMoof = boxes.firstOrNull { it.type == "moof" }
            val fragmented = firstMoof != null

            if (fragmented) {
                if (boxes.takeWhile { it !== moov }.any { it.type == "sidx" }) return false
                if (usesAbsoluteBaseOffset(raf, firstMoof!!)) return false
            } else if (mdat == null) {
                return false
            }

            val oldMoov =
                ByteArray(moov.size.toInt()).also {
                    raf.seek(moov.start)
                    raf.readFully(it)
                }
            var newMoov = withTags(oldMoov, tags) ?: return false
            val delta = newMoov.size - oldMoov.size
            if (!fragmented && mdat != null && mdat.start > moov.start && delta != 0) {
                if (!shiftChunkOffsets(newMoov, delta.toLong())) return false
            }

            FileOutputStream(dst).channel.use { out ->
                for (box in boxes) {
                    when {
                        box === moov -> {
                            ByteBuffer.wrap(newMoov).let { buf -> while (buf.hasRemaining()) out.write(buf) }
                        }

                        box.type == "mfra" -> {
                            Unit
                        }

                        else -> {
                            var position = box.start
                            var left = box.size
                            while (left > 0) {
                                val n = raf.channel.transferTo(position, left, out)
                                if (n <= 0) return false
                                position += n
                                left -= n
                            }
                        }
                    }
                }
            }
        }
        return true
    }

    private fun topLevel(raf: RandomAccessFile): List<Box>? {
        val length = raf.length()
        var pos = 0L
        val list = ArrayList<Box>()
        while (pos < length) {
            if (length - pos < 8) return null
            raf.seek(pos)
            var size = raf.readInt().toLong() and 0xFFFFFFFFL
            val type = ByteArray(4).also { raf.readFully(it) }
            var header = 8
            if (size == 1L) {
                size = raf.readLong()
                header = 16
            } else if (size == 0L) {
                size = length - pos
            }
            if (size < header || pos + size > length) return null
            list += Box(String(type, Charsets.ISO_8859_1), pos, size, header)
            pos += size
        }
        return list
    }

    private fun usesAbsoluteBaseOffset(
        raf: RandomAccessFile,
        moof: Box,
    ): Boolean {
        if (moof.size > MAX_MOOV) return true
        val bytes =
            ByteArray(moof.size.toInt()).also {
                raf.seek(moof.start)
                raf.readFully(it)
            }
        val traf = children(bytes, 8, bytes.size).firstOrNull { it.type == "traf" } ?: return true
        val tfhd = children(bytes, traf.start + 8, traf.end).firstOrNull { it.type == "tfhd" } ?: return true
        val flags = ByteBuffer.wrap(bytes, tfhd.start + 8, 4).int and 0x00FFFFFF
        return (flags and 0x000001) != 0
    }

    private class Child(
        val type: String,
        val start: Int,
        val end: Int,
    )

    private fun children(
        buf: ByteArray,
        from: Int,
        to: Int,
    ): List<Child> {
        val list = ArrayList<Child>()
        var p = from
        while (p + 8 <= to) {
            val size = ByteBuffer.wrap(buf, p, 4).int
            if (size < 8 || p + size > to) break
            list += Child(String(buf, p + 4, 4, Charsets.ISO_8859_1), p, p + size)
            p += size
        }
        return list
    }

    private fun withTags(
        moov: ByteArray,
        tags: AudioTags,
    ): ByteArray? {
        val meta = buildMeta(tags)
        val kids = children(moov, 8, moov.size)
        if (kids.isEmpty()) return null
        val out = java.io.ByteArrayOutputStream()
        var placed = false
        for (kid in kids) {
            if (kid.type == "udta") {
                val keep = children(moov, kid.start + 8, kid.end).filter { it.type != "meta" }
                val body = java.io.ByteArrayOutputStream()
                keep.forEach { body.write(moov, it.start, it.end - it.start) }
                body.write(meta)
                out.write(box("udta", body.toByteArray()))
                placed = true
            } else {
                out.write(moov, kid.start, kid.end - kid.start)
            }
        }
        if (!placed) out.write(box("udta", meta))
        return box("moov", out.toByteArray())
    }

    private fun buildMeta(tags: AudioTags): ByteArray {
        val items = java.io.ByteArrayOutputStream()

        fun text(
            type: String,
            value: String?,
        ) {
            if (value.isNullOrBlank()) return
            items.write(box(type, data(1, value.toByteArray(Charsets.UTF_8))))
        }
        text("\u00A9nam", tags.title)
        text("\u00A9ART", tags.artist)
        text("aART", tags.artist)
        text("\u00A9alb", tags.album)
        text("\u00A9lyr", tags.lyrics)
        tags.cover?.takeIf { it.isNotEmpty() }?.let { cover ->
            items.write(box("covr", data(if (tags.coverMime.contains("png", ignoreCase = true)) 14 else 13, cover)))
        }
        tags.isrc?.takeIf { it.isNotBlank() }?.let { isrc ->
            items.write(
                box(
                    "----",
                    box("mean", u32(0) + "com.apple.iTunes".toByteArray()) +
                        box("name", u32(0) + "ISRC".toByteArray()) +
                        data(1, isrc.toByteArray()),
                ),
            )
        }
        val hdlr = box("hdlr", u32(0) + u32(0) + "mdir".toByteArray() + "appl".toByteArray() + u32(0) + u32(0) + byteArrayOf(0))
        return box("meta", u32(0) + hdlr + box("ilst", items.toByteArray()))
    }

    private fun data(
        flags: Int,
        payload: ByteArray,
    ) = box("data", u32(flags) + u32(0) + payload)

    private fun box(
        type: String,
        payload: ByteArray,
    ): ByteArray = u32(payload.size + 8) + type.toByteArray(Charsets.ISO_8859_1) + payload

    private fun u32(v: Int): ByteArray = ByteBuffer.allocate(4).putInt(v).array()

    private fun shiftChunkOffsets(
        moov: ByteArray,
        delta: Long,
    ): Boolean {
        fun walk(
            from: Int,
            to: Int,
        ): Boolean {
            for (child in children(moov, from, to)) {
                when (child.type) {
                    in CONTAINERS -> {
                        if (!walk(child.start + 8, child.end)) return false
                    }

                    "stco" -> {
                        val bb = ByteBuffer.wrap(moov)
                        val count = bb.getInt(child.start + 12)
                        for (i in 0 until count) {
                            val at = child.start + 16 + i * 4
                            val moved = (bb.getInt(at).toLong() and 0xFFFFFFFFL) + delta
                            if (moved < 0 || moved > 0xFFFFFFFFL) return false
                            bb.putInt(at, moved.toInt())
                        }
                    }

                    "co64" -> {
                        val bb = ByteBuffer.wrap(moov)
                        val count = bb.getInt(child.start + 12)
                        for (i in 0 until count) {
                            val at = child.start + 16 + i * 8
                            bb.putLong(at, bb.getLong(at) + delta)
                        }
                    }
                }
            }
            return true
        }
        return walk(8, moov.size)
    }

    private fun verify(file: File): Boolean {
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val boxes = topLevel(raf) ?: return false
                val moov = boxes.firstOrNull { it.type == "moov" } ?: return false
                val mdat = boxes.firstOrNull { it.type == "mdat" }
                val fragmented = boxes.any { it.type == "moof" }
                if (mdat == null && !fragmented) return false
                val bytes =
                    ByteArray(moov.size.toInt()).also {
                        raf.seek(moov.start)
                        raf.readFully(it)
                    }
                val udta = children(bytes, 8, bytes.size).firstOrNull { it.type == "udta" } ?: return false
                val meta = children(bytes, udta.start + 8, udta.end).firstOrNull { it.type == "meta" } ?: return false
                if (children(bytes, meta.start + 12, meta.end).none { it.type == "ilst" }) return false
                if (!fragmented && mdat != null) chunkOffsetsInside(bytes, mdat.start, mdat.start + mdat.size) else true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun chunkOffsetsInside(
        moov: ByteArray,
        low: Long,
        high: Long,
    ): Boolean {
        fun walk(
            from: Int,
            to: Int,
        ): Boolean {
            for (child in children(moov, from, to)) {
                when (child.type) {
                    in CONTAINERS -> {
                        if (!walk(child.start + 8, child.end)) return false
                    }

                    "stco" -> {
                        val bb = ByteBuffer.wrap(moov)
                        for (i in 0 until bb.getInt(child.start + 12)) {
                            if ((bb.getInt(child.start + 16 + i * 4).toLong() and 0xFFFFFFFFL) !in low..high) return false
                        }
                    }

                    "co64" -> {
                        val bb = ByteBuffer.wrap(moov)
                        for (i in 0 until bb.getInt(child.start + 12)) {
                            if (bb.getLong(child.start + 16 + i * 8) !in low..high) return false
                        }
                    }
                }
            }
            return true
        }
        return walk(8, moov.size)
    }

    private const val MAX_MOOV = 32L * 1024 * 1024
}
