package se.jferm.bookdriver.data

import kotlinx.serialization.Serializable

/** One audio file in Drive. A book has one (an .m4b) or many (a folder of .mp3s). */
@Serializable
data class Track(
    val fileId: String,
    val name: String,
    val mimeType: String,
    val size: Long,
    val durationMs: Long = 0,
)

/** A chapter starts at [startMs] inside track [trackIndex]. It ends where the next one starts. */
@Serializable
data class Chapter(
    val title: String,
    val trackIndex: Int,
    val startMs: Long,
)

@Serializable
enum class ImportStatus { IMPORTING, READY, FAILED }

@Serializable
data class Book(
    val id: String,
    val title: String,
    val author: String? = null,
    val series: String? = null,
    val seriesIndex: String? = null,
    val tracks: List<Track>,
    val chapters: List<Chapter> = emptyList(),
    val coverFile: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
    val status: ImportStatus = ImportStatus.READY,
    val error: String? = null,
) {
    val durationMs: Long get() = tracks.sumOf { it.durationMs }
    val sizeBytes: Long get() = tracks.sumOf { it.size }

    /** Chapters, falling back to one per track when the files carry none. */
    val effectiveChapters: List<Chapter>
        get() = chapters.ifEmpty {
            tracks.mapIndexed { i, t -> Chapter(Library.prettyTrackName(t.name, i), i, 0) }
        }

    fun trackOffsetMs(trackIndex: Int): Long = tracks.take(trackIndex.coerceAtLeast(0)).sumOf { it.durationMs }

    fun globalMs(trackIndex: Int, positionMs: Long): Long = trackOffsetMs(trackIndex) + positionMs

    /** Map a book-wide position back to (track, position in track). */
    fun locate(globalMs: Long): Pair<Int, Long> {
        var remaining = globalMs.coerceAtLeast(0)
        tracks.forEachIndexed { i, t ->
            if (t.durationMs <= 0 || remaining < t.durationMs || i == tracks.lastIndex) {
                return i to remaining.coerceAtMost(if (t.durationMs > 0) t.durationMs else remaining)
            }
            remaining -= t.durationMs
        }
        return 0 to 0
    }

    fun chapterStartGlobal(index: Int): Long {
        val c = effectiveChapters.getOrNull(index) ?: return 0
        return globalMs(c.trackIndex, c.startMs)
    }

    fun chapterEndGlobal(index: Int): Long {
        val chs = effectiveChapters
        return if (index + 1 < chs.size) chapterStartGlobal(index + 1) else durationMs
    }

    fun chapterIndexAt(trackIndex: Int, positionMs: Long): Int {
        val g = globalMs(trackIndex, positionMs)
        val chs = effectiveChapters
        var found = 0
        for (i in chs.indices) {
            if (chapterStartGlobal(i) <= g + 250) found = i else break
        }
        return found
    }
}

@Serializable
data class Progress(
    val trackIndex: Int = 0,
    val positionMs: Long = 0,
    val updatedAt: Long = 0,
    val lastPlayedAt: Long = 0,
    val finished: Boolean = false,
)

object Library {
    private val leadingNumber = Regex("^\\s*\\d+[\\s._-]*")
    private val extension = Regex("\\.[A-Za-z0-9]{2,4}$")

    fun stripExtension(name: String) = name.replace(extension, "")

    /** "03 - The Hunt.mp3" → "The Hunt"; falls back to "Part N". */
    fun prettyTrackName(name: String, index: Int): String {
        val base = stripExtension(name).replace(leadingNumber, "").trim()
        return base.ifEmpty { "Part ${index + 1}" }
    }

    private val bookN = Regex("^(.*?)[\\s,:–-]*\\(?\\b(?:Book|Bok|Volume|Vol\\.?)\\s*(\\d+(?:\\.\\d+)?)\\)?\\s*$", RegexOption.IGNORE_CASE)

    /** "A Parade of Horribles, Book 8" → ("A Parade of Horribles", "8"). */
    fun splitSeriesIndex(title: String): Pair<String, String?> {
        val m = bookN.find(title.trim()) ?: return title.trim() to null
        val clean = m.groupValues[1].trim().trimEnd(',', ':', '-', '–').trim()
        return (clean.ifEmpty { title.trim() }) to m.groupValues[2]
    }

    /** "A Parade of Horribles - Dungeon Crawler Carl" with series "Dungeon Crawler Carl" → "A Parade of Horribles". */
    fun stripSeriesSuffix(title: String, series: String?): String {
        if (series.isNullOrBlank()) return title
        for (sep in listOf(" - ", " – ", ": ", " — ", ", ")) {
            val suffix = sep + series
            if (title.length > suffix.length + 1 && title.endsWith(suffix, ignoreCase = true)) {
                return title.dropLast(suffix.length).trim()
            }
        }
        return title
    }

    fun formatDuration(ms: Long): String {
        val totalMin = ms / 60_000
        val h = totalMin / 60
        val m = totalMin % 60
        return when {
            ms <= 0 -> "—"
            h > 0 -> "${h}h ${m}m"
            else -> "${m}m"
        }
    }

    fun formatClock(ms: Long): String {
        val s = (ms.coerceAtLeast(0)) / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60)
        else "%d:%02d".format(s / 60, s % 60)
    }
}
