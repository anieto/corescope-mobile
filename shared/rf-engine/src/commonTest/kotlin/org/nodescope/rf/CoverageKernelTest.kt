package org.nodescope.rf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.meshtastic.kp1812.*

class CoverageKernelTest {
    private fun predict(distance: DoubleArray = doubleArrayOf(0.0, 500.0, 1000.0),
                        elevation: DoubleArray = doubleArrayOf(100.0, 100.0, 100.0),
                        frequency: Double = 915.0, height: Double = 10.0, latitude: Double = 50.0) =
        CoverageKernel.predict(distance, elevation, frequency, height, 1.5, latitude)

    @Test fun rejectsUnsafeInputsWithoutThrowing() {
        val cases = listOf(
            predict(doubleArrayOf()), predict(doubleArrayOf(0.0, 1000.0)),
            predict(doubleArrayOf(1.0, 500.0, 1000.0)),
            predict(doubleArrayOf(0.0, 500.0, 500.0)),
            predict(doubleArrayOf(0.0, 100.0, 249.0)),
            predict(doubleArrayOf(0.0, 500.0, 100001.0)),
            predict(doubleArrayOf(0.0, Double.NaN, 1000.0)),
            predict(elevation = doubleArrayOf(0.0, -32768.0, 0.0)),
            predict(elevation = doubleArrayOf(0.0, Double.NaN, 0.0)),
            predict(elevation = doubleArrayOf(0.0)), predict(height = -1.0),
            predict(frequency = Double.POSITIVE_INFINITY), predict(frequency = 29.0),
            predict(latitude = 1000.0), predict(latitude = Double.NaN),
            predict(DoubleArray(100002), DoubleArray(100002)))
        cases.forEach { assertEquals(CoverageKernelState.INVALID_INPUT, it.state); assertNull(it.lossDb) }
    }

    @Test fun preservesModelUnitsAndScenario() {
        val expected = P1812.predict(TerrainPath(doubleArrayOf(0.0, 0.5, 1.0),
            doubleArrayOf(100.0, 100.0, 100.0), DoubleArray(3), IntArray(3) { 4 }),
            frequencyGhz = 0.915, txHeightM = 10.0, rxHeightM = 1.5,
            pathCenterLatitudeDeg = 50.0).basicTransmissionLossDb
        val result = predict()
        assertEquals(CoverageKernelState.PREDICTED, result.state)
        assertEquals(expected, result.lossDb!!, 1e-9)
        assertTrue(CoverageKernel.scenario.contains("zero clutter"))
    }

    @Test fun acceptsBelowSeaLevelAndDoesNotMutateInputs() {
        val d = doubleArrayOf(0.0, 500.0, 1000.0)
        val h = doubleArrayOf(-50.0, -20.0, -30.0)
        val result = predict(d, h)
        assertEquals(CoverageKernelState.PREDICTED, result.state)
        assertEquals(500.0, d[1]); assertEquals(-20.0, h[1])
    }
}
