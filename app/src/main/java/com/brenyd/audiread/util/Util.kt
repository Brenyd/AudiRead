package com.brenyd.audiread.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale
import java.util.zip.ZipFile

object Util {

    fun naturalCompare(a: String, b: String): Int {
        val x = a.lowercase()
        val y = b.lowercase()
        var i = 0
        var j = 0
        while (i < x.length && j < y.length) {
            val ca = x[i]
            val cb = y[j]
            if (ca.isDigit() && cb.isDigit()) {
                var ei = i
                while (ei < x.length && x[ei].isDigit()) ei++
                var ej = j
                while (ej < y.length && y[ej].isDigit()) ej++
                val na = x.substring(i, ei).trimStart('0')
                val nb = y.substring(j, ej).trimStart('0')
                if (na.length != nb.length) return na.length - nb.length
                val c = na.compareTo(nb)
                if (c != 0) return c
                i = ei
                j = ej
            } else {
                if (ca != cb) return ca - cb
                i++
                j++
            }
        }
        return (x.length - i) - (y.length - j)
    }

    fun displayName(ctx: Context, uri: Uri): String {
        runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val s = c.getString(0)
                    if (!s.isNullOrBlank()) return s
                }
            }
        }
        return Uri.decode(uri.lastPathSegment ?: "Untitled").substringAfterLast('/')
    }

    fun extension(name: String): String {
        val n = name.lowercase()
        if (n.endsWith(".fb2.zip")) return "fb2.zip"
        return n.substringAfterLast('.', "")
    }

    fun cleanTitle(name: String): String {
        var n = name
        if (n.lowercase().endsWith(".fb2.zip")) n = n.dropLast(8)
        else if (n.contains('.')) n = n.substringBeforeLast('.')
        return n.replace('_', ' ').replace(Regex("\\s+"), " ").trim().ifEmpty { name }
    }

    fun copyToFile(ctx: Context, uri: Uri, dest: File) {
        val input = ctx.contentResolver.openInputStream(uri) ?: throw IOException("Can't open file")
        input.use { i -> dest.outputStream().use { o -> i.copyTo(o, 64 * 1024) } }
    }

    fun readBytes(ctx: Context, uri: Uri): ByteArray {
        val input = ctx.contentResolver.openInputStream(uri) ?: throw IOException("Can't open file")
        return input.use { it.readBytes() }
    }

    /** Extracts a zip, refusing entries that would escape [dest]. */
    fun unzip(zipFile: File, dest: File, filter: (String) -> Boolean = { true }): List<File> {
        val out = ArrayList<File>()
        val canon = dest.canonicalPath + File.separator
        ZipFile(zipFile).use { zip ->
            for (e in zip.entries().toList()) {
                if (e.isDirectory || !filter(e.name)) continue
                val f = File(dest, e.name)
                if (!f.canonicalPath.startsWith(canon)) continue
                f.parentFile?.mkdirs()
                zip.getInputStream(e).use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                out += f
            }
        }
        return out
    }

    fun saveCover(ctx: Context, bytes: ByteArray?, id: String): String? {
        if (bytes == null || bytes.isEmpty()) return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outHeight / (sample * 2) >= 700) sample *= 2
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return null
            saveCoverBitmap(ctx, bmp, id)
        }.getOrNull()
    }

    fun saveCoverBitmap(ctx: Context, src: Bitmap, id: String): String? = runCatching {
        val maxH = 720
        val bmp = if (src.height > maxH) {
            Bitmap.createScaledBitmap(src, (src.width * maxH.toFloat() / src.height).toInt().coerceAtLeast(1), maxH, true)
        } else src
        val dir = File(ctx.filesDir, "covers").apply { mkdirs() }
        val f = File(dir, "${id}_${System.currentTimeMillis()}.jpg")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        f.absolutePath
    }.getOrNull()

    fun escapeHtml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    fun htmlPage(title: String, body: String): String =
        "<!DOCTYPE html>\n<html><head><meta charset=\"utf-8\"/>" +
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"/>" +
            "<title>${escapeHtml(title)}</title>" +
            "<style>body{font-family:serif;} h1,h2,h3{text-align:center;} .ar-center{text-align:center} " +
            "blockquote{margin-left:1.5em;font-style:italic} .poem p,.v{margin:0 0 0 1.5em}</style>" +
            "</head><body>$body</body></html>"

    /** Decodes text as UTF-8 when valid, otherwise Windows-1252. */
    fun decodeText(bytes: ByteArray): String {
        var b = bytes
        if (b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()) b = b.copyOfRange(3, b.size)
        if (b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte()) return String(b, 2, b.size - 2, Charsets.UTF_16LE)
        if (b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte()) return String(b, 2, b.size - 2, Charsets.UTF_16BE)
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(b)).toString()
        } catch (e: Exception) {
            String(b, Charset.forName("windows-1252"))
        }
    }

    fun formatTime(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
        else String.format(Locale.US, "%d:%02d", m, sec)
    }

    fun formatDuration(ms: Long): String {
        val totalMin = (ms / 60000).coerceAtLeast(0)
        val h = totalMin / 60
        val m = totalMin % 60
        return when {
            h > 0 && m > 0 -> "${h}h ${m}m"
            h > 0 -> "${h}h"
            else -> "${m}m"
        }
    }
}
