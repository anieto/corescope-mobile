package org.nodescope.rf

import org.meshtastic.kp1812.Atmosphere
import org.meshtastic.kp1812.P1812
import org.meshtastic.kp1812.Polarization
import org.meshtastic.kp1812.TerrainPath

enum class CoverageKernelState { PREDICTED, INVALID_INPUT, INVALID_MODEL }

class CoverageKernelResult(val state: CoverageKernelState, val lossDb: Double?)

/** Bounded terrain-only scenario. These assumptions must accompany every displayed prediction. */
object CoverageKernel {
    const val sourceRevision = "a911b6ecd58f8c16ac8ae4becf6bdbba1784e5a9"
    const val scenario = "P.1812-8; inland; zero clutter; vertical polarization; median atmosphere (45/325); coast distances 500 km; time/location 50%; location variability 0 dB"

    /** Own the profile while calling the synchronous kernel; callers must not mutate during copying.
     * Cancellation is checked by the caller before/after this bounded synchronous operation.
     * Expected validation failures never escape through the Kotlin/Native Swift boundary.
     */
    fun predict(distancesMeters: DoubleArray, elevationsMeters: DoubleArray,
                frequencyMHz: Double, transmitHeightMeters: Double, receiveHeightMeters: Double,
                pathCenterLatitude: Double): CoverageKernelResult {
        fun invalid() = CoverageKernelResult(CoverageKernelState.INVALID_INPUT, null)
        if (distancesMeters.size !in 3..100_001 || elevationsMeters.size != distancesMeters.size ||
            !frequencyMHz.isFinite() || frequencyMHz !in 30.0..6000.0 ||
            !transmitHeightMeters.isFinite() || transmitHeightMeters !in 1.0..3000.0 ||
            !receiveHeightMeters.isFinite() || receiveHeightMeters !in 1.0..3000.0 ||
            !pathCenterLatitude.isFinite() || pathCenterLatitude !in -85.0..85.0) return invalid()
        val distance = distancesMeters.copyOf()
        val elevation = elevationsMeters.copyOf()
        if (distance.first() != 0.0 || distance.last() !in 250.0..100_000.0) return invalid()
        for (i in distance.indices) {
            if (!distance[i].isFinite() || (i > 0 && distance[i] <= distance[i - 1]) ||
                !elevation[i].isFinite() || elevation[i] !in -12000.0..10000.0) return invalid()
        }
        return try {
            val loss = P1812.predict(
                TerrainPath(DoubleArray(distance.size) { distance[it] / 1000.0 }, elevation,
                    DoubleArray(distance.size), IntArray(distance.size) { 4 }),
                frequencyGhz = frequencyMHz / 1000.0, txHeightM = transmitHeightMeters,
                rxHeightM = receiveHeightMeters, timePercent = 50.0,
                pathCenterLatitudeDeg = pathCenterLatitude, polarization = Polarization.VERTICAL,
                atmosphere = Atmosphere(45.0, 325.0, 500.0, 500.0),
                locationPercent = 50.0, locationVariabilityDb = 0.0).basicTransmissionLossDb
            if (loss.isFinite()) CoverageKernelResult(CoverageKernelState.PREDICTED, loss)
            else CoverageKernelResult(CoverageKernelState.INVALID_MODEL, null)
        } catch (_: IllegalArgumentException) {
            CoverageKernelResult(CoverageKernelState.INVALID_MODEL, null)
        }
    }
}
