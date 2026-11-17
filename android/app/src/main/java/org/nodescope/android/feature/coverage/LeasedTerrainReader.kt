package org.nodescope.android.feature.coverage

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest

enum class TerrainReadState { INVALID_DESCRIPTOR, CORRUPT, IO, CANCELLED, CLOSED, INVALID_RANGE }
class TerrainReadFailure(val state: TerrainReadState) : Exception(state.name)

/**
 * One worker owns this reader and its transferred repository pin. Files stay immutable while pinned.
 *
 * After the size and digest checks the file is memory-mapped read-only, as on iOS, so heights are
 * read from memory without a file call or an allocation each. Java cannot unmap a buffer; [close]
 * drops it and the system reclaims the mapping later. The cache replaces files by rename, never by
 * truncating in place, so a mapping outliving its lease still sees the bytes it was checked against.
 * If mapping fails, reads go through four 16 KiB blocks per reader (iOS's fallback size).
 */
class LeasedTerrainReader private constructor(
    private var file: RandomAccessFile?, val side: Int, private var release: (() -> Unit)?, map: Boolean
) : AutoCloseable {
    private var mapped: ByteBuffer? = if (map) runCatching {
        file?.channel?.map(FileChannel.MapMode.READ_ONLY, 0, side.toLong() * side * 2)
    }.getOrNull() else null
    private val blocks = arrayOfNulls<ByteArray>(BLOCKS)
    private val blockStarts = LongArray(BLOCKS) { -1 }
    private var nextBlock = 0
    var mappedReadCalls = 0L; private set
    var blockHits = 0L; private set
    var blockMisses = 0L; private set
    var fileReadBytes = 0L; private set
    val isMapped: Boolean get() = mapped != null
    val mappedBytes: Long get() = mapped?.capacity()?.toLong() ?: 0

    companion object {
        const val MAXIMUM_READ_SAMPLES = 32768
        private const val BLOCKS = 4
        private const val BLOCK_BYTES = 16 * 1024
        /** Off for comparison runs and parity tests only. */
        @Volatile var mappingEnabled = true

        // Ownership transfers even when validation/open fails; callers must not release twice.
        fun open(path: File, side: Int, decodedSHA256: String,
                 cancelled: () -> Boolean = { false }, release: () -> Unit): LeasedTerrainReader {
            var file: RandomAccessFile? = null
            var transferred = false
            try {
                if (side !in 2..3601 || 3600 % (side - 1) != 0 ||
                    !Regex("[a-f0-9]{64}").matches(decodedSHA256)) {
                    throw TerrainReadFailure(TerrainReadState.INVALID_DESCRIPTOR)
                }
                if (cancelled()) throw TerrainReadFailure(TerrainReadState.CANCELLED)
                val handle = RandomAccessFile(path, "r")
                file = handle
                val size = side.toLong() * side * 2
                if (handle.length() != size) throw TerrainReadFailure(TerrainReadState.CORRUPT)
                val hash = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(65536)
                var remaining = size
                while (remaining > 0) {
                    if (cancelled()) throw TerrainReadFailure(TerrainReadState.CANCELLED)
                    val count = handle.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (count <= 0) throw TerrainReadFailure(TerrainReadState.CORRUPT)
                    hash.update(buffer, 0, count)
                    remaining -= count
                }
                val digest = hash.digest().joinToString("") { "%02x".format(java.util.Locale.ROOT, it.toInt() and 255) }
                if (digest != decodedSHA256 || handle.length() != size) throw TerrainReadFailure(TerrainReadState.CORRUPT)
                if (cancelled()) throw TerrainReadFailure(TerrainReadState.CANCELLED)
                val result = LeasedTerrainReader(handle, side, release, mappingEnabled)
                transferred = true
                return result
            } catch (error: IOException) {
                throw TerrainReadFailure(TerrainReadState.IO)
            } finally {
                if (!transferred) {
                    try { file?.close() } finally { release() }
                }
            }
        }
    }

    /**
     * One height by sample index, north-first signed big-endian HGT; -32768 means no data and callers
     * must propagate it. The mosaic checks cancellation and ranges around this hot call.
     */
    fun value(index: Int): Short {
        if (index < 0 || index >= side * side) throw TerrainReadFailure(TerrainReadState.INVALID_RANGE)
        mapped?.let { buffer -> mappedReadCalls++; return buffer.getShort(index * 2) }
        val handle = file ?: throw TerrainReadFailure(TerrainReadState.CLOSED)
        val offset = index.toLong() * 2
        val block = blockStarts.indices.firstOrNull { blockStarts[it] >= 0 && offset >= blockStarts[it] && offset + 2 <= blockStarts[it] + (blocks[it]?.size ?: 0) }
        val bytes: ByteArray
        val start: Long
        if (block != null) { blockHits++; bytes = blocks[block]!!; start = blockStarts[block] } else {
            blockMisses++
            start = offset - offset % BLOCK_BYTES
            val length = minOf(BLOCK_BYTES.toLong(), side.toLong() * side * 2 - start).toInt()
            bytes = blocks[nextBlock]?.takeIf { it.size == length } ?: ByteArray(length)
            try {
                handle.seek(start)
                var done = 0
                while (done < length) {
                    val received = handle.read(bytes, done, length - done)
                    if (received <= 0) throw TerrainReadFailure(TerrainReadState.CORRUPT)
                    done += received
                }
            } catch (error: IOException) { throw TerrainReadFailure(TerrainReadState.IO) }
            fileReadBytes += length
            blocks[nextBlock] = bytes
            blockStarts[nextBlock] = start
            nextBlock = (nextBlock + 1) % BLOCKS
        }
        val at = (offset - start).toInt()
        return ((bytes[at].toInt() shl 8) or (bytes[at + 1].toInt() and 255)).toShort()
    }

    // North-first signed HGT; callers must propagate -32768 as unknown terrain.
    fun read(start: Int, count: Int, cancelled: () -> Boolean = { false }): ShortArray {
        if (file == null) throw TerrainReadFailure(TerrainReadState.CLOSED)
        if (start < 0 || count !in 1..MAXIMUM_READ_SAMPLES || start > side * side - count) {
            throw TerrainReadFailure(TerrainReadState.INVALID_RANGE)
        }
        if (cancelled()) throw TerrainReadFailure(TerrainReadState.CANCELLED)
        val samples = ShortArray(count) { value(start + it) }
        if (cancelled()) throw TerrainReadFailure(TerrainReadState.CANCELLED)
        return samples
    }

    override fun close() {
        val handle = file ?: return
        file = null
        mapped = null
        blocks.fill(null)
        blockStarts.fill(-1)
        val callback = release
        release = null
        try { handle.close() } finally { callback?.invoke() }
    }
}
