package org.nodescope.android.feature.coverage

import java.util.UUID

enum class CoverageNodeRunPhase { running, completed, cancelled, invalidated }
/** Single-owner run state; caller must serialize access. Not a persistent full RF input snapshot. */
class CoverageNodeRun(nodes: List<CoverageNodeSnapshot>, val catalogComplete: Boolean,
    evaluationLimit: Int, val radiusMeters: Double) {
    private data class Key(val source: String, val id: String) {
        constructor(node: CoverageNodeSnapshot) : this(node.sourceID, node.nodeID)
    }
    val id: UUID = UUID.randomUUID()
    val nodesToEvaluate: List<CoverageNodeSnapshot>
    val receivedCount: Int
    val excludedCount: Int
    var phase = CoverageNodeRunPhase.running
        private set
    private val catalog: Map<Key, CoverageNodeSnapshot>
    private val selected: Set<Key>
    private val evaluations = mutableMapOf<Key, CoverageNodeEvaluation>()
    init {
        // Defensive storage ceiling, not a qualified RF throughput target.
        require(evaluationLimit in 1..10_000 && radiusMeters.isFinite() && radiusMeters > 0)
        require(nodes.size <= 10_000)
        val copy = nodes.toList()
        require(copy.all { it.isValid })
        catalog = copy.associateBy { Key(it) }
        require(catalog.size == copy.size)
        nodesToEvaluate = java.util.Collections.unmodifiableList(copy.take(evaluationLimit))
        receivedCount = copy.size
        excludedCount = receivedCount - nodesToEvaluate.size
        selected = nodesToEvaluate.map { Key(it) }.toSet()
    }
    fun accept(evaluation: CoverageNodeEvaluation): Boolean {
        val key = Key(evaluation.node)
        if (phase != CoverageNodeRunPhase.running || evaluation.runID != id || key !in selected ||
            catalog[key] != evaluation.node || key in evaluations || !evaluation.distanceMeters.isFinite() ||
            evaluation.distanceMeters < 0) return false
        evaluations[key] = evaluation
        return true
    }
    fun complete(): Boolean {
        if (phase != CoverageNodeRunPhase.running || evaluations.size != nodesToEvaluate.size) return false
        phase = CoverageNodeRunPhase.completed
        return true
    }
    fun cancel() {
        if (phase != CoverageNodeRunPhase.running) return
        evaluations.clear()
        phase = CoverageNodeRunPhase.cancelled
    }
    fun invalidate() { phase = CoverageNodeRunPhase.invalidated }
    fun status(node: CoverageNodeSnapshot): CoverageNodeStatus {
        fun status(state: CoverageNodeState, reason: CoverageNodeReason) = CoverageNodeStatus(state, reason, null)
        if (!node.isValid) return status(CoverageNodeState.unknown, CoverageNodeReason.invalidInput)
        val key = Key(node)
        val original = catalog[key] ?: return status(CoverageNodeState.notEvaluated, CoverageNodeReason.noResult)
        if (original != node) return status(CoverageNodeState.notEvaluated, CoverageNodeReason.changedNode)
        if (key !in selected) return status(CoverageNodeState.notEvaluated, CoverageNodeReason.processingLimit)
        if (phase == CoverageNodeRunPhase.cancelled) return status(CoverageNodeState.notEvaluated, CoverageNodeReason.cancelled)
        if (phase == CoverageNodeRunPhase.invalidated) return if (key in evaluations)
            status(CoverageNodeState.stale, CoverageNodeReason.changedInputs) else status(CoverageNodeState.notEvaluated, CoverageNodeReason.noResult)
        if (phase != CoverageNodeRunPhase.completed) return status(CoverageNodeState.notEvaluated, CoverageNodeReason.noResult)
        return CoverageNodeStatus.resolve(node, id, radiusMeters, evaluations[key])
    }
}
