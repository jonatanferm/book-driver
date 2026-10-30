package se.jferm.bookdriver.drive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import se.jferm.bookdriver.data.Book
import se.jferm.bookdriver.data.Track

class LibraryMergeTest {
    private fun book(id: String, title: String = id) = Book(id = id, title = title, tracks = listOf(Track(id, "$id.m4b", "audio/mp4", 1)))
    private fun has(b: Book, at: Long) = LibraryEntry(b, at)
    private fun gone(at: Long) = LibraryEntry(null, at)

    @Test fun bookFromAnotherDeviceIsAdded() {
        val (merged, wins) = mergeLibrary(emptyMap(), mapOf("a" to has(book("a"), 10)))
        assertEquals(listOf("a"), wins)
        assertEquals("a", merged["a"]?.book?.id)
    }

    @Test fun newerLocalEditIsKept() {
        val (merged, wins) = mergeLibrary(
            mapOf("a" to has(book("a", "New title"), 20)),
            mapOf("a" to has(book("a", "Old title"), 10)),
        )
        assertEquals(emptyList<String>(), wins)
        assertEquals("New title", merged["a"]?.book?.title)
    }

    @Test fun removalOnAnotherDeviceWins() {
        val (merged, wins) = mergeLibrary(mapOf("a" to has(book("a"), 10)), mapOf("a" to gone(20)))
        assertEquals(listOf("a"), wins)
        assertNull(merged["a"]?.book)
    }

    @Test fun localRemovalBeatsOlderCopyOnDrive() {
        val (merged, wins) = mergeLibrary(mapOf("a" to gone(30)), mapOf("a" to has(book("a"), 10)))
        assertEquals(emptyList<String>(), wins)
        assertNull(merged["a"]?.book)
    }

    @Test fun reAddingAfterRemovalWins() {
        val (merged, wins) = mergeLibrary(mapOf("a" to has(book("a"), 40)), mapOf("a" to gone(30)))
        assertEquals(emptyList<String>(), wins)
        assertEquals("a", merged["a"]?.book?.id)
    }

    @Test fun tombstoneForBookNeverSeenHereNeedsNoWork() {
        val (merged, wins) = mergeLibrary(emptyMap(), mapOf("x" to gone(5)))
        assertEquals(emptyList<String>(), wins)
        assertNull(merged["x"]?.book) // still kept, so it keeps spreading
    }

    @Test fun nothingChangedMeansNothingToWrite() {
        val both = mapOf("a" to has(book("a"), 10), "b" to gone(5))
        val (merged, wins) = mergeLibrary(both, both)
        assertEquals(both, merged)
        assertEquals(emptyList<String>(), wins)
    }
}
