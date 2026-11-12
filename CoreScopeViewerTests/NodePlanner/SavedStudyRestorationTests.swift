import Foundation
import Testing
@testable import CoreScopeViewer

private actor SavedStudyElevationProbe: NodePlannerElevationProviding {
    var requests = 0
    func elevations(for coordinates: [NodePlannerCoordinate]) async throws -> NodePlannerTerrainProfile {
        requests += 1
        throw NodePlannerElevationError.unavailable
    }
}

@MainActor
struct SavedStudyRestorationTests {
    @Test func linkRestoresBothEndpointsWithoutRequestingTerrain() async throws {
        let probe = SavedStudyElevationProbe()
        let original = LinkProfileModel(elevationProvider: probe)
        original.latitudeA = 50.5
        original.longitudeA = -115.5
        original.latitudeB = 50.51
        original.longitudeB = -115.52
        original.nameA = "Saved ridge"
        original.heightA = 6.096
        original.heightB = 1.5
        original.gainADB = 4
        original.gainBDB = 2
        original.lossADB = 0.5
        original.lossBDB = 1.25
        original.frequencyMHz = 869.618
        original.transmitPowerDBm = 14
        original.receiverSensitivityDBm = -130
        original.refractionK = 1.2
        original.sampleCount = 50
        let snapshot = try #require(original.savedSnapshot())
        let reopened = LinkProfileModel(elevationProvider: probe)
        reopened.restore(snapshot)
        #expect(reopened.savedSnapshot()?.linkProfile == snapshot.linkProfile)
        #expect(reopened.savedSnapshot()?.id == snapshot.id)
        #expect(reopened.result == nil && !reopened.isAnalyzing)
        reopened.configurationChanged()
        try await Task.sleep(for: .milliseconds(750))
        #expect(await probe.requests == 0)
        #expect(reopened.awaitsExplicitCalculation)
        reopened.analyze()
        for _ in 0..<100 {
            if await probe.requests > 0 { break }
            try await Task.sleep(for: .milliseconds(10))
        }
        #expect(await probe.requests == 1)
    }

    @Test func coverageResultSurvivesDiskRoundTripAndRendersOffline() async throws {
        let original = EstimatedCoverageModel(distanceUnit: .metric)
        original.latitude = 29.38
        original.longitude = -98.37
        original.radiusMeters = 1000
        original.spacingMeters = 1000
        let inputs = try original.inputs()
        var run = try CoverageNodeRun(nodes: [], catalogComplete: false, evaluationLimit: 200, radiusMeters: 1000)
        let completed = run.complete()
        #expect(completed)
        let cells = try (0..<inputs.grid.count).map { index -> CoverageAreaCell in
            let point = try #require(inputs.grid.point(at: index))
            switch point.state {
            case .nearSite: return .init(state: .nearSite, lossDb: nil, marginDb: nil)
            case .outsideRadius: return .init(state: .outsideRadius, lossDb: nil, marginDb: nil)
            case .eligible:
                if index == 1 { return .init(state: .notComputed, lossDb: nil, marginDb: nil) }
                return .init(state: .qualified, lossDb: 100,
                    marginDb: try inputs.budget.reception(pathLossDb: 100).adjustedMarginDb)
            }
        }
        let result = CoverageStudyResult(executionID: UUID(), inputs: inputs,
            limits: .init(areaSamples: 120_000_000, areaGeometry: 120_000_000,
                nodeSamples: 30_000_000, nodeGeometry: 30_000_000, nodeModelCalls: 1000),
            area: .init(cells: cells, chargedSamples: 100, chargedGeometry: 100, workLimited: true),
            nodes: .init(run: run, chargedTerrainEvaluations: 0, chargedGeometryEvaluations: 0, modelCalls: 0))
        var saved = try #require(original.savedSnapshot())
        saved.coverage?.calculatedResult = try SavedCoverageCalculation(result).encoded()
        let document = DeploymentPlanDocument(schemaVersion: 2, plans: [], studies: [saved])
        let decoded = try DeploymentPlanCodec.decode(DeploymentPlanCodec.encode(document))
        let reopened = EstimatedCoverageModel(distanceUnit: .imperial)
        reopened.restore(try #require(decoded.studies.first))
        let restored = try #require(reopened.activeResult)
        #expect(restored.area.cells.map(\.marginDb) == result.area.cells.map(\.marginDb))
        #expect(restored.area.pendingCount == 1)
        #expect(!reopened.isBusy)
        await reopened.renderOverlay()
        #expect(reopened.layer != nil)
        let resaved = try #require(reopened.savedSnapshot()?.coverage?.calculatedResult)
        #expect(try SavedCoverageCalculation.decode(resaved).area.pendingCount == 1)
        reopened.transmitPowerDbm += 1
        #expect(reopened.activeResult == nil)
        #expect(reopened.savedSnapshot()?.coverage?.calculatedResult == nil)
        saved.coverage?.calculatedResult = Data([0, 1, 2])
        reopened.restore(saved)
        #expect(reopened.activeResult == nil)
    }

    @Test func coverageRestoresFrozenNodesAndEveryInputWithoutRunning() throws {
        let original = EstimatedCoverageModel(distanceUnit: .imperial)
        original.latitude = 50.5
        original.longitude = -115.5
        original.transmitterName = "Saved ridge"
        original.transmitHeightMeters = 6.096
        original.receiveHeightMeters = 2
        original.transmitGainDbi = 4
        original.receiveGainDbi = 3
        original.transmitLossDb = 0.5
        original.receiveLossDb = 1.5
        original.frequencyMHz = 869.618
        original.transmitPowerDbm = 14
        original.sensitivityDbm = -130
        original.reserveDb = 7
        original.spacingMeters = 2000
        original.setCatalog([MeshNode(publicKey: "saved-node", name: "Old name", role: "repeater",
            lat: 50.52, lon: -115.51, lastSeen: Date(timeIntervalSince1970: 1_700_000_000),
            firstSeen: nil, advertCount: nil, hashSize: nil, hashSizeInconsistent: nil,
            lastHeard: nil, defaultScope: nil)], sourceID: "original-source")
        let snapshot = try #require(original.savedSnapshot())
        let reopened = EstimatedCoverageModel(distanceUnit: .metric)
        reopened.restore(snapshot)
        #expect(reopened.savedSnapshot()?.coverage == snapshot.coverage)
        #expect(reopened.savedSnapshot()?.id == snapshot.id)
        #expect(reopened.usesSavedCatalog)
        #expect(reopened.knownNodes.first?.name == "Old name")
        #expect(reopened.nodeSnapshots.first?.sourceID == "original-source")
        #expect(reopened.activeResult == nil && !reopened.isBusy)
        #expect(!reopened.acceptsScenario)
        reopened.latitude = 50.6
        reopened.refreshKnownNodes()
        #expect(reopened.nodeSnapshots == original.nodeSnapshots)
    }
}
