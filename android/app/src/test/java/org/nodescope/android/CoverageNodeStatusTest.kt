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
}
