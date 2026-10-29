package se.jferm.bookdriver.drive

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import se.jferm.bookdriver.DiagLog
import se.jferm.bookdriver.data.Book
import se.jferm.bookdriver.data.Chapter
import se.jferm.bookdriver.data.ImportStatus
import se.jferm.bookdriver.data.Library
import se.jferm.bookdriver.data.LibraryRepository
import se.jferm.bookdriver.data.Track
import se.jferm.bookdriver.media.Mp3Probe
import se.jferm.bookdriver.media.Mp4Parser
import se.jferm.bookdriver.media.RangeSource
import java.io.File

/**
 * Turns files picked in the Drive picker into books.
 *
 * Grouping: every .m4b is its own book; all other audio files picked from the same
 * folder become one book (one chapter per file, in natural order). Folders themselves
 * grant no access to their contents under drive.file, so they only contribute the
 * files the user has also picked.
 *
 * Each new book is saved right away as IMPORTING, then probed in the background for
 * durations, chapters, tags and cover art using Range requests (no audio is downloaded).
 */
class BookImporter(
    private val drive: DriveApi,
    private val repo: LibraryRepository,
    private val scope: CoroutineScope,
) {
    data class Result(val added: List<String>, val alreadyInLibrary: Int, val notAudio: Int, val folders: Int)

    private val probeLock = Mutex()

    suspend fun import(pickedIds: List<String>): Result {
        val audio = mutableListOf<DriveFile>()
        var notAudio = 0
        var folders = 0
        for (id in pickedIds.distinct()) {
            val f = runCatching { drive.get(id) }.getOrElse { DiagLog.e(TAG, "get($id) failed", it); null } ?: continue
            when {
                f.isFolder -> {
                    folders++
                    val kids = runCatching { drive.listChildren(f.id) }.getOrDefault(emptyList())
                    audio += kids.filter { it.isAudio }
                }
                f.isAudio -> audio += f
                else -> notAudio++
            }
        }
        val unique = audio.distinctBy { it.id }
        val fresh = unique.filterNot { repo.containsFile(it.id) }

        val added = mutableListOf<String>()
        for (group in group(fresh)) {
            val book = placeholderBook(group)
            repo.upsert(book)
            added += book.id
            scope.launch(Dispatchers.IO) { probeLock.withLock { probe(book.id) } }
        }
        DiagLog.i(TAG, "import: ${added.size} new book(s), ${unique.size - fresh.size} already in library, $notAudio not audio")
        return Result(added, unique.size - fresh.size, notAudio, folders)
    }

    /** Re-run the probe for a book whose import failed. */
    fun retry(bookId: String) {
        repo.update(bookId) { it.copy(status = ImportStatus.IMPORTING, error = null) }
        scope.launch(Dispatchers.IO) { probeLock.withLock { probe(bookId) } }
    }

    private fun group(files: List<DriveFile>): List<List<DriveFile>> {
        val out = mutableListOf<List<DriveFile>>()
        files.groupBy { it.parents.firstOrNull() }.forEach { (_, inFolder) ->
            val (m4b, rest) = inFolder.partition { isStandalone(it) }
            m4b.forEach { out += listOf(it) }
            if (rest.isNotEmpty()) out += rest.sortedWith(compareBy(NaturalOrder) { it.name })
        }
        return out
    }

    private fun placeholderBook(files: List<DriveFile>): Book {
        val first = files.first()
        val (title, index) = Library.splitSeriesIndex(
            if (files.size == 1) Library.stripExtension(first.name) else Library.prettyTrackName(first.name, 0)
        )
        return Book(
            id = first.id,
            title = title,
            seriesIndex = index,
            tracks = files.map { Track(it.id, it.name, it.mimeType, it.size ?: 0) },
            status = ImportStatus.IMPORTING,
        )
    }

    private suspend fun probe(bookId: String) {
        val book = repo.book(bookId) ?: return
        try {
            val tracks = book.tracks.toMutableList()
            val chapters = mutableListOf<Chapter>()
            var title: String? = null
            var album: String? = null
            var artist: String? = null
            var grouping: String? = null
            var cover: ByteArray? = null

            tracks.forEachIndexed { i, t ->
                val src = RangeSource(drive, t.fileId, t.size)
                // Sniff the container rather than trusting names/MIME types (same block is reused by the parser).
                val head = src.read(0, 12)
                val mp4 = head.size >= 8 && String(head, 4, 4, Charsets.ISO_8859_1) == "ftyp"
                if (mp4 || (head.size < 8 && isMp4(t))) {
                    val info = Mp4Parser(src).parse()
                    tracks[i] = t.copy(durationMs = info.durationMs)
                    info.chapters.forEach { (start, name) -> chapters += Chapter(name, i, start) }
                    if (i == 0) {
                        title = info.title; album = info.album
                        artist = info.artist ?: info.albumArtist
                        grouping = info.grouping
                        cover = info.cover
                    }
                } else {
                    val info = Mp3Probe(src).probe(wantCover = i == 0)
                    tracks[i] = t.copy(durationMs = info.durationMs)
                    if (i == 0) {
                        title = info.title; album = info.album
                        artist = info.artist ?: info.albumArtist
                        cover = info.cover
                    }
                }
                DiagLog.i(TAG, "probed ${t.name}: ${Library.formatClock(tracks[i].durationMs)}, ${src.requests} request(s)")
            }

            // Single-file books: the title tag names the book, and the folder is often the series.
            // Multi-file books: the album tag, else the folder they sit in, names the book.
            val parentName = parentFolderName(book)
            val single = tracks.size == 1
            val rawTitle = (if (single) title ?: album else album ?: parentName)?.takeIf { it.isNotBlank() }
            val (cleanTitle, idx) = rawTitle?.let { Library.splitSeriesIndex(it) } ?: (book.title to book.seriesIndex)
            val series = grouping ?: parentName?.takeIf { single && !it.equals(cleanTitle, ignoreCase = true) }
            val coverFile = cover?.takeIf { it.size > 1024 }?.let { bytes ->
                val name = "$bookId.img"
                File(repo.coversDir, name).writeBytes(bytes)
                name
            }

            // A multi-file book where the files carry no chapter marks: one chapter per file, named from tags or file names.
            val finalChapters = if (chapters.isEmpty()) emptyList() else chapters

            repo.update(bookId) {
                it.copy(
                    title = Library.stripSeriesSuffix(cleanTitle, series ?: it.series),
                    seriesIndex = idx ?: it.seriesIndex,
                    author = artist ?: it.author,
                    series = series ?: it.series,
                    tracks = tracks,
                    chapters = finalChapters,
                    coverFile = coverFile ?: it.coverFile,
                    status = ImportStatus.READY,
                    error = null,
                )
            }
            DiagLog.i(TAG, "imported '$cleanTitle': ${tracks.size} file(s), ${finalChapters.size} chapter mark(s), cover=${coverFile != null}")
        } catch (t: Throwable) {
            DiagLog.e(TAG, "probe failed for ${book.title}", t)
            repo.update(bookId) { it.copy(status = ImportStatus.FAILED, error = t.message ?: t.javaClass.simpleName) }
        }
    }

    private suspend fun parentFolderName(book: Book): String? {
        val file = runCatching { drive.get(book.tracks.first().fileId) }.getOrNull() ?: return null
        val parent = file.parents.firstOrNull() ?: return null
        return runCatching { drive.get(parent).name }.getOrNull()?.takeIf { it != "My Drive" }
    }

    /** An .m4b (by name or MIME type), or an MP4-family file with no audio extension at all. */
    private fun isStandalone(f: DriveFile): Boolean =
        f.name.endsWith(".m4b", ignoreCase = true) ||
            f.mimeType in setOf("audio/x-m4b", "audio/m4b") ||
            (!f.hasAudioExtension && f.mimeType in setOf("audio/mp4", "video/mp4", "audio/x-m4a"))

    companion object {
        private const val TAG = "import"
        fun isMp4(t: Track): Boolean {
            val n = t.name.lowercase()
            return n.endsWith(".m4b") || n.endsWith(".m4a") || n.endsWith(".mp4") ||
                t.mimeType in setOf("audio/mp4", "audio/x-m4b", "audio/m4b", "audio/x-m4a", "video/mp4")
        }
    }
}

/** "Chapter 2" < "Chapter 10". Splits into digit / non-digit runs. */
object NaturalOrder : Comparator<String> {
    private val chunk = Regex("\\d+|\\D+")
    override fun compare(a: String, b: String): Int {
        val ca = chunk.findAll(a.lowercase()).map { it.value }.toList()
        val cb = chunk.findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(ca.size, cb.size)) {
            val x = ca[i]; val y = cb[i]
            val c = if (x[0].isDigit() && y[0].isDigit()) {
                val nx = x.trimStart('0'); val ny = y.trimStart('0')
                if (nx.length != ny.length) nx.length - ny.length else nx.compareTo(ny)
            } else x.compareTo(y)
            if (c != 0) return c
        }
        return ca.size - cb.size
    }
}
