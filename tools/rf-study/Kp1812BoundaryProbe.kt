// Copy into the isolated checkout's commonTest tree. Not production validation.
package org.meshtastic.kp1812

import kotlin.test.Test

class Kp1812BoundaryProbe {
    @Test
    fun recordBoundaryBehavior() {
        for (scenario in listOf("valid", "negative_tx", "nan_terrain", "invalid_zone", "negative_clutter", "invalid_latitude", "singular_atmosphere")) {
            try {
                val path = TerrainPath(
                    doubleArrayOf(0.0, 5.0, 10.0),
                    doubleArrayOf(100.0, if (scenario == "nan_terrain") Double.NaN else 100.0, 100.0),
                    DoubleArray(3) { if (scenario == "negative_clutter") -10.0 else 0.0 },
                    IntArray(3) { if (scenario == "invalid_zone") 99 else 4 },
                )
                val loss = P1812.predict(path, 0.915,
                    if (scenario == "negative_tx") -10.0 else 10.0, 1.5,
                    pathCenterLatitudeDeg = if (scenario == "invalid_latitude") 1000.0 else 47.6,
                    atmosphere = Atmosphere(deltaN = if (scenario == "singular_atmosphere") 157.0 else 45.0),
                ).basicTransmissionLossDb
                println("boundary $scenario returned $loss finite=${loss.isFinite()}")
            } catch (error: IllegalArgumentException) {
                println("boundary $scenario rejected ${error.message}")
            }
        }
    }
}
