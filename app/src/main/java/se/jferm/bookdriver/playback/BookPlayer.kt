package se.jferm.bookdriver.playback

import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import se.jferm.bookdriver.data.Book
import se.jferm.bookdriver.data.LibraryRepository
import se.jferm.bookdriver.data.Settings

/**
 * The player the media session exposes. Adds audiobook behaviour on top of ExoPlayer:
 *  - next / previous move by CHAPTER (inside one .m4b too), not just by file
 *  - smart rewind: resuming after a pause backs up a little, more after longer breaks
 */
@OptIn(UnstableApi::class)
class BookPlayer(
    val exo: ExoPlayer,
    private val repo: LibraryRepository,
    private val settings: Settings,
) : ForwardingPlayer(exo) {

    private var pausedAt = 0L

    init {
        exo.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) pausedAt = 0
                else if (exo.playbackState == Player.STATE_READY && !exo.playWhenReady) pausedAt = SystemClock.elapsedRealtime()
            }
        })
    }

    fun currentBook(): Book? = BookMedia.parse(currentMediaItem?.mediaId)?.first?.let(repo::book)

    override fun getAvailableCommands(): Player.Commands =
        super.getAvailableCommands().buildUpon()
            .add(Player.COMMAND_SEEK_TO_NEXT)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS)
            .build()

    override fun isCommandAvailable(command: Int): Boolean =
        command == Player.COMMAND_SEEK_TO_NEXT || command == Player.COMMAND_SEEK_TO_PREVIOUS || super.isCommandAvailable(command)

    // ForwardingPlayer passes ExoPlayer's own command set through onAvailableCommandsChanged,
    // which for a one-file book lacks "next". Re-send ours so the session (notification,
    // Bluetooth, Android Auto) keeps offering chapter skips.
    private val wrapped = HashMap<Player.Listener, Player.Listener>()

    override fun addListener(listener: Player.Listener) {
        val w = CommandsListener(listener) { availableCommands }
        wrapped[listener] = w
        super.addListener(w)
    }

    override fun removeListener(listener: Player.Listener) {
        super.removeListener(wrapped.remove(listener) ?: listener)
    }

    override fun seekToNext() = jumpChapter(+1)
    override fun seekToPrevious() = jumpChapter(-1)

    /**
     * Runs before every resume, however it was asked for (app, notification, lock screen,
     * headset, car), and calls `proceed` when playback may start. The service uses it to
     * check Drive for newer progress from another device first.
     */
    var beforeResume: ((proceed: () -> Unit) -> Unit)? = null
    private var resuming = false

    override fun play() = resume { super.play() }

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (playWhenReady) resume { super.setPlayWhenReady(true) } else super.setPlayWhenReady(false)
    }

    private fun resume(start: () -> Unit) {
        val hook = beforeResume
        if (hook == null || exo.playWhenReady) { applySmartRewind(); start(); return }
        if (resuming) return // already checking; a second press shouldn't stack up
        resuming = true
        hook {
            resuming = false
            applySmartRewind()
            start()
        }
    }

    /** We just jumped to where another device left off: don't rewind from there. */
    fun skipSmartRewind() { pausedAt = 0 }

    /** Book-wide position in ms, or the item position when no book is loaded. */
    fun globalPosition(): Long {
        val book = currentBook() ?: return currentPosition
        return book.globalMs(currentMediaItemIndex, currentPosition)
    }

    fun seekToGlobal(book: Book, globalMs: Long) {
        val (track, pos) = book.locate(globalMs)
        exo.seekTo(track, pos)
    }

    fun seekToChapter(index: Int) {
        val book = currentBook() ?: return
        seekToGlobal(book, book.chapterStartGlobal(index))
    }

    fun jumpChapter(direction: Int) {
        val book = currentBook()
        if (book == null) {
            if (direction > 0) super.seekToNext() else super.seekToPrevious()
            return
        }
        val chapters = book.effectiveChapters
        val idx = book.chapterIndexAt(currentMediaItemIndex, currentPosition)
        val now = globalPosition()
        val target = if (direction > 0) {
            if (idx + 1 >= chapters.size) return
            book.chapterStartGlobal(idx + 1)
        } else {
            // Like a CD player: first press goes to the start of this chapter.
            if (now - book.chapterStartGlobal(idx) > RESTART_THRESHOLD_MS || idx == 0) book.chapterStartGlobal(idx)
            else book.chapterStartGlobal(idx - 1)
        }
        seekToGlobal(book, target)
    }

    private fun applySmartRewind() {
        val since = pausedAt
        pausedAt = 0
        if (since == 0L || !settings.smartRewind.value || exo.isPlaying) return
        val gap = SystemClock.elapsedRealtime() - since
        val back = when {
            gap < 5_000 -> 0L
            gap < 60_000 -> 3_000L
            gap < 60 * 60_000 -> 10_000L
            else -> 20_000L
        }
        if (back > 0) exo.seekTo(exo.currentMediaItemIndex, (exo.currentPosition - back).coerceAtLeast(0))
    }

    companion object {
        private const val RESTART_THRESHOLD_MS = 3_000L
    }
}
