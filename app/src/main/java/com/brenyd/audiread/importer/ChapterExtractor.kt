package com.brenyd.audiread.importer

import android.content.Context
import android.net.Uri
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** Random-access reads over a file channel. */
class ByteReader(private val ch: FileChannel) {
    val size: Long = ch.size()

    fun read(pos: Long, len: Int): ByteArray {
        if (pos < 0 || len <= 0 || pos >= size) return ByteArray(0)
        val n = minOf(len.toLong(), size - pos).toInt()
        val buf = ByteBuffer.allocate(n)
        var p = pos
        while (buf.hasRemaining()) {
            val k = ch.read(buf, p)
            if (k <= 0) break
            p += k
        }
        return if (buf.position() == n) buf.array() else buf.array().copyOf(buf.position())
    }
}

object Bytes {
    fun u16(b: ByteArray, o: Int): Int =
        if (o < 0 || o + 2 > b.size) 0 else ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

    fun u32(b: ByteArray, o: Int): Long =
        if (o < 0 || o + 4 > b.size) 0L else ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or
            ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)

    fun u64(b: ByteArray, o: Int): Long = if (o < 0 || o + 8 > b.size) 0L else (u32(b, o) shl 32) or u32(b, o + 4)

    fun synchsafe(b: ByteArray, o: Int): Int =
        if (o < 0 || o + 4 > b.size) 0 else ((b[o].toInt() and 0x7F) shl 21) or ((b[o + 1].toInt() and 0x7F) shl 14) or
            ((b[o + 2].toInt() and 0x7F) shl 7) or (b[o + 3].toInt() and 0x7F)
}

/** Reads embedded chapter markers: MP4/M4B (QuickTime text track or Nero chpl) and MP3 (ID3v2 CHAP). */
object ChapterExtractor {
    fun extract(ctx: Context, uri: Uri): List<Pair<String, Long>> {
        val pfd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: return emptyList()
        return pfd.use { p ->
            FileInputStream(p.fileDescriptor).channel.use { ch ->
                val r = ByteReader(ch)
                val head = r.read(0, 12)
                when {
                    head.size >= 8 && String(head, 4, 4, Charsets.ISO_8859_1) == "ftyp" -> Mp4Chapters.read(r)
                    head.size >= 3 && String(head, 0, 3, Charsets.ISO_8859_1) == "ID3" -> Id3Chapters.read(r)
                    else -> emptyList()
                }
            }
        }
    }
}

object Mp4Chapters {
    private class Atom(val type: String, val start: Long, val end: Long)

    private fun children(r: ByteReader, start: Long, end: Long): List<Atom> {
        val out = ArrayList<Atom>()
        var p = start
        while (p + 8 <= end) {
            val h = r.read(p, 16)
            if (h.size < 8) break
            var size = Bytes.u32(h, 0)
            val type = String(h, 4, 4, Charsets.ISO_8859_1)
            var hdr = 8
            if (size == 1L) {
                if (h.size < 16) break
                size = Bytes.u64(h, 8)
                hdr = 16
            } else if (size == 0L) size = end - p
            if (size < hdr) break
            out += Atom(type, p + hdr, minOf(p + size, end))
            p += size
        }
        return out
    }

    private fun find(r: ByteReader, from: Atom, vararg path: String): Atom? {
        var cur = from
        for (t in path) cur = children(r, cur.start, cur.end).firstOrNull { it.type == t } ?: return null
        return cur
    }

    private fun box(r: ByteReader, a: Atom): ByteArray = r.read(a.start, (a.end - a.start).coerceAtMost(16L shl 20).toInt())

    fun read(r: ByteReader): List<Pair<String, Long>> {
        val moov = children(r, 0, r.size).firstOrNull { it.type == "moov" } ?: return emptyList()
        val qt = runCatching { quickTime(r, moov) }.getOrDefault(emptyList())
        if (qt.size > 1) return qt
        val nero = runCatching { nero(r, moov) }.getOrDefault(emptyList())
        return if (nero.isNotEmpty()) nero else qt
    }

    private fun nero(r: ByteReader, moov: Atom): List<Pair<String, Long>> {
        val chpl = find(r, moov, "udta", "chpl") ?: return emptyList()
        val d = box(r, chpl)
        if (d.size < 5) return emptyList()
        var p = 4
        if (d[0].toInt() != 0) p += 4
        val count = d.getOrNull(p)?.toInt()?.and(0xFF) ?: return emptyList()
        p += 1
        val out = ArrayList<Pair<String, Long>>()
        for (i in 0 until count) {
            if (p + 9 > d.size) break
            val start = Bytes.u64(d, p)
            val len = d[p + 8].toInt() and 0xFF
            p += 9
            if (p + len > d.size) break
            val title = String(d, p, len, Charsets.UTF_8).trim()
            p += len
            out += title to start / 10_000
        }
        return out
    }

    private fun quickTime(r: ByteReader, moov: Atom): List<Pair<String, Long>> {
        class T(val id: Long, val trak: Atom, val handler: String)
        val chapIds = HashSet<Long>()
        val tracks = children(r, moov.start, moov.end).filter { it.type == "trak" }.map { t ->
            val kids = children(r, t.start, t.end)
            val tkhd = kids.firstOrNull { it.type == "tkhd" }?.let { r.read(it.start, 24) }
            val id = when {
                tkhd == null || tkhd.isEmpty() -> -1L
                tkhd[0].toInt() == 1 -> Bytes.u32(tkhd, 4 + 8 + 8)
                else -> Bytes.u32(tkhd, 4 + 4 + 4)
            }
            kids.firstOrNull { it.type == "tref" }?.let { tref ->
                children(r, tref.start, tref.end).firstOrNull { it.type == "chap" }?.let { c ->
                    val d = box(r, c)
                    for (k in 0 until d.size / 4) chapIds += Bytes.u32(d, k * 4)
                }
            }
            val hdlr = find(r, t, "mdia", "hdlr")?.let { String(r.read(it.start + 8, 4), Charsets.ISO_8859_1) } ?: ""
            T(id, t, hdlr)
        }
        val text = tracks.firstOrNull { it.id in chapIds }
            ?: tracks.firstOrNull { chapIds.isEmpty() && it.handler == "text" }
            ?: return emptyList()

        val mdhd = find(r, text.trak, "mdia", "mdhd") ?: return emptyList()
        val mh = r.read(mdhd.start, 32)
        val timescale = if (mh.isNotEmpty() && mh[0].toInt() == 1) Bytes.u32(mh, 20) else Bytes.u32(mh, 12)
        if (timescale <= 0) return emptyList()

        val stbl = find(r, text.trak, "mdia", "minf", "stbl") ?: return emptyList()
        val sk = children(r, stbl.start, stbl.end)
        fun get(t: String): ByteArray? = sk.firstOrNull { it.type == t }?.let { box(r, it) }

        val stts = get("stts") ?: return emptyList()
        val durations = ArrayList<Long>()
        val sttsN = Bytes.u32(stts, 4).toInt()
        for (i in 0 until sttsN) {
            val o = 8 + i * 8
            if (o + 8 > stts.size) break
            val cnt = Bytes.u32(stts, o)
            val delta = Bytes.u32(stts, o + 4)
            for (k in 0 until cnt.coerceAtMost(100_000)) durations += delta
        }

        val stsz = get("stsz") ?: return emptyList()
        val fixed = Bytes.u32(stsz, 4)
        val count = Bytes.u32(stsz, 8).toInt().coerceIn(0, 100_000)
        val sizes = LongArray(count) { if (fixed != 0L) fixed else Bytes.u32(stsz, 12 + it * 4) }

        val chunkOffsets: LongArray = get("stco")?.let { d ->
            LongArray(Bytes.u32(d, 4).toInt().coerceIn(0, 1_000_000)) { Bytes.u32(d, 8 + it * 4) }
        } ?: get("co64")?.let { d ->
            LongArray(Bytes.u32(d, 4).toInt().coerceIn(0, 1_000_000)) { Bytes.u64(d, 8 + it * 8) }
        } ?: return emptyList()

        val stsc = get("stsc") ?: return emptyList()
        val scN = Bytes.u32(stsc, 4).toInt().coerceIn(0, 1_000_000)
        val firstChunk = IntArray(scN) { Bytes.u32(stsc, 8 + it * 12).toInt() }
        val perChunk = IntArray(scN) { Bytes.u32(stsc, 12 + it * 12).toInt() }

        val offsets = LongArray(count)
        var s = 0
        var e = 0
        for (c in 1..chunkOffsets.size) {
            while (e + 1 < scN && firstChunk[e + 1] <= c) e++
            var off = chunkOffsets[c - 1]
            val spc = if (scN == 0) 1 else perChunk[e]
            for (k in 0 until spc) {
                if (s >= count) break
                offsets[s] = off
                off += sizes[s]
                s++
            }
            if (s >= count) break
        }

        val out = ArrayList<Pair<String, Long>>()
        var t = 0L
        for (i in 0 until count) {
            val sd = r.read(offsets[i], sizes[i].toInt().coerceIn(0, 4096))
            val len = Bytes.u16(sd, 0)
            val title = if (len > 0 && sd.size >= 2 + len) {
                if (len >= 2 && sd[2] == 0xFE.toByte() && sd[3] == 0xFF.toByte()) String(sd, 4, len - 2, Charsets.UTF_16BE)
                else String(sd, 2, len, Charsets.UTF_8)
            } else ""
            out += title.trim() to (t * 1000 / timescale)
            t += durations.getOrElse(i) { 0L }
        }
        return out
    }
}

object Id3Chapters {
    fun read(r: ByteReader): List<Pair<String, Long>> {
        val h = r.read(0, 10)
        if (h.size < 10) return emptyList()
        val ver = h[3].toInt()
        if (ver < 3) return emptyList()
        val size = Bytes.synchsafe(h, 6)
        val tag = r.read(10, size.coerceAtMost(64 shl 20))
        var p = 0
        if (h[5].toInt() and 0x40 != 0) {
            p = if (ver == 4) Bytes.synchsafe(tag, 0) else Bytes.u32(tag, 0).toInt() + 4
        }
        val out = ArrayList<Pair<String, Long>>()
        frames(tag, p, tag.size, ver) { id, data ->
            if (id == "CHAP") chap(data, ver)?.let { out += it }
        }
        return out.sortedBy { it.second }
    }

    private fun frames(b: ByteArray, start: Int, end: Int, ver: Int, cb: (String, ByteArray) -> Unit) {
        var p = start
        while (p + 10 <= end) {
            if (b[p].toInt() == 0) break
            val id = String(b, p, 4, Charsets.ISO_8859_1)
            val sz = if (ver == 4) Bytes.synchsafe(b, p + 4) else Bytes.u32(b, p + 4).toInt()
            if (sz <= 0 || p + 10 + sz > end) break
            cb(id, b.copyOfRange(p + 10, p + 10 + sz))
            p += 10 + sz
        }
    }

    private fun chap(d: ByteArray, ver: Int): Pair<String, Long>? {
        var p = 0
        while (p < d.size && d[p].toInt() != 0) p++
        p++
        if (p + 16 > d.size) return null
        val start = Bytes.u32(d, p)
        p += 16
        var title = ""
        frames(d, p, d.size, ver) { id, data -> if (id == "TIT2" && title.isEmpty()) title = text(data) }
        return title to start
    }

    private fun text(data: ByteArray): String {
        if (data.isEmpty()) return ""
        val cs = when (data[0].toInt()) {
            1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            3 -> Charsets.UTF_8
            else -> Charsets.ISO_8859_1
        }
        return String(data, 1, data.size - 1, cs).trim('\u0000', ' ', '﻿')
    }
}
