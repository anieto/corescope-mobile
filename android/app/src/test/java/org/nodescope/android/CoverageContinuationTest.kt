package org.nodescope.android

import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import org.nodescope.android.feature.coverage.*

/** Continue calculation, radius growth, budget reuse and the calculation report, ported from iOS's tests. */
class CoverageContinuationTest {
    private val budget = CoverageLinkBudget(22.0, 3.0, 2.0, 1.0, 1.0, -130.0, 5.0)
    private val origin = CoverageTerrainCoordinate(0.0, 0.0)
    private fun flat(path: CoverageTerrainPath) = CoverageTerrainSampledPath(
        CoverageTerrainProfile(listOf(0.0, path.distanceMeters), listOf(0.0, 0.0), 2, 2), 4)

    @Test fun onePathLimitDoesNotStopOtherPathsWithRemainingBudget() {
        var calls = 0
        val result = CoverageArea.evaluate(CoverageGrid.studyArea(1000, 1000), origin, budget, 1_000_000, 1_000_000,
            profile = { path, _, _, _ ->
                calls++
                if (calls == 1) throw CoverageTerrainProfileFailure(CoverageTerrainProfileState.WORK_LIMIT)
                flat(path)
            }, predictLoss = { 140.0 })
        assertTrue(result.workLimited)
        assertEquals(CoverageAreaState.workLimit, result.cells[1].state)
        assertEquals(3, result.cells.count { it.state == CoverageAreaState.qualified })
        assertFalse(result.cells.any { it.state == CoverageAreaState.notComputed })
        assertEquals(250_018L, result.chargedSamples)
        assertEquals(200_036L, result.chargedGeometry)
    }

    @Test fun distributedBatchesResumeWithoutRecalculatingCompletedSamples() {
        val grid = CoverageGrid.studyArea(5000, 1000)
        var paths = 0
        fun batch(previous: CoverageAreaResult? = null, pause: Boolean = false) = CoverageArea.evaluate(grid, origin, budget,
            1_000_000, 1_000_000, previous = previous, shouldPause = { pause && paths >= 8 },
            profile = { path, spacing, _, _ -> if (spacing == 30.0) paths++; flat(path) }, predictLoss = { 140.0 })
        val first = batch(pause = true)
        assertTrue(first.pendingCount > 0)
        val completed = first.cells.indices.filter { first.cells[it].state == CoverageAreaState.qualified }
        assertEquals(8, completed.size)
        // The spread-out order covers both halves of the area before the pause.
        assertTrue(completed.any { (grid.point(it)?.northMeters ?: 0) < 0 })
        assertTrue(completed.any { (grid.point(it)?.northMeters ?: 0) > 0 })
        val remaining = first.pendingCount
        paths = 0
        val resumed = batch(first)
        assertEquals(remaining, paths)
        assertEquals(0, resumed.pendingCount)
        val full = batch()
        assertEquals(full.cells.map { it.state }, resumed.cells.map { it.state })
        assertEquals(full.cells.map { it.marginDb }, resumed.cells.map { it.marginDb })

        val invalid = first.cells.toMutableList().also { it[completed[0]] = CoverageAreaCell(CoverageAreaState.qualified, 140.0, 999.0) }
        val failure = assertThrows(CoverageTerrainProfileFailure::class.java) { batch(CoverageAreaResult(invalid, 0, 0, true)) }
        assertEquals(CoverageTerrainProfileState.INVALID_INPUT, failure.state)
        for (count in listOf(1, 9, 121, 60_000)) {
            val order = CoverageArea.distributedOrder(count)
            assertEquals(count, order.size)
            assertEquals(count, order.toSet().size)
            assertTrue(order.all { it in 0 until count })
        }
    }

    @Test fun largerRadiusKeepsMatchingSamplesByPosition() {
        val small = CoverageGrid.studyArea(2000, 1000)
        val large = CoverageGrid.studyArea(4000, 1000)
        val area = CoverageArea.evaluate(small, origin, budget, 1_000_000, 1_000_000,
            profile = { path, _, _, _ -> flat(path) }, predictLoss = { 140.0 })
        val grown = area.expanded(small, large)
        assertEquals(large.count, grown.cells.size)
        assertEquals(area.cells.count { it.state == CoverageAreaState.qualified }, grown.cells.count { it.state == CoverageAreaState.qualified })
        assertTrue(grown.pendingCount > 0)
        // Each kept sample sits at the same physical offset it had in the smaller grid.
        for (index in 0 until large.count) {
            val cell = grown.cells[index]
            if (cell.state != CoverageAreaState.qualified) continue
            val point = requireNotNull(large.point(index))
            val oldIndex = (0 until small.count).single { small.point(it)?.let { p -> p.eastMeters == point.eastMeters && p.northMeters == point.northMeters } == true }
            assertEquals(area.cells[oldIndex], cell)
        }
        var paths = 0
        val finished = CoverageArea.evaluate(large, origin, budget, 1_000_000, 1_000_000, previous = grown,
            profile = { path, spacing, _, _ -> if (spacing == 30.0) paths++; flat(path) }, predictLoss = { 140.0 })
        assertEquals(grown.pendingCount, paths)
        assertEquals(0, finished.pendingCount)
        assertThrows(CoverageTerrainProfileFailure::class.java) { area.expanded(small, CoverageGrid.studyArea(4000, 500)) }
    }

    private fun inputs(radius: Int, power: Double = 22.0, latitude: Double = 0.0) = CoverageStudyInputs(
        CoverageTerrainCoordinate(latitude, 0.0), CoverageGrid.studyArea(radius, 1000),
        CoverageLinkBudget(power, 3.0, 2.0, 1.0, 1.0, -130.0, 5.0), 915.0, 10.0, 1.5)

    @Test fun inputsExpandOnlyWhenOnlyTheRadiusGrows() {
        assertTrue(inputs(2000).canExpand(inputs(4000)))
        assertFalse(inputs(4000).canExpand(inputs(2000)))
        assertFalse(inputs(2000).canExpand(inputs(4000, power = 20.0)))
        assertFalse(inputs(2000).canExpand(inputs(4000, latitude = 0.1)))
        assertTrue(inputs(2000).matchesPropagation(inputs(2000, power = 20.0)))
        assertFalse(inputs(2000).matchesPropagation(inputs(4000)))
    }

    private fun result(inputs: CoverageStudyInputs, nodes: List<CoverageNodeSnapshot>, nodeMargin: Double): CoverageStudyResult {
        val area = CoverageArea.evaluate(inputs.grid, inputs.transmitter, inputs.budget, 1_000_000, 1_000_000,
            profile = { path, _, _, _ -> flat(path) }, predictLoss = { 140.0 })
        val run = CoverageNodeRun(nodes, true, 200, inputs.grid.radiusMeters.toDouble())
        nodes.forEach { assertTrue(run.accept(CoverageNodeEvaluation(run.id, it, 500.0, CoverageNodePrediction.QualifiedMargin(nodeMargin)))) }
        assertTrue(run.complete())
        return CoverageStudyResult(UUID.randomUUID(), inputs, CoverageStudyLimits(1_000_000, 1_000_000, 1_000_000, 1_000_000, 100),
            area, CoverageNodeBatchResult(run, 0, 0, 0), CoverageStudyTerrainSources(emptyList()),
            CoverageStudyModel(CoverageRFKernel.sourceRevision, CoverageRFKernel.scenario))
    }

    @Test fun budgetChangeRescoresLossesAndLeavesNearThresholdForContinue() {
        val node = CoverageNodeSnapshot("source", "n1", 0.001, 0.001)
        val original = result(inputs(2000), listOf(node), nodeMargin = 3.0)
        val updated = original.updatingBudget(inputs(2000, power = 20.0))
        val qualified = original.area.cells.indices.filter { original.area.cells[it].state == CoverageAreaState.qualified }
        assertTrue(qualified.isNotEmpty())
        for (index in qualified) {
            val cell = updated.area.cells[index]
            assertEquals(original.area.cells[index].lossDb, cell.lossDb)
            assertEquals(original.area.cells[index].marginDb!! - 2.0, cell.marginDb!!, 1e-9)
        }
        assertEquals(1.0, updated.nodes.run.status(node).adjustedMarginDb!!, 1e-9)
        assertFalse(updated.hasPendingWork)
        // A margin that lands within 0.25 dB of the threshold is recalculated by Continue.
        val near = original.updatingBudget(inputs(2000, power = 19.0))
        assertEquals(CoverageNodeReason.processingLimit, near.nodes.run.status(node).reason)
        assertTrue(near.hasPendingWork)
        assertThrows(CoverageTerrainProfileFailure::class.java) { original.updatingBudget(inputs(4000)) }
    }

    @Test fun nodeContinuationOnlyEvaluatesUnfinishedNodes() {
        val nodes = (1..3).map { CoverageNodeSnapshot("source", "n$it", 0.004 * it, 0.0) }
        var calls = 0
        fun run(previous: CoverageNodeBatchResult? = null, modelCalls: Int = 1000, radius: Double = 2000.0) =
            CoverageNodeBatch.evaluate(nodes, true, 10, origin, radius, budget, 1_000_000, 1_000_000, modelCalls,
                previous = previous, profile = { path, _, _, _ -> flat(path) }, predictLoss = { calls++; 140.0 })
        val limited = run(modelCalls = 0)
        assertEquals(nodes.map { CoverageNodeReason.processingLimit }, nodes.map { limited.run.status(it).reason })
        calls = 0
        val finished = run(limited)
        assertTrue(calls > 0)
        assertTrue(nodes.none { finished.run.status(it).reason == CoverageNodeReason.processingLimit })
        calls = 0
        val again = run(finished)
        assertEquals(0, calls)
        assertEquals(nodes.map { finished.run.status(it) }, nodes.map { again.run.status(it) })
        assertThrows(CoverageTerrainProfileFailure::class.java) { run(finished, radius = 1000.0) }
    }

    @Test fun reportIsWrittenWithAStableFingerprint() {
        val root = Files.createTempDirectory("coverage-report").toFile()
        try {
            val file = File(root, "CoverageDiagnostics/latest.json")
            val study = result(inputs(2000), listOf(CoverageNodeSnapshot("source", "n1", 0.001, 0.001)), 4.0)
            val diagnostics = CoverageRunDiagnostics(study.inputs, file, availableMemory = { 1L shl 30 })
            diagnostics.setPhase("calculating")
            assertFalse(diagnostics.shouldPause())
            diagnostics.measure("rfPrediction") { 1 }
            diagnostics.finish(study)
            val report = Json.parseToJsonElement(file.readText()).jsonObject
            assertEquals("completed", report["phase"]?.jsonPrimitive?.content)
            assertEquals(CoverageRunDiagnostics.predictionFingerprint(study), report["predictionFingerprint"]?.jsonPrimitive?.content)
            assertEquals(64, CoverageRunDiagnostics.predictionFingerprint(study).length)
            // Same predictions, same fingerprint; a changed margin changes it.
            assertEquals(CoverageRunDiagnostics.predictionFingerprint(study),
                CoverageRunDiagnostics.predictionFingerprint(study.copy(executionID = UUID.randomUUID())))
            assertNotEquals(CoverageRunDiagnostics.predictionFingerprint(study),
                CoverageRunDiagnostics.predictionFingerprint(study.updatingBudget(inputs(2000, power = 20.0))))
            CoverageRunDiagnostics.recordRasterPreparation(file, study.executionID, 0.5)
            assertTrue("rasterPreparation" in file.readText())
            // Low memory pauses the batch and is recorded.
            val low = CoverageRunDiagnostics(study.inputs, file, availableMemory = { 1024L })
            assertTrue(low.shouldPause())
            low.finishIfNeeded(cancelled = false)
            assertTrue("low available memory" in file.readText())
        } finally { root.deleteRecursively() }
    }
}
