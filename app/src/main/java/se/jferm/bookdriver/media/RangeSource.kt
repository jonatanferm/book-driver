package se.jferm.bookdriver.media

import se.jferm.bookdriver.drive.DriveApi

/**
 * Random access over a Drive file using Range requests, with a small block cache so
 * walking box headers doesn't cost one request per 8 bytes.
 */
class RangeSource(
    private val fetch: suspend (offset: Long, length: Int) -> ByteArray,
    val size: Long,
) {
    constructor(drive: DriveApi, fileId: String, size: Long) :
        this({ offset, length -> drive.readRange(fileId, offset, length) }, size)

    private val blocks = LinkedHashMap<Long, ByteArray>()
    var requests = 0
        private set

    suspend fun read(offset: Long, length: Int): ByteArray {
        if (offset >= size || length <= 0) return ByteArray(0)
        val len = minOf(length.toLong(), size - offset).toInt()
        if (len > BIG_READ) {
            requests++
            return fetch(offset, len)
        }
        val out = ByteArray(len)
        var pos = 0
        while (pos < len) {
            val abs = offset + pos
            val blockStart = abs - (abs % BLOCK)
            val block = blocks[blockStart] ?: fetchBlock(blockStart)
            val inBlock = (abs - blockStart).toInt()
            val n = minOf(len - pos, block.size - inBlock)
            if (n <= 0) break
            System.arraycopy(block, inBlock, out, pos, n)
            pos += n
        }
        return if (pos == len) out else out.copyOf(pos)
    }

    private suspend fun fetchBlock(start: Long): ByteArray {
        requests++
        val bytes = fetch(start, minOf(BLOCK.toLong(), size - start).toInt())
        blocks[start] = bytes
        if (blocks.size > MAX_BLOCKS) blocks.remove(blocks.keys.first())
        return bytes
    }

    companion object {
        private const val BLOCK = 64 * 1024
        private const val BIG_READ = 256 * 1024
        private const val MAX_BLOCKS = 32
    }
}

internal fun ByteArray.u8(i: Int) = this[i].toInt() and 0xFF
internal fun ByteArray.u16(i: Int) = (u8(i) shl 8) or u8(i + 1)
internal fun ByteArray.u24(i: Int) = (u8(i) shl 16) or (u8(i + 1) shl 8) or u8(i + 2)
internal fun ByteArray.u32(i: Int): Long =
    ((u8(i).toLong() shl 24) or (u8(i + 1).toLong() shl 16) or (u8(i + 2).toLong() shl 8) or u8(i + 3).toLong())
internal fun ByteArray.u64(i: Int): Long = (u32(i) shl 32) or u32(i + 4)
internal fun ByteArray.fourcc(i: Int) = String(this, i, 4, Charsets.ISO_8859_1)
