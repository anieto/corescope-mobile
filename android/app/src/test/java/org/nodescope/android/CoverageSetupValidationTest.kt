package org.nodescope.android

import java.io.File
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.nodescope.android.feature.coverage.*

class CoverageSetupValidationTest {
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
