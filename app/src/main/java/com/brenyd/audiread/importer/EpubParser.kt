package com.brenyd.audiread.importer

import android.net.Uri
import com.brenyd.audiread.data.Section
import com.brenyd.audiread.data.TocEntry
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.io.File
import java.io.IOException

/** Reads an extracted EPUB 2/3: package metadata, spine, table of contents and cover. */
object EpubParser {

    class Parsed(
        val title: String?,
        val author: String?,
        val cover: File?,
        val sections: List<Section>,
        val toc: List<TocEntry>,
    )

    private class Item(val id: String, val href: String, val type: String, val props: List<String>)

    private fun Element.byName(name: String): List<Element> =
        getAllElements().filter { val n = it.normalName(); n == name || n.endsWith(":$name") }

    fun parse(dir: File): Parsed {
        val root = dir.canonicalFile
        val opfPath = runCatching {
            val c = Jsoup.parse(File(dir, "META-INF/container.xml").readText(), "", Parser.xmlParser())
            c.byName("rootfile").firstOrNull()?.attr("full-path")
        }.getOrNull()?.takeIf { it.isNotBlank() }

        val opfFile = opfPath?.let { File(root, Uri.decode(it)) }?.takeIf { it.exists() }
            ?: root.walkTopDown().firstOrNull { it.isFile && it.extension.equals("opf", true) }
            ?: throw IOException("Not a valid EPUB (no package file)")
        val opfDir = opfFile.parentFile ?: root
        val opf = Jsoup.parse(opfFile.readText(), "", Parser.xmlParser())

        val title = opf.byName("title").firstOrNull()?.text()?.trim()?.takeIf { it.isNotEmpty() }
        val author = opf.byName("creator").firstOrNull()?.text()?.trim()?.takeIf { it.isNotEmpty() }

        val items = opf.byName("item").map {
            Item(it.attr("id"), it.attr("href"), it.attr("media-type"), it.attr("properties").split(' ').filter { p -> p.isNotBlank() })
        }
        val byId = items.associateBy { it.id }

        fun fileOf(href: String, base: File = opfDir): File =
            File(base, Uri.decode(href.substringBefore('#'))).canonicalFile

        val spineFiles = opf.byName("itemref")
            .mapNotNull { byId[it.attr("idref")] }
            .map { fileOf(it.href) }
            .filter { it.exists() }
            .distinct()
        if (spineFiles.isEmpty()) throw IOException("This EPUB has no readable content")

        val indexByPath = HashMap<String, Int>()
        spineFiles.forEachIndexed { i, f -> indexByPath[f.path] = i }

        // ---- table of contents
        val toc = ArrayList<TocEntry>()
        fun addEntry(title: String, href: String?, base: File, depth: Int) {
            if (href.isNullOrBlank() || title.isBlank()) return
            val f = fileOf(href, base)
            val idx = indexByPath[f.path] ?: return
            val frag = href.substringAfter('#', "").takeIf { it.isNotBlank() }?.let { Uri.decode(it) }
            toc += TocEntry(title.trim().replace(Regex("\\s+"), " "), idx, frag, depth)
        }

        val navItem = items.firstOrNull { "nav" in it.props }
        if (navItem != null) runCatching {
            val navFile = fileOf(navItem.href)
            val doc = Jsoup.parse(navFile, "UTF-8")
            val nav = doc.select("nav").firstOrNull { it.attr("epub:type").contains("toc") } ?: doc.select("nav").firstOrNull()
            val base = navFile.parentFile ?: opfDir
            fun walk(ol: Element, depth: Int) {
                for (li in ol.children()) {
                    if (li.normalName() != "li") continue
                    val label = li.children().firstOrNull { it.normalName() == "a" || it.normalName() == "span" }
                    if (label != null) addEntry(label.text(), if (label.normalName() == "a") label.attr("href") else null, base, depth)
                    li.children().firstOrNull { it.normalName() == "ol" || it.normalName() == "ul" }?.let { walk(it, depth + 1) }
                }
            }
            nav?.children()?.firstOrNull { it.normalName() == "ol" || it.normalName() == "ul" }?.let { walk(it, 0) }
                ?: nav?.selectFirst("ol")?.let { walk(it, 0) }
        }

        if (toc.isEmpty()) runCatching {
            val ncxId = opf.byName("spine").firstOrNull()?.attr("toc")
            val ncxItem = ncxId?.let { byId[it] } ?: items.firstOrNull { it.type == "application/x-dtbncx+xml" }
            if (ncxItem != null) {
                val ncxFile = fileOf(ncxItem.href)
                val ncx = Jsoup.parse(ncxFile.readText(), "", Parser.xmlParser())
                val base = ncxFile.parentFile ?: opfDir
                fun walk(parent: Element, depth: Int) {
                    for (np in parent.children()) {
                        if (!np.normalName().endsWith("navpoint")) continue
                        val label = np.children().firstOrNull { it.normalName().endsWith("navlabel") }?.text() ?: ""
                        val src = np.children().firstOrNull { it.normalName().endsWith("content") }?.attr("src")
                        addEntry(label, src, base, depth)
                        walk(np, depth + 1)
                    }
                }
                ncx.byName("navmap").firstOrNull()?.let { walk(it, 0) }
            }
        }

        val firstTitle = HashMap<Int, String>()
        toc.forEach { if (!firstTitle.containsKey(it.section)) firstTitle[it.section] = it.title }

        val sections = spineFiles.mapIndexed { i, f ->
            val weight = runCatching { Jsoup.parse(f, "UTF-8").text().length.toLong() }.getOrDefault(f.length() / 3)
            Section(f.relativeTo(root).path, weight.coerceAtLeast(1), firstTitle[i])
        }

        // ---- cover
        val coverMeta = opf.byName("meta").firstOrNull { it.attr("name") == "cover" }?.attr("content")
        val coverItem = items.firstOrNull { "cover-image" in it.props }
            ?: coverMeta?.let { c -> byId[c] ?: items.firstOrNull { it.href == c } }
            ?: items.firstOrNull { it.type.startsWith("image/") && (it.id.contains("cover", true) || it.href.contains("cover", true)) }
        val cover = coverItem?.takeIf { it.type.startsWith("image/") || it.type.isBlank() }
            ?.let { fileOf(it.href) }?.takeIf { it.exists() }

        return Parsed(title, author, cover, sections, toc)
    }
}
