package org.nodescope.android

import org.junit.Assert.*
import org.junit.Test
import org.nodescope.android.feature.coverage.*

class CoverageNodeRunTest {
    private val first = CoverageNodeSnapshot("source", "1", 29.0, -98.0)
    private val second = CoverageNodeSnapshot("source", "2", 30.0, -98.0)
    private fun evaluation(run: CoverageNodeRun, node: CoverageNodeSnapshot) =
        CoverageNodeEvaluation(run.id, node, 500.0, CoverageNodePrediction.QualifiedMargin(10.0))
    @Test fun completionCapAndInvalidationPreserveSnapshot() {
        val input = mutableListOf(first, second)
        val run = CoverageNodeRun(input, false, 1, 1000.0)
        input.clear()
        assertFalse(run.catalogComplete)
        assertEquals(2, run.receivedCount)
        assertEquals(1, run.excludedCount)
        assertFalse(run.complete())
        assertFalse(run.accept(evaluation(run, second)))
        assertTrue(run.accept(evaluation(run, first)))
        assertFalse(run.accept(evaluation(run, first)))
        assertEquals(CoverageNodeState.notEvaluated, run.status(first).state)
        assertTrue(run.complete())
        assertEquals(CoverageNodeState.inCoverage, run.status(first).state)
        assertEquals(CoverageNodeReason.processingLimit, run.status(second).reason)
        assertEquals(CoverageNodeReason.changedNode, run.status(first.copy(latitude = 31.0)).reason)
        run.invalidate()
        assertEquals(CoverageNodeState.stale, run.status(first).state)
        assertNull(run.status(first).adjustedMarginDb)
        assertFalse(run.accept(evaluation(run, first)))
    }
    @Test fun cancellationAndOldRunResultsCannotPublish() {
        val run = CoverageNodeRun(listOf(first, second), true, 2, 1000.0)
        val old = CoverageNodeRun(listOf(first), true, 1, 1000.0)
        assertFalse(run.accept(evaluation(old, first)))
        assertTrue(run.accept(evaluation(run, first)))
        run.cancel()
        assertFalse(run.accept(evaluation(run, second)))
        assertFalse(run.complete())
        assertEquals(CoverageNodeReason.cancelled, run.status(first).reason)
        assertNull(run.status(first).adjustedMarginDb)
    }
    @Test fun emptyCatalogCompletesAndInvalidCatalogsFail() {
        val empty = CoverageNodeRun(emptyList(), true, 1, 1000.0)
        assertTrue(empty.complete())
        assertEquals(CoverageNodeReason.noResult, empty.status(first).reason)
        assertThrows(IllegalArgumentException::class.java) { CoverageNodeRun(listOf(first, first), true, 1, 1000.0) }
        assertThrows(IllegalArgumentException::class.java) { CoverageNodeRun(listOf(first), true, 0, 1000.0) }
    }
}
