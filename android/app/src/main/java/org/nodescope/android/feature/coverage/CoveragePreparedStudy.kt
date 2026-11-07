package org.nodescope.android.feature.coverage

import java.io.File
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive

enum class CoveragePreparationStage { validating, preparingTerrain, calculating }

/** Worker operation owning its cache/readers. Catalog trust and qualified RF binding stay explicit. */
object CoveragePreparedStudy {
    suspend fun evaluate(inputs: CoverageStudyInputs, nodes: List<CoverageNodeSnapshot>,
                         catalogComplete: Boolean, nodeLimit: Int, limits: CoverageStudyLimits,
                         catalog: List<CoverageTerrainDescriptor>, samplingHaloMeters: Double,
                         cacheRoot: File, cacheQuota: Long, downloadLimit: Long,
                         offline: Boolean, executionID: UUID,
                         progress: suspend (CoveragePreparationStage) -> Unit = {},
                         predictLoss: (CoverageTerrainProfile, CoverageStudyInputs) -> Double): CoverageStudyResult {
        val context = currentCoroutineContext()
        context.ensureActive()
        // Freeze caller-owned collections before the first suspension.
        require(nodes.size <= 10_000 && catalog.size <= 4096)
        val snapshot = nodes.toList()
        val terrainCatalog = catalog.toList()
        progress(CoveragePreparationStage.validating)
        context.ensureActive()
        limits.validate()
        CoverageNodeRun(snapshot, catalogComplete, nodeLimit, inputs.grid.radiusMeters.toDouble())
        CoverageGridPreflight.estimate(inputs.grid, limits.areaSamples.toLong(), limits.areaGeometry.toLong())
        val footprint = CoverageTerrainFootprint.make(inputs.transmitter.latitude, inputs.transmitter.longitude,
            inputs.grid.radiusMeters.toDouble(), samplingHaloMeters)
        val descriptors = footprint.resolve(terrainCatalog)
        val budget = CoverageTerrainDownloadBudget(downloadLimit)
        context.ensureActive()
        progress(CoveragePreparationStage.preparingTerrain)
        context.ensureActive()
        val study = CoverageTerrainDiskCache(cacheRoot, cacheQuota).use { cache ->
            cache.prepareMosaic(footprint, descriptors, budget, offline).use { mosaic ->
                context.ensureActive()
                progress(CoveragePreparationStage.calculating)
                context.ensureActive()
                CoverageStudy.evaluate(inputs, snapshot, catalogComplete, nodeLimit, limits, mosaic, executionID,
                    cancelled = { !context.isActive }, predictLoss = predictLoss)
            }
        }
        context.ensureActive()
        return study.copy(terrainSources = CoverageStudyTerrainSources(descriptors))
    }
}
