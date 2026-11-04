package com.brenyd.audiread.importer

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.brenyd.audiread.data.Book
import com.brenyd.audiread.data.LibraryRepository
import com.brenyd.audiread.data.ReadPosition
import com.brenyd.audiread.sync.TitleMatch
import com.brenyd.audiread.util.Util
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.UUID

/** Runs imports in the background and reports progress to the UI. */
object ImportManager {
    data class Status(val message: String, val progress: Float? = null)

    private val _status = MutableStateFlow<Status?>(null)
    val status: StateFlow<Status?> = _status.asStateFlow()
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private fun launchJob(start: String, block: suspend () -> Unit) {
        scope.launch {
            mutex.withLock {
                _status.value = Status(start)
                try {
                    block()
                } catch (e: Throwable) {
                    _messages.tryEmit(e.message ?: e.toString())
                } finally {
                    _status.value = null
                }
            }
        }
    }

    private fun newId() = UUID.randomUUID().toString()

    fun importEbooks(ctx: Context, uris: List<Uri>) = launchJob("Importing…") {
        var ok = 0
        uris.forEachIndexed { i, u ->
            _status.value = Status("Importing ${i + 1} of ${uris.size}…", i.toFloat() / uris.size)
            try {
                createFromEbook(ctx, u, null)
                ok++
            } catch (e: Exception) {
                _messages.tryEmit("${Util.displayName(ctx, u)}: ${e.message}")
            }
        }
        if (ok > 0) _messages.tryEmit(if (ok == 1) "Book added" else "$ok books added")
    }

    private suspend fun createFromEbook(ctx: Context, uri: Uri, audio: AudioImporter.Imported?): Book {
        val id = newId()
        val r = EbookImporter.import(ctx, uri, id)
        val cover = Util.saveCover(ctx, r.cover, id) ?: Util.saveCover(ctx, audio?.cover, id)
        val book = Book(
            id = id, title = r.title, author = r.author ?: audio?.author ?: "",
            coverPath = cover, ebook = r.info, audio = audio?.audio,
        )
        LibraryRepository.upsert(book)
        return book
    }

    fun importAudioFiles(ctx: Context, uris: List<Uri>, attachTo: String?) = launchJob("Reading audio files…") {
        attachOrCreate(ctx, AudioImporter.fromUris(ctx, uris), attachTo)
    }

    fun importAudioFolder(ctx: Context, tree: Uri, attachTo: String?) = launchJob("Reading audiobook folder…") {
        attachOrCreate(ctx, AudioImporter.fromTree(ctx, tree), attachTo)
    }

    private fun attachOrCreate(ctx: Context, a: AudioImporter.Imported, attachTo: String?) {
        if (attachTo != null) {
            val existing = LibraryRepository.get(attachTo) ?: throw IOException("Book not found")
            val cover = existing.coverPath ?: Util.saveCover(ctx, a.cover, existing.id)
            LibraryRepository.update(attachTo) {
                it.copy(audio = a.audio, listenMs = 0, lastListenAt = 0, syncAnchors = emptyList(), coverPath = cover)
            }
            _messages.tryEmit("Audiobook linked (${a.audio.chapters.size} chapters)")
        } else {
            val id = newId()
            LibraryRepository.upsert(
                Book(id = id, title = a.title, author = a.author ?: "", coverPath = Util.saveCover(ctx, a.cover, id), audio = a.audio)
            )
            _messages.tryEmit("Audiobook added")
        }
    }

    fun attachEbook(ctx: Context, uri: Uri, bookId: String) = launchJob("Importing ebook…") {
        val existing = LibraryRepository.get(bookId) ?: throw IOException("Book not found")
        val r = EbookImporter.import(ctx, uri, bookId)
        val cover = existing.coverPath ?: Util.saveCover(ctx, r.cover, bookId)
        LibraryRepository.update(bookId) {
            it.copy(
                ebook = r.info, readPos = ReadPosition(), lastReadAt = 0, syncAnchors = emptyList(), coverPath = cover,
                author = it.author.ifBlank { r.author ?: "" },
            )
        }
        _messages.tryEmit("Ebook linked")
    }

    // ---------------------------------------------------------------- library folder scan

    private class AudioGroup(val name: String, val dir: String, val sources: MutableList<AudioImporter.Source>)

    private val discFolder = Regex("^(cd|disc|disk|part|pt)[ _.-]*\\d+.*$", RegexOption.IGNORE_CASE)

    fun scanFolder(ctx: Context, tree: Uri) = launchJob("Scanning folder…") {
        val root = DocumentFile.fromTreeUri(ctx, tree) ?: throw IOException("Can't open that folder")
        val ebooks = ArrayList<Pair<DocumentFile, String>>()
        val groups = LinkedHashMap<String, AudioGroup>()

        fun walk(d: DocumentFile, path: String) {
            for (f in d.listFiles()) {
                val n = f.name ?: continue
                if (f.isDirectory) {
                    walk(f, "$path/$n")
                } else if (EbookImporter.formatFor(n) != null) {
                    ebooks += f to path
                } else if (AudioImporter.isAudio(n)) {
                    val last = path.substringAfterLast('/')
                    val dir = if (discFolder.matches(last)) path.substringBeforeLast('/') else path
                    val key = if (Util.extension(n) == "m4b") "$dir/$n" else dir
                    val gName = if (Util.extension(n) == "m4b") Util.cleanTitle(n) else dir.substringAfterLast('/').ifBlank { root.name ?: "Audiobook" }
                    groups.getOrPut(key) { AudioGroup(gName, dir, ArrayList()) }.sources +=
                        AudioImporter.Source(f.uri, n, "$path/$n")
                }
            }
        }
        walk(root, "")

        // skip what's already in the library
        val known = HashSet<String>()
        LibraryRepository.books.value.forEach { b ->
            b.ebook?.let { known += it.sourceUri }
            b.audio?.files?.forEach { known += it.uri }
        }
        val newEbooks = ebooks.filter { it.first.uri.toString() !in known }
        val newGroups = groups.values.filter { g -> g.sources.none { it.uri.toString() in known } }.toMutableList()

        val ebooksPerDir = newEbooks.groupingBy { it.second }.eachCount()
        val pairs = ArrayList<Pair<Pair<DocumentFile, String>?, AudioGroup?>>()
        for (e in newEbooks) {
            val title = Util.cleanTitle(e.first.name ?: "")
            var best: AudioGroup? = null
            var bestScore = 0.0
            for (g in newGroups) {
                var s = TitleMatch.similarity(title, g.name)
                if (g.dir == e.second && ebooksPerDir[e.second] == 1) s = maxOf(s, 0.9)
                if (s > bestScore) { bestScore = s; best = g }
            }
            if (best != null && bestScore >= 0.5) {
                newGroups.remove(best)
                pairs += e to best
            } else pairs += e to null
        }
        newGroups.forEach { pairs += null to it }

        if (pairs.isEmpty()) {
            _messages.tryEmit("No new books found in that folder")
            return@launchJob
        }
        var added = 0
        pairs.forEachIndexed { i, (ebook, group) ->
            val label = ebook?.first?.name ?: group?.name ?: ""
            _status.value = Status("Adding ${i + 1} of ${pairs.size}: $label", i.toFloat() / pairs.size)
            try {
                val audio = group?.let { AudioImporter.fromSources(ctx, it.sources, it.name) }
                if (ebook != null) createFromEbook(ctx, ebook.first.uri, audio)
                else if (audio != null) {
                    val id = newId()
                    LibraryRepository.upsert(
                        Book(id = id, title = audio.title, author = audio.author ?: "", coverPath = Util.saveCover(ctx, audio.cover, id), audio = audio.audio)
                    )
                }
                added++
            } catch (e: Exception) {
                _messages.tryEmit("$label: ${e.message}")
            }
        }
        val matched = pairs.count { it.first != null && it.second != null }
        _messages.tryEmit("Added $added books ($matched with ebook + audio)")
    }
}
