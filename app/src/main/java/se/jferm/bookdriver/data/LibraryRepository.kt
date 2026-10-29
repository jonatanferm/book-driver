package se.jferm.bookdriver.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import se.jferm.bookdriver.DiagLog
import java.io.File

/**
 * The library lives in two small JSON files in app storage: the books (with their
 * tracks and chapters) and listening progress. Small enough to keep in memory and
 * rewrite on change; progress is mirrored to Drive by [se.jferm.bookdriver.drive.ProgressSync].
 */
class LibraryRepository(context: Context, private val scope: CoroutineScope) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val dir = File(context.filesDir, "library").apply { mkdirs() }
    val coversDir = File(dir, "covers").apply { mkdirs() }
    private val booksFile = File(dir, "books.json")
    private val progressFile = File(dir, "progress.json")
    private val writeLock = Mutex()

    private val booksSerializer = ListSerializer(Book.serializer())
    private val progressSerializer = MapSerializer(String.serializer(), Progress.serializer())

    private val _books = MutableStateFlow(readBooks())
    val books: StateFlow<List<Book>> = _books

    private val _progress = MutableStateFlow(readProgress())
    val progress: StateFlow<Map<String, Progress>> = _progress

    /** Called after local progress changes so the Drive mirror can be scheduled. */
    var onProgressChanged: (() -> Unit)? = null

    fun book(id: String): Book? = _books.value.firstOrNull { it.id == id }

    fun containsFile(fileId: String): Boolean = _books.value.any { b -> b.tracks.any { it.fileId == fileId } }

    fun upsert(book: Book) {
        _books.update { list ->
            val i = list.indexOfFirst { it.id == book.id }
            if (i >= 0) list.toMutableList().also { it[i] = book } else list + book
        }
        persistBooks()
    }

    fun update(id: String, change: (Book) -> Book) {
        var changed = false
        _books.update { list ->
            list.map { if (it.id == id) change(it).also { changed = true } else it }
        }
        if (changed) persistBooks()
    }

    fun updateTrackDuration(bookId: String, trackIndex: Int, durationMs: Long) {
        val book = book(bookId) ?: return
        val track = book.tracks.getOrNull(trackIndex) ?: return
        if (durationMs <= 0 || kotlin.math.abs(track.durationMs - durationMs) < 1500) return
        update(bookId) { b ->
            b.copy(tracks = b.tracks.mapIndexed { i, t -> if (i == trackIndex) t.copy(durationMs = durationMs) else t })
        }
    }

    fun remove(id: String) {
        book(id)?.coverFile?.let { File(coversDir, it).delete() }
        _books.update { list -> list.filterNot { it.id == id } }
        _progress.update { it - id }
        persistBooks()
        persistProgress()
    }

    fun saveProgress(bookId: String, trackIndex: Int, positionMs: Long, finished: Boolean? = null) {
        val now = System.currentTimeMillis()
        _progress.update { map ->
            val old = map[bookId] ?: Progress()
            map + (bookId to old.copy(
                trackIndex = trackIndex,
                positionMs = positionMs.coerceAtLeast(0),
                updatedAt = now,
                lastPlayedAt = now,
                finished = finished ?: old.finished,
            ))
        }
        persistProgress()
        onProgressChanged?.invoke()
    }

    fun setFinished(bookId: String, finished: Boolean) {
        val now = System.currentTimeMillis()
        _progress.update { map ->
            val old = map[bookId] ?: Progress()
            val reset = if (!finished) old.copy(trackIndex = 0, positionMs = 0) else old
            map + (bookId to reset.copy(finished = finished, updatedAt = now))
        }
        persistProgress()
        onProgressChanged?.invoke()
    }

    /** Merge progress from another device: newest write per book wins. Returns true if anything changed. */
    fun mergeRemoteProgress(remote: Map<String, Progress>): Boolean {
        var changed = false
        _progress.update { local ->
            val out = local.toMutableMap()
            remote.forEach { (id, r) ->
                val l = out[id]
                if (l == null || r.updatedAt > l.updatedAt) {
                    out[id] = r
                    changed = true
                }
            }
            out
        }
        if (changed) persistProgress()
        return changed
    }

    fun clearAll() {
        _books.value = emptyList()
        _progress.value = emptyMap()
        coversDir.listFiles()?.forEach { it.delete() }
        persistBooks()
        persistProgress()
    }

    // ---- persistence -----------------------------------------------------------------

    private fun readBooks(): List<Book> = runCatching {
        if (booksFile.exists()) json.decodeFromString(booksSerializer, booksFile.readText()) else emptyList()
    }.getOrElse { DiagLog.e("library", "couldn't read books.json", it); emptyList() }
        // An import interrupted by the app being killed would otherwise spin forever.
        .map { if (it.status == ImportStatus.IMPORTING) it.copy(status = ImportStatus.FAILED, error = "Import was interrupted") else it }
        .map { it.copy(title = Library.stripSeriesSuffix(it.title, it.series)) }

    private fun readProgress(): Map<String, Progress> = runCatching {
        if (progressFile.exists()) json.decodeFromString(progressSerializer, progressFile.readText()) else emptyMap()
    }.getOrElse { DiagLog.e("library", "couldn't read progress.json", it); emptyMap() }

    private fun persistBooks() {
        val snapshot = _books.value
        scope.launch(Dispatchers.IO) { writeLock.withLock { atomicWrite(booksFile, json.encodeToString(booksSerializer, snapshot)) } }
    }

    private fun persistProgress() {
        val snapshot = _progress.value
        scope.launch(Dispatchers.IO) { writeLock.withLock { atomicWrite(progressFile, json.encodeToString(progressSerializer, snapshot)) } }
    }

    private fun atomicWrite(target: File, text: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.writeText(text)
            tmp.delete()
        }
    }
}
