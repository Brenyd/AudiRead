package com.brenyd.audiread.importer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import com.brenyd.audiread.data.BookFormat
import com.brenyd.audiread.data.EbookInfo
import com.brenyd.audiread.data.Section
import com.brenyd.audiread.data.TocEntry
import com.brenyd.audiread.util.Util
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.zip.ZipInputStream

object EbookImporter {

    private val formats = mapOf(
        "epub" to BookFormat.EPUB, "kepub" to BookFormat.EPUB,
        "pdf" to BookFormat.PDF,
        "mobi" to BookFormat.MOBI, "azw" to BookFormat.MOBI, "azw3" to BookFormat.MOBI,
        "kf8" to BookFormat.MOBI, "prc" to BookFormat.MOBI, "pdb" to BookFormat.MOBI,
        "fb2" to BookFormat.FB2, "fb2.zip" to BookFormat.FB2,
        "txt" to BookFormat.TXT, "text" to BookFormat.TXT,
        "html" to BookFormat.HTML, "htm" to BookFormat.HTML, "xhtml" to BookFormat.HTML,
        "cbz" to BookFormat.CBZ,
        "md" to BookFormat.MARKDOWN, "markdown" to BookFormat.MARKDOWN,
    )

    fun formatFor(name: String): BookFormat? = formats[Util.extension(name)]

    class Imported(val title: String, val author: String?, val cover: ByteArray?, val info: EbookInfo)

    suspend fun import(ctx: Context, uri: Uri, bookId: String): Imported = withContext(Dispatchers.IO) {
        val name = Util.displayName(ctx, uri)
        val format = formatFor(name) ?: throw IOException("Unsupported file type: $name")
        val dir = File(ctx.filesDir, "books/$bookId").apply { deleteRecursively(); mkdirs() }
        val fallbackTitle = Util.cleanTitle(name)
        fun info(sections: List<Section>, toc: List<TocEntry>, pages: Int = 0) =
            EbookInfo(uri.toString(), name, format, dir.absolutePath, sections, toc, pages)

        when (format) {
            BookFormat.EPUB -> {
                val tmp = File(dir, "__source.epub")
                Util.copyToFile(ctx, uri, tmp)
                Util.unzip(tmp, dir)
                tmp.delete()
                val p = EpubParser.parse(dir)
                Imported(p.title ?: fallbackTitle, p.author, p.cover?.readBytes(), info(p.sections, p.toc))
            }

            BookFormat.MOBI -> {
                val p = MobiParser.parse(Util.readBytes(ctx, uri), dir)
                val (sections, toc) = writeSections(dir, p.title, p.parts)
                Imported(p.title.ifBlank { fallbackTitle }, p.author, p.cover?.readBytes(), info(sections, toc))
            }

            BookFormat.FB2 -> {
                val bytes = Util.readBytes(ctx, uri)
                val xml = if (name.lowercase().endsWith(".zip")) unzipFirst(bytes, ".fb2") else bytes
                val p = Fb2Parser.parse(xml.inputStream(), dir)
                val title = p.title ?: fallbackTitle
                val (sections, toc) = writeSections(dir, title, p.parts)
                Imported(title, p.author, p.cover?.readBytes(), info(sections, toc))
            }

            BookFormat.TXT, BookFormat.MARKDOWN -> {
                val text = Util.decodeText(Util.readBytes(ctx, uri))
                val parts = TextParser.parse(text, format == BookFormat.MARKDOWN)
                val (sections, toc) = writeSections(dir, fallbackTitle, parts)
                Imported(fallbackTitle, null, null, info(sections, toc))
            }

            BookFormat.HTML -> {
                val bytes = Util.readBytes(ctx, uri)
                val f = File(dir, "section_0000.html")
                f.writeBytes(bytes)
                val doc = Jsoup.parse(f, null)
                val title = doc.title().trim().ifEmpty { fallbackTitle }
                val toc = doc.select("h1,h2").mapNotNull { h ->
                    val id = h.id().ifBlank { null } ?: return@mapNotNull null
                    TocEntry(h.text(), 0, id)
                }
                Imported(title, null, null, info(listOf(Section(f.name, doc.text().length.toLong().coerceAtLeast(1), title)), toc))
            }

            BookFormat.CBZ -> {
                val tmp = File(dir, "__source.cbz")
                Util.copyToFile(ctx, uri, tmp)
                val imageExt = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")
                val pagesDir = File(dir, "pages").apply { mkdirs() }
                val files = Util.unzip(tmp, pagesDir) { n -> n.substringAfterLast('.', "").lowercase() in imageExt && !n.contains("__MACOSX") }
                tmp.delete()
                if (files.isEmpty()) throw IOException("No images found in this comic")
                val root = dir.canonicalFile
                val sorted = files.map { it.canonicalFile }.sortedWith { a, b -> Util.naturalCompare(a.relativeTo(root).path, b.relativeTo(root).path) }
                val sections = sorted.map { Section(it.relativeTo(root).path, 1) }
                Imported(fallbackTitle, null, sorted.first().readBytes(), info(sections, emptyList(), sections.size))
            }

            BookFormat.PDF -> {
                val pfd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: throw IOException("Can't open PDF")
                var pages = 0
                var cover: ByteArray? = null
                PdfRenderer(pfd).use { r ->
                    pages = r.pageCount
                    if (pages > 0) {
                        val page = r.openPage(0)
                        try {
                            val w = 480
                            val h = (w * page.height.toFloat() / page.width.coerceAtLeast(1)).toInt().coerceAtLeast(1)
                            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                            bmp.eraseColor(Color.WHITE)
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            val bos = ByteArrayOutputStream()
                            bmp.compress(Bitmap.CompressFormat.JPEG, 90, bos)
                            cover = bos.toByteArray()
                        } finally {
                            page.close()
                        }
                    }
                }
                if (pages <= 0) throw IOException("This PDF has no pages")
                Imported(fallbackTitle, null, cover, info(emptyList(), emptyList(), pages))
            }
        }
    }

    private fun unzipFirst(bytes: ByteArray, suffix: String): ByteArray {
        ZipInputStream(bytes.inputStream()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (!e.isDirectory && e.name.lowercase().endsWith(suffix)) return z.readBytes()
            }
        }
        throw IOException("No $suffix file inside the archive")
    }

    private fun writeSections(dir: File, bookTitle: String, parts: List<Pair<String?, String>>): Pair<List<Section>, List<TocEntry>> {
        val sections = parts.mapIndexed { i, (t, body) ->
            val f = File(dir, String.format(Locale.US, "section_%04d.html", i))
            f.writeText(Util.htmlPage(t ?: bookTitle, body))
            val weight = runCatching { Jsoup.parse(body).text().length.toLong() }.getOrDefault(body.length.toLong() / 2)
            Section(f.name, weight.coerceAtLeast(1), t)
        }
        val toc = parts.mapIndexedNotNull { i, (t, _) -> t?.let { TocEntry(it, i) } }
            .ifEmpty { if (parts.size > 1) parts.indices.map { TocEntry("Part ${it + 1}", it) } else emptyList() }
        return sections to toc
    }
}
