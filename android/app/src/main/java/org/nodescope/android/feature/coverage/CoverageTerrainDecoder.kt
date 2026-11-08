package org.nodescope.android.feature.coverage

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import okio.Buffer
import okio.ForwardingSource
import okio.GzipSource
import okio.HashingSource
import okio.source

// Explicit corruption/cancellation states; unrelated file errors remain I/O failures.
enum class CoverageTerrainDecodeState { CORRUPT, CANCELLED }
class CoverageTerrainDecodeFailure(val state: CoverageTerrainDecodeState) : Exception(state.name)

/** Owns private decoded output until close. Cache publication copies the verified file. */
class DecodedCoverageTerrain internal constructor(private val directory: File, val sha256: String) : AutoCloseable {
    val file = File(directory, "terrain.hgt")
    override fun close() {
        if (file.exists()) Files.delete(file.toPath())
        if (directory.exists()) Files.delete(directory.toPath())
    }
}

/** One gzip member, bounded optional header, exact source and output sizes, no trailing bytes. */
object CoverageTerrainDecoder {
    fun decode(descriptor: CoverageTerrainDescriptor, packedFile: File, workspace: File,
               downloadedSHA256: String? = null, cancelled: () -> Boolean = { false }): DecodedCoverageTerrain {
        fun check() { if (cancelled()) throw CoverageTerrainDecodeFailure(CoverageTerrainDecodeState.CANCELLED) }
        check()
        val prefix = packedFile.inputStream().use { input ->
            val bytes = ByteArray(minOf(65536, descriptor.packedBytes + 1).toInt())
            var count = 0
            while (count < bytes.size) {
                check()
                val n = input.read(bytes, count, bytes.size - count)
                if (n < 0) break
                count += n
            }
            bytes.copyOf(count)
        }
        validateHeader(prefix)
        val directory = File(workspace, ".decode-${UUID.randomUUID()}")
        Files.createDirectory(directory.toPath())
        var transferred = false
        val destination = File(directory, "terrain.hgt")
        try {
            var received = 0L
            val bounded = object : ForwardingSource(packedFile.source()) {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    check()
                    val count = super.read(sink, minOf(byteCount, 65536, descriptor.packedBytes - received + 1))
                    if (count > 0) {
                        if (count > descriptor.packedBytes - received) corrupt()
                        received += count
                    }
                    return count
                }
            }
            val hashing = HashingSource.sha256(bounded)
            val decodedHash = MessageDigest.getInstance("SHA-256")
            var written = 0L
            GzipSource(hashing).use { gzip ->
                destination.outputStream().use { output ->
                    val buffer = Buffer()
                    while (true) {
                        check()
                        // GzipSource validates header/trailer CRC and rejects a nonempty remainder.
                        val count = try { gzip.read(buffer, minOf(65536, descriptor.decodedBytes - written + 1)) }
                            catch (error: IOException) { throw CoverageTerrainDecodeFailure(CoverageTerrainDecodeState.CORRUPT).also { it.initCause(error) } }
                        if (count < 0) break
                        if (count > descriptor.decodedBytes - written) corrupt()
                        val bytes = buffer.readByteArray(count)
                        output.write(bytes)
                        decodedHash.update(bytes)
                        written += count
                    }
                    check()
                    if (received != descriptor.packedBytes || written != descriptor.decodedBytes || hashing.hash.hex() != (descriptor.compressedSHA256 ?: downloadedSHA256)) corrupt()
                    output.fd.sync()
                }
            }
            check()
            val result = DecodedCoverageTerrain(directory, decodedHash.digest().joinToString("") { "%02x".format(it.toInt() and 255) })
            transferred = true
            return result
        } finally {
            if (!transferred) {
                if (destination.exists()) Files.delete(destination.toPath())
                Files.delete(directory.toPath())
            }
        }
    }

    private fun validateHeader(bytes: ByteArray) {
        if (bytes.size < 10 || bytes[0] != 0x1f.toByte() || bytes[1] != 0x8b.toByte() || bytes[2] != 8.toByte() ||
            bytes[3].toInt() and 0xe0 != 0) corrupt()
        val flags = bytes[3].toInt()
        var position = 10
        if (flags and 4 != 0) {
            if (position + 2 > bytes.size) corrupt()
            val length = (bytes[position].toInt() and 255) or ((bytes[position + 1].toInt() and 255) shl 8)
            position += 2 + length
            if (position > bytes.size) corrupt()
        }
        for (flag in listOf(8, 16)) if (flags and flag != 0) {
            while (position < bytes.size && bytes[position] != 0.toByte()) position++
            if (position >= bytes.size) corrupt()
            position++
        }
        if (flags and 2 != 0) position += 2
        if (position > bytes.size) corrupt()
    }
    private fun corrupt(): Nothing = throw CoverageTerrainDecodeFailure(CoverageTerrainDecodeState.CORRUPT)
}
