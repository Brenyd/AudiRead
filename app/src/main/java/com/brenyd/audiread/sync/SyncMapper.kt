package com.brenyd.audiread.sync

import com.brenyd.audiread.data.Book
import com.brenyd.audiread.data.EbookInfo
import com.brenyd.audiread.data.ReadPosition
import com.brenyd.audiread.data.SyncAnchor
import kotlin.math.abs

/**
 * Maps between a position in the text (0..1 across the whole book) and a position in the audiobook (ms).
 *
 * The map is piecewise linear between anchors:
 *  - the start and end of the book,
 *  - automatic anchors where an audio chapter title matches a table-of-contents entry,
 *  - sync points the user pinned by hand (these always win).
 */
object SyncMapper {

    private val startsCache = HashMap<String, DoubleArray>()
    private val anchorCache = HashMap<String, Pair<Int, List<SyncAnchor>>>()

    fun isPaged(e: EbookInfo) = e.fixedLayout

    /** Cumulative 0..1 start of each section (size n+1). */
    fun starts(e: EbookInfo): DoubleArray = synchronized(startsCache) {
        startsCache.getOrPut(e.contentDir + ":" + e.sections.size) {
            val w = e.sections.map { it.weight.coerceAtLeast(1).toDouble() }
            val total = w.sum().takeIf { it > 0 } ?: 1.0
            val s = DoubleArray(w.size + 1)
            for (i in w.indices) s[i + 1] = s[i] + w[i] / total
            s
        }
    }

    fun textGlobal(e: EbookInfo, section: Int, progression: Double): Double {
        if (isPaged(e)) {
            val c = e.pageCount.coerceAtLeast(1)
            return (section.toDouble() / c).coerceIn(0.0, 1.0)
        }
        val s = starts(e)
        if (s.size < 2) return 0.0
        val i = section.coerceIn(0, s.size - 2)
        return (s[i] + (s[i + 1] - s[i]) * progression.coerceIn(0.0, 1.0)).coerceIn(0.0, 1.0)
    }

    fun textLocal(e: EbookInfo, t: Double): ReadPosition {
        val tt = t.coerceIn(0.0, 1.0)
        if (isPaged(e)) {
            val c = e.pageCount.coerceAtLeast(1)
            return ReadPosition(minOf((tt * c).toInt(), c - 1), 0.0, tt)
        }
        val s = starts(e)
        if (s.size < 2) return ReadPosition(0, 0.0, 0.0)
        var i = 0
        while (i < s.size - 2 && s[i + 1] <= tt) i++
        val span = s[i + 1] - s[i]
        val prog = if (span > 0) ((tt - s[i]) / span).coerceIn(0.0, 0.9999) else 0.0
        return ReadPosition(i, prog, tt)
    }

    fun sectionStart(e: EbookInfo, section: Int): Double = textGlobal(e, section, 0.0)

    /** Automatic anchors from matching audio chapter titles to the table of contents. */
    fun autoAnchors(book: Book): List<SyncAnchor> {
        val e = book.ebook ?: return emptyList()
        val audio = book.audio ?: return emptyList()
        if (isPaged(e)) return emptyList()
        val toc = e.toc.filter { it.section in e.sections.indices }.distinctBy { it.section }.sortedBy { it.section }
        val chapters = audio.chapters
        if (toc.size < 2 || chapters.size < 2) return emptyList()

        val tocTokens = toc.map { TitleMatch.tokens(it.title) }
        val chTokens = chapters.map { TitleMatch.tokens(it.title) }
        val matched = ArrayList<SyncAnchor>()
        var j = 0
        for (i in chapters.indices) {
            var best = -1
            var bestScore = 0.0
            for (k in j until minOf(toc.size, j + 12)) {
                val sc = TitleMatch.score(chTokens[i], tocTokens[k])
                if (sc > bestScore) { bestScore = sc; best = k }
            }
            if (best >= 0 && bestScore >= 0.5) {
                matched += SyncAnchor(sectionStart(e, toc[best].section), chapters[i].startMs)
                j = best + 1
            }
        }
        if (matched.size >= maxOf(2, chapters.size / 3)) return matched

        // Fall back to pairing by order when the counts line up.
        val credits = Regex("credits|opening|closing|copyright", RegexOption.IGNORE_CASE)
        val chs = chapters.filterNot { credits.containsMatchIn(it.title) }
        if (chs.size == toc.size) return chs.mapIndexed { i, c -> SyncAnchor(sectionStart(e, toc[i].section), c.startMs) }
        return matched
    }

    fun anchors(book: Book): List<SyncAnchor> {
        val total = book.audio?.totalMs ?: 0L
        val ends = listOf(SyncAnchor(0.0, 0L), SyncAnchor(1.0, total))
        if (total <= 0L || book.ebook == null) return ends
        val key = book.id
        val al = book.alignedAnchors
        val sig = book.syncAnchors.hashCode() * 31 + (book.ebook.toc.size * 17) + book.audio!!.chapters.size +
            total.hashCode() + al.size * 7919 + (al.firstOrNull()?.hashCode() ?: 0) + (al.lastOrNull()?.hashCode() ?: 0) * 3
        synchronized(anchorCache) { anchorCache[key]?.let { if (it.first == sig) return it.second } }

        val manual = book.syncAnchors.sortedBy { it.text }
        // A sync file (speech alignment) beats chapter-title matching; hand-pinned points beat both.
        val base = if (al.isNotEmpty()) al else autoAnchors(book)
        val keep = base.filter { a ->
            manual.all { m -> abs(m.text - a.text) > 0.002 && ((a.text < m.text) == (a.audioMs < m.audioMs)) }
        }
        val all = (manual + keep + ends).sortedWith(compareBy<SyncAnchor>({ it.text }, { it.audioMs }))
        val out = ArrayList<SyncAnchor>(all.size)
        for (a in all) {
            val last = out.lastOrNull()
            if (last == null || (a.text > last.text && a.audioMs > last.audioMs)) out += a
        }
        synchronized(anchorCache) { anchorCache[key] = sig to out }
        return out
    }

    fun textToAudio(book: Book, t: Double): Long {
        val a = anchors(book)
        val tt = t.coerceIn(0.0, 1.0)
        var lo = 0
        var hi = a.size - 1
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (a[mid].text < tt) lo = mid + 1 else hi = mid
        }
        if (lo == 0) return a[0].audioMs
        val x0 = a[lo - 1]
        val x1 = a[lo]
        val f = if (x1.text > x0.text) (tt - x0.text) / (x1.text - x0.text) else 0.0
        return (x0.audioMs + f.coerceIn(0.0, 1.0) * (x1.audioMs - x0.audioMs)).toLong()
    }

    fun audioToText(book: Book, ms: Long): Double {
        val a = anchors(book)
        var lo = 0
        var hi = a.size - 1
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (a[mid].audioMs < ms) lo = mid + 1 else hi = mid
        }
        if (lo == 0) return a[0].text
        val x0 = a[lo - 1]
        val x1 = a[lo]
        val f = if (x1.audioMs > x0.audioMs) (ms - x0.audioMs).toDouble() / (x1.audioMs - x0.audioMs) else 0.0
        return (x0.text + f.coerceIn(0.0, 1.0) * (x1.text - x0.text)).coerceIn(0.0, 1.0)
    }

    /** Returns [book] with a new manual sync point, dropping any that would now be out of order. */
    fun withManualAnchor(book: Book, t: Double, ms: Long): Book {
        val kept = book.syncAnchors.filter { abs(it.text - t) > 0.003 && ((it.text < t) == (it.audioMs < ms)) }
        return book.copy(syncAnchors = (kept + SyncAnchor(t, ms)).sortedBy { it.text })
    }
}
