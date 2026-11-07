package org.nodescope.android

import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.nodescope.android.feature.coverage.*

class CoverageStudyPublicationTest {
    private fun result(id: UUID, completed: Boolean = true, cells: Boolean = true): CoverageStudyResult {
        val run = CoverageNodeRun(emptyList(), true, 1, 1000.0)
        if (completed) run.complete()
        return CoverageStudyResult(id, CoverageStudyInputs(CoverageTerrainCoordinate(0.0, 0.0), CoverageGrid(1000, 1000, 0, 0, 0, 0),
            CoverageLinkBudget(22.0, 3.0, 2.0, 1.0, 1.0, -130.0, 5.0), 915.0, 30.0, 1.5), CoverageStudyLimits(0, 0, 0, 0, 0),
            CoverageAreaResult(if (cells) listOf(CoverageAreaCell(CoverageAreaState.nearSite)) else emptyList(), 0, 0, false),
            CoverageNodeBatchResult(run, 0, 0, 0))
    }
    @Test fun supersededCallbacksCannotReplaceActiveOrCompletedStudy() {
        val gate = CoverageStudyPublication()
        val old = gate.begin()
        val current = gate.begin()
        assertFalse(gate.publish(result(old)))
        assertFalse(gate.cancel(old))
        assertFalse(gate.fail(old))
        assertEquals(CoverageStudyPublicationPhase.running, gate.snapshot().phase)
        assertNull(gate.snapshot().result)
        assertTrue(gate.publish(result(current)))
        assertFalse(gate.publish(result(current)))
        assertFalse(gate.fail(current))
        assertEquals(current, gate.snapshot().result?.executionID)
        gate.invalidate()
        assertEquals(CoverageStudyPublicationPhase.stale, gate.snapshot().phase)
        assertEquals(current, gate.snapshot().result?.executionID)
        assertFalse(gate.publish(result(current)))
        gate.begin()
        assertNull(gate.snapshot().result)
    }
    @Test fun cancellationInvalidationAndMalformedResultsCannotPublish() {
        val gate = CoverageStudyPublication()
        val cancelled = gate.begin()
        assertTrue(gate.cancel(cancelled))
        assertFalse(gate.publish(result(cancelled)))
        assertEquals(CoverageStudyPublicationPhase.cancelled, gate.snapshot().phase)
        val invalidated = gate.begin()
        gate.invalidate()
        assertFalse(gate.publish(result(invalidated)))
        val current = gate.begin()
        assertFalse(gate.publish(result(current, completed = false)))
        assertFalse(gate.publish(result(current, cells = false)))
        assertTrue(gate.fail(current))
        assertEquals(CoverageStudyPublicationPhase.failed, gate.snapshot().phase)
        assertNull(gate.snapshot().result)
    }
}
