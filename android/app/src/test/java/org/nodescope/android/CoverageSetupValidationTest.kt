package org.nodescope.android

import java.io.File
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.nodescope.android.feature.coverage.*

class CoverageSetupValidationTest {
    @Test fun completeStudyInputsAreFrozenAndRequireEveryAssumption() {
        val fields = mapOf("latitude" to "50.96", "longitude" to "-115.16", "frequency" to "915", "power" to "22",
            "txHeight" to "30", "txGain" to "3", "txLoss" to "1", "rxHeight" to "1.5", "rxGain" to "2",
            "rxLoss" to "1", "sensitivity" to "-130", "reserve" to "5")
        val edited = fields.toMutableMap()
        val inputs = CoverageStudyInputs.fromEditor(edited, 20_000, 1000)
        edited["frequency"] = "868"
        edited["txHeight"] = "10"
        assertEquals(915.0, inputs.frequencyMHz, 0.0)
        assertEquals(30.0, inputs.transmitHeightMeters, 0.0)
        assertEquals(1.5, inputs.receiveHeightMeters, 0.0)
        assertEquals(50.96, inputs.transmitter.latitude, 0.0)
        assertEquals(-115.16, inputs.transmitter.longitude, 0.0)
        assertEquals(20_000, inputs.grid.radiusMeters)
        assertEquals(1000, inputs.grid.spacingMeters)
        assertEquals(150.0, inputs.budget.maximumLossDb, 0.0)
        for (field in fields.keys) {
            val failure = assertThrows(CoverageStudyInputFailure::class.java) {
                CoverageStudyInputs.fromEditor(fields - field, 20_000, 1000)
            }
            assertEquals(field, failure.field)
            assertEquals(CoverageSetupIssue.required, failure.issue)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CoverageStudyInputs.fromEditor(fields + mapOf("power" to "1e308", "txGain" to "1e308"), 20_000, 1000)
        }
        assertThrows(CoverageGridFailure::class.java) { CoverageStudyInputs.fromEditor(fields, Int.MIN_VALUE, 1000) }
        assertThrows(CoverageStudyInputFailure::class.java) {
            CoverageStudyInputs(inputs.transmitter, inputs.grid, inputs.budget, Double.NaN, 30.0, 1.5)
        }
    }

    @Test fun sharedInputBoundaries() {
        val rows = Json.parseToJsonElement(File("../../shared/fixtures/coverage/setup-validation.json").readText()).jsonArray
        assertEquals(28, rows.size)
        for (row in rows) {
            val item = row.jsonObject
            val field = item.getValue("field").jsonPrimitive.content
            val text = item.getValue("text").jsonPrimitive.content
            assertEquals("$field: $text", item.getValue("issue").jsonPrimitive.contentOrNull,
                CoverageSetupValidation.issue(field, text)?.name)
        }
    }
    @Test fun editorChoicesCanExceedWorkBeforeGridSize() {
        val grid = CoverageGrid(100_000, 1000, -100_000, 100_000, -100_000, 100_000)
        assertTrue(grid.count <= 60_000)
        assertEquals(CoverageGridPreflightFailureState.sampleBudget,
            assertThrows(CoverageGridPreflightFailure::class.java) {
                CoverageGridPreflight.estimate(grid, 150_000_000, 150_000_000)
            }.state)
        val ordinary = CoverageGrid(20_000, 1000, -20_000, 20_000, -20_000, 20_000)
        assertTrue(CoverageGridPreflight.estimate(ordinary, 150_000_000, 150_000_000).eligiblePoints > 0)
    }
}
