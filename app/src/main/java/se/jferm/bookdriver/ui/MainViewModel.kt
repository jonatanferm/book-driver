package se.jferm.bookdriver.ui

import android.app.Application
import android.content.ComponentName
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import se.jferm.bookdriver.BookDriverApp
import se.jferm.bookdriver.DiagLog
import se.jferm.bookdriver.data.Book
import se.jferm.bookdriver.playback.BookMedia
import se.jferm.bookdriver.playback.PlaybackService

enum class AuthKind { CONNECT, PICK }

enum class Screen { Welcome, Library, Player, Chapters, AddBook }

enum class Sheet { None, SpeedSleep, Settings }

/** What the UI needs from the player, sampled from the MediaController. */
data class PlaybackUi(
    val bookId: String? = null,
    val trackIndex: Int = 0,
    val positionMs: Long = 0,
    val isPlaying: Boolean = false,
    val buffering: Boolean = false,
    /** Why playback stopped, when it stopped on an error. */
    val error: String? = null,
)

data class ImportUi(
    val running: Boolean = false,
    val bookIds: List<String> = emptyList(),
    val message: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val deps = app as BookDriverApp
    val auth = deps.auth
    val library = deps.library
    val settings = deps.settings
    val sleepTimer = deps.sleepTimer
    val coversDir get() = library.coversDir

    private val _stack = MutableStateFlow(listOf(if (auth.account.value == null) Screen.Welcome else Screen.Library))
    val stack: StateFlow<List<Screen>> = _stack.asStateFlow()

    private val _sheet = MutableStateFlow(Sheet.None)
    val sheet: StateFlow<Sheet> = _sheet.asStateFlow()

    private val _playback = MutableStateFlow(PlaybackUi())
    val playback: StateFlow<PlaybackUi> = _playback.asStateFlow()

    private val _import = MutableStateFlow(ImportUi())
    val import: StateFlow<ImportUi> = _import.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    init {
        connectController()
        if (auth.account.value != null) viewModelScope.launch { deps.progressSync.syncNow() }
        viewModelScope.launch {
            while (isActive) {
                sample()
                delay(if (_playback.value.isPlaying) 250 else 600)
            }
        }
    }

    override fun onCleared() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
    }

    // ---- navigation ------------------------------------------------------------------

    fun open(screen: Screen) {
        DiagLog.i("nav", "open $screen")
        _stack.update { if (it.lastOrNull() == screen) it else it + screen }
    }

    fun replaceAll(screen: Screen) { _stack.value = listOf(screen) }

    /** Returns false when there's nothing to go back to (let the system finish the activity). */
    fun back(): Boolean {
        if (_sheet.value != Sheet.None) { _sheet.value = Sheet.None; return true }
        val s = _stack.value
        if (s.size <= 1) return false
        DiagLog.i("nav", "back from ${s.last()}")
        _stack.value = s.dropLast(1)
        return true
    }

    fun showSheet(sheet: Sheet) { _sheet.value = sheet }

    fun consumeToast() { _toast.value = null }

    // ---- auth ------------------------------------------------------------------------

    fun onAuthResult(result: AuthorizationResult, kind: AuthKind) {
        val before = auth.account.value
        val picked = auth.onGranted(result, kind.name)
        viewModelScope.launch {
            if (kind == AuthKind.CONNECT || auth.account.value == null) {
                val email = runCatching { deps.drive.aboutEmail() }
                    .onFailure { DiagLog.e("auth", "couldn't read account email", it) }
                    .getOrNull()
                if (email != null) {
                    if (before != null && !before.equals(email, ignoreCase = true)) {
                        // Different Google account: its library and progress are its own.
                        DiagLog.i("auth", "switched account, clearing local library")
                        controller?.run { stop(); clearMediaItems() }
                        deps.progressSync.reset()
                        library.clearAll()
                        _import.value = ImportUi()
                    }
                    auth.setAccountEmail(email)
                }
            }
            when (kind) {
                AuthKind.CONNECT -> {
                    deps.progressSync.syncNow()
                    replaceAll(Screen.Library)
                    if (library.books.value.isEmpty()) open(Screen.AddBook)
                }
                AuthKind.PICK -> importPicked(picked)
            }
        }
    }

    fun onAuthError(message: String) { _toast.value = message }

    private suspend fun importPicked(ids: List<String>) {
        if (ids.isEmpty()) return
        _import.update { it.copy(running = true, message = null) }
        val r = runCatching { deps.importer.import(ids) }
        r.onSuccess { res ->
            val msg = when {
                res.added.isEmpty() && res.alreadyInLibrary > 0 -> "Those files are already in your library."
                res.added.isEmpty() && res.folders > 0 -> "Picking a folder doesn't share its files. Open the folder and select the files instead."
                res.added.isEmpty() -> "No audio files in that selection."
                res.notAudio > 0 -> "Skipped ${res.notAudio} file(s) that aren't audio."
                else -> null
            }
            _import.update { ImportUi(running = false, bookIds = (res.added + it.bookIds).distinct(), message = msg) }
        }.onFailure { e ->
            DiagLog.e("import", "import failed", e)
            _import.update { it.copy(running = false, message = "Couldn't read those files: ${e.message}") }
        }
    }

    fun retryImport(bookId: String) = deps.importer.retry(bookId)

    fun signOut() {
        controller?.run { stop(); clearMediaItems() }
        sleepTimer.cancel()
        viewModelScope.launch {
            auth.signOut()
            deps.progressSync.reset()
            library.clearAll()
            _import.value = ImportUi()
            _sheet.value = Sheet.None
            replaceAll(Screen.Welcome)
        }
    }

    // ---- playback --------------------------------------------------------------------

    private fun connectController() {
        val ctx = getApplication<Application>()
        val token = SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))
        val future = MediaController.Builder(ctx, token).buildAsync()
        controllerFuture = future
        future.addListener({
            controller = runCatching { future.get() }.getOrElse { DiagLog.e("ui", "controller connect failed", it); null }
            controller?.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = sample()
            })
            sample()
        }, ContextCompat.getMainExecutor(ctx))
    }

    private fun sample() {
        val c = controller ?: return
        val mid = c.currentMediaItem?.mediaId
        // Right after play() the item is still the "book:<id>" request until the service
        // expands it into tracks; keep showing that book instead of dropping to "nothing".
        val pending = mid?.takeIf { it.startsWith(BookMedia.BOOK_PREFIX) }?.removePrefix(BookMedia.BOOK_PREFIX)
        if (pending != null) {
            _playback.update { it.copy(bookId = pending, isPlaying = c.isPlaying, buffering = true) }
            return
        }
        val parsed = BookMedia.parse(mid)
        _playback.value = PlaybackUi(
            bookId = parsed?.first,
            trackIndex = parsed?.second ?: 0,
            positionMs = c.currentPosition.coerceAtLeast(0),
            isPlaying = c.isPlaying,
            buffering = c.playbackState == Player.STATE_BUFFERING,
            error = c.playerError?.let { e -> listOfNotNull(e.errorCodeName, e.cause?.message ?: e.message).joinToString(": ") },
        )
    }

    fun play(bookId: String, openPlayer: Boolean = true) {
        val c = controller ?: run { _toast.value = "Player is starting, try again"; return }
        val book = library.book(bookId) ?: return
        if (_playback.value.bookId != bookId) {
            if (library.progress.value[bookId]?.finished == true) library.setFinished(bookId, false)
            c.setMediaItem(MediaItem.Builder().setMediaId(BookMedia.BOOK_PREFIX + book.id).build())
            c.prepare()
            // Show the player right away; the next sample fills in the real position.
            val p = library.progress.value[bookId]
            _playback.value = PlaybackUi(bookId = bookId, trackIndex = p?.trackIndex ?: 0, positionMs = p?.positionMs ?: 0, buffering = true)
        }
        c.play()
        if (openPlayer) open(Screen.Player)
    }

    fun togglePlay() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            c.play()
        }
    }

    fun skipBack() = controller?.seekBack()
    fun skipForward() = controller?.seekForward()

    fun nextChapter() {
        val book = currentBook() ?: return
        val p = _playback.value
        val idx = book.chapterIndexAt(p.trackIndex, p.positionMs)
        if (idx + 1 < book.effectiveChapters.size) seekGlobal(book, book.chapterStartGlobal(idx + 1))
    }

    /** First press restarts the chapter; a second press within 3 s goes to the previous one. */
    fun previousChapter() {
        val book = currentBook() ?: return
        val p = _playback.value
        val idx = book.chapterIndexAt(p.trackIndex, p.positionMs)
        val now = book.globalMs(p.trackIndex, p.positionMs)
        val start = book.chapterStartGlobal(idx)
        seekGlobal(book, if (now - start > 3_000 || idx == 0) start else book.chapterStartGlobal(idx - 1))
    }

    fun seekToChapter(index: Int) {
        val book = currentBook() ?: return
        seekGlobal(book, book.chapterStartGlobal(index))
        controller?.play()
    }

    /** Seek within the current chapter, [fraction] 0..1. */
    fun seekInChapter(fraction: Float) {
        val book = currentBook() ?: return
        val p = _playback.value
        val idx = book.chapterIndexAt(p.trackIndex, p.positionMs)
        val start = book.chapterStartGlobal(idx)
        val end = book.chapterEndGlobal(idx)
        seekGlobal(book, start + ((end - start) * fraction.coerceIn(0f, 1f)).toLong())
    }

    private fun seekGlobal(book: Book, globalMs: Long) {
        val (track, pos) = book.locate(globalMs)
        controller?.seekTo(track, pos)
        _playback.update { it.copy(trackIndex = track, positionMs = pos) }
    }

    fun currentBook(): Book? = _playback.value.bookId?.let(library::book)

    fun removeBook(bookId: String) {
        if (_playback.value.bookId == bookId) controller?.run { stop(); clearMediaItems() }
        library.remove(bookId)
        _import.update { it.copy(bookIds = it.bookIds - bookId) }
        deps.progressSync.schedulePush(2_000)
    }

    fun setFinished(bookId: String, finished: Boolean) {
        if (finished && _playback.value.bookId == bookId) controller?.pause()
        library.setFinished(bookId, finished)
        deps.progressSync.schedulePush(2_000)
    }

    fun copyDiagnostics(): String = DiagLog.dump()
}
