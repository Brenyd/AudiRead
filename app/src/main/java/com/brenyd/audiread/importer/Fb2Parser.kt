package com.brenyd.audiread.importer

import android.util.Base64
import com.brenyd.audiread.util.Util
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import java.io.File
import java.io.InputStream

/** Converts FictionBook 2 (.fb2) into HTML chapters. */
object Fb2Parser {

    class Parsed(val title: String?, val author: String?, val parts: List<Pair<String?, String>>, val cover: File?)

    private fun Element.local(): String = normalName().substringAfter(':')
    private fun Element.first(name: String): Element? = getAllElements().firstOrNull { it !== this && it.local() == name }
    private fun Element.hrefAttr(): String? =
        attributes().firstOrNull { it.key.endsWith("href", ignoreCase = true) }?.value?.removePrefix("#")

    fun parse(input: InputStream, outDir: File): Parsed {
        val doc = Jsoup.parse(input, null, "", Parser.xmlParser())
        val root = doc.children().firstOrNull() ?: doc

        val binaries = HashMap<String, String>()
        root.getAllElements().filter { it.local() == "binary" }.forEach { b ->
            val type = b.attr("content-type")
            val ext = when {
                "png" in type -> "png"
                "gif" in type -> "gif"
                else -> "jpg"
            }
            val bytes = runCatching { Base64.decode(b.text().replace(Regex("\\s"), ""), Base64.DEFAULT) }.getOrNull()
                ?: return@forEach
            val name = "bin_${binaries.size}.$ext"
            File(outDir, name).writeBytes(bytes)
            binaries[b.attr("id")] = name
        }

        val info = root.first("title-info")
        val title = info?.first("book-title")?.text()?.trim()
        val author = info?.first("author")?.let { a ->
            listOf("first-name", "middle-name", "last-name").mapNotNull { a.first(it)?.text()?.trim() }
                .filter { it.isNotEmpty() }.joinToString(" ").ifEmpty { a.first("nickname")?.text() }
        }
        val cover = info?.first("coverpage")?.first("image")?.hrefAttr()?.let { binaries[it] }?.let { File(outDir, it) }

        val bodies = root.children().filter { it.local() == "body" }
        val parts = ArrayList<Pair<String?, String>>()
        for ((bi, body) in bodies.withIndex()) {
            val isNotes = body.attr("name").isNotBlank() && bi > 0
            if (isNotes) {
                val sb = StringBuilder("<h2>Notes</h2>")
                conv(body, sb, 1, binaries)
                parts += "Notes" to sb.toString()
                continue
            }
            val sections = body.children().filter { it.local() == "section" }
            if (sections.isEmpty()) {
                val sb = StringBuilder()
                conv(body, sb, 0, binaries)
                parts += null to sb.toString()
                continue
            }
            val intro = StringBuilder()
            for (c in body.children()) if (c.local() != "section") convElement(c, intro, 0, binaries)
            sections.forEachIndexed { i, s ->
                val sb = StringBuilder()
                if (i == 0 && intro.isNotBlank()) sb.append(intro)
                conv(s, sb, 1, binaries)
                val t = s.children().firstOrNull { it.local() == "title" }?.text()?.trim()?.takeIf { it.isNotEmpty() }
                parts += t to sb.toString()
            }
        }
        return Parsed(title, author, parts, cover)
    }

    private fun conv(e: Element, sb: StringBuilder, depth: Int, bin: Map<String, String>) {
        for (node in e.childNodes()) {
            when (node) {
                is TextNode -> sb.append(Util.escapeHtml(node.text()))
                is Element -> convElement(node, sb, depth, bin)
            }
        }
    }

    private fun convElement(n: Element, sb: StringBuilder, depth: Int, bin: Map<String, String>) {
        fun wrap(open: String, close: String, d: Int = depth) {
            sb.append(open); conv(n, sb, d, bin); sb.append(close)
        }
        when (n.local()) {
            "p" -> wrap("<p>", "</p>")
            "title" -> {
                val h = if (depth <= 1) "h2" else "h3"
                sb.append("<$h>")
                val lines = n.children().filter { it.local() == "p" }
                if (lines.isEmpty()) conv(n, sb, depth, bin)
                else lines.forEachIndexed { i, p -> if (i > 0) sb.append("<br/>"); conv(p, sb, depth, bin) }
                sb.append("</$h>")
            }
            "section" -> wrap("<div class=\"section\">", "</div>", depth + 1)
            "emphasis" -> wrap("<em>", "</em>")
            "strong" -> wrap("<strong>", "</strong>")
            "strikethrough" -> wrap("<s>", "</s>")
            "sub" -> wrap("<sub>", "</sub>")
            "sup" -> wrap("<sup>", "</sup>")
            "code" -> wrap("<code>", "</code>")
            "epigraph" -> wrap("<blockquote class=\"epigraph\">", "</blockquote>")
            "cite" -> wrap("<blockquote>", "</blockquote>")
            "poem" -> wrap("<div class=\"poem\">", "</div>")
            "stanza" -> wrap("<div class=\"stanza\">", "</div><br/>")
            "v" -> wrap("<p class=\"v\">", "</p>")
            "subtitle" -> wrap("<h4>", "</h4>")
            "text-author" -> wrap("<p class=\"ar-center\"><em>", "</em></p>")
            "empty-line" -> sb.append("<br/>")
            "image" -> n.hrefAttr()?.let { bin[it] }?.let { sb.append("<p class=\"ar-center\"><img src=\"$it\"/></p>") }
            "a" -> wrap("<sup>", "</sup>")
            "table", "tr", "td", "th" -> wrap("<${n.local()}>", "</${n.local()}>")
            "binary", "description" -> Unit
            else -> conv(n, sb, depth, bin)
        }
    }
}
