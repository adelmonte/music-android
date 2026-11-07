package dev.musicandroid.media

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Writes iTunes-style tags (title, artists, album, numbering, year, cover)
 * into an MP4/M4A file's `moov/udta/meta/ilst`, replacing any there. When
 * `moov` grows and sits before the media data, absolute sample offsets
 * (`stco`/`co64`, and `tfhd` base offsets in fragmented files) are shifted.
 */
object Mp4Tags {
    data class Tags(
        val title: String,
        val artist: String,
        val albumArtist: String,
        val album: String,
        val year: Int?,
        val trackNo: Int?,
        val discNo: Int?,
        val cover: ByteArray?,
    )

    private class Box(val type: String, val start: Int, val header: Int, val end: Int)

    private fun boxes(b: ByteArray, from: Int, to: Int): List<Box> {
        val out = mutableListOf<Box>()
        var at = from
        while (at + 8 <= to) {
            var size = u32(b, at)
            val type = String(b, at + 4, 4, Charsets.ISO_8859_1)
            var header = 8
            if (size == 1L) { size = ByteBuffer.wrap(b, at + 8, 8).long; header = 16 }
            if (size == 0L) size = (to - at).toLong()
            if (size < header || at + size > to) break
            out += Box(type, at, header, (at + size).toInt())
            at += size.toInt()
        }
        return out
    }

    private fun u32(b: ByteArray, at: Int): Long = ByteBuffer.wrap(b, at, 4).int.toLong() and 0xffffffffL

    private fun box(type: String, vararg parts: ByteArray): ByteArray {
        val len = 8 + parts.sumOf { it.size }
        val out = ByteBuffer.allocate(len)
        out.putInt(len)
        out.put(type.toByteArray(Charsets.ISO_8859_1))
        parts.forEach { out.put(it) }
        return out.array()
    }

    private fun int(v: Int) = ByteBuffer.allocate(4).putInt(v).array()

    private fun data(kind: Int, payload: ByteArray) = box("data", int(kind), int(0), payload)

    private fun text(type: String, s: String) = box(type, data(1, s.toByteArray(Charsets.UTF_8)))

    private fun ilst(t: Tags): ByteArray {
        val items = mutableListOf<ByteArray>()
        if (t.title.isNotEmpty()) items += text("©nam", t.title)
        if (t.artist.isNotEmpty()) items += text("©ART", t.artist)
        if (t.albumArtist.isNotEmpty()) items += text("aART", t.albumArtist)
        if (t.album.isNotEmpty()) items += text("©alb", t.album)
        t.year?.let { items += text("©day", it.toString()) }
        t.trackNo?.let { n -> items += box("trkn", data(0, ByteBuffer.allocate(8).putShort(0).putShort(n.toShort()).putShort(0).putShort(0).array())) }
        t.discNo?.let { n -> items += box("disk", data(0, ByteBuffer.allocate(6).putShort(0).putShort(n.toShort()).putShort(0).array())) }
        t.cover?.let { c ->
            val png = c.size > 4 && c[0] == 0x89.toByte() && c[1] == 'P'.code.toByte()
            items += box("covr", data(if (png) 14 else 13, c))
        }
        return box("ilst", *items.toTypedArray())
    }

    private fun udta(t: Tags): ByteArray {
        val hdlr = box("hdlr", int(0), int(0), "mdir".toByteArray(), "appl".toByteArray(), int(0), int(0), byteArrayOf(0))
        return box("udta", box("meta", int(0), hdlr, ilst(t)))
    }

    private val containers = setOf("moov", "trak", "mdia", "minf", "stbl", "moof", "traf", "edts", "mvex")

    /** Shift absolute chunk offsets inside [b] (in place) by [delta]. */
    private fun shift(b: ByteArray, from: Int, to: Int, delta: Long) {
        for (x in boxes(b, from, to)) {
            val body = x.start + x.header
            when (x.type) {
                in containers -> shift(b, body, x.end, delta)
                "stco" -> {
                    val n = u32(b, body + 4).toInt()
                    val buf = ByteBuffer.wrap(b)
                    for (i in 0 until n) {
                        val at = body + 8 + i * 4
                        buf.putInt(at, (u32(b, at) + delta).toInt())
                    }
                }
                "co64" -> {
                    val n = u32(b, body + 4).toInt()
                    val buf = ByteBuffer.wrap(b)
                    for (i in 0 until n) {
                        val at = body + 8 + i * 8
                        buf.putLong(at, buf.getLong(at) + delta)
                    }
                }
                "tfhd" -> {
                    val flags = u32(b, body) and 0xffffff
                    if (flags and 1L != 0L) {
                        val buf = ByteBuffer.wrap(b)
                        buf.putLong(body + 8, buf.getLong(body + 8) + delta)
                    }
                }
            }
        }
    }

    /** The file with [t] written in; the input unchanged when it isn't an MP4 we understand. */
    fun write(file: ByteArray, t: Tags): ByteArray {
        val top = boxes(file, 0, file.size)
        val moov = top.firstOrNull { it.type == "moov" } ?: return file
        if (top.last().end != file.size) return file
        val kids = boxes(file, moov.start + moov.header, moov.end).filter { it.type != "udta" }
        val body = ByteArrayOutputStream()
        kids.forEach { body.write(file, it.start, it.end - it.start) }
        body.write(udta(t))
        val newMoov = box("moov", body.toByteArray())
        val delta = (newMoov.size - (moov.end - moov.start)).toLong()
        val dataAfter = top.any { (it.type == "mdat" || it.type == "moof") && it.start > moov.start }
        val tail = file.copyOfRange(moov.end, file.size)
        if (dataAfter && delta != 0L) {
            shift(newMoov, 8, newMoov.size, delta)
            shift(tail, 0, tail.size, delta)
        }
        val out = ByteArrayOutputStream(file.size + newMoov.size)
        out.write(file, 0, moov.start)
        out.write(newMoov)
        out.write(tail)
        return out.toByteArray()
    }
}
