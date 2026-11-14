package org.nodescope.android.feature.coverage

import java.io.File
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive

enum class CoveragePreparationStage { validating, preparingTerrain, calculating }

/**
 * Owns cache/readers. Null catalog discovers AWS Skadi online; offline requires retained descriptors.
 * [previous] continues an unfinished result (same inputs) or grows a smaller one (larger radius), as iOS.
 */
object CoveragePreparedStudy {
    suspend fun evaluate(inputs: CoverageStudyInputs, nodes: List<CoverageNodeSnapshot>,
                         catalogComplete: Boolean, nodeLimit: Int, limits: CoverageStudyLimits,
                         catalog: List<CoverageTerrainDescriptor>? = null, samplingHaloMeters: Double,
                         cacheRoot: File, cacheQuota: Long, downloadLimit: Long,
                         offline: Boolean, executionID: UUID,
                         previous: CoverageStudyResult? = null, diagnosticsFile: File? = null,
                         progress: suspend (CoveragePreparationStage) -> Unit = {},
                         /** Called on the worker, about four times a second while the map samples run. */
                         calculationProgress: (CoverageCalculationProgress) -> Unit = {},
                         predictLoss: ((CoverageTerrainProfile, CoverageStudyInputs) -> Double)? = null): CoverageStudyResult {
        val model = if (predictLoss == null) CoverageStudyModel(CoverageRFKernel.sourceRevision, CoverageRFKernel.scenario) else null
        val predictor: (CoverageTerrainProfile, CoverageStudyInputs) -> Double = predictLoss ?: { profile, captured ->
            val latitude = profile.pathCenterLatitude
                ?: throw CoverageRFConvergenceFailure(CoverageRFConvergenceFailureState.INVALID_INPUT)
            CoverageRFKernel.predict(profile, captured, latitude)
        }
        val context = currentCoroutineContext()
        val diagnostics = CoverageRunDiagnostics(inputs, diagnosticsFile)
        diagnostics.setPhase("validating")
        try {
            context.ensureActive()
            // Freeze caller-owned collections before the first suspension.
            require(nodes.size <= 10_000 && (catalog?.size ?: 0) <= 4096)
            val snapshot = nodes.toList()
            val terrainCatalog = catalog?.toList()
            val validated = CoverageNodeRun(snapshot, catalogComplete, nodeLimit, inputs.grid.radiusMeters.toDouble())
            val expanding = previous?.inputs?.canExpand(inputs) == true
            if (previous != null && (!(previous.inputs.matches(inputs) || expanding) || previous.model != model ||
                    previous.nodes.run.nodesToEvaluate != validated.nodesToEvaluate ||
                    previous.nodes.run.catalogComplete != catalogComplete || previous.terrainSources == null)) {
                throw CoverageTerrainProfileFailure(CoverageTerrainProfileState.INVALID_INPUT)
            }
            progress(CoveragePreparationStage.validating)
            context.ensureActive()
            limits.validate()
            CoverageGridPreflight.estimate(inputs.grid, limits.areaSamples.toLong(), limits.areaGeometry.toLong())
            val footprint = CoverageTerrainFootprint.make(inputs.transmitter.latitude, inputs.transmitter.longitude,
                inputs.grid.radiusMeters.toDouble(), samplingHaloMeters)
            val budget = CoverageTerrainDownloadBudget(downloadLimit)
            context.ensureActive()
            val terrainStarted = System.nanoTime()
            diagnostics.setPhase("preparing terrain")
            progress(CoveragePreparationStage.preparingTerrain)
            context.ensureActive()
            val pinned = previous?.terrainSources?.descriptors
            val descriptors = when {
                // Continue against exactly the same terrain, even if discovery would now differ.
                pinned != null -> runCatching { footprint.resolve(pinned) }.getOrNull() ?: if (expanding) {
                    val discovered = terrainCatalog ?: run {
                        if (offline) throw CoverageTerrainCacheFailure(CoverageTerrainCacheState.MISSING)
                        CoverageSkadiProvider.discover(footprint)
                    }
                    val tiles = pinned.map { it.coordinate }.toSet()
                    footprint.resolve(pinned + discovered.filter { it.coordinate !in tiles })
                } else footprint.resolve(pinned)
                terrainCatalog != null -> footprint.resolve(terrainCatalog)
                else -> {
                    if (offline) throw CoverageTerrainCacheFailure(CoverageTerrainCacheState.MISSING)
                    CoverageSkadiProvider.discover(footprint)
                }
            }
            context.ensureActive()
            val retainedArea = previous?.let { old -> if (expanding) old.area.expanded(old.grid, inputs.grid) else old.area }
            if (expanding && retainedArea != null) diagnostics.recordRadiusReuse(retainedArea)
            val study = CoverageTerrainDiskCache(cacheRoot, cacheQuota).use { cache ->
                cache.prepareMosaic(footprint, descriptors, budget, offline).use { mosaic ->
                    diagnostics.recordDuration("terrainPreparation", terrainStarted)
                    context.ensureActive()
                    diagnostics.setPhase("calculating")
                    progress(CoveragePreparationStage.calculating)
                    context.ensureActive()
                    CoverageStudy.evaluate(inputs, snapshot, catalogComplete, nodeLimit, limits, mosaic, executionID,
                        cancelled = { !context.isActive }, progress = calculationProgress,
                        resuming = retainedArea, resumingNodes = previous?.nodes,
                        shouldPause = diagnostics::shouldPause, diagnostics = diagnostics, predictLoss = predictor)
                        .also { diagnostics.recordCache(mosaic) }
                }
            }
            context.ensureActive()
            val result = study.copy(terrainSources = CoverageStudyTerrainSources(descriptors), model = model)
            diagnostics.finish(result)
            return result
        } catch (error: Throwable) {
            diagnostics.setPhase(if (!context.isActive) "cancelled" else "failed: ${error::class.simpleName}")
            throw error
        } finally {
            diagnostics.finishIfNeeded(cancelled = !context.isActive)
        }
    }
}
