package org.nodescope.android.feature.coverage

import java.io.File
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive

enum class CoveragePreparationStage { validating, preparingTerrain, calculating }

/** Owns cache/readers. Null catalog discovers AWS Skadi online; offline requires retained descriptors. */
object CoveragePreparedStudy {
    suspend fun evaluate(inputs: CoverageStudyInputs, nodes: List<CoverageNodeSnapshot>,
                         catalogComplete: Boolean, nodeLimit: Int, limits: CoverageStudyLimits,
                         catalog: List<CoverageTerrainDescriptor>? = null, samplingHaloMeters: Double,
                         cacheRoot: File, cacheQuota: Long, downloadLimit: Long,
                         offline: Boolean, executionID: UUID,
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
        context.ensureActive()
        // Freeze caller-owned collections before the first suspension.
        require(nodes.size <= 10_000 && (catalog?.size ?: 0) <= 4096)
        val snapshot = nodes.toList()
        val terrainCatalog = catalog?.toList()
        progress(CoveragePreparationStage.validating)
        context.ensureActive()
        limits.validate()
        CoverageNodeRun(snapshot, catalogComplete, nodeLimit, inputs.grid.radiusMeters.toDouble())
        CoverageGridPreflight.estimate(inputs.grid, limits.areaSamples.toLong(), limits.areaGeometry.toLong())
        val footprint = CoverageTerrainFootprint.make(inputs.transmitter.latitude, inputs.transmitter.longitude,
            inputs.grid.radiusMeters.toDouble(), samplingHaloMeters)
        val budget = CoverageTerrainDownloadBudget(downloadLimit)
        context.ensureActive()
        progress(CoveragePreparationStage.preparingTerrain)
        context.ensureActive()
        val descriptors = if (terrainCatalog != null) footprint.resolve(terrainCatalog) else {
            if (offline) throw CoverageTerrainCacheFailure(CoverageTerrainCacheState.MISSING)
            CoverageSkadiProvider.discover(footprint)
        }
        context.ensureActive()
        val study = CoverageTerrainDiskCache(cacheRoot, cacheQuota).use { cache ->
            cache.prepareMosaic(footprint, descriptors, budget, offline).use { mosaic ->
                context.ensureActive()
                progress(CoveragePreparationStage.calculating)
                context.ensureActive()
                CoverageStudy.evaluate(inputs, snapshot, catalogComplete, nodeLimit, limits, mosaic, executionID,
                    cancelled = { !context.isActive }, progress = calculationProgress, predictLoss = predictor)
            }
        }
        context.ensureActive()
        return study.copy(terrainSources = CoverageStudyTerrainSources(descriptors), model = model)
    }
}
