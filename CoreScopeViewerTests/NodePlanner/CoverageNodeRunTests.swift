import Foundation
import Testing
@testable import CoreScopeViewer

struct CoverageNodeRunTests {
    private let first = CoverageNodeSnapshot(sourceID: "source", nodeID: "1", latitude: 29, longitude: -98)
    private let second = CoverageNodeSnapshot(sourceID: "source", nodeID: "2", latitude: 30, longitude: -98)
    private func evaluation(_ run: CoverageNodeRun, _ node: CoverageNodeSnapshot) -> CoverageNodeEvaluation {
        CoverageNodeEvaluation(runID: run.id, node: node, distanceMeters: 500, prediction: .qualifiedMargin(10))
    }

    @Test func completionCapAndInvalidationPreserveSnapshot() throws {
        var run = try CoverageNodeRun(nodes: [first, second], catalogComplete: false, evaluationLimit: 1, radiusMeters: 1000)
        #expect(!run.catalogComplete)
        #expect(run.receivedCount == 2 && run.excludedCount == 1)
        let transition1 = !run.complete()
        #expect(transition1)
        let transition2 = !run.accept(evaluation(run, second))
        #expect(transition2)
        let transition3 = run.accept(evaluation(run, first))
        #expect(transition3)
        let transition4 = !run.accept(evaluation(run, first))
        #expect(transition4)
        #expect(run.status(for: first).state == .notEvaluated)
        let transition5 = run.complete()
        #expect(transition5)
        #expect(run.status(for: first).state == .inCoverage)
        #expect(run.status(for: second).reason == .processingLimit)
        let moved = CoverageNodeSnapshot(sourceID: "source", nodeID: "1", latitude: 31, longitude: -98)
        #expect(run.status(for: moved).reason == .changedNode)
        run.invalidate()
        #expect(run.status(for: first).state == .stale)
        #expect(run.status(for: first).adjustedMarginDb == nil)
        let transition6 = !run.accept(evaluation(run, first))
        #expect(transition6)
    }

    @Test func cancellationAndOldRunResultsCannotPublish() throws {
        var run = try CoverageNodeRun(nodes: [first, second], catalogComplete: true, evaluationLimit: 2, radiusMeters: 1000)
        let old = try CoverageNodeRun(nodes: [first], catalogComplete: true, evaluationLimit: 1, radiusMeters: 1000)
        let transition7 = !run.accept(evaluation(old, first))
        #expect(transition7)
        let transition8 = run.accept(evaluation(run, first))
        #expect(transition8)
        run.cancel()
        let transition9 = !run.accept(evaluation(run, second))
        #expect(transition9)
        let transition10 = !run.complete()
        #expect(transition10)
        #expect(run.status(for: first).reason == .cancelled)
        #expect(run.status(for: first).adjustedMarginDb == nil)
    }

    @Test func emptyCatalogCompletesAndInvalidCatalogsFail() throws {
        var empty = try CoverageNodeRun(nodes: [], catalogComplete: true, evaluationLimit: 1, radiusMeters: 1000)
        let transition11 = empty.complete()
        #expect(transition11)
        #expect(empty.status(for: first).reason == .noResult)
        #expect(throws: CoverageNodeRunFailure.self) {
            try CoverageNodeRun(nodes: [first, first], catalogComplete: true, evaluationLimit: 1, radiusMeters: 1000)
        }
        #expect(throws: CoverageNodeRunFailure.self) {
            try CoverageNodeRun(nodes: [first], catalogComplete: true, evaluationLimit: 0, radiusMeters: 1000)
        }
    }
}
