package com.brenyd.audiread.playback

import com.brenyd.audiread.data.AudioChapter
import com.brenyd.audiread.data.Book
import kotlinx.coroutines.flow.MutableStateFlow

data class SleepState(val endsAt: Long = 0L, val endOfChapter: Boolean = false, val chapterIndex: Int = -1)

/**
 * The book currently loaded in the player, shared by the playback service and the UI (same process).
 * An audiobook is a playlist of files; positions are expressed globally across all files.
 */
object NowPlaying {
    val bookId = MutableStateFlow<String?>(null)
    val sleep = MutableStateFlow<SleepState?>(null)

    @Volatile var offsets: LongArray = LongArray(1)
        private set
    @Volatile var chapters: List<AudioChapter> = emptyList()
        private set
    @Volatile var totalMs: Long = 0L
        private set

    fun load(b: Book) {
        val files = b.audio?.files ?: emptyList()
        val o = LongArray(files.size + 1)
        files.forEachIndexed { i, f -> o[i + 1] = o[i] + f.durationMs }
        offsets = o
        totalMs = o.last()
        chapters = b.audio?.chapters ?: emptyList()
        bookId.value = b.id
    }

    fun clear() {
        bookId.value = null
        sleep.value = null
    }

    fun globalMs(fileIndex: Int, posMs: Long): Long =
        offsets.getOrElse(fileIndex) { 0L } + posMs.coerceAtLeast(0L)

    /** Global position → (file index, position within file). */
    fun locate(g: Long): Pair<Int, Long> {
        val o = offsets
        val n = o.size - 1
        if (n <= 0) return 0 to 0L
        var i = 0
        while (i < n - 1 && g >= o[i + 1]) i++
        return i to (g - o[i]).coerceAtLeast(0L)
    }

    fun chapterIndexAt(g: Long): Int = ChapterMath.indexAt(chapters, g)
}

object ChapterMath {
    fun indexAt(ch: List<AudioChapter>, g: Long): Int {
        if (ch.isEmpty()) return -1
        var idx = 0
        for (i in ch.indices) {
            if (ch[i].startMs <= g) idx = i else break
        }
        return idx
    }

    fun start(ch: List<AudioChapter>, i: Int): Long = ch.getOrNull(i)?.startMs ?: 0L
    fun end(ch: List<AudioChapter>, i: Int, total: Long): Long = ch.getOrNull(i + 1)?.startMs ?: total
}
