package se.jferm.bookdriver.drive

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import se.jferm.bookdriver.DiagLog
import se.jferm.bookdriver.data.Book
import se.jferm.bookdriver.data.ImportStatus
import se.jferm.bookdriver.data.LibraryRepository
import java.io.File

/** One book in library.json: the book, or null when it was removed (a tombstone). */
@Serializable
data class LibraryEntry(val book: Book? = null, val updatedAt: Long = 0)

@Serializable
data class LibraryFile(val version: Int = 1, val books: Map<String, LibraryEntry> = emptyMap())

/**
 * Mirrors the library (books, tracks, chapters, tags) to library.json in the hidden
 * appDataFolder, and cover art to cover-<bookId>.img next to it. That restores the whole
 * library on a new phone and lets the web player show it. Newest change per book wins;
 * removals travel as tombstones so a deleted book doesn't come back from another device.
 */
/**
 * Newest entry per book id wins. Returns the merged library and the ids where Drive's entry
 * won and differs from what this device has (those need applying locally).
 */
fun mergeLibrary(
    local: Map<String, LibraryEntry>,
    remote: Map<String, LibraryEntry>,
): Pair<Map<String, LibraryEntry>, List<String>> {
    val remoteWins = mutableListOf<String>()
    val merged = (local.keys + remote.keys).associateWith { id ->
        val l = local[id]; val r = remote[id]
        when {
            r == null -> l!!
            l == null -> { if (r.book != null) remoteWins += id; r }
            r.updatedAt > l.updatedAt -> { remoteWins += id; r }
            else -> l
        }
    }
    return merged to remoteWins
}

class LibrarySync(
    private val drive: DriveApi,
    private val repo: LibraryRepository,
    private val scope: CoroutineScope,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()
    private var pending: Job? = null

    suspend fun syncNow() = mutex.withLock {
        runCatching { sync() }.onFailure { DiagLog.e(TAG, "library sync failed", it) }
    }

    /** Coalesce bursts of local changes (an import updates a book several times). */
    fun schedule(delayMs: Long = 5_000) {
        pending?.cancel()
        pending = scope.launch {
            delay(delayMs)
            syncNow()
        }
    }

    fun reset() { pending?.cancel() }

    private suspend fun sync() {
        val files = drive.listAppData()
        val libFile = files.firstOrNull { it.name == FILE_NAME }
        val remote = libFile?.let { f ->
            runCatching { json.decodeFromString(LibraryFile.serializer(), drive.downloadText(f.id)).books }.getOrNull()
        } ?: emptyMap()

        // Merge: newest entry per book id wins. Books still importing here stay local.
        val local = buildMap {
            repo.books.value.filter { it.status == ImportStatus.READY }
                .forEach { put(it.id, LibraryEntry(it, it.updatedAt)) }
            repo.removed.value.forEach { (id, at) -> put(id, LibraryEntry(null, at)) }
        }
        val (merged, remoteWins) = mergeLibrary(local, remote)
        var pulled = 0
        for (id in remoteWins) {
            if (repo.book(id)?.status == ImportStatus.IMPORTING) continue
            val r = remote.getValue(id)
            if (r.book != null) repo.applyRemoteBook(r.book.copy(coverFile = repo.book(id)?.coverFile))
            else repo.applyRemoteRemoval(id, r.updatedAt)
            pulled++
        }
        if (pulled > 0) DiagLog.i(TAG, "applied $pulled change(s) from Drive")

        if (merged != remote) {
            val text = json.encodeToString(LibraryFile.serializer(), LibraryFile(books = merged))
            if (libFile == null) drive.createAppDataFile(FILE_NAME, text) else drive.updateFileContent(libFile.id, text)
            DiagLog.i(TAG, "saved library to Drive: ${merged.count { it.value.book != null }} book(s)")
        }

        syncCovers(files, merged)
    }

    private suspend fun syncCovers(files: List<DriveFile>, merged: Map<String, LibraryEntry>) {
        val remoteCovers = files.filter { it.name.startsWith(COVER_PREFIX) }
            .associateBy { it.name.removePrefix(COVER_PREFIX).removeSuffix(".img") }
        for ((id, entry) in merged) {
            val remoteCover = remoteCovers[id]
            val book = repo.book(id)
            when {
                // Removed book: drop its cover from Drive too.
                entry.book == null -> remoteCover?.let { runCatching { drive.delete(it.id) } }
                // Cover only here: upload it.
                book?.coverFile != null && remoteCover == null -> {
                    val f = File(repo.coversDir, book.coverFile)
                    if (f.exists()) runCatching {
                        drive.createAppDataBinary(COVER_PREFIX + id + ".img", withContext(Dispatchers.IO) { f.readBytes() }, "image/jpeg")
                    }.onFailure { DiagLog.e(TAG, "cover upload failed for $id", it) }
                }
                // Cover only on Drive (book came from another device): download it.
                book != null && book.coverFile == null && remoteCover != null -> runCatching {
                    val bytes = drive.downloadBytes(remoteCover.id)
                    val name = "$id.img"
                    withContext(Dispatchers.IO) { File(repo.coversDir, name).writeBytes(bytes) }
                    repo.attachCover(id, name)
                }.onFailure { DiagLog.e(TAG, "cover download failed for $id", it) }
            }
        }
    }

    companion object {
        const val FILE_NAME = "library.json"
        const val COVER_PREFIX = "cover-"
        private const val TAG = "library"
    }
}
