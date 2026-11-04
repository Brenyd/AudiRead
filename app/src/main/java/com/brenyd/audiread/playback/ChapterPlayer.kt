package com.brenyd.audiread.playback

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.brenyd.audiread.data.SettingsStore

/**
 * Wraps ExoPlayer so that "next"/"previous" (headphones, notification, Android Auto, steering wheel)
 * move between audiobook chapters — even inside a single long file — and skip back/forward use the
 * user's increments across file boundaries.
 */
class ChapterPlayer(private val exo: ExoPlayer) : ForwardingPlayer(exo) {

    fun globalPos(): Long = NowPlaying.globalMs(exo.currentMediaItemIndex, exo.currentPosition)

    fun seekGlobal(ms: Long) {
        if (exo.mediaItemCount == 0) return
        val clamped = if (NowPlaying.totalMs > 1000) ms.coerceIn(0L, NowPlaying.totalMs - 500) else ms.coerceAtLeast(0L)
        val (i, p) = NowPlaying.locate(clamped)
        if (i < exo.mediaItemCount) exo.seekTo(i, p)
    }

    fun skip(deltaMs: Long) = seekGlobal(globalPos() + deltaMs)

    fun nextChapter() {
        val ch = NowPlaying.chapters
        if (ch.size <= 1) {
            if (exo.hasNextMediaItem()) exo.seekToNextMediaItem() else skip(SettingsStore.playback.value.fwdSec * 1000L)
            return
        }
        val i = NowPlaying.chapterIndexAt(globalPos())
        if (i + 1 < ch.size) seekGlobal(ch[i + 1].startMs)
    }

    fun prevChapter() {
        val ch = NowPlaying.chapters
        if (ch.size <= 1) {
            if (exo.currentPosition < 3000 && exo.hasPreviousMediaItem()) exo.seekToPreviousMediaItem() else exo.seekTo(exo.currentMediaItemIndex, 0)
            return
        }
        val g = globalPos()
        val i = NowPlaying.chapterIndexAt(g).coerceAtLeast(0)
        if (g - ch[i].startMs > 3000 || i == 0) seekGlobal(ch[i].startMs) else seekGlobal(ch[i - 1].startMs)
    }

    override fun seekToNext() = nextChapter()
    override fun seekToPrevious() = prevChapter()
    override fun seekToNextMediaItem() = nextChapter()
    override fun seekToPreviousMediaItem() = prevChapter()
    override fun seekForward() = skip(SettingsStore.playback.value.fwdSec * 1000L)
    override fun seekBack() = skip(-SettingsStore.playback.value.backSec * 1000L)
    override fun getSeekForwardIncrement(): Long = SettingsStore.playback.value.fwdSec * 1000L
    override fun getSeekBackIncrement(): Long = SettingsStore.playback.value.backSec * 1000L

    override fun hasNextMediaItem(): Boolean = exo.mediaItemCount > 0
    override fun hasPreviousMediaItem(): Boolean = exo.mediaItemCount > 0

    override fun getAvailableCommands(): Player.Commands =
        super.getAvailableCommands().buildUpon().addAll(
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_SEEK_BACK,
            Player.COMMAND_SEEK_FORWARD,
        ).build()

    override fun isCommandAvailable(command: Int): Boolean = availableCommands.contains(command)
}
