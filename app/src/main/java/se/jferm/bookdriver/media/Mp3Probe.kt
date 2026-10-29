package se.jferm.bookdriver.media

class Mp3Info(
    val durationMs: Long,
    val title: String?,
    val album: String?,
    val artist: String?,
    val albumArtist: String?,
    val cover: ByteArray?,
)

/**
 * Reads an MP3's ID3v2 tag (title/album/artist/cover) and works out its duration from
 * the Xing/Info or VBRI header, falling back to a constant-bitrate estimate.
 * Costs one or two small Range requests per file.
 */
class Mp3Probe(private val src: RangeSource) {

    suspend fun probe(wantCover: Boolean): Mp3Info {
        val head = src.read(0, 10)
        var audioStart = 0L
        val tags = mutableMapOf<String, String>()
        var cover: ByteArray? = null

        if (head.size >= 10 && head.fourcc(0).startsWith("ID3")) {
            val major = head.u8(3)
            val flags = head.u8(5)
            val tagSize = syncsafe(head, 6)
            audioStart = 10L + tagSize + if (flags and 0x10 != 0) 10 else 0
            if (major in 3..4) {
                val readLen = if (wantCover) minOf(tagSize, MAX_TAG) else minOf(tagSize, 64 * 1024)
                val tag = src.read(10, readLen)
                parseFrames(tag, major, wantCover, tags) { cover = it }
            }
        }

        val durationMs = runCatching { duration(audioStart) }.getOrDefault(0L)
        return Mp3Info(
            durationMs = durationMs,
            title = tags["TIT2"],
            album = tags["TALB"],
            artist = tags["TPE1"],
            albumArtist = tags["TPE2"],
            cover = cover,
        )
    }

    private fun parseFrames(b: ByteArray, major: Int, wantCover: Boolean, tags: MutableMap<String, String>, onCover: (ByteArray) -> Unit) {
        var i = 0
        while (i + 10 <= b.size) {
            val id = b.fourcc(i)
            if (id[0] == '\u0000') break
            val size = if (major == 4) syncsafe(b, i + 4) else b.u32(i + 4).toInt()
            val start = i + 10
            if (size <= 0 || start + size > b.size) break
            when {
                id in TEXT_FRAMES -> decodeText(b, start, size)?.let { tags[id] = it }
                id == "APIC" && wantCover -> picture(b, start, size)?.let(onCover)
            }
            i = start + size
        }
    }

    private fun decodeText(b: ByteArray, start: Int, len: Int): String? {
        if (len < 2) return null
        val enc = b.u8(start)
        val raw = b.copyOfRange(start + 1, start + len)
        val s = when (enc) {
            0 -> String(raw, Charsets.ISO_8859_1)
            1 -> String(raw, Charsets.UTF_16)
            2 -> String(raw, Charsets.UTF_16BE)
            else -> String(raw, Charsets.UTF_8)
        }
        return s.trim('\u0000', ' ').substringBefore('\u0000').ifBlank { null }
    }

    private fun picture(b: ByteArray, start: Int, len: Int): ByteArray? {
        val end = start + len
        val enc = b.u8(start)
        var i = start + 1
        while (i < end && b[i] != 0.toByte()) i++ // MIME type
        i += 2 // NUL + picture type
        // Description, NUL-terminated in its encoding (two-byte NUL for UTF-16).
        if (enc == 1 || enc == 2) {
            while (i + 1 < end && !(b[i] == 0.toByte() && b[i + 1] == 0.toByte())) i += 2
            i += 2
        } else {
            while (i < end && b[i] != 0.toByte()) i++
            i += 1
        }
        return if (i < end) b.copyOfRange(i, end) else null
    }

    private suspend fun duration(audioStart: Long): Long {
        val buf = src.read(audioStart, 8192)
        // Find the first frame sync.
        var f = -1
        for (i in 0 until buf.size - 4) {
            if (buf.u8(i) == 0xFF && (buf.u8(i + 1) and 0xE0) == 0xE0 && validHeader(buf, i)) { f = i; break }
        }
        if (f < 0) return 0
        val b1 = buf.u8(f + 1)
        val b2 = buf.u8(f + 2)
        val b3 = buf.u8(f + 3)
        val versionBits = (b1 shr 3) and 0x3 // 3 = MPEG1, 2 = MPEG2, 0 = MPEG2.5
        val mpeg1 = versionBits == 3
        val bitrateKbps = (if (mpeg1) BITRATE_V1 else BITRATE_V2)[(b2 shr 4) and 0xF]
        val sampleRate = when (versionBits) { 3 -> SR_V1; 2 -> SR_V2; else -> SR_V25 }[(b2 shr 2) and 0x3]
        val mono = ((b3 shr 6) and 0x3) == 3
        val samplesPerFrame = if (mpeg1) 1152 else 576
        val sideInfo = if (mpeg1) (if (mono) 17 else 32) else (if (mono) 9 else 17)

        val xing = f + 4 + sideInfo
        if (xing + 12 <= buf.size) {
            val tag = buf.fourcc(xing)
            if (tag == "Xing" || tag == "Info") {
                val flags = buf.u32(xing + 4)
                if (flags and 1L != 0L) {
                    val frames = buf.u32(xing + 8)
                    if (frames > 0) return frames * samplesPerFrame * 1000 / sampleRate
                }
            }
        }
        val vbri = f + 4 + 32
        if (vbri + 18 <= buf.size && buf.fourcc(vbri) == "VBRI") {
            val frames = buf.u32(vbri + 14)
            if (frames > 0) return frames * samplesPerFrame * 1000 / sampleRate
        }
        if (bitrateKbps <= 0) return 0
        val audioBytes = src.size - audioStart - f
        return audioBytes * 8 / bitrateKbps // bits / (kbit/s) = ms
    }

    private fun validHeader(b: ByteArray, i: Int): Boolean {
        val b1 = b.u8(i + 1)
        val b2 = b.u8(i + 2)
        val layer = (b1 shr 1) and 0x3
        val versionBits = (b1 shr 3) and 0x3
        val bitrate = (b2 shr 4) and 0xF
        val sr = (b2 shr 2) and 0x3
        return layer == 1 && versionBits != 1 && bitrate in 1..14 && sr != 3
    }

    private fun syncsafe(b: ByteArray, i: Int) =
        (b.u8(i) shl 21) or (b.u8(i + 1) shl 14) or (b.u8(i + 2) shl 7) or b.u8(i + 3)

    companion object {
        private const val MAX_TAG = 4 * 1024 * 1024
        private val TEXT_FRAMES = setOf("TIT2", "TALB", "TPE1", "TPE2", "TRCK")
        private val BITRATE_V1 = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0)
        private val BITRATE_V2 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0)
        private val SR_V1 = intArrayOf(44100, 48000, 32000, 0)
        private val SR_V2 = intArrayOf(22050, 24000, 16000, 0)
        private val SR_V25 = intArrayOf(11025, 12000, 8000, 0)
    }
}
