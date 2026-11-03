package org.nodescope.android

import java.io.File
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.nodescope.android.feature.coverage.*

class CoverageRFConvergenceTest {
    private fun sample(spacing: Double, samples: Int, geometry: Int): CoverageTerrainSampledPath {
        if (samples < 100 || geometry < 10) throw CoverageTerrainProfileFailure(CoverageTerrainProfileState.WORK_LIMIT)
        return CoverageTerrainSampledPath(CoverageTerrainProfile(listOf(0.0, 1000.0), listOf(200.0, 200.0), 100, 2), 10)
    }
    @Test fun sharedConvergenceDecisionsDoNotPublishUnresolvedLosses() {
        val fixtures = Json.parseToJsonElement(File("../../shared/fixtures/coverage/rf-convergence.json").readText()).jsonArray
        assertEquals(8, fixtures.size)
        val states = mapOf("qualified" to CoverageRFConvergenceState.QUALIFIED,
            "accuracyUnresolved" to CoverageRFConvergenceState.ACCURACY_UNRESOLVED,
            "thresholdUnresolved" to CoverageRFConvergenceState.THRESHOLD_UNRESOLVED)
        for (entry in fixtures) {
            val item = entry.jsonObject
            val losses = item.getValue("losses").jsonArray.map { it.jsonPrimitive.double }
            val maximum = item.getValue("maximumLoss").jsonPrimitive.double
            var pass = 0
            val result = CoverageRFConvergence.evaluate(1000.0, maximum, profile = ::sample) { losses[pass++] }
            assertEquals(states.getValue(item.getValue("state").jsonPrimitive.content), result.state)
            assertEquals(item.getValue("passes").jsonPrimitive.int, result.passes)
            assertEquals(pass * 100, result.terrainEvaluations)
            assertEquals(pass * 10, result.geometryEvaluations)
            if (result.state == CoverageRFConvergenceState.QUALIFIED) {
                assertEquals(losses[pass - 1], result.lossDb!!, 0.0)
                assertEquals(maximum - losses[pass - 1], result.marginDb!!, 0.0)
            } else {
                assertNull(result.lossDb)
                assertNull(result.marginDb)
            }
        }
    }
    @Test fun workBudgetIsSharedAcrossPasses() {
        for ((samples, geometry) in listOf(250 to 100, 1000 to 25)) {
            var calls = 0
            val error = assertThrows(CoverageTerrainProfileFailure::class.java) {
                CoverageRFConvergence.evaluate(1000.0, 152.0, samples, geometry, profile = ::sample) { calls++; 100.0 }
            }
            assertEquals(CoverageTerrainProfileState.WORK_LIMIT, error.state)
            assertEquals(2, calls)
        }
    }
    @Test fun invalidModelAndCancellationCannotPublish() {
        val invalid = assertThrows(CoverageRFConvergenceFailure::class.java) {
            CoverageRFConvergence.evaluate(1000.0, 152.0, profile = ::sample) { Double.NaN }
        }
        assertEquals(CoverageRFConvergenceFailureState.INVALID_MODEL, invalid.state)
        var cancelled = false
        val stopped = assertThrows(CoverageTerrainProfileFailure::class.java) {
            CoverageRFConvergence.evaluate(1000.0, 152.0, cancelled = { cancelled }, profile = ::sample) { cancelled = true; 100.0 }
        }
        assertEquals(CoverageTerrainProfileState.CANCELLED, stopped.state)
        val badPath = assertThrows(CoverageRFConvergenceFailure::class.java) {
            CoverageRFConvergence.evaluate(1000.0, 152.0, profile = { _, _, _ ->
                CoverageTerrainSampledPath(CoverageTerrainProfile(listOf(0.0, 999.0), listOf(200.0, 200.0), 2, 2), 0)
            }) { error("Invalid profile reached model") }
        }
        assertEquals(CoverageRFConvergenceFailureState.INVALID_INPUT, badPath.state)
    }
}
