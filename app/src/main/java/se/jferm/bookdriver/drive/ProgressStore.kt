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

    /** Pull, merge, push. Safe to call often; does nothing when signed out (requests fail). */
    suspend fun syncNow() = mutex.withLock {
        runCatching {
            val remote = pull()
            if (remote.isNotEmpty()) {
                val changed = repo.mergeRemoteProgress(remote)
                if (changed) DiagLog.i(TAG, "merged progress for ${remote.size} book(s) from Drive")
            }
            push(repo.progress.value)
        }.onFailure { DiagLog.e(TAG, "sync failed", it) }
    }

    /** Coalesce frequent local changes into one upload. */
    fun schedulePush(delayMs: Long = 30_000) {
        pending?.cancel()
        pending = scope.launch {
            delay(delayMs)
            mutex.withLock { runCatching { push(repo.progress.value) }.onFailure { DiagLog.e(TAG, "push failed", it) } }
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
