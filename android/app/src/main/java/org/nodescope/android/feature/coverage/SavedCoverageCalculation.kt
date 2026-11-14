package org.nodescope.android.feature.coverage

import java.util.Base64
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * A versioned, value-only coverage checkpoint saved with a study, as iOS's `SavedCoverageCalculation`.
 * Opening it performs no downloads or RF calculations. Android writes its own JSON form (base64 in the
 * study's `calculatedResult`); iOS writes a binary property list. Saved plans stay on one device, so a
 * result from the other platform simply fails to decode and the inputs open without it.
 */
@Serializable
internal data class SavedCoverageCalculation(
    val version: Int = 1,
    val format: String = FORMAT,
    val inputValues: List<Double>,
    val cells: List<Cell>,
    val chargedSamples: Long,
    val chargedGeometry: Long,
    val workLimited: Boolean,
    val limits: List<Int>,
    val modelRevision: String? = null,
    val modelScenario: String? = null,
    val terrain: List<Terrain>? = null,
    val nodes: List<Node>,
) {
    @Serializable data class Cell(val state: String, val lossDb: Double? = null, val marginDb: Double? = null)

    /** A known node's exact-coordinate prediction: a margin, or the reason there is none. */
    @Serializable data class Node(val sourceID: String, val nodeID: String, val latitude: Double, val longitude: Double,
        val distanceMeters: Double, val marginDb: Double? = null, val reason: String? = null)

    @Serializable data class Terrain(val dataset: String, val revision: String, val tile: String, val compressedSHA256: String? = null,
        val packedBytes: Long, val side: Int, val url: String, val verticalDatum: String, val attribution: String,
        val sourceType: String, val originURL: String, val sourceETag: String? = null)

    fun encoded(): String = Base64.getEncoder().encodeToString(json.encodeToString(this).toByteArray(Charsets.UTF_8))

    /** Rebuilds the result for [inputs] and [snapshots], checking it as iOS does; throws if anything disagrees. */
    fun restore(inputs: CoverageStudyInputs, snapshots: List<CoverageNodeSnapshot>, catalogComplete: Boolean): CoverageStudyResult {
        val grid = inputs.grid
        require(version == 1 && format == FORMAT && inputValues == values(inputs) && cells.size == grid.count)
        require(nodes.size <= 200 && (terrain?.size ?: 0) <= 64 && chargedSamples >= 0 && chargedGeometry >= 0 && limits.size == 5)
        require(nodes.map { CoverageNodeSnapshot(it.sourceID, it.nodeID, it.latitude, it.longitude) } == snapshots)
        val studyLimits = CoverageStudyLimits(limits[0], limits[1], limits[2], limits[3], limits[4]).also { it.validate() }
        // The grid's masks and each qualified sample's margin must agree with these inputs.
        val area = cells.mapIndexed { index, saved ->
            val point = requireNotNull(grid.point(index))
            val state = CoverageAreaState.valueOf(saved.state)
            when (point.state) {
                CoverageGridPointState.nearSite -> require(state == CoverageAreaState.nearSite)
                CoverageGridPointState.outsideRadius -> require(state == CoverageAreaState.outsideRadius)
                CoverageGridPointState.eligible -> require(state != CoverageAreaState.nearSite && state != CoverageAreaState.outsideRadius)
            }
            if (state == CoverageAreaState.qualified) {
                val loss = requireNotNull(saved.lossDb)
                val margin = requireNotNull(saved.marginDb)
                require(loss.isFinite() && margin.isFinite() && inputs.budget.reception(loss).adjustedMarginDb == margin)
            } else require(saved.lossDb == null && saved.marginDb == null)
            CoverageAreaCell(state, saved.lossDb, saved.marginDb)
        }
        val run = CoverageNodeRun(snapshots, catalogComplete, 200, grid.radiusMeters.toDouble())
        for (node in nodes) {
            val prediction = when {
                node.marginDb != null -> { require(node.marginDb.isFinite()); CoverageNodePrediction.QualifiedMargin(node.marginDb) }
                else -> CoverageNodePrediction.Unavailable(CoverageNodeReason.valueOf(requireNotNull(node.reason)))
            }
            val snapshot = CoverageNodeSnapshot(node.sourceID, node.nodeID, node.latitude, node.longitude)
            require(run.accept(CoverageNodeEvaluation(run.id, snapshot, node.distanceMeters, prediction)))
        }
        require(run.complete())
        val sources = terrain?.let { saved -> CoverageStudyTerrainSources(saved.map {
            CoverageTerrainDescriptor(it.dataset, it.revision, it.tile, it.compressedSHA256, it.packedBytes, it.side, it.url,
                it.verticalDatum, it.attribution, it.sourceType, it.originURL, it.sourceETag)
        }) }
        val model = if (modelRevision != null && modelScenario != null) CoverageStudyModel(modelRevision, modelScenario) else null
        return CoverageStudyResult(UUID.randomUUID(), inputs, studyLimits,
            CoverageAreaResult(java.util.Collections.unmodifiableList(area), chargedSamples, chargedGeometry, workLimited),
            CoverageNodeBatchResult(run, 0, 0, 0), sources, model)
    }

    companion object {
        private const val FORMAT = "nodescope-android-json"
        private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true; explicitNulls = false }

        /** Captures [result]; null if a node it evaluated has no recorded evaluation. */
        fun of(result: CoverageStudyResult): SavedCoverageCalculation? {
            val run = result.nodes.run
            val nodes = run.nodesToEvaluate.map { node ->
                val evaluation = run.evaluation(node) ?: return null
                val prediction = evaluation.prediction
                Node(node.sourceID, node.nodeID, node.latitude, node.longitude, evaluation.distanceMeters,
                    (prediction as? CoverageNodePrediction.QualifiedMargin)?.margin,
                    (prediction as? CoverageNodePrediction.Unavailable)?.reason?.name)
            }
            return SavedCoverageCalculation(
                inputValues = values(result.inputs),
                cells = result.area.cells.map { Cell(it.state.name, it.lossDb, it.marginDb) },
                chargedSamples = result.area.chargedSamples, chargedGeometry = result.area.chargedGeometry,
                workLimited = result.area.workLimited,
                limits = result.limits.let { listOf(it.areaSamples, it.areaGeometry, it.nodeSamples, it.nodeGeometry, it.nodeModelCalls) },
                modelRevision = result.model?.sourceRevision, modelScenario = result.model?.scenario,
                terrain = result.terrainSources?.descriptors?.map {
                    Terrain(it.dataset, it.revision, it.tile, it.compressedSHA256, it.packedBytes, it.side, it.url,
                        it.verticalDatum, it.attribution, it.sourceType, it.originURL, it.sourceETag)
                },
                nodes = nodes)
        }

        /** Decodes a saved study's result; at most 8 MiB, as the study validates. */
        fun decode(encoded: String): SavedCoverageCalculation {
            val bytes = Base64.getDecoder().decode(encoded)
            require(bytes.size <= 8 * 1024 * 1024)
            return json.decodeFromString(bytes.decodeToString(throwOnInvalidSequence = true))
        }

        /** Every input the result depends on, in the order iOS records them. */
        private fun values(inputs: CoverageStudyInputs): List<Double> {
            val b = inputs.budget
            val g = inputs.grid
            return listOf(inputs.transmitter.latitude, inputs.transmitter.longitude, inputs.frequencyMHz,
                inputs.transmitHeightMeters, inputs.receiveHeightMeters, b.transmitPowerDbm, b.transmitGainDbi,
                b.receiveGainDbi, b.transmitLossDb, b.receiveLossDb, b.sensitivityDbm, b.reserveDb,
                g.radiusMeters.toDouble(), g.spacingMeters.toDouble(), g.minEastMeters.toDouble(),
                g.maxNorthMeters.toDouble(), g.columns.toDouble(), g.rows.toDouble())
        }
    }
}
