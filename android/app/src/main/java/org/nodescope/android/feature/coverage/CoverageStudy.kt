package org.nodescope.android.feature.coverage

import java.util.UUID

data class CoverageStudyLimits(val areaSamples: Int, val areaGeometry: Int, val nodeSamples: Int,
    val nodeGeometry: Int, val nodeModelCalls: Int) {
    fun validate() {
        if (listOf(areaSamples, areaGeometry, nodeSamples, nodeGeometry).any { it !in 0..150_000_000 } ||
            areaSamples + nodeSamples > 150_000_000 || areaGeometry + nodeGeometry > 150_000_000 || nodeModelCalls !in 0..50_000) {
            throw CoverageTerrainProfileFailure(CoverageTerrainProfileState.INVALID_INPUT)
        }
    }
}
/** Defensive snapshot of selected sources, not a complete RF reproducibility record. */
class CoverageStudyTerrainSources(descriptors: List<CoverageTerrainDescriptor>) {
    val descriptors: List<CoverageTerrainDescriptor> = java.util.Collections.unmodifiableList(descriptors.toList())
}

data class CoverageStudyModel(val sourceRevision: String, val scenario: String)

data class CoverageStudyResult(val executionID: UUID, val inputs: CoverageStudyInputs,
    val limits: CoverageStudyLimits, val area: CoverageAreaResult, val nodes: CoverageNodeBatchResult,
    val terrainSources: CoverageStudyTerrainSources? = null, val model: CoverageStudyModel? = null,
    /** When the batch that produced this result finished; null for converted legacy results. Saving never changes it. */
    val calculatedAtMilliseconds: Long? = null) {
    val transmitter get() = inputs.transmitter
    val grid get() = inputs.grid
    val budget get() = inputs.budget
    /** Samples or nodes a batch left for Continue (not ones a single path's limit stopped). */
    val hasPendingWork: Boolean get() = area.pendingCount > 0 ||
        nodes.run.nodesToEvaluate.any { nodes.run.status(it).reason == CoverageNodeReason.processingLimit }

    /**
     * The same paths under a new link budget, as iOS: losses are kept and margins recomputed; samples
     * whose margin lands within 0.25 dB of the threshold, or that were unresolved, are left for Continue.
     */
    fun updatingBudget(updated: CoverageStudyInputs): CoverageStudyResult {
        fun invalid(): Nothing = throw CoverageTerrainProfileFailure(CoverageTerrainProfileState.INVALID_INPUT)
        if (!inputs.matchesPropagation(updated) || model?.sourceRevision != CoverageRFKernel.sourceRevision ||
            model.scenario != CoverageRFKernel.scenario || terrainSources == null) invalid()
        val cells = area.cells.map { cell ->
            val loss = cell.lossDb
            when {
                cell.state == CoverageAreaState.qualified && loss != null -> {
                    val margin = updated.budget.reception(loss).adjustedMarginDb
                    if (kotlin.math.abs(margin) > 0.25) CoverageAreaCell(CoverageAreaState.qualified, loss, margin)
                    else CoverageAreaCell(CoverageAreaState.notComputed)
                }
                cell.state == CoverageAreaState.thresholdUnresolved || cell.state == CoverageAreaState.accuracyUnresolved ||
                    cell.state == CoverageAreaState.workLimit -> CoverageAreaCell(CoverageAreaState.notComputed)
                else -> cell
            }
        }
        val old = nodes.run
        val run = CoverageNodeRun(old.nodesToEvaluate, old.catalogComplete, maxOf(1, old.nodesToEvaluate.size), old.radiusMeters)
        for (node in run.nodesToEvaluate) {
            val evaluation = old.evaluation(node) ?: invalid()
            val prediction = when (val previous = evaluation.prediction) {
                is CoverageNodePrediction.QualifiedMargin -> {
                    val next = updated.budget.reception(budget.maximumLossDb - previous.margin).adjustedMarginDb
                    if (kotlin.math.abs(next) > 0.25) CoverageNodePrediction.QualifiedMargin(next)
                    else CoverageNodePrediction.Unavailable(CoverageNodeReason.processingLimit)
                }
                is CoverageNodePrediction.Unavailable -> if (previous.reason in listOf(CoverageNodeReason.thresholdUnresolved,
                        CoverageNodeReason.accuracyUnresolved, CoverageNodeReason.processingLimit, CoverageNodeReason.pathWorkLimit))
                    CoverageNodePrediction.Unavailable(CoverageNodeReason.processingLimit) else previous
            }
            if (!run.accept(CoverageNodeEvaluation(run.id, node, evaluation.distanceMeters, prediction))) invalid()
        }
        if (!run.complete()) invalid()
        return CoverageStudyResult(UUID.randomUUID(), updated, limits,
            CoverageAreaResult(java.util.Collections.unmodifiableList(cells), 0, 0, cells.any { it.state == CoverageAreaState.notComputed }),
            CoverageNodeBatchResult(run, 0, 0, 0), terrainSources, model,
            // Repricing reuses the calculated losses: it isn't a new calculation.
            calculatedAtMilliseconds)
    }
}
/** How far the calculation stage has got: map samples done of those needing a path, then the known nodes. */
sealed interface CoverageCalculationProgress {
    data class MapSamples(val completed: Int, val total: Int) : CoverageCalculationProgress
    data object CheckingNodes : CoverageCalculationProgress
}

/** Serialized worker operation borrowing terrain. No area-only result escapes node failure. */
object CoverageStudy {
    fun evaluate(inputs: CoverageStudyInputs,
                 nodes: List<CoverageNodeSnapshot>, catalogComplete: Boolean, nodeLimit: Int,
                 limits: CoverageStudyLimits, mosaic: CoverageTerrainMosaic, executionID: UUID = UUID.randomUUID(), cancelled: () -> Boolean = { false },
                 progress: (CoverageCalculationProgress) -> Unit = {},
                 resuming: CoverageAreaResult? = null, resumingNodes: CoverageNodeBatchResult? = null,
                 shouldPause: () -> Boolean = { false }, diagnostics: CoverageRunDiagnostics? = null,
                 predictLoss: (CoverageTerrainProfile, CoverageStudyInputs) -> Double): CoverageStudyResult {
        limits.validate()
        val transmitter = inputs.transmitter
        val grid = inputs.grid
        val budget = inputs.budget
        val predict: (CoverageTerrainProfile) -> Double = { profile ->
            diagnostics?.measure("rfPrediction") { predictLoss(profile, inputs) } ?: predictLoss(profile, inputs)
        }
        require(nodes.size <= 10_000)
        val snapshot = nodes.toList()
        CoverageNodeRun(snapshot, catalogComplete, nodeLimit, grid.radiusMeters.toDouble())
        val profile: (CoverageTerrainPath, Double, Int, Int) -> CoverageTerrainSampledPath = { path, spacing, samples, geometry ->
            diagnostics?.measure("pathPreparation") { mosaic.profile(path, spacing, samples, geometry, cancelled) }
                ?: mosaic.profile(path, spacing, samples, geometry, cancelled)
        }
        val area = CoverageArea.evaluate(grid, transmitter, budget, limits.areaSamples.toLong(), limits.areaGeometry.toLong(),
            cancelled, resuming, shouldPause, { completed, total -> progress(CoverageCalculationProgress.MapSamples(completed, total)) },
            profile, predict)
        progress(CoverageCalculationProgress.CheckingNodes)
        val nodeResults = CoverageNodeBatch.evaluate(snapshot, catalogComplete, nodeLimit, transmitter,
            grid.radiusMeters.toDouble(), budget, limits.nodeSamples, limits.nodeGeometry, limits.nodeModelCalls,
            cancelled, shouldPause, resumingNodes, profile, predict)
        if (cancelled()) throw CoverageTerrainProfileFailure(CoverageTerrainProfileState.CANCELLED)
        return CoverageStudyResult(executionID, inputs, limits, area, nodeResults, calculatedAtMilliseconds = System.currentTimeMillis())
    }
}
