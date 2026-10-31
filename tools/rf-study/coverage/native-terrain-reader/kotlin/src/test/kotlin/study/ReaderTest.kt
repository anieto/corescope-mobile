package study

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.*

class ReaderTest {
    private class Fixture : AutoCloseable {
        val folder = Files.createTempDirectory("terrain-reader").toFile()
        val file = File(folder, "terrain.hgt")
        val values = shortArrayOf(-32768, -32767, -23, -1, 0, 1, 256, 32767, 17)
        var releases = 0
        val digest: String
        init {
            val bytes = values.flatMap { listOf((it.toInt() shr 8).toByte(), it.toByte()) }.toByteArray()
            file.writeBytes(bytes)
            digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        fun open(side: Int = 3, digest: String = this.digest, cancelled: () -> Boolean = { false }) =
            LeasedTerrainReader.open(file, side, digest, cancelled) { releases++ }
        override fun close() { folder.deleteRecursively() }
    }
    private fun failure(state: TerrainReadState, action: () -> Unit) {
        assertEquals(state, assertFailsWith<TerrainReadFailure>(block = action).state)
    }

    @Test fun signedSamplesAndRowCrossing() = Fixture().use { f ->
        f.open().use { reader ->
            assertContentEquals(f.values, reader.read(0, 9))
            assertContentEquals(f.values.sliceArray(2..5), reader.read(2, 4))
            assertContentEquals(shortArrayOf(17), reader.read(8, 1))
            assertEquals(0, f.releases)
        }
        assertEquals(1, f.releases)
    }
    @Test fun closeReleasesExactlyOnceAndPreventsReads() = Fixture().use { f ->
        val reader = f.open(); reader.close(); reader.close()
        assertEquals(1, f.releases)
        failure(TerrainReadState.CLOSED) { reader.read(0, 1) }
    }
    @Test fun invalidDimensionsReleasePin() {
        for (side in listOf(0, 1, 8, 3602, Int.MAX_VALUE)) Fixture().use { f ->
            failure(TerrainReadState.INVALID_DESCRIPTOR) { f.open(side = side) }
            assertEquals(1, f.releases)
        }
    }
    @Test fun malformedDigestReleasePin() {
        for (digest in listOf("", "xyz", "A".repeat(64))) Fixture().use { f ->
            failure(TerrainReadState.INVALID_DESCRIPTOR) { f.open(digest = digest) }
            assertEquals(1, f.releases)
        }
    }
    @Test fun checksumMismatchReleasesPin() = Fixture().use { f ->
        failure(TerrainReadState.CORRUPT) { f.open(digest = "0".repeat(64)) }
        assertEquals(1, f.releases)
    }
    @Test fun wrongFileSizeReleasesPin() {
        for (size in listOf(0, 17, 19)) Fixture().use { f ->
            f.file.writeBytes(ByteArray(size))
            failure(TerrainReadState.CORRUPT) { f.open() }
            assertEquals(1, f.releases)
        }
    }
    @Test fun missingFileReleasesPin() = Fixture().use { f ->
        check(f.file.delete())
        failure(TerrainReadState.IO) { f.open() }
        assertEquals(1, f.releases)
    }
    @Test fun cancellationDuringOpenReleasesPin() {
        for (check in 1..3) Fixture().use { f ->
            var calls = 0
            failure(TerrainReadState.CANCELLED) { f.open(cancelled = { ++calls == check }) }
            assertEquals(1, f.releases)
        }
    }
    @Test fun invalidRangesNeverOverflow() = Fixture().use { f ->
        f.open().use { reader ->
            for ((start, count) in listOf(-1 to 1, 0 to 0, 9 to 1, 8 to 2, 0 to 32769, Int.MAX_VALUE to 1, 0 to Int.MAX_VALUE)) {
                failure(TerrainReadState.INVALID_RANGE) { reader.read(start, count) }
            }
        }
    }
    @Test fun cancelledReadsKeepLeaseUntilClose() {
        for (check in 1..3) Fixture().use { f ->
            f.open().use { reader ->
                var calls = 0
                failure(TerrainReadState.CANCELLED) { reader.read(0, 1) { ++calls == check } }
                assertEquals(0, f.releases)
                assertContentEquals(shortArrayOf(-32768), reader.read(0, 1))
            }
        }
    }
    @Test fun truncationAfterOpenIsUnknownNotZero() = Fixture().use { f ->
        f.open().use { reader ->
            RandomAccessFile(f.file, "rw").use { it.setLength(2) }
            failure(TerrainReadState.CORRUPT) { reader.read(8, 1) }
        }
    }
    @Test fun pinnedRealTileReplay() {
        val root = File(requireNotNull(System.getenv("TERRAIN_READER_FIXTURES")))
        val cases = File(root, "samples.tsv").readLines().map { it.split('\t') }
        var checked = 0
        for (line in File(root, "tiles.tsv").readLines()) {
            val fields = line.split('\t')
            var releases = 0
            LeasedTerrainReader.open(File(root, fields[0]), 3601, fields[1], release = { releases++ }).use { reader ->
                for (sample in cases.filter { it[0] == fields[0] }) {
                    val expected = sample[2].split(',').map { it.toShort() }.toShortArray()
                    assertContentEquals(expected, reader.read(sample[1].toInt(), expected.size))
                    checked++
                }
            }
            assertEquals(1, releases)
        }
        assertEquals(848, checked)
    }
}
