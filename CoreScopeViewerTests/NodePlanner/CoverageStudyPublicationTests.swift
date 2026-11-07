import Foundation
import Testing
@testable import CoreScopeViewer

@MainActor
struct CoverageStudyPublicationTests {
    private func result(_ id: UUID, completed: Bool = true, cells: Bool = true) throws -> CoverageStudyResult {
        let grid = try CoverageGrid(radiusMeters: 1000, spacingMeters: 1000,
            minEastMeters: 0, maxEastMeters: 0, minNorthMeters: 0, maxNorthMeters: 0)
        let budget = try CoverageLinkBudget(transmitPowerDbm: 22, transmitGainDbi: 3, receiveGainDbi: 2,
            transmitLossDb: 1, receiveLossDb: 1, sensitivityDbm: -130, reserveDb: 5)
        var run = try CoverageNodeRun(nodes: [], catalogComplete: true, evaluationLimit: 1, radiusMeters: 1000)
        if completed { run.complete() }
        return try CoverageStudyResult(executionID: id, inputs: CoverageStudyInputs(transmitter: CoverageTerrainCoordinate(latitude: 0, longitude: 0),
            grid: grid, budget: budget, frequencyMHz: 915, transmitHeightMeters: 30, receiveHeightMeters: 1.5),
            limits: CoverageStudyLimits(areaSamples: 0, areaGeometry: 0, nodeSamples: 0, nodeGeometry: 0, nodeModelCalls: 0),
            area: CoverageAreaResult(cells: cells ? [CoverageAreaCell(state: .nearSite, lossDb: nil, marginDb: nil)] : [],
                chargedSamples: 0, chargedGeometry: 0, workLimited: false),
            nodes: CoverageNodeBatchResult(run: run, chargedTerrainEvaluations: 0, chargedGeometryEvaluations: 0, modelCalls: 0))
    }
    @Test func supersededCallbacksCannotReplaceActiveOrCompletedStudy() throws {
        let gate = CoverageStudyPublication()
        let old = gate.begin()
        let current = gate.begin()
        #expect(!gate.publish(try result(old)))
        #expect(!gate.cancel(old))
        #expect(!gate.fail(old))
        #expect(gate.phase == .running && gate.result == nil)
        #expect(gate.publish(try result(current)))
        #expect(!gate.publish(try result(current)))
        #expect(!gate.fail(current))
        #expect(gate.result?.executionID == current)
        gate.invalidate()
        #expect(gate.phase == .stale && gate.result?.executionID == current)
        #expect(!gate.publish(try result(current)))
        _ = gate.begin()
        #expect(gate.result == nil)
    }
    @Test func cancellationInvalidationAndMalformedResultsCannotPublish() throws {
        let gate = CoverageStudyPublication()
        let cancelled = gate.begin()
        #expect(gate.cancel(cancelled))
        #expect(!gate.publish(try result(cancelled)))
        #expect(gate.phase == .cancelled)
        let invalidated = gate.begin()
        gate.invalidate()
        #expect(!gate.publish(try result(invalidated)))
        let current = gate.begin()
        #expect(!gate.publish(try result(current, completed: false)))
        #expect(!gate.publish(try result(current, cells: false)))
        #expect(gate.fail(current))
        #expect(gate.phase == .failed && gate.result == nil)
    }
}
