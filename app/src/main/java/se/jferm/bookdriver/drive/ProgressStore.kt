package se.jferm.bookdriver.drive

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import se.jferm.bookdriver.DiagLog
import se.jferm.bookdriver.data.LibraryRepository
import se.jferm.bookdriver.data.Progress

@Serializable
private data class ProgressFile(val version: Int = 2, val books: Map<String, Progress> = emptyMap())

/**
 * Mirrors listening progress to progress.json in the user's hidden appDataFolder, so it
 * follows them across devices and reinstalls. Book IDs are Drive file IDs, which are the
 * same on every device. Newest write per book wins.
 */
class ProgressSync(
    private val drive: DriveApi,
    private val repo: LibraryRepository,
    private val scope: CoroutineScope,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()
    private var fileId: String? = null
    private var pending: Job? = null
    @Volatile private var lastSyncAt = 0L

    fun secondsSinceSync(): Long = (System.currentTimeMillis() - lastSyncAt) / 1000

    /**
     * Pull, merge, push. Always merging before writing means a book another device (the web
     * player, another phone) saved in the meantime is never overwritten. Safe to call often.
     */
    suspend fun syncNow() = mutex.withLock {
        runCatching {
            val remote = pull()
            if (remote.isNotEmpty()) {
                val changed = repo.mergeRemoteProgress(remote)
                if (changed) DiagLog.i(TAG, "merged newer progress from Drive")
            }
            val local = repo.progress.value
            if (local != remote) push(local)
            lastSyncAt = System.currentTimeMillis()
        }.onFailure { DiagLog.e(TAG, "sync failed", it) }
    }

    /** Coalesce frequent local changes into one sync. */
    fun schedulePush(delayMs: Long = 30_000) {
        pending?.cancel()
        pending = scope.launch {
            delay(delayMs)
            syncNow()
        }
    }

    fun reset() {
        pending?.cancel()
        fileId = null
    }

    private suspend fun pull(): Map<String, Progress> {
        val f = drive.findAppDataFile(FILE_NAME) ?: return emptyMap()
        fileId = f.id
        val text = drive.downloadText(f.id)
        return runCatching { json.decodeFromString(ProgressFile.serializer(), text).books }
            .getOrElse { emptyMap() } // older spike format or junk: start fresh
    }

    private suspend fun push(map: Map<String, Progress>) {
        val text = json.encodeToString(ProgressFile.serializer(), ProgressFile(books = map))
        val id = fileId ?: drive.findAppDataFile(FILE_NAME)?.id
        if (id == null) {
            fileId = drive.createAppDataFile(FILE_NAME, text)
        } else {
            fileId = id
            drive.updateFileContent(id, text)
        }
    }

    companion object {
        private const val FILE_NAME = "progress.json"
        private const val TAG = "progress"
    }
}
