package org.nodescope.android.feature.coverage

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest

enum class TerrainReadState { INVALID_DESCRIPTOR, CORRUPT, IO, CANCELLED, CLOSED, INVALID_RANGE }
class TerrainReadFailure(val state: TerrainReadState) : Exception(state.name)

/** One worker owns this reader and its transferred repository pin. Files stay immutable while pinned. */
class LeasedTerrainReader private constructor(
    private var file: RandomAccessFile?, val side: Int, private var release: (() -> Unit)?
) : AutoCloseable {
    companion object {
        const val MAXIMUM_READ_SAMPLES = 32768

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
                val digest = hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                if (digest != decodedSHA256 || handle.length() != size) throw TerrainReadFailure(TerrainReadState.CORRUPT)
                if (cancelled()) throw TerrainReadFailure(TerrainReadState.CANCELLED)
                val result = LeasedTerrainReader(handle, side, release)
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

    // North-first signed HGT; callers must propagate -32768 as unknown terrain.
    fun read(start: Int, count: Int, cancelled: () -> Boolean = { false }): ShortArray {
        val handle = file ?: throw TerrainReadFailure(TerrainReadState.CLOSED)
        if (start < 0 || count !in 1..MAXIMUM_READ_SAMPLES || start > side * side - count) {
            throw TerrainReadFailure(TerrainReadState.INVALID_RANGE)
        }
        if (cancelled()) throw TerrainReadFailure(TerrainReadState.CANCELLED)
        try {
            handle.seek(start.toLong() * 2)
            val bytes = ByteArray(count * 2)
            var done = 0
            while (done < bytes.size) {
                if (cancelled()) throw TerrainReadFailure(TerrainReadState.CANCELLED)
                val received = handle.read(bytes, done, bytes.size - done)
                if (received <= 0) throw TerrainReadFailure(TerrainReadState.CORRUPT)
                done += received
            }
            if (cancelled()) throw TerrainReadFailure(TerrainReadState.CANCELLED)
            return ShortArray(count) {
                ((bytes[it * 2].toInt() shl 8) or (bytes[it * 2 + 1].toInt() and 255)).toShort()
            }
        } catch (error: IOException) {
            throw TerrainReadFailure(TerrainReadState.IO)
        }
    }

    override fun close() {
        val handle = file ?: return
        file = null
        val callback = release
        release = null
        try { handle.close() } finally { callback?.invoke() }
    }
}
