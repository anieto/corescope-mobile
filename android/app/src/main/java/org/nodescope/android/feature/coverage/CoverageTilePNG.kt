package org.nodescope.android.feature.coverage

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

enum class CoverageTilePNGState { invalidPixels, encoding, cancelled }
class CoverageTilePNGFailure(val state: CoverageTilePNGState) : Exception(state.name)

/** Fixed 256×256, 8-bit straight-alpha RGBA PNG. No Bitmap premultiplication or resampling. */
object CoverageTilePNG {
    fun encode(rgba: ByteArray, cancelled: () -> Boolean = { false }): ByteArray {
        if (rgba.size != 256 * 256 * 4) throw CoverageTilePNGFailure(CoverageTilePNGState.invalidPixels)
        fun check() { if (cancelled()) throw CoverageTilePNGFailure(CoverageTilePNGState.cancelled) }
        check()
        // PNG filter 0 preserves bytes and row orientation; zlib handles compression and its checksum.
        val scanlines = ByteArray(256 * (256 * 4 + 1))
        for (row in 0 until 256) {
            check()
            rgba.copyInto(scanlines, row * 1025 + 1, row * 1024, (row + 1) * 1024)
        }
        val compressed = ByteArrayOutputStream()
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION)
        try {
            deflater.setInput(scanlines)
            deflater.finish()
            val buffer = ByteArray(8192)
            while (!deflater.finished()) {
                check()
                val count = deflater.deflate(buffer)
                if (count <= 0) throw CoverageTilePNGFailure(CoverageTilePNGState.encoding)
                compressed.write(buffer, 0, count)
            }
        } finally { deflater.end() }
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { stream ->
            stream.write(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
            fun chunk(type: String, data: ByteArray) {
                val name = type.toByteArray(Charsets.US_ASCII)
                val crc = CRC32().apply { update(name); update(data) }
                stream.writeInt(data.size)
                stream.write(name)
                stream.write(data)
                stream.writeInt(crc.value.toInt())
            }
            val header = ByteArrayOutputStream()
            DataOutputStream(header).use { h ->
                h.writeInt(256); h.writeInt(256)
                h.write(byteArrayOf(8, 6, 0, 0, 0))
            }
            chunk("IHDR", header.toByteArray())
            chunk("sRGB", byteArrayOf(0))
            chunk("IDAT", compressed.toByteArray())
            chunk("IEND", byteArrayOf())
        }
        check()
        return output.toByteArray()
    }
}

fun CoverageRaster.pngTile(zoom: Int, x: Int, y: Int, cancelled: () -> Boolean = { false }): ByteArray =
    CoverageTilePNG.encode(tile(zoom, x, y, cancelled), cancelled)
