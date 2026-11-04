package com.brenyd.audiread.importer

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.brenyd.audiread.data.AudioChapter
import com.brenyd.audiread.data.AudioFile
import com.brenyd.audiread.data.AudioInfo
import com.brenyd.audiread.util.Util
import java.io.IOException

object AudioImporter {

    val extensions = setOf(
        "mp3", "m4a", "m4b", "aac", "mp4", "ogg", "oga", "opus", "flac", "wav",
        "amr", "awb", "mka", "webm", "weba", "3gp",
    )

    fun isAudio(name: String) = Util.extension(name) in extensions

    class Source(val uri: Uri, val name: String, val sortKey: String)
    class Imported(val title: String, val author: String?, val cover: ByteArray?, val audio: AudioInfo)

    fun fromUris(ctx: Context, uris: List<Uri>): Imported {
        val sources = uris.mapNotNull { u ->
            val n = Util.displayName(ctx, u)
            val mime = runCatching { ctx.contentResolver.getType(u) }.getOrNull() ?: ""
            if (isAudio(n) || mime.startsWith("audio") || mime == "video/mp4") Source(u, n, n) else null
        }
        return build(ctx, sources, null, null)
    }

    fun fromTree(ctx: Context, tree: Uri): Imported {
        val root = DocumentFile.fromTreeUri(ctx, tree) ?: throw IOException("Can't open that folder")
        val files = ArrayList<Source>()
        var cover: Uri? = null
        val coverName = Regex("(cover|folder|front).*\\.(jpe?g|png|webp)", RegexOption.IGNORE_CASE)
        fun walk(d: DocumentFile, prefix: String) {
            for (f in d.listFiles()) {
                val n = f.name ?: continue
                when {
                    f.isDirectory -> walk(f, "$prefix$n/")
                    isAudio(n) -> files += Source(f.uri, n, "$prefix$n")
                    cover == null && coverName.matches(n) -> cover = f.uri
                }
            }
        }
        walk(root, "")
        return build(ctx, files, root.name, cover)
    }

    fun fromSources(ctx: Context, sources: List<Source>, name: String?): Imported = build(ctx, sources, name, null)

    private class Meta(
        val src: Source, val dur: Long, val title: String?, val album: String?,
        val artist: String?, val track: Int?, val disc: Int?,
    )

    private fun num(s: String?): Int? = s?.substringBefore('/')?.trim()?.toIntOrNull()

    private fun build(ctx: Context, sources: List<Source>, folderName: String?, coverUri: Uri?): Imported {
        if (sources.isEmpty()) throw IOException("No audio files found")
        var art: ByteArray? = null
        val metas = sources.map { s ->
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(ctx, s.uri)
                if (art == null) art = runCatching { mmr.embeddedPicture }.getOrNull()
                Meta(
                    s,
                    mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L,
                    mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.trim()?.takeIf { it.isNotEmpty() },
                    mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.trim()?.takeIf { it.isNotEmpty() },
                    (mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                        ?: mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                        ?: mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_AUTHOR))?.trim()?.takeIf { it.isNotEmpty() },
                    num(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)),
                    num(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)),
                )
            } catch (e: Exception) {
                Meta(s, 0L, null, null, null, null, null)
            } finally {
                runCatching { mmr.release() }
            }
        }

        val anyDigits = metas.any { m -> m.src.sortKey.any { it.isDigit() } }
        val tracksUsable = metas.size > 1 && metas.all { it.track != null } &&
            metas.map { (it.disc ?: 0) to it.track }.toSet().size == metas.size
        val sorted = if (!anyDigits && tracksUsable) metas.sortedWith(compareBy<Meta>({ it.disc ?: 0 }, { it.track }))
        else metas.sortedWith { a, b -> Util.naturalCompare(a.src.sortKey, b.src.sortKey) }

        val files = sorted.map { AudioFile(it.src.uri.toString(), it.src.name, it.dur) }
        val chapters = ArrayList<AudioChapter>()
        val distinctTitles = sorted.mapNotNull { it.title }.toSet().size == sorted.size
        var offset = 0L
        for (m in sorted) {
            val embedded = runCatching { ChapterExtractor.extract(ctx, m.src.uri) }.getOrDefault(emptyList())
            if (embedded.isNotEmpty()) {
                for ((t, st) in embedded) {
                    if (m.dur > 0 && st >= m.dur) continue
                    chapters += AudioChapter(t.ifBlank { "Chapter ${chapters.size + 1}" }, offset + st)
                }
            } else {
                val title = when {
                    sorted.size > 1 && distinctTitles && m.title != null -> m.title
                    sorted.size > 1 -> Util.cleanTitle(m.src.name)
                    else -> m.title ?: "Full book"
                }
                chapters += AudioChapter(title, offset)
            }
            offset += m.dur
        }

        val first = sorted.first()
        val title = first.album ?: folderName?.takeIf { it.isNotBlank() }
            ?: (if (sorted.size == 1) first.title else null)
            ?: Util.cleanTitle(first.src.name)
        val cover = art ?: coverUri?.let { u -> runCatching { Util.readBytes(ctx, u) }.getOrNull() }
        return Imported(title, first.artist, cover, AudioInfo(files, chapters.sortedBy { it.startMs }))
    }
}
