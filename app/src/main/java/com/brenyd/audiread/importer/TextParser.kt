package com.brenyd.audiread.importer

import com.brenyd.audiread.util.Util

/** Turns plain text or Markdown into HTML chapters, detecting chapter headings where possible. */
object TextParser {

    private val heading = Regex(
        "^\\s*(chapter|part|book|prologue|epilogue|introduction|preface|foreword|afterword|interlude|" +
            "chapitre|kapitel|cap[ií]tulo|capitolo|hoofdstuk)\\b.{0,80}$",
        RegexOption.IGNORE_CASE,
    )
    private val mdHeading = Regex("^(#{1,2})\\s+(.+)$")

    fun parse(text: String, markdown: Boolean): List<Pair<String?, String>> {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val chapters = ArrayList<Pair<String?, MutableList<String>>>()
        var current: Pair<String?, MutableList<String>> = Pair(null, ArrayList<String>())
        chapters += current
        var headings = 0
        for (line in lines) {
            val t = line.trim()
            val title: String? = if (markdown) mdHeading.find(t)?.groupValues?.get(2)
            else if (t.length in 3..90 && heading.matches(t)) t else null
            if (title != null) {
                headings++
                current = Pair<String?, MutableList<String>>(title, ArrayList<String>())
                chapters += current
            } else current.second += line
        }
        val usable = chapters.filter { it.first != null || it.second.any { l -> l.isNotBlank() } }
        val blankRatio = lines.count { it.isBlank() }.toDouble() / lines.size.coerceAtLeast(1)
        val result = ArrayList<Pair<String?, String>>()
        val chosen = if (headings >= 2 || markdown) usable else listOf<Pair<String?, MutableList<String>>>(null to lines.toMutableList())
        for ((title, body) in chosen) {
            val paras = paragraphs(body, blankRatio < 0.02)
            val htmlParas = paras.map { p -> if (markdown) mdBlock(p) else "<p>${Util.escapeHtml(p)}</p>" }
            // keep each file a reasonable size
            var chunk = StringBuilder()
            var part = 0
            if (title != null) chunk.append("<h2>").append(Util.escapeHtml(title)).append("</h2>")
            for (h in htmlParas) {
                chunk.append(h).append('\n')
                if (chunk.length > 90_000) {
                    result += (if (part == 0) title else title?.let { "$it (cont.)" }) to chunk.toString()
                    chunk = StringBuilder()
                    part++
                }
            }
            if (chunk.isNotBlank()) result += (if (part == 0) title else title?.let { "$it (cont.)" }) to chunk.toString()
        }
        return result.ifEmpty { listOf(null to "<p></p>") }
    }

    private fun paragraphs(lines: List<String>, linePerParagraph: Boolean): List<String> {
        if (linePerParagraph) return lines.map { it.trim() }.filter { it.isNotEmpty() }
        val out = ArrayList<String>()
        val cur = StringBuilder()
        for (l in lines) {
            if (l.isBlank()) {
                if (cur.isNotEmpty()) { out += cur.toString(); cur.setLength(0) }
            } else {
                if (cur.isNotEmpty()) cur.append(' ')
                cur.append(l.trim())
            }
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out
    }

    private fun mdInline(s: String): String {
        var h = Util.escapeHtml(s)
        h = h.replace(Regex("\\*\\*(.+?)\\*\\*"), "<strong>$1</strong>")
        h = h.replace(Regex("(?<![*\\w])\\*(?!\\s)(.+?)\\*(?!\\w)"), "<em>$1</em>")
        h = h.replace(Regex("(?<!\\w)_(?!\\s)(.+?)_(?!\\w)"), "<em>$1</em>")
        h = h.replace(Regex("`([^`]+)`"), "<code>$1</code>")
        return h
    }

    private fun mdBlock(p: String): String {
        val m = Regex("^(#{3,6})\\s+(.+)$").find(p)
        if (m != null) return "<h3>${mdInline(m.groupValues[2])}</h3>"
        if (p.startsWith("> ")) return "<blockquote>${mdInline(p.removePrefix("> "))}</blockquote>"
        if (p.startsWith("- ") || p.startsWith("* ")) return "<p>&bull; ${mdInline(p.substring(2))}</p>"
        if (p.matches(Regex("^-{3,}$|^\\*{3,}$"))) return "<hr/>"
        return "<p>${mdInline(p)}</p>"
    }
}
