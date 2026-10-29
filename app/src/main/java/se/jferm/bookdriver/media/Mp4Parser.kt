package se.jferm.bookdriver.media

/** What we need from an .m4b/.m4a/.mp4 for the library: no audio is read. */
class Mp4Info(
    val durationMs: Long,
    val title: String?,
    val album: String?,
    val artist: String?,
    val albumArtist: String?,
    val grouping: String?,
    /** (start ms, title), sorted. Empty when the file has no chapter marks. */
    val chapters: List<Pair<Long, String>>,
    val cover: ByteArray?,
)

/**
 * Reads the moov box of an MP4 over Range requests, touching only what it needs:
 * mvhd (duration), udta/chpl (Nero chapters), udta/meta/ilst (tags + cover art),
 * and, when there's no chpl, a QuickTime chapter text track (tref/chap).
 * The audio track's sample tables (often 10+ MB in a long book) are never fetched.
 */
class Mp4Parser(private val src: RangeSource) {

    private class Box(val type: String, val start: Long, val headerLen: Int, val size: Long) {
        val payload get() = start + headerLen
        val end get() = start + size
        val payloadSize get() = size - headerLen
    }

    private class Trak(
        val id: Long,
        val handler: String?,
        val timescale: Long,
        val chapRefs: List<Long>,
        val stbl: Box?,
    )

    suspend fun parse(): Mp4Info {
        val moov = children(0, src.size).firstOrNull { it.type == "moov" }
            ?: throw IllegalStateException("Not an MP4 file (no moov box)")
        val kids = children(moov.payload, moov.end)

        var durationMs = 0L
        kids.firstOrNull { it.type == "mvhd" }?.let { b ->
            val p = read(b, 64)
            val v1 = p.u8(0) == 1
            val timescale = if (v1) p.u32(20) else p.u32(12)
            val duration = if (v1) p.u64(24) else p.u32(16)
            if (timescale > 0) durationMs = duration * 1000 / timescale
        }

        var chapters: List<Pair<Long, String>> = emptyList()
        val tags = mutableMapOf<String, String>()
        var cover: ByteArray? = null

        val udta = kids.firstOrNull { it.type == "udta" }
        if (udta != null && udta.payloadSize < MAX_UDTA) {
            val bytes = src.read(udta.payload, udta.payloadSize.toInt())
            forEachBox(bytes, 0, bytes.size) { type, start, len ->
                when (type) {
                    "chpl" -> chapters = parseChpl(bytes, start, len)
                    "meta" -> parseMeta(bytes, start, len, tags) { cover = it }
                }
            }
        }

        if (chapters.isEmpty()) {
            chapters = runCatching { quickTimeChapters(kids) }.getOrDefault(emptyList())
        }

        return Mp4Info(
            durationMs = durationMs,
            title = tags["©nam"],
            album = tags["©alb"],
            artist = tags["©ART"],
            albumArtist = tags["aART"],
            grouping = tags["©grp"],
            chapters = chapters.sortedBy { it.first },
            cover = cover,
        )
    }

    // ---- Nero chapters (udta/chpl) ---------------------------------------------------

    private fun parseChpl(b: ByteArray, start: Int, len: Int): List<Pair<Long, String>> {
        val end = start + len
        var i = start
        val version = b.u8(i); i += 4
        if (version != 0) i += 4
        val count = b.u8(i); i += 1
        val out = mutableListOf<Pair<Long, String>>()
        repeat(count) {
            if (i + 9 > end) return out
            val start100ns = b.u64(i); i += 8
            val n = b.u8(i); i += 1
            if (i + n > end) return out
            out += (start100ns / 10_000) to String(b, i, n, Charsets.UTF_8).trim()
            i += n
        }
        return out
    }

    // ---- iTunes-style tags (udta/meta/ilst) ------------------------------------------

    private fun parseMeta(b: ByteArray, start: Int, len: Int, tags: MutableMap<String, String>, onCover: (ByteArray) -> Unit) {
        // meta is a full box: skip version/flags. Some writers omit them; detect by peeking for "hdlr".
        val off = if (start + 8 <= b.size && b.fourcc(start + 4) == "hdlr") 0 else 4
        forEachBox(b, start + off, start + len) { type, s, l ->
            if (type != "ilst") return@forEachBox
            forEachBox(b, s, s + l) { key, itemStart, itemLen ->
                forEachBox(b, itemStart, itemStart + itemLen) { dataType, ds, dl ->
                    if (dataType != "data" || dl < 8) return@forEachBox
                    val kind = (b.u32(ds) and 0xFFFFFF).toInt()
                    val valueStart = ds + 8
                    val valueLen = dl - 8
                    if (key == "covr") {
                        if (valueLen > 0) onCover(b.copyOfRange(valueStart, valueStart + valueLen))
                    } else if (kind == 1 && valueLen > 0) {
                        tags[key] = String(b, valueStart, valueLen, Charsets.UTF_8).trim()
                    }
                }
            }
        }
    }

    // ---- QuickTime chapter track (trak with tref/chap → text track) -------------------

    private suspend fun quickTimeChapters(moovKids: List<Box>): List<Pair<Long, String>> {
        val traks = moovKids.filter { it.type == "trak" }.mapNotNull { shallowTrak(it) }
        val chapIds = traks.flatMap { it.chapRefs }.toSet()
        val text = traks.firstOrNull { it.id in chapIds } ?: return emptyList()
        val stbl = text.stbl ?: return emptyList()
        if (text.timescale <= 0) return emptyList()

        val tables = children(stbl.payload, stbl.end).associateBy { it.type }
        val stts = tables["stts"]?.let { read(it, MAX_TABLE) } ?: return emptyList()
        val stsz = tables["stsz"]?.let { read(it, MAX_TABLE) } ?: return emptyList()
        val stsc = tables["stsc"]?.let { read(it, MAX_TABLE) } ?: return emptyList()
        val co64 = tables["co64"] != null
        val stco = (tables["stco"] ?: tables["co64"])?.let { read(it, MAX_TABLE) } ?: return emptyList()

        // Start times.
        val starts = mutableListOf<Long>()
        var t = 0L
        val sttsCount = stts.u32(4).toInt()
        for (e in 0 until sttsCount) {
            val n = stts.u32(8 + e * 8).toInt()
            val delta = stts.u32(12 + e * 8)
            repeat(n) { starts += t; t += delta }
        }
        val sampleCount = starts.size
        if (sampleCount == 0 || sampleCount > 2000) return emptyList()

        // Sizes.
        val fixed = stsz.u32(4)
        val sizes = LongArray(sampleCount) { i -> if (fixed != 0L) fixed else stsz.u32(12 + i * 4) }

        // Offsets: walk chunks with the samples-per-chunk runs from stsc.
        val chunkCount = stco.u32(4).toInt()
        val chunkOffsets = LongArray(chunkCount) { i -> if (co64) stco.u64(8 + i * 8) else stco.u32(8 + i * 4) }
        val runs = (0 until stsc.u32(4).toInt()).map { e -> stsc.u32(8 + e * 12).toInt() to stsc.u32(12 + e * 12).toInt() }
        val offsets = LongArray(sampleCount)
        var sample = 0
        for (c in 0 until chunkCount) {
            val perChunk = runs.lastOrNull { it.first <= c + 1 }?.second ?: 1
            var off = chunkOffsets[c]
            repeat(perChunk) {
                if (sample < sampleCount) {
                    offsets[sample] = off
                    off += sizes[sample]
                    sample++
                }
            }
        }

        // Read the titles: one request when they sit together (the usual case).
        val lo = offsets.min()
        val hi = (0 until sampleCount).maxOf { offsets[it] + sizes[it] }
        val span: ByteArray? = if (hi - lo <= MAX_SPAN) src.read(lo, (hi - lo).toInt()) else null
        return (0 until sampleCount).map { i ->
            val bytes = span?.let { s ->
                val rel = (offsets[i] - lo).toInt()
                s.copyOfRange(rel, minOf(s.size, rel + sizes[i].toInt()))
            } ?: src.read(offsets[i], sizes[i].toInt().coerceAtMost(4096))
            (starts[i] * 1000 / text.timescale) to decodeTextSample(bytes, i)
        }
    }

    private fun decodeTextSample(b: ByteArray, index: Int): String {
        if (b.size < 2) return "Chapter ${index + 1}"
        val n = minOf(b.u16(0), b.size - 2)
        if (n <= 0) return "Chapter ${index + 1}"
        val utf16 = n >= 2 && b.u8(2) == 0xFE && b.u8(3) == 0xFF
        val s = if (utf16) String(b, 4, n - 2, Charsets.UTF_16BE) else String(b, 2, n, Charsets.UTF_8)
        return s.trim().ifEmpty { "Chapter ${index + 1}" }
    }

    private suspend fun shallowTrak(trak: Box): Trak? {
        val kids = children(trak.payload, trak.end)
        val tkhd = kids.firstOrNull { it.type == "tkhd" }?.let { read(it, 128) } ?: return null
        val id = if (tkhd.u8(0) == 1) tkhd.u32(20) else tkhd.u32(12)

        val chapRefs = mutableListOf<Long>()
        kids.firstOrNull { it.type == "tref" }?.let { tref ->
            for (ref in children(tref.payload, tref.end)) {
                if (ref.type != "chap") continue
                val p = read(ref, 1024)
                for (i in 0 until p.size / 4) chapRefs += p.u32(i * 4)
            }
        }

        var handler: String? = null
        var timescale = 0L
        var stbl: Box? = null
        kids.firstOrNull { it.type == "mdia" }?.let { mdia ->
            for (b in children(mdia.payload, mdia.end)) {
                when (b.type) {
                    "mdhd" -> { val p = read(b, 64); timescale = if (p.u8(0) == 1) p.u32(20) else p.u32(12) }
                    "hdlr" -> { val p = read(b, 64); if (p.size >= 12) handler = p.fourcc(8) }
                    "minf" -> stbl = children(b.payload, b.end).firstOrNull { it.type == "stbl" }
                }
            }
        }
        return Trak(id, handler, timescale, chapRefs, stbl)
    }

    // ---- box plumbing ----------------------------------------------------------------

    private suspend fun header(off: Long, limit: Long): Box? {
        if (off + 8 > limit) return null
        val h = src.read(off, 16)
        if (h.size < 8) return null
        var size = h.u32(0)
        val type = h.fourcc(4)
        var headerLen = 8
        when (size) {
            1L -> { if (h.size < 16) return null; size = h.u64(8); headerLen = 16 }
            0L -> size = limit - off
        }
        if (size < headerLen || off + size > limit + 8) return null
        return Box(type, off, headerLen, size)
    }

    private suspend fun children(start: Long, end: Long): List<Box> {
        val out = mutableListOf<Box>()
        var off = start
        while (off < end) {
            val b = header(off, end) ?: break
            out += b
            off = b.end
            if (out.size > 10_000) break
        }
        return out
    }

    private suspend fun read(b: Box, max: Int): ByteArray =
        src.read(b.payload, minOf(b.payloadSize, max.toLong()).toInt())

    private inline fun forEachBox(b: ByteArray, start: Int, end: Int, block: (type: String, payloadStart: Int, payloadLen: Int) -> Unit) {
        var i = start
        while (i + 8 <= end) {
            var size = b.u32(i)
            var hl = 8
            if (size == 1L && i + 16 <= end) { size = b.u64(i + 8); hl = 16 }
            if (size == 0L) size = (end - i).toLong()
            if (size < hl || i + size > end) break
            block(b.fourcc(i + 4), i + hl, (size - hl).toInt())
            i += size.toInt()
        }
    }

    companion object {
        private const val MAX_UDTA = 16L * 1024 * 1024
        private const val MAX_TABLE = 512 * 1024
        private const val MAX_SPAN = 2L * 1024 * 1024
    }
}
