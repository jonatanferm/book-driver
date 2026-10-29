package se.jferm.bookdriver.media

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.jferm.bookdriver.data.Library

/**
 * Fixtures were made with ffmpeg (see app/src/test/resources): 120 s of AAC with three
 * chapters, title/album/artist tags and cover art; an MP3 with ID3v2.3 tags + cover;
 * a CBR MP3 with no Xing header. Reads go through RangeSource exactly like Drive.
 */
class ParsersTest {

    private fun source(name: String): Pair<RangeSource, IntArray> {
        val bytes = javaClass.classLoader!!.getResourceAsStream(name)!!.readBytes()
        val calls = IntArray(1)
        val src = RangeSource({ off, len ->
            calls[0]++
            val end = minOf(bytes.size.toLong(), off + len).toInt()
            bytes.copyOfRange(off.toInt(), end)
        }, bytes.size.toLong())
        return src to calls
    }

    private val expected = listOf(0L to "Opening Credits", 20_000L to "Chapter One: The Road", 65_000L to "Chapter Two")

    @Test fun neroChaptersTagsAndCover() = runBlocking {
        val (src, calls) = source("test.m4b")
        val info = Mp4Parser(src).parse()
        assertEquals(120_000.0, info.durationMs.toDouble(), 100.0)
        assertEquals(expected, info.chapters)
        assertEquals("A Test Book, Book 3", info.title)
        assertEquals("A Test Book", info.album)
        assertEquals("Jane Narrator", info.artist)
        assertNotNull(info.cover)
        assertTrue("cover should be a JPEG", info.cover!![0] == 0xFF.toByte() && info.cover!![1] == 0xD8.toByte())
        println("test.m4b parsed with ${calls[0]} range request(s)")
    }

    @Test fun moovAtEndOfFile() = runBlocking {
        val (src, calls) = source("test_moov_end.m4b")
        val info = Mp4Parser(src).parse()
        assertEquals(expected, info.chapters)
        assertTrue("should not read the whole file (${calls[0]} reads)", calls[0] <= 6)
    }

    @Test fun quickTimeChapterTrackWhenNoChpl() = runBlocking {
        val (src, _) = source("test_qt_only.m4b")
        val info = Mp4Parser(src).parse()
        assertEquals(expected.map { it.second }, info.chapters.map { it.second })
        info.chapters.zip(expected).forEach { (got, want) -> assertEquals(want.first.toDouble(), got.first.toDouble(), 50.0) }
    }

    @Test fun mp3TagsCoverAndDuration() = runBlocking {
        val (src, _) = source("test.mp3")
        val info = Mp3Probe(src).probe(wantCover = true)
        assertEquals("Part One", info.title)
        assertEquals("Folder Book", info.album)
        assertEquals("Some Author", info.artist)
        assertNotNull(info.cover)
        assertEquals(95_000.0, info.durationMs.toDouble(), 600.0)
    }

    @Test fun cbrMp3WithoutXing() = runBlocking {
        val (src, _) = source("cbr.mp3")
        val info = Mp3Probe(src).probe(wantCover = false)
        assertEquals(61_000.0, info.durationMs.toDouble(), 1500.0)
    }

    @Test fun seriesIndexFromTitles() {
        assertEquals("A Parade of Horribles" to "8", Library.splitSeriesIndex("A Parade of Horribles, Book 8"))
        assertEquals("The Way of Kings" to "1", Library.splitSeriesIndex("The Way of Kings (Book 1)"))
        assertEquals("Carl's Doomsday Scenario" to null, Library.splitSeriesIndex("Carl's Doomsday Scenario"))
        assertEquals("The Hunt", Library.prettyTrackName("03 - The Hunt.mp3", 2))
    }
}

class TitleTest {
    @Test fun stripsSeriesSuffix() {
        assertEquals("A Parade of Horribles", Library.stripSeriesSuffix("A Parade of Horribles - Dungeon Crawler Carl", "Dungeon Crawler Carl"))
        assertEquals("Carl's Doomsday Scenario", Library.stripSeriesSuffix("Carl's Doomsday Scenario: Dungeon Crawler Carl", "Dungeon Crawler Carl"))
        assertEquals("Dungeon Crawler Carl", Library.stripSeriesSuffix("Dungeon Crawler Carl", "Dungeon Crawler Carl"))
        assertEquals("Mistborn", Library.stripSeriesSuffix("Mistborn", null))
    }
}
