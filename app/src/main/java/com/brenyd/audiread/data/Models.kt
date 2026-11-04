package com.brenyd.audiread.data

import kotlinx.serialization.Serializable

enum class BookFormat(val label: String) {
    EPUB("EPUB"), PDF("PDF"), MOBI("MOBI/AZW3"), FB2("FB2"), TXT("TXT"),
    HTML("HTML"), CBZ("CBZ"), MARKDOWN("Markdown")
}

/** One reflowable document the reader can load (an EPUB spine item, a converted chapter, or a comic page). */
@Serializable
data class Section(val path: String, val weight: Long, val title: String? = null)

@Serializable
data class TocEntry(val title: String, val section: Int, val fragment: String? = null, val depth: Int = 0)

@Serializable
data class EbookInfo(
    val sourceUri: String,
    val fileName: String,
    val format: BookFormat,
    /** Absolute path of the extracted/converted content. */
    val contentDir: String,
    val sections: List<Section> = emptyList(),
    val toc: List<TocEntry> = emptyList(),
    /** Page count for fixed-layout formats (PDF, CBZ). */
    val pageCount: Int = 0,
) {
    val fixedLayout: Boolean get() = format == BookFormat.PDF || format == BookFormat.CBZ
}

@Serializable
data class AudioFile(val uri: String, val name: String, val durationMs: Long)

/** A chapter in the whole audiobook; [startMs] is measured across all files. */
@Serializable
data class AudioChapter(val title: String, val startMs: Long)

@Serializable
data class AudioInfo(
    val files: List<AudioFile>,
    val chapters: List<AudioChapter>,
) {
    val totalMs: Long get() = files.sumOf { it.durationMs }
}

/** Reading position. [section] is the page index for PDF/CBZ. [global] is 0..1 across the whole book. */
@Serializable
data class ReadPosition(val section: Int = 0, val progression: Double = 0.0, val global: Double = 0.0)

/** A user-pinned link between a text position (0..1) and an audio position. */
@Serializable
data class SyncAnchor(val text: Double, val audioMs: Long)

@Serializable
data class Book(
    val id: String,
    val title: String,
    val author: String = "",
    val coverPath: String? = null,
    val ebook: EbookInfo? = null,
    val audio: AudioInfo? = null,
    val readPos: ReadPosition = ReadPosition(),
    val listenMs: Long = 0,
    val syncAnchors: List<SyncAnchor> = emptyList(),
    val addedAt: Long = System.currentTimeMillis(),
    val lastReadAt: Long = 0,
    val lastListenAt: Long = 0,
    val lastOpenedAt: Long = 0,
) {
    val readProgress: Float get() = if (ebook == null) 0f else readPos.global.toFloat().coerceIn(0f, 1f)
    val listenProgress: Float
        get() {
            val total = audio?.totalMs ?: 0L
            return if (total <= 0) 0f else (listenMs.toFloat() / total).coerceIn(0f, 1f)
        }
    val progress: Float get() = maxOf(readProgress, listenProgress)
}

enum class ReaderTheme(val label: String, val bg: Long, val fg: Long, val dark: Boolean) {
    LIGHT("Light", 0xFFFFFFFF, 0xFF1C1B1F, false),
    SEPIA("Sepia", 0xFFF4EAD5, 0xFF4B3A26, false),
    DUSK("Dusk", 0xFF2A2B2F, 0xFFD8D4CC, true),
    BLACK("Black", 0xFF000000, 0xFFBEBEBE, true),
}

@Serializable
data class ReaderSettings(
    val theme: ReaderTheme = ReaderTheme.SEPIA,
    val fontScale: Int = 100,
    val font: String = "default",
    val lineHeight: Float = 1.5f,
    val margin: Int = 22,
    val justify: Boolean = true,
    val scroll: Boolean = false,
)

@Serializable
data class PlaybackSettings(
    val backSec: Int = 15,
    val fwdSec: Int = 30,
    val speed: Float = 1f,
    val skipSilence: Boolean = false,
)
