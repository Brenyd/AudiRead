package com.brenyd.audiread.importer

import android.content.Context
import android.net.Uri
import com.brenyd.audiread.data.Book
import com.brenyd.audiread.data.SyncAnchor
import com.brenyd.audiread.sync.SyncMapper
import com.brenyd.audiread.util.Util
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup
import java.io.File
import java.io.IOException
import kotlin.math.abs

/**
 * Reads a sync file made by the AudiRead Sync desktop tool.
 *
 * Each point says "at <ms> into audio file <f>, the narrator is reading the words <w>". The words are
 * looked up in this book's own text, so the file works no matter how the ebook was converted on the phone.
 */
object SyncFileImporter {

    @Serializable
    data class FileAudio(val name: String, val durationMs: Long = 0)

    @Serializable
    data class FileAnchor(val f: Int, val ms: Long, val w: String, val p: Double = -1.0)

    @Serializable
    data class SyncFile(
        val format: String = "",
        val version: Int = 1,
        val audio: List<FileAudio> = emptyList(),
        val anchors: List<FileAnchor> = emptyList(),
    )

    class Resolved(val anchors: List<SyncAnchor>, val summary: String)

    private val json = Json { ignoreUnknownKeys = true }
    private val wordRegex = Regex("[\\p{L}\\p{N}]+")

    /** Word stream of the book's text (lower-cased), with where each word starts. */
    private class Words(n: Int) {
        val tokens = ArrayList<String>(n)
        val section = IntArrayList(n)
        val offset = IntArrayList(n)
        val sectionLength = ArrayList<Int>()
    }

    private class IntArrayList(cap: Int) {
        var data = IntArray(maxOf(16, cap))
        var size = 0
        fun add(v: Int) {
            if (size == data.size) data = data.copyOf(size * 2)
            data[size++] = v
        }
        operator fun get(i: Int) = data[i]
    }

    fun resolve(ctx: Context, uri: Uri, book: Book, progress: (Float) -> Unit): Resolved {
        val ebook = book.ebook ?: throw IOException("Add the ebook to this book first")
        val audio = book.audio ?: throw IOException("Add the audiobook to this book first")
        if (ebook.fixedLayout) throw IOException("Sync files work with EPUB and other text ebooks, not PDF or comics")

        val doc = runCatching { json.decodeFromString(SyncFile.serializer(), Util.decodeText(Util.readBytes(ctx, uri))) }
            .getOrElse { throw IOException("That isn't an AudiRead sync file") }
        if (doc.format != "audiread-sync" || doc.anchors.isEmpty()) throw IOException("That isn't an AudiRead sync file")

        // ---- audio files: match by name, falling back to position
        val offsets = LongArray(audio.files.size + 1)
        audio.files.forEachIndexed { i, f -> offsets[i + 1] = offsets[i] + f.durationMs }
        val nameCount = audio.files.groupingBy { it.name }.eachCount()
        val fileMap = IntArray(doc.audio.size) { -1 }
        doc.audio.forEachIndexed { i, fa ->
            val same = audio.files.getOrNull(i)?.name == fa.name
            fileMap[i] = when {
                same -> i
                nameCount[fa.name] == 1 -> audio.files.indexOfFirst { it.name == fa.name }
                doc.audio.size == audio.files.size -> i
                else -> -1
            }
        }
        if (fileMap.all { it < 0 }) {
            throw IOException("The sync file was made for different audio files")
        }

        // ---- book text
        val words = Words(200_000)
        ebook.sections.forEachIndexed { si, s ->
            val text = runCatching {
                val d = Jsoup.parse(File(ebook.contentDir, s.path), "UTF-8")
                (d.body() ?: d).text()
            }.getOrDefault("")
            words.sectionLength += text.length
            val lower = text.lowercase()
            for (m in wordRegex.findAll(lower)) {
                words.tokens += m.value
                words.section.add(si)
                words.offset.add(m.range.first)
            }
            progress(0.4f * (si + 1) / ebook.sections.size)
        }
        val total = words.tokens.size
        if (total == 0) throw IOException("This ebook has no text to sync with")

        val freq = HashMap<String, Int>()
        val positions = HashMap<String, IntArrayList>()
        for (i in 0 until total) {
            val t = words.tokens[i]
            freq[t] = (freq[t] ?: 0) + 1
            positions.getOrPut(t) { IntArrayList(4) }.add(i)
        }

        // ---- find each snippet, moving forward through the book
        val out = ArrayList<SyncAnchor>(doc.anchors.size)
        var cursor = 0
        var found = 0
        var skippedAudio = 0
        doc.anchors.forEachIndexed { k, a ->
            if (k % 200 == 0) progress(0.4f + 0.6f * k / doc.anchors.size)
            val fi = fileMap.getOrElse(a.f) { -1 }
            if (fi < 0 || fi >= audio.files.size) { skippedAudio++; return@forEachIndexed }
            val snippet = wordRegex.findAll(a.w.lowercase()).map { it.value }.toList()
            if (snippet.isEmpty()) return@forEachIndexed
            // search on the rarest word of the snippet
            var key = 0
            for (q in snippet.indices) if ((freq[snippet[q]] ?: 0) < (freq[snippet[key]] ?: 0)) key = q
            val list = positions[snippet[key]] ?: return@forEachIndexed
            var lo = 0
            var hi = list.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (list[mid] - key < cursor) lo = mid + 1 else hi = mid
            }
            var hit = -1
            var c = lo
            while (c < list.size && c < lo + 400) {
                val start = list[c] - key
                if (start >= 0 && start + snippet.size <= total && matches(words.tokens, start, snippet)) {
                    hit = start
                    break
                }
                c++
            }
            if (hit < 0) return@forEachIndexed
            // ignore a match that jumps far ahead of where the desktop tool placed it
            if (a.p >= 0 && cursor > 0 && hit.toDouble() / total - a.p > 0.08) return@forEachIndexed
            cursor = hit + 1
            found++
            val si = words.section[hit]
            val len = words.sectionLength[si].coerceAtLeast(1)
            val frac = (words.offset[hit].toDouble() / len).coerceIn(0.0, 1.0)
            val t = SyncMapper.textGlobal(ebook, si, frac)
            val ms = offsets[fi] + a.ms.coerceIn(0, audio.files[fi].durationMs.coerceAtLeast(a.ms))
            out += SyncAnchor(t, ms)
        }
        progress(1f)

        // keep strictly increasing in both text and time
        val clean = ArrayList<SyncAnchor>(out.size)
        for (a in out.sortedBy { it.audioMs }) {
            val last = clean.lastOrNull()
            if (last == null || (a.text > last.text && a.audioMs > last.audioMs)) clean += a
        }
        if (clean.size < 10) {
            throw IOException("Couldn't match this sync file to the book — was it made from the same ebook?")
        }
        val pct = found * 100 / doc.anchors.size
        val summary = "Synced with ${clean.size} points ($pct% matched)" +
            if (skippedAudio > 0) " — some audio files didn't match" else ""
        return Resolved(clean, summary)
    }

    private fun matches(tokens: List<String>, start: Int, snippet: List<String>): Boolean {
        for (q in snippet.indices) if (tokens[start + q] != snippet[q]) return false
        return true
    }

    @Suppress("unused")
    private fun close(a: Double, b: Double) = abs(a - b) < 1e-9
}
