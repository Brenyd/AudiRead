package com.brenyd.audiread.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/** The library, kept in memory and persisted as JSON in the app's private storage. */
object LibraryRepository {
    private lateinit var file: File
    private lateinit var filesDir: File
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _books = MutableStateFlow<List<Book>>(emptyList())
    val books: StateFlow<List<Book>> = _books.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val saves = Channel<Unit>(Channel.CONFLATED)
    private val lock = Any()
    private var initialized = false

    fun init(ctx: Context) {
        if (initialized) return
        initialized = true
        filesDir = ctx.applicationContext.filesDir
        file = File(filesDir, "library.json")
        if (file.exists()) {
            runCatching {
                _books.value = json.decodeFromString(ListSerializer(Book.serializer()), file.readText())
            }
        }
        scope.launch {
            for (u in saves) {
                delay(400)
                writeNow()
            }
        }
    }

    private fun writeNow() {
        synchronized(lock) {
            runCatching {
                val tmp = File(file.path + ".tmp")
                tmp.writeText(json.encodeToString(ListSerializer(Book.serializer()), _books.value))
                if (!tmp.renameTo(file)) {
                    file.writeText(tmp.readText())
                    tmp.delete()
                }
            }
        }
    }

    fun flush() {
        scope.launch { writeNow() }
    }

    fun get(id: String): Book? = _books.value.firstOrNull { it.id == id }

    fun bookFlow(id: String): Flow<Book?> =
        _books.map { l -> l.firstOrNull { it.id == id } }.distinctUntilChanged()

    fun upsert(book: Book) {
        _books.update { l ->
            if (l.any { it.id == book.id }) l.map { if (it.id == book.id) book else it } else l + book
        }
        saves.trySend(Unit)
    }

    fun update(id: String, f: (Book) -> Book) {
        _books.update { l -> l.map { if (it.id == id) f(it) else it } }
        saves.trySend(Unit)
    }

    fun remove(id: String) {
        val b = get(id)
        _books.update { l -> l.filterNot { it.id == id } }
        saves.trySend(Unit)
        scope.launch {
            File(filesDir, "books/$id").deleteRecursively()
            b?.coverPath?.let { File(it).delete() }
        }
    }
}
