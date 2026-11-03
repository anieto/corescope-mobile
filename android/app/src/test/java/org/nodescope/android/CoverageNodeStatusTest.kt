package org.nodescope.android

import java.io.File
import java.util.UUID
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.nodescope.android.feature.coverage.*

class CoverageNodeStatusTest {
    @Test fun sharedStatusDecisionsNeverLeakObsoleteMargins() {
        val fixtures = Json.parseToJsonElement(File("../../shared/fixtures/coverage/node-status.json").readText()).jsonArray
        assertEquals(18, fixtures.size)
        val run = UUID.randomUUID()
        val node = CoverageNodeSnapshot("source", "node", 29.0, -98.0)
        for (entry in fixtures) {
            val f = entry.jsonObject
            val flag = f["flag"]?.jsonPrimitive?.content
            val current = node.copy(sourceID = if (flag == "sourceChanged") "other" else "source",
                latitude = if (flag == "moved") 30.0 else 29.0)
            val unavailable = f["unavailable"]?.jsonPrimitive?.content
            val margin = f["margin"]?.jsonPrimitive?.double
            val prediction = if (unavailable != null) CoverageNodePrediction.Unavailable(CoverageNodeReason.valueOf(unavailable))
                else CoverageNodePrediction.QualifiedMargin(if (flag == "invalid") Double.NaN else requireNotNull(margin))
            val evaluation = CoverageNodeEvaluation(run, node, if (flag == "outside") 1001.0 else 1000.0, prediction)
            val result = CoverageNodeStatus.resolve(current, if (flag == "stale") UUID.randomUUID() else run,
                1000.0, if (flag == "absent") null else evaluation)
            val reason = f.getValue("reason").jsonPrimitive.content
            assertEquals(f.getValue("state").jsonPrimitive.content, result.state.name)
            assertEquals(reason, result.reason.name)
            assertEquals(if (reason == "predicted") margin else null, result.adjustedMarginDb)
        }
    }
    @Test fun convergenceFeedsNodeStatusWithoutBypassingGuard() {
        val node = CoverageNodeSnapshot("source", "node", 29.0, -98.0)
        val run = UUID.randomUUID()
        val budget = CoverageLinkBudget(22.0, 3.0, 2.0, 1.0, 1.0, -130.0, 5.0)
        for ((loss, expected) in listOf(140.0 to CoverageNodeState.inCoverage, 160.0 to CoverageNodeState.belowThreshold,
            150.0 to CoverageNodeState.unknown, 149.75 to CoverageNodeState.unknown, 150.25 to CoverageNodeState.unknown)) {
            val convergence = CoverageRFConvergence.evaluate(1000.0, budget.maximumLossDb, profile = { _, _, _ ->
                CoverageTerrainSampledPath(CoverageTerrainProfile(listOf(0.0, 1000.0), listOf(0.0, 0.0), 2, 2), 0)
            }) { loss }
            val evaluation = CoverageNodeEvaluation(run, node, 1000.0, CoverageNodePrediction.fromConvergence(convergence, budget))
            val status = CoverageNodeStatus.resolve(node, run, 1000.0, evaluation)
            assertEquals(expected, status.state)
            if (expected == CoverageNodeState.unknown) {
                assertEquals(CoverageNodeReason.thresholdUnresolved, status.reason)
                assertNull(status.adjustedMarginDb)
            } else assertEquals(budget.maximumLossDb - loss, status.adjustedMarginDb!!, 0.0)
        }
    }
    @Test fun inconsistentOrPrematureResultsCannotBecomePredictions() {
        val budget = CoverageLinkBudget(22.0, 3.0, 2.0, 1.0, 1.0, -130.0, 5.0)
        for ((margin, passes, spacing) in listOf(Triple(11.0, 3, 5.0), Triple(10.0, 1, 5.0), Triple(10.0, 3, 30.0))) {
            val result = CoverageRFConvergenceResult(CoverageRFConvergenceState.QUALIFIED, 140.0, margin, passes, spacing, 6, 0)
            assertEquals(CoverageNodePrediction.Unavailable(CoverageNodeReason.invalidInput),
                CoverageNodePrediction.fromConvergence(result, budget))
        }
    }

}
