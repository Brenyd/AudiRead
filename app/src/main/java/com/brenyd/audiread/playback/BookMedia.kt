package com.brenyd.audiread.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.brenyd.audiread.data.Book
import com.brenyd.audiread.data.LibraryRepository
import com.brenyd.audiread.util.Util

/** Builds the media items for playback and for the Android Auto browse tree. */
object BookMedia {
    const val ROOT = "root"
    const val RECENT = "recent"
    const val ALL = "all"

    /** "book:<id>" or "book:<id>@<globalMs>" or "<id>#<fileIndex>" → (bookId, start position). */
    fun parseId(id: String): Pair<String, Long?>? = when {
        id.startsWith("book:") -> {
            val rest = id.removePrefix("book:")
            rest.substringBefore('@') to rest.substringAfter('@', "").toLongOrNull()
        }
        id.contains('#') -> id.substringBefore('#') to null
        else -> null
    }

    fun folder(id: String, title: String): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder().setTitle(title).setIsBrowsable(true).setIsPlayable(false).build()
        ).build()

    fun bookItem(ctx: Context, b: Book): MediaItem {
        val total = b.audio?.totalMs ?: 0L
        val subtitle = buildString {
            if (b.author.isNotBlank()) append(b.author)
            if (total > 0 && b.listenMs > 0) {
                if (isNotEmpty()) append(" · ")
                append("${Util.formatDuration((total - b.listenMs).coerceAtLeast(0))} left")
            }
        }
        return MediaItem.Builder()
            .setMediaId("book:${b.id}")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(b.title)
                    .setArtist(b.author)
                    .setSubtitle(subtitle)
                    .setArtworkUri(CoverProvider.uriFor(ctx, b))
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .build()
            ).build()
    }

    fun playlist(ctx: Context, b: Book): List<MediaItem> {
        val audio = b.audio ?: return emptyList()
        val art = CoverProvider.uriFor(ctx, b)
        var offset = 0L
        return audio.files.mapIndexed { i, f ->
            val chapter = audio.chapters.lastOrNull { it.startMs <= offset }?.title
            offset += f.durationMs
            MediaItem.Builder()
                .setMediaId("${b.id}#$i")
                .setUri(Uri.parse(f.uri))
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(if (audio.files.size > 1 && chapter != null) chapter else b.title)
                        .setArtist(b.author)
                        .setAlbumTitle(b.title)
                        .setArtworkUri(art)
                        .setTrackNumber(i + 1)
                        .setIsBrowsable(false)
                        .setIsPlayable(true)
                        .build()
                ).build()
        }
    }

    fun children(ctx: Context, parentId: String): List<MediaItem> {
        val books = LibraryRepository.books.value.filter { it.audio != null }
        return when (parentId) {
            ROOT -> listOf(folder(RECENT, "Continue listening"), folder(ALL, "All audiobooks"))
            RECENT -> books.filter { it.lastListenAt > 0 }.sortedByDescending { it.lastListenAt }.take(12).map { bookItem(ctx, it) }
            ALL -> books.sortedBy { it.title.lowercase() }.map { bookItem(ctx, it) }
            else -> emptyList()
        }
    }

    fun item(ctx: Context, id: String): MediaItem? = when (id) {
        ROOT -> folder(ROOT, "AudiRead")
        RECENT -> folder(RECENT, "Continue listening")
        ALL -> folder(ALL, "All audiobooks")
        else -> parseId(id)?.let { LibraryRepository.get(it.first) }?.let { bookItem(ctx, it) }
    }
}
