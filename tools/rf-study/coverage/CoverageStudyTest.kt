package study
import kotlin.test.*

class CoverageStudyTest {
    @Test fun sampleCountIsStableAtExactSpacingMultiples() {
        assertEquals(61, profileSampleCount(1800.0 - 1e-8))
        assertEquals(61, profileSampleCount(1800.0 + 1e-8))
        assertEquals(62, profileSampleCount(1800.001))
        assertEquals(335, profileSampleCount(10000.0))
    }
    private fun bytes(values: List<Int>): ByteArray {
        val data = ByteArray(32 + values.size * 2)
        for ((index, n) in listOf(0x43535631, 2, 2, 0, 0, 51, -115, 3600).withIndex()) {
            for (j in 0..3) data[index * 4 + j] = (n ushr (24 - 8 * j)).toByte()
        }
        for ((i, n) in values.withIndex()) { data[32 + 2 * i] = (n shr 8).toByte(); data[33 + 2 * i] = n.toByte() }
        return data
    }
    @Test fun signedElevationOrientationAndInterpolation() {
        val engine = CoverageEngine.load(bytes(listOf(-20, 0, 20, 40))).engine!!
        assertEquals(-20.0, engine.elevationAt(52.0, -115.0))
        assertEquals(40.0, engine.elevationAt(52.0 - 1.0/3600, -115.0 + 1.0/3600)!!, 1e-7)
        assertEquals(10.0, engine.elevationAt(52.0 - .5/3600, -115.0 + .5/3600)!!, 1e-7)
        assertNull(engine.elevationAt(53.0, -115.0))
    }
    @Test fun nodataDoesNotBecomeSeaLevel() {
        val engine = CoverageEngine.load(bytes(listOf(1, 2, 3, -32768))).engine!!
        assertNull(engine.elevationAt(52.0 - .5/3600, -115.0 + .5/3600))
    }
    @Test fun badPayloadsReturnTypedFailures() {
        assertEquals(CellState.INVALID_INPUT, CoverageEngine.load(byteArrayOf()).state)
        assertEquals(CellState.INVALID_INPUT, CoverageEngine.load(bytes(listOf(1,2,3,4)).dropLast(1).toByteArray()).state)
        val overflow = bytes(listOf(1,2,3,4)); for (i in 4..7) overflow[i] = 127
        assertEquals(CellState.INVALID_INPUT, CoverageEngine.load(overflow).state)
        assertEquals(CellState.INVALID_INPUT, CoverageEngine.load(bytes(listOf(1,2,3,32767))).state)
    }
    @Test fun ownedSnapshotAndTerminalCancellation() {
        val data = bytes(listOf(-20, 0, 20, 40)); val engine = CoverageEngine.load(data).engine!!
        data[32] = 0; assertEquals(-20.0, engine.elevationAt(52.0,-115.0))
        val job = engine.newJob(); job.step(32, object: StopSignal { override fun isCancelled() = true })
        assertEquals(JobState.CANCELLED, job.state); assertEquals(0, job.completed)
        assertEquals(0, job.step(32, NeverStop())); assertEquals(CellState.NOT_COMPUTED,job.cell(0).state)
    }
    @Test fun invalidReceiverNeverReachesKernel() {
        val engine = CoverageEngine.load(bytes(listOf(1,2,3,4))).engine!!
        for (height in listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY, 3001.0))
            assertEquals(CellState.INVALID_INPUT,engine.predict(51.16,-114.3,height,NeverStop()).state)
        assertEquals(CellState.NEAR_SITE,engine.predict(51.15,-114.3,1.5,NeverStop()).state)
        assertEquals(CellState.MISSING_TERRAIN,engine.predict(51.16,-114.3,1.5,NeverStop()).state)
    }
}
