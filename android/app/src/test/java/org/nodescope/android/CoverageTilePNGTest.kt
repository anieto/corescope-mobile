package org.nodescope.android

import java.io.ByteArrayInputStream
import org.junit.Assert.*
import org.junit.Test
import org.nodescope.android.feature.coverage.*

class CoverageTilePNGTest {
    @Test fun standardDecoderPreservesEveryPixelAndAlpha() {
        val rgba = ByteArray(256 * 256 * 4)
        for (y in 0 until 256) for (x in 0 until 256) {
            val offset = (y * 256 + x) * 4
            rgba[offset] = x.toByte()
            rgba[offset + 1] = y.toByte()
            rgba[offset + 2] = 90
            rgba[offset + 3] = ((x + y) % 256).toByte()
        }
        // Android's compile classpath omits java.desktop; the host JVM supplies this independent decoder.
        val image = Class.forName("javax.imageio.ImageIO").getMethod("read", java.io.InputStream::class.java)
            .invoke(null, ByteArrayInputStream(CoverageTilePNG.encode(rgba)))
        assertNotNull(image)
        assertEquals(256, image.javaClass.getMethod("getWidth").invoke(image))
        assertEquals(256, image.javaClass.getMethod("getHeight").invoke(image))
        val getRGB = image.javaClass.getMethod("getRGB", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        for (y in 0 until 256) for (x in 0 until 256) {
            val expected = (((x + y) % 256) shl 24) or (x shl 16) or (y shl 8) or 90
            assertEquals(expected, getRGB.invoke(image, x, y))
        }
        assertEquals(CoverageTilePNGState.invalidPixels,
            assertThrows(CoverageTilePNGFailure::class.java) { CoverageTilePNG.encode(byteArrayOf()) }.state)
        assertEquals(CoverageTilePNGState.cancelled,
            assertThrows(CoverageTilePNGFailure::class.java) { CoverageTilePNG.encode(rgba) { true } }.state)
    }
}
