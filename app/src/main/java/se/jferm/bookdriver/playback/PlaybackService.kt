package se.jferm.bookdriver.playback

import android.app.PendingIntent
import android.content.Intent
import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import se.jferm.bookdriver.BookDriverApp
import se.jferm.bookdriver.DiagLog
import se.jferm.bookdriver.R
import se.jferm.bookdriver.data.Book
import se.jferm.bookdriver.ui.MainActivity

/**
 * Background playback. Owns the player and the media session, which gives us the
 * notification and lock-screen controls, Bluetooth/headset buttons, Android Auto
 * browsing and playback resumption. The UI talks to it through a MediaController.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {

    private val app by lazy { application as BookDriverApp }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var player: BookPlayer
    private var session: MediaLibrarySession? = null
    private var ticks = 0
    private var retries = 0
    private var lastRetryWindow = 0L

    override fun onCreate() {
        super.onCreate()
        val exo = PlayerFactory.create(this, app.http, app.settings.pauseForOtherSounds.value)
        player = BookPlayer(exo, app.library, app.settings)
        exo.addListener(ProgressListener())

        val back = CommandButton.Builder(CommandButton.ICON_SKIP_BACK_10)
            .setDisplayName("Back 10 seconds")
            .setPlayerCommand(Player.COMMAND_SEEK_BACK)
            .setSlots(CommandButton.SLOT_BACK)
            .build()
        val forward = CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD_30)
            .setDisplayName("Forward 30 seconds")
            .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
            .setSlots(CommandButton.SLOT_FORWARD)
            .build()

        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaLibrarySession.Builder(this, player, Callback())
            .setSessionActivity(openApp)
            .setMediaButtonPreferences(ImmutableList.of(back, forward))
            .build()

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().also { it.setSmallIcon(R.drawable.ic_notification) }
        )

        // Keep speed / skip-silence in step with settings.
        scope.launch { app.settings.speed.collect { exo.setPlaybackSpeed(it) } }
        scope.launch { app.settings.skipSilence.collect { exo.skipSilenceEnabled = it } }
        scope.launch {
            app.settings.pauseForOtherSounds.collect {
                exo.setAudioAttributes(PlayerFactory.audioAttributes(it), /* handleAudioFocus = */ true)
            }
        }
        app.sleepTimer.attach(player, scope)

        // Save position regularly while playing; push to Drive every couple of minutes.
        scope.launch {
            while (isActive) {
                delay(10_000)
                if (player.isPlaying) {
                    saveProgress()
                    if (++ticks % 12 == 0) app.progressSync.schedulePush(0)
                }
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        saveProgress()
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            pauseAllPlayersAndStopSelf()
        }
    }

    override fun onDestroy() {
        saveProgress()
        app.progressSync.schedulePush(0)
        app.sleepTimer.detach()
        scope.cancel()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    // ---- progress --------------------------------------------------------------------

    private fun saveProgress(finished: Boolean? = null) {
        if (!::player.isInitialized) return
        val (bookId, track) = BookMedia.parse(player.currentMediaItem?.mediaId) ?: return
        if (player.playbackState == Player.STATE_IDLE && finished == null) return
        app.library.saveProgress(bookId, track, player.currentPosition, finished)
    }

    private inner class ProgressListener : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) {
                saveProgress()
                app.progressSync.schedulePush(5_000)
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // A newly loaded book isn't progress; moving to the next file is.
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) return
            saveProgress(finished = false)
        }

        override fun onPlaybackStateChanged(state: Int) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (state == Player.STATE_BUFFERING) bufferingSince = now
            else if (bufferingSince != 0L) {
                val ms = now - bufferingSince
                bufferingSince = 0
                if (player.playWhenReady && ms > 300) DiagLog.i(TAG, "stalled ${ms} ms waiting for data")
            }
            when (state) {
                Player.STATE_READY -> {
                    val (bookId, track) = BookMedia.parse(player.currentMediaItem?.mediaId) ?: return
                    val d = player.duration
                    if (d != C.TIME_UNSET) app.library.updateTrackDuration(bookId, track, d)
                }
                Player.STATE_ENDED -> {
                    val (bookId, _) = BookMedia.parse(player.currentMediaItem?.mediaId) ?: return
                    DiagLog.i(TAG, "finished $bookId")
                    app.library.saveProgress(bookId, 0, 0, finished = true)
                    app.progressSync.schedulePush(0)
                }
            }
        }

        // What stopped the sound, for the diagnostics log: our own pause, another app taking
        // audio focus, Bluetooth disconnecting, or the network not keeping up.
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            val why = when (reason) {
                Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST -> "user/controller"
                Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS -> "audio focus lost"
                Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY -> "headphones/Bluetooth disconnected"
                Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE -> "remote"
                Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM -> "end of item"
                Player.PLAY_WHEN_READY_CHANGE_REASON_SUPPRESSED_TOO_LONG -> "suppressed too long"
                else -> "reason $reason"
            }
            DiagLog.i(TAG, "${if (playWhenReady) "play" else "pause"} ($why)")
        }

        override fun onPlaybackSuppressionReasonChanged(reason: Int) {
            val why = when (reason) {
                Player.PLAYBACK_SUPPRESSION_REASON_NONE -> "none"
                Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS -> "another app is using audio"
                Player.PLAYBACK_SUPPRESSION_REASON_UNSUITABLE_AUDIO_OUTPUT -> "unsuitable audio output"
                else -> "reason $reason"
            }
            DiagLog.i(TAG, "suppression: $why")
        }

        private var bufferingSince = 0L

        override fun onPlayerError(error: PlaybackException) {
            DiagLog.e(TAG, "player error ${error.errorCodeName}", error.cause ?: error)
            // Streaming hiccups (dropped connection, timeout, expired token) are usually
            // gone a moment later: pick up where we were, a few times, before giving up.
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastRetryWindow > 120_000) { lastRetryWindow = now; retries = 0 }
            val network = error.errorCode in 2000..2999
            if (network && retries < 4 && player.playWhenReady) {
                retries++
                scope.launch {
                    delay(1_500L * retries)
                    DiagLog.i(TAG, "retrying after ${error.errorCodeName} (#$retries)")
                    player.prepare()
                }
            }
        }
    }

    // ---- session callback ------------------------------------------------------------

    /** Expand "book:<id>" into its tracks, starting where the listener left off. */
    private fun resolve(book: Book): MediaSession.MediaItemsWithStartPosition {
        val items = BookMedia.trackItems(book, app.drive, app.library.coversDir)
        val p = app.library.progress.value[book.id]
        val (index, pos) = if (p == null || p.finished) 0 to 0L
        else p.trackIndex.coerceIn(0, items.lastIndex) to p.positionMs
        return MediaSession.MediaItemsWithStartPosition(items, index, pos)
    }

    private inner class Callback : MediaLibrarySession.Callback {
        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> = Futures.immediateFuture(LibraryResult.ofItem(BookMedia.root(), params))

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val progress = app.library.progress.value
            val books = app.library.books.value
                .sortedByDescending { progress[it.id]?.lastPlayedAt ?: it.addedAt }
                .map { BookMedia.browseItem(it) }
            return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(books), params))
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val book = app.library.book(mediaId.removePrefix(BookMedia.BOOK_PREFIX))
            return Futures.immediateFuture(
                if (book != null) LibraryResult.ofItem(BookMedia.browseItem(book), null)
                else LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
            )
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val id = mediaItems.firstOrNull()?.mediaId
            val book = id?.takeIf { it.startsWith(BookMedia.BOOK_PREFIX) }
                ?.let { app.library.book(it.removePrefix(BookMedia.BOOK_PREFIX)) }
            if (book != null) return Futures.immediateFuture(resolve(book))
            return super.onSetMediaItems(mediaSession, controller, mediaItems, startIndex, startPositionMs)
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
        ): ListenableFuture<List<MediaItem>> {
            val out = mediaItems.flatMap { item ->
                val book = item.mediaId.takeIf { it.startsWith(BookMedia.BOOK_PREFIX) }
                    ?.let { app.library.book(it.removePrefix(BookMedia.BOOK_PREFIX)) }
                book?.let { BookMedia.trackItems(it, app.drive, app.library.coversDir) } ?: listOf(item)
            }
            return Futures.immediateFuture(out)
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val progress = app.library.progress.value
            val last = app.library.books.value
                .filter { progress[it.id]?.finished != true }
                .maxByOrNull { progress[it.id]?.lastPlayedAt ?: 0 }
                ?: return Futures.immediateFailedFuture(UnsupportedOperationException("Nothing to resume"))
            return Futures.immediateFuture(resolve(last))
        }

        override fun onMediaButtonEvent(
            session: MediaSession,
            controllerInfo: MediaSession.ControllerInfo,
            intent: Intent,
        ): Boolean {
            @Suppress("DEPRECATION")
            val key = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return false
            if (key.action != KeyEvent.ACTION_DOWN) return false
            return when (key.keyCode) {
                KeyEvent.KEYCODE_MEDIA_NEXT -> { player.jumpChapter(+1); true }
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { player.jumpChapter(-1); true }
                else -> false
            }
        }
    }

    companion object {
        private const val TAG = "playback"
    }
}
