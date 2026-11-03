import Foundation

enum CoverageNodeRunFailure: Error { case invalidCatalog, invalidLimit }
enum CoverageNodeRunPhase: Sendable { case running, completed, cancelled, invalidated }

/// Value-owned node work for one calculation. Not a persistent full RF input snapshot.
struct CoverageNodeRun: Sendable {
    private struct Key: Hashable, Sendable {
        let source: String
        let id: String
        init(_ node: CoverageNodeSnapshot) { source = node.sourceID; id = node.nodeID }
    }
    let id = UUID()
    let radiusMeters: Double
    let nodesToEvaluate: [CoverageNodeSnapshot]
    let catalogComplete: Bool
    let receivedCount: Int
    let excludedCount: Int
    private(set) var phase: CoverageNodeRunPhase = .running
    private let catalog: [Key: CoverageNodeSnapshot]
    private let selected: Set<Key>
    private var evaluations: [Key: CoverageNodeEvaluation] = [:]

    init(nodes: [CoverageNodeSnapshot], catalogComplete: Bool, evaluationLimit: Int, radiusMeters: Double) throws {
        // Defensive storage ceiling, not a qualified RF throughput target. Never silently drop catalog entries.
        guard (1...10_000).contains(evaluationLimit), radiusMeters.isFinite, radiusMeters > 0 else {
            throw CoverageNodeRunFailure.invalidLimit
        }
        guard nodes.count <= 10_000 else { throw CoverageNodeRunFailure.invalidCatalog }
        var catalog: [Key: CoverageNodeSnapshot] = [:]
        for node in nodes {
            guard node.isValid, catalog.updateValue(node, forKey: Key(node)) == nil else {
                throw CoverageNodeRunFailure.invalidCatalog
            }
        }
        self.radiusMeters = radiusMeters
        self.catalogComplete = catalogComplete
        self.receivedCount = nodes.count
        self.nodesToEvaluate = Array(nodes.prefix(evaluationLimit))
        self.excludedCount = nodes.count - nodesToEvaluate.count
        self.catalog = catalog
        self.selected = Set(nodesToEvaluate.map(Key.init))
    }

    /// Accept each selected snapshot once. Late, duplicate and moved-node outputs cannot replace it.
    mutating func accept(_ evaluation: CoverageNodeEvaluation) -> Bool {
        let key = Key(evaluation.node)
        guard phase == .running, evaluation.runID == id, selected.contains(key),
              catalog[key] == evaluation.node, evaluations[key] == nil,
              evaluation.distanceMeters.isFinite, evaluation.distanceMeters >= 0 else { return false }
        evaluations[key] = evaluation
        return true
    }

    @discardableResult mutating func complete() -> Bool {
        guard phase == .running, evaluations.count == nodesToEvaluate.count else { return false }
        phase = .completed
        return true
    }

    mutating func cancel() {
        guard phase == .running else { return }
        evaluations.removeAll()
        phase = .cancelled
    }

    /// Call on any radio/site/radius/receiver change. A new calculation requires a new run.
    mutating func invalidate() {
        phase = .invalidated
    }

    func status(for node: CoverageNodeSnapshot) -> CoverageNodeStatus {
        func status(_ state: CoverageNodeState, _ reason: CoverageNodeReason) -> CoverageNodeStatus {
            CoverageNodeStatus(state: state, reason: reason, adjustedMarginDb: nil)
        }
        guard node.isValid else { return status(.unknown, .invalidInput) }
        let key = Key(node)
        guard let original = catalog[key] else { return status(.notEvaluated, .noResult) }
        guard original == node else { return status(.notEvaluated, .changedNode) }
        guard selected.contains(key) else { return status(.notEvaluated, .processingLimit) }
        if phase == .cancelled { return status(.notEvaluated, .cancelled) }
        if phase == .invalidated {
            return evaluations[key] == nil ? status(.notEvaluated, .noResult) : status(.stale, .changedInputs)
        }
        // Never publish a provisional green/red subset while node evaluation is still running.
        guard phase == .completed else { return status(.notEvaluated, .noResult) }
        return CoverageNodeStatus.resolve(node: node, activeRunID: id, radiusMeters: radiusMeters, evaluation: evaluations[key])
    }
}
