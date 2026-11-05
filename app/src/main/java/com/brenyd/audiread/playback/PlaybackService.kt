package com.brenyd.audiread.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.brenyd.audiread.MainActivity
import com.brenyd.audiread.R
import com.brenyd.audiread.data.LibraryRepository
import com.brenyd.audiread.data.SettingsStore
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

object Cmd {
    const val REWIND = "audiread.REWIND"
    const val FORWARD = "audiread.FORWARD"
    const val NEXT_CHAPTER = "audiread.NEXT_CHAPTER"
    const val PREV_CHAPTER = "audiread.PREV_CHAPTER"
    const val SEEK_GLOBAL = "audiread.SEEK_GLOBAL"
    const val SLEEP = "audiread.SLEEP"
    const val SKIP_SILENCE = "audiread.SKIP_SILENCE"
    const val ARG_MS = "ms"
    const val ARG_MIN = "min"
    const val ARG_ON = "on"
    val all = listOf(REWIND, FORWARD, NEXT_CHAPTER, PREV_CHAPTER, SEEK_GLOBAL, SLEEP, SKIP_SILENCE)
}

/** Background audiobook playback, media notification, and the Android Auto media browser. */
class PlaybackService : MediaLibraryService() {
    private lateinit var exo: ExoPlayer
    private lateinit var player: ChapterPlayer
    private var session: MediaLibrarySession? = null
    private val scope = MainScope()

    override fun onCreate() {
        super.onCreate()
        exo = ExoPlayer.Builder(this, AudioDecoders.renderersFactory(this))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        player = ChapterPlayer(exo)

        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val s = MediaLibrarySession.Builder(this, player, Callback())
            .setSessionActivity(open)
            .build()
        s.setCustomLayout(customLayout())
        session = s

        exo.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) saveProgress()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) saveProgress()
            }
        })

        scope.launch {
            var tick = 0
            while (isActive) {
                delay(1000)
                tick++
                if (exo.isPlaying && tick % 5 == 0) saveProgress()
                checkSleep()
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        saveProgress()
        if (!exo.playWhenReady || exo.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        saveProgress()
        LibraryRepository.flush()
        scope.cancel()
        session?.release()
        session = null
        exo.release()
        super.onDestroy()
    }

    private fun customLayout(): List<CommandButton> {
        val pb = SettingsStore.playback.value
        return listOf(
            CommandButton.Builder()
                .setDisplayName("Back ${pb.backSec}s")
                .setIconResId(R.drawable.ic_replay)
                .setSessionCommand(SessionCommand(Cmd.REWIND, Bundle.EMPTY))
                .build(),
            CommandButton.Builder()
                .setDisplayName("Forward ${pb.fwdSec}s")
                .setIconResId(R.drawable.ic_forward)
                .setSessionCommand(SessionCommand(Cmd.FORWARD, Bundle.EMPTY))
                .build(),
        )
    }

    private fun saveProgress() {
        val id = NowPlaying.bookId.value ?: return
        if (exo.mediaItemCount == 0) return
        val g = player.globalPos()
        val now = System.currentTimeMillis()
        LibraryRepository.update(id) { it.copy(listenMs = g, lastListenAt = now, lastOpenedAt = now) }
    }

    private fun checkSleep() {
        val s = NowPlaying.sleep.value ?: return
        if (s.endOfChapter) {
            val idx = NowPlaying.chapterIndexAt(player.globalPos())
            if (s.chapterIndex >= 0 && idx != s.chapterIndex) {
                exo.pause()
                NowPlaying.sleep.value = null
            }
            return
        }
        val left = s.endsAt - System.currentTimeMillis()
        when {
            left <= 0 -> {
                exo.pause()
                exo.volume = 1f
                NowPlaying.sleep.value = null
            }
            left < 10_000 -> exo.volume = (left / 10_000f).coerceIn(0.1f, 1f)
            else -> if (exo.volume != 1f) exo.volume = 1f
        }
    }

    private fun resolve(mediaId: String): MediaSession.MediaItemsWithStartPosition? {
        val (bookId, at) = BookMedia.parseId(mediaId) ?: return null
        val book = LibraryRepository.get(bookId) ?: return null
        val audio = book.audio ?: return null
        if (audio.files.isEmpty()) return null
        saveProgress()
        NowPlaying.load(book)
        val total = audio.totalMs
        var g = at ?: book.listenMs
        if (at == null && total > 0 && g >= total - 5000) g = 0
        val (index, pos) = NowPlaying.locate(g)
        val pb = SettingsStore.playback.value
        exo.setPlaybackSpeed(pb.speed.takeIf { !it.isNaN() }?.coerceIn(0.5f, 3.5f) ?: 1f)
        exo.skipSilenceEnabled = pb.skipSilence
        return MediaSession.MediaItemsWithStartPosition(BookMedia.playlist(this, book), index, pos)
    }

    private inner class Callback : MediaLibrarySession.Callback {

        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val base = super.onConnect(session, controller)
            val commands = base.availableSessionCommands.buildUpon()
            Cmd.all.forEach { commands.add(SessionCommand(it, Bundle.EMPTY)) }
            return MediaSession.ConnectionResult.accept(commands.build(), base.availablePlayerCommands)
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            val pb = SettingsStore.playback.value
            when (customCommand.customAction) {
                Cmd.REWIND -> player.skip(-args.getLong(Cmd.ARG_MS, pb.backSec * 1000L))
                Cmd.FORWARD -> player.skip(args.getLong(Cmd.ARG_MS, pb.fwdSec * 1000L))
                Cmd.NEXT_CHAPTER -> player.nextChapter()
                Cmd.PREV_CHAPTER -> player.prevChapter()
                Cmd.SEEK_GLOBAL -> player.seekGlobal(args.getLong(Cmd.ARG_MS, 0L))
                Cmd.SLEEP -> {
                    val m = args.getInt(Cmd.ARG_MIN, 0)
                    exo.volume = 1f
                    NowPlaying.sleep.value = when {
                        m == 0 -> null
                        m < 0 -> SleepState(endOfChapter = true, chapterIndex = NowPlaying.chapterIndexAt(player.globalPos()))
                        else -> SleepState(endsAt = System.currentTimeMillis() + m * 60_000L)
                    }
                }
                Cmd.SKIP_SILENCE -> exo.skipSilenceEnabled = args.getBoolean(Cmd.ARG_ON, false)
                else -> return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(BookMedia.folder(BookMedia.ROOT, "AudiRead"), params))

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
            Futures.immediateFuture(LibraryResult.ofItemList(BookMedia.children(this@PlaybackService, parentId), params))

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val item = BookMedia.item(this@PlaybackService, mediaId)
                ?: return Futures.immediateFuture(LibraryResult.ofError<MediaItem>(LibraryResult.RESULT_ERROR_BAD_VALUE))
            return Futures.immediateFuture(LibraryResult.ofItem(item, null))
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val resolved = mediaItems.firstOrNull()?.let { resolve(it.mediaId) }
            return Futures.immediateFuture(
                resolved ?: MediaSession.MediaItemsWithStartPosition(mediaItems, startIndex, startPositionMs)
            )
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            val out = ArrayList<MediaItem>()
            for (m in mediaItems) {
                if (m.localConfiguration != null) out += m
                else resolve(m.mediaId)?.let { out.addAll(it.mediaItems) }
            }
            return Futures.immediateFuture(out)
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val last = LibraryRepository.books.value.filter { it.audio != null && it.lastListenAt > 0 }.maxByOrNull { it.lastListenAt }
                ?: LibraryRepository.books.value.firstOrNull { it.audio != null }
            val r = last?.let { resolve("book:${it.id}") }
                ?: return Futures.immediateFailedFuture(UnsupportedOperationException("Nothing to resume"))
            return Futures.immediateFuture(r)
        }
    }
}
