package com.brenyd.audiread.playback

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.brenyd.audiread.data.Book
import com.brenyd.audiread.data.SettingsStore
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class PlayerUiState(
    val connected: Boolean = false,
    val bookId: String? = null,
    val isPlaying: Boolean = false,
    val playWhenReady: Boolean = false,
    val buffering: Boolean = false,
    val globalMs: Long = 0L,
    val totalMs: Long = 0L,
    val speed: Float = 1f,
    val chapterIndex: Int = -1,
)

/** UI-side handle on the playback service. */
object PlayerConnection {
    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private val pending = ArrayList<(MediaController) -> Unit>()
    private val scope = MainScope()
    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    fun connect(ctx: Context) {
        if (future != null) return
        val app = ctx.applicationContext
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        val f = MediaController.Builder(app, token).buildAsync()
        future = f
        f.addListener({
            val c = runCatching { f.get() }.getOrNull()
            if (c == null) {
                future = null
            } else {
                controller = c
                val run = ArrayList(pending)
                pending.clear()
                run.forEach { it(c) }
                refresh()
            }
        }, ContextCompat.getMainExecutor(app))
        scope.launch {
            while (isActive) {
                refresh()
                delay(250)
            }
        }
    }

    private fun refresh() {
        val c = controller ?: return
        val has = c.mediaItemCount > 0
        val g = if (has) NowPlaying.globalMs(c.currentMediaItemIndex, c.currentPosition) else 0L
        _state.value = PlayerUiState(
            connected = true,
            bookId = if (has) NowPlaying.bookId.value else null,
            isPlaying = c.isPlaying,
            playWhenReady = c.playWhenReady,
            buffering = c.playbackState == Player.STATE_BUFFERING,
            globalMs = g,
            totalMs = NowPlaying.totalMs,
            speed = c.playbackParameters.speed,
            chapterIndex = NowPlaying.chapterIndexAt(g),
        )
    }

    private fun withController(block: (MediaController) -> Unit) {
        val c = controller
        if (c != null) block(c) else pending += block
    }

    /** Loads [book] into the player. [startMs] overrides the saved listening position. */
    fun playBook(book: Book, startMs: Long? = null, play: Boolean = true) = withController { c ->
        val id = "book:${book.id}" + (startMs?.let { "@$it" } ?: "")
        c.setMediaItem(MediaItem.Builder().setMediaId(id).build())
        c.prepare()
        c.playWhenReady = play
        refresh()
    }

    /** Makes sure [book] is the loaded book, then optionally plays. */
    fun ensure(book: Book, play: Boolean) {
        if (_state.value.bookId == book.id) {
            if (play) withController { it.play() }
        } else playBook(book, null, play)
    }

    fun togglePlay() = withController { if (it.isPlaying) it.pause() else it.play() }
    fun play() = withController { it.play() }
    fun pause() = withController { it.pause() }

    fun stop() = withController {
        it.stop()
        it.clearMediaItems()
        NowPlaying.clear()
    }

    private fun cmd(action: String, args: Bundle = Bundle.EMPTY) = withController {
        it.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), args)
    }

    fun rewind() = cmd(Cmd.REWIND, bundleOf(Cmd.ARG_MS to SettingsStore.playback.value.backSec * 1000L))
    fun forward() = cmd(Cmd.FORWARD, bundleOf(Cmd.ARG_MS to SettingsStore.playback.value.fwdSec * 1000L))
    fun nextChapter() = cmd(Cmd.NEXT_CHAPTER)
    fun prevChapter() = cmd(Cmd.PREV_CHAPTER)
    fun seekGlobal(ms: Long) = cmd(Cmd.SEEK_GLOBAL, bundleOf(Cmd.ARG_MS to ms))
    fun sleep(minutes: Int) = cmd(Cmd.SLEEP, bundleOf(Cmd.ARG_MIN to minutes))

    fun setSkipSilence(on: Boolean) {
        SettingsStore.setPlayback(SettingsStore.playback.value.copy(skipSilence = on))
        cmd(Cmd.SKIP_SILENCE, bundleOf(Cmd.ARG_ON to on))
    }

    fun setSpeed(speed: Float) {
        SettingsStore.setPlayback(SettingsStore.playback.value.copy(speed = speed))
        withController { it.setPlaybackSpeed(speed) }
    }
}
