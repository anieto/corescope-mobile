package org.nodescope.android

import java.io.File
import java.nio.file.Files
import java.util.Base64
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.nodescope.android.feature.coverage.*

private class DecodeFiles : AutoCloseable {
    val root = Files.createTempDirectory("coverage-decode").toFile()
    val packed = File(root, "input.gz")
    fun write(fixture: JsonObject) { packed.writeBytes(Base64.getDecoder().decode(fixture.getValue("packed_base64").jsonPrimitive.content)) }
    val decodedDirectories get() = requireNotNull(root.list()).filter { it.startsWith(".decode-") }
    override fun close() { root.deleteRecursively() }
}

class CoverageTerrainDecoderTest {
    private fun fixtures() = Json.parseToJsonElement(File("../../shared/fixtures/coverage/terrain-gzip.json").readText()).jsonArray.map { it.jsonObject }
    private fun descriptor(item: JsonObject) = CoverageTerrainDescriptor("gzip-test", "r1", "N00E000",
        item.getValue("source_sha256").jsonPrimitive.content, item.getValue("packed_bytes").jsonPrimitive.long,
        item.getValue("side").jsonPrimitive.int, "https://example.com/tile.gz", "EGM96", "Synthetic")
    private fun failure(state: CoverageTerrainDecodeState, block: () -> Unit) {
        assertEquals(state, assertThrows(CoverageTerrainDecodeFailure::class.java, block).state)
    }

    @Test fun sharedValidAndCorruptGzipFixtures() = DecodeFiles().use { files ->
        val cases = fixtures()
        assertEquals(22, cases.size)
        for (fixture in cases) {
            files.write(fixture)
            val descriptor = descriptor(fixture)
            if (fixture.getValue("valid").jsonPrimitive.boolean) {
                CoverageTerrainDecoder.decode(descriptor, files.packed, files.root).use { decoded ->
                    assertEquals(fixture.getValue("decoded_sha256").jsonPrimitive.content, decoded.sha256)
                    LeasedTerrainReader.open(decoded.file, descriptor.side, decoded.sha256) {}.use { }
                    decoded.close()
                }
            } else {
                failure(CoverageTerrainDecodeState.CORRUPT) { CoverageTerrainDecoder.decode(descriptor, files.packed, files.root) }
            }
            assertTrue(fixture.getValue("name").jsonPrimitive.content, files.decodedDirectories.isEmpty())
        }
    }

    @Test fun cancellationAtEveryCheckpointRemovesDecodedOutput() = DecodeFiles().use { files ->
        val fixture = fixtures().first { it.getValue("name").jsonPrimitive.content == "multiple_input_output_chunks" }
        files.write(fixture)
        val descriptor = descriptor(fixture)
        var total = 0
        CoverageTerrainDecoder.decode(descriptor, files.packed, files.root) { total++; false }.use { }
        assertTrue(total > 5)
        for (checkpoint in 1..total) {
            var count = 0
            failure(CoverageTerrainDecodeState.CANCELLED) {
                CoverageTerrainDecoder.decode(descriptor, files.packed, files.root) { ++count == checkpoint }
            }
            assertTrue(files.decodedDirectories.isEmpty())
        }
    }

    @Test fun decodePublishReopenAndSignedRead() = DecodeFiles().use { files ->
        val fixture = fixtures().first { it.getValue("name").jsonPrimitive.content == "signed_and_nodata" }
        files.write(fixture)
        val descriptor = descriptor(fixture)
        val cacheDirectory = File(files.root, "cache")
        CoverageTerrainDiskCache(cacheDirectory, 100000).use { cache ->
            CoverageTerrainDecoder.decode(descriptor, files.packed, files.root).use { decoded ->
                cache.publish(descriptor, decoded.file, decoded.sha256, 1)
            }
        }
        CoverageTerrainDiskCache(cacheDirectory, 100000).use { cache ->
            cache.acquire(descriptor).use { assertArrayEquals(shortArrayOf(-32768, -1, 0, 1), it.read(0, 4)) }
        }
    }

    @Test fun filesystemFailureDoesNotDeleteUnownedFiles() = DecodeFiles().use { files ->
        val fixture = fixtures().first()
        files.write(fixture)
        val sentinel = File(files.root, "not-a-directory").also { it.writeBytes(byteArrayOf(7)) }
        assertThrows(Exception::class.java) { CoverageTerrainDecoder.decode(descriptor(fixture), files.packed, sentinel) }
        assertArrayEquals(byteArrayOf(7), sentinel.readBytes())
        assertTrue(files.decodedDirectories.isEmpty())
    }
}
