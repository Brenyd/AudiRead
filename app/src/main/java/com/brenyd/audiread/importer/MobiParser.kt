package com.brenyd.audiread.importer

import org.jsoup.Jsoup
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.charset.Charset

/**
 * Reads DRM-free Mobipocket / Kindle files (.mobi, .prc, .azw, .azw3).
 * Text records are PalmDOC-decompressed and split at page breaks into chapters.
 */
object MobiParser {

    class Parsed(val title: String, val author: String?, val parts: List<Pair<String?, String>>, val cover: File?)

    private fun u16(b: ByteArray, o: Int): Int =
        if (o + 2 > b.size || o < 0) 0 else ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

    private fun u32(b: ByteArray, o: Int): Long =
        if (o + 4 > b.size || o < 0) 0L else ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or
            ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)

    fun parse(data: ByteArray, outDir: File): Parsed {
        if (data.size < 86) throw IOException("File too small to be an ebook")
        val n = u16(data, 76)
        if (n < 2) throw IOException("Not a Mobipocket file")
        val offs = IntArray(n) { u32(data, 78 + it * 8).toInt() }
        fun rec(i: Int): ByteArray {
            val s = offs[i].coerceIn(0, data.size)
            val e = (if (i + 1 < n) offs[i + 1] else data.size).coerceIn(s, data.size)
            return data.copyOfRange(s, e)
        }

        val r0 = rec(0)
        val compression = u16(r0, 0)
        val textRecs = u16(r0, 8)
        val encryption = u16(r0, 12)
        if (encryption != 0) throw IOException("This book is DRM-protected and can't be opened")
        if (compression == 17480) throw IOException("This Kindle file uses HUFF/CDIC compression, which isn't supported")

        var cs: Charset = Charset.forName("windows-1252")
        var firstImage = -1
        var extraFlags = 0
        var title: String? = null
        var author: String? = null
        var coverIdx = -1

        val isMobi = r0.size > 24 && String(r0, 16, 4, Charsets.ISO_8859_1) == "MOBI"
        if (isMobi) {
            val hl = u32(r0, 20).toInt()
            if (u32(r0, 28) == 65001L) cs = Charsets.UTF_8
            firstImage = u32(r0, 0x6C).toInt()
            if (hl >= 0xE4) extraFlags = u16(r0, 0xF2)
            val fnOff = u32(r0, 0x54).toInt()
            val fnLen = u32(r0, 0x58).toInt()
            if (fnOff > 0 && fnLen > 0 && fnOff + fnLen <= r0.size) title = String(r0, fnOff, fnLen, cs)
            if ((u32(r0, 0x80) and 0x40L) != 0L) {
                var p = 16 + hl
                if (p + 12 <= r0.size && String(r0, p, 4, Charsets.ISO_8859_1) == "EXTH") {
                    val count = u32(r0, p + 8).toInt()
                    p += 12
                    for (k in 0 until count) {
                        if (p + 8 > r0.size) break
                        val type = u32(r0, p).toInt()
                        val len = u32(r0, p + 4).toInt()
                        if (len < 8 || p + len > r0.size) break
                        val d = r0.copyOfRange(p + 8, p + len)
                        when (type) {
                            100 -> { val a = String(d, cs).trim(); author = if (author == null) a else "$author, $a" }
                            503 -> title = String(d, cs).trim()
                            201 -> coverIdx = u32(d, 0).toInt()
                        }
                        p += len
                    }
                }
            }
        }
        if (title.isNullOrBlank()) {
            title = String(data, 0, 32, Charsets.ISO_8859_1).trim('\u0000', ' ').replace('_', ' ')
        }

        val out = ByteArrayOutputStream()
        for (i in 1..textRecs) {
            if (i >= n) break
            var r = rec(i)
            val trail = trailingSize(r, extraFlags)
            if (trail in 1 until r.size) r = r.copyOf(r.size - trail)
            out.write(if (compression == 2) palmDoc(r) else r)
        }
        var html = String(out.toByteArray(), cs)

        // images
        val images = HashMap<Int, String>()
        if (firstImage in 1 until n) {
            for (ri in firstImage until n) {
                val d = rec(ri)
                val ext = imageExt(d) ?: continue
                val idx = ri - firstImage + 1
                val name = "img_$idx.$ext"
                File(outDir, name).writeBytes(d)
                images[idx] = name
            }
        }
        val cover = (if (coverIdx >= 0) images[coverIdx + 1] else null)?.let { File(outDir, it) }

        html = Regex("\\brecindex\\s*=\\s*[\"']?0*(\\d+)[\"']?", RegexOption.IGNORE_CASE).replace(html) { m ->
            images[m.groupValues[1].toInt()]?.let { "src=\"$it\"" } ?: m.value
        }
        html = Regex("kindle:embed:([0-9A-Va-v]{4})(\\?mime=[^\"')\\s]*)?").replace(html) { m ->
            val idx = m.groupValues[1].uppercase().fold(0) { acc, ch -> acc * 32 + (if (ch.isDigit()) ch - '0' else ch - 'A' + 10) }
            images[idx] ?: m.value
        }

        val parts = ArrayList<String>()
        for (body in extractBodies(html)) parts += splitPagebreaks(body)
        val merged = mergeSmall(parts.map { it.trim() }.filter { it.isNotEmpty() })
        if (merged.isEmpty()) throw IOException("No readable text found in this file")

        val titled = merged.map { part ->
            val h = runCatching { Jsoup.parse(part).selectFirst("h1,h2,h3,h4")?.text()?.trim() }.getOrNull()
            (h?.takeIf { it.isNotEmpty() && it.length < 120 }) to part
        }
        return Parsed(title ?: "Untitled", author, titled, cover)
    }

    private fun imageExt(d: ByteArray): String? {
        if (d.size < 4) return null
        val b0 = d[0].toInt() and 0xFF
        val b1 = d[1].toInt() and 0xFF
        return when {
            b0 == 0xFF && b1 == 0xD8 -> "jpg"
            b0 == 0x89 && d[1] == 'P'.code.toByte() -> "png"
            d[0] == 'G'.code.toByte() && d[1] == 'I'.code.toByte() && d[2] == 'F'.code.toByte() -> "gif"
            d[0] == 'B'.code.toByte() && d[1] == 'M'.code.toByte() -> "bmp"
            else -> null
        }
    }

    private fun trailingSize(d: ByteArray, flags: Int): Int {
        var num = 0
        var f = flags shr 1
        while (f != 0) {
            if (f and 1 != 0) num += backwardVarint(d, d.size - num)
            f = f shr 1
        }
        if (flags and 1 != 0) {
            val idx = d.size - num - 1
            if (idx >= 0) num += (d[idx].toInt() and 3) + 1
        }
        return num
    }

    private fun backwardVarint(d: ByteArray, end: Int): Int {
        var v = 0
        val e = end.coerceIn(0, d.size)
        for (k in maxOf(0, e - 4) until e) {
            val b = d[k].toInt() and 0xFF
            if (b and 0x80 != 0) v = 0
            v = (v shl 7) or (b and 0x7F)
        }
        return v
    }

    private fun palmDoc(src: ByteArray): ByteArray {
        var buf = ByteArray(maxOf(4096, src.size * 4))
        var o = 0
        fun ensure(k: Int) {
            if (o + k > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, o + k))
        }
        var i = 0
        while (i < src.size) {
            val c = src[i++].toInt() and 0xFF
            when {
                c == 0 || c in 9..0x7F -> { ensure(1); buf[o++] = c.toByte() }
                c in 1..8 -> {
                    ensure(c)
                    for (k in 0 until c) if (i < src.size) buf[o++] = src[i++]
                }
                c >= 0xC0 -> { ensure(2); buf[o++] = ' '.code.toByte(); buf[o++] = (c xor 0x80).toByte() }
                else -> {
                    if (i >= src.size) break
                    val c2 = src[i++].toInt() and 0xFF
                    val v = (c shl 8) or c2
                    val dist = (v shr 3) and 0x7FF
                    val len = (v and 7) + 3
                    ensure(len)
                    for (k in 0 until len) {
                        val from = o - dist
                        buf[o] = if (dist > 0 && from >= 0) buf[from] else 0
                        o++
                    }
                }
            }
        }
        return buf.copyOf(o)
    }

    private fun extractBodies(html: String): List<String> {
        val res = ArrayList<String>()
        var p = 0
        while (true) {
            val b = html.indexOf("<body", p, ignoreCase = true)
            if (b < 0) break
            val gt = html.indexOf('>', b)
            if (gt < 0) break
            var e = html.indexOf("</body", gt, ignoreCase = true)
            if (e < 0) e = html.length
            res += html.substring(gt + 1, e)
            p = e + 1
        }
        if (res.isEmpty()) res += html
        return res
    }

    private fun splitPagebreaks(body: String): List<String> {
        val res = ArrayList<String>()
        var start = 0
        while (true) {
            val b = body.indexOf("<mbp:pagebreak", start, ignoreCase = true)
            if (b < 0) break
            res += body.substring(start, b)
            val gt = body.indexOf('>', b)
            start = if (gt < 0) body.length else gt + 1
        }
        res += body.substring(start)
        return res
    }

    private fun mergeSmall(parts: List<String>): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        for (p in parts) {
            cur.append(p).append('\n')
            if (cur.length >= 3000) {
                out += cur.toString()
                cur.setLength(0)
            }
        }
        if (cur.isNotBlank()) {
            if (out.isNotEmpty() && cur.length < 600) out[out.lastIndex] = out.last() + cur.toString()
            else out += cur.toString()
        }
        return out
    }
}
