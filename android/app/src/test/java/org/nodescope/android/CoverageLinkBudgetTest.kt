package org.nodescope.android

import java.io.File
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.nodescope.android.feature.coverage.CoverageLinkBudget

class CoverageLinkBudgetTest {
    private fun budget(v: List<Double>) = CoverageLinkBudget(v[0], v[1], v[2], v[3], v[4], v[5], v[6])
    @Test fun sharedRadioBudgetsPreserveMarginSign() {
        val fixtures = Json.parseToJsonElement(File("../../shared/fixtures/coverage/link-budget.json").readText()).jsonArray
        assertEquals(5, fixtures.size)
        for (entry in fixtures) {
            val f = entry.jsonObject
            val model = budget(f.getValue("inputs").jsonArray.map { it.jsonPrimitive.double })
            val loss = f.getValue("loss").jsonPrimitive.double
            val margin = f.getValue("margin").jsonPrimitive.double
            val result = model.reception(loss)
            assertEquals(f.getValue("maximum").jsonPrimitive.double, model.maximumLossDb, 0.0)
            assertEquals(f.getValue("receive").jsonPrimitive.double, result.receiveLevelDbm, 1e-10)
            assertEquals(margin, result.adjustedMarginDb, 1e-10)
            assertEquals(margin >= 0, result.adjustedMarginDb >= 0)
            assertEquals(model.maximumLossDb - loss, result.adjustedMarginDb, 0.0)
        }
    }
    @Test fun rejectsNonfiniteInputsNegativeLossesAndOverflow() {
        val valid = listOf(22.0, 3.0, 2.0, 1.0, 1.0, -130.0, 5.0)
        for (index in valid.indices) {
            for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
                val values = valid.toMutableList().also { it[index] = bad }
                assertThrows(IllegalArgumentException::class.java) { budget(values) }
            }
        }
        for (index in listOf(3, 4, 6)) {
            val values = valid.toMutableList().also { it[index] = -1.0 }
            assertThrows(IllegalArgumentException::class.java) { budget(values) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            budget(listOf(Double.MAX_VALUE, Double.MAX_VALUE, 0.0, 0.0, 0.0, -100.0, 0.0))
        }
        val model = budget(valid)
        for (loss in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { model.reception(loss) }
        }
    }
}
