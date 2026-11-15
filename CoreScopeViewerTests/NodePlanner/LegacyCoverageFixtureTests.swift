import Foundation
import Testing
@testable import CoreScopeViewer

/// Saved-plans v3 legacy inventory: a coverage result written by iOS's 0.10.6 encoder (binary plist),
/// kept as shared/fixtures/saved-plans/legacy/ios-0.10.6-coverage.plist so A1's migration and B's
/// conversion are tested against real bytes. Same inputs as the v3 reference's migrated study.
///
/// The first run records the file from the production encoder and reports that it did; commit it.
/// Later runs decode and restore it with the production code and check every value. Values are
/// compared, not bytes: binary plist key order isn't guaranteed to be stable.
struct LegacyCoverageFixtureTests {
    private static let relativePath = "shared/fixtures/saved-plans/legacy/ios-0.10.6-coverage.plist"

    private var fixtureURL: URL {
        var root = URL(fileURLWithPath: #filePath).resolvingSymlinksInPath().deletingLastPathComponent()
        while root.path != "/" && !FileManager.default.fileExists(atPath: root.appendingPathComponent("shared/fixtures").path) {
            root.deleteLastPathComponent()
        }
        return root.appendingPathComponent(Self.relativePath)
    }

    private let nodes = [
        CoverageNodeSnapshot(sourceID: "analyzer.example", nodeID: "a1b2c3d4", latitude: 29.4282, longitude: -98.4936),
        CoverageNodeSnapshot(sourceID: "analyzer.example", nodeID: "e5f6a7b8", latitude: 29.5, longitude: -98.4),
    ]
    /// North-west first, as the grid indexes cells: corners outside the radius, centre near the site.
    private let states: [CoverageAreaState] = [.outsideRadius, .qualified, .outsideRadius, .qualified, .nearSite,
                                               .notComputed, .outsideRadius, .qualified, .outsideRadius]
    private let losses: [Double?] = [nil, 121.25, nil, 118.0, nil, nil, nil, 126.75, nil]

    private func inputs() throws -> CoverageStudyInputs {
        try CoverageStudyInputs(
            transmitter: CoverageTerrainCoordinate(latitude: 29.4241, longitude: -98.4936),
            grid: CoverageGrid.studyArea(radiusMeters: 1000, spacingMeters: 1000),
            budget: CoverageLinkBudget(transmitPowerDbm: 22, transmitGainDbi: 2.15, receiveGainDbi: 2.15,
                                       transmitLossDb: 1, receiveLossDb: 1, sensitivityDbm: -128, reserveDb: 5),
            frequencyMHz: 910.525, transmitHeightMeters: 6.096, receiveHeightMeters: 1.524)
    }

    private func result() throws -> CoverageStudyResult {
        let inputs = try inputs()
        let cells = try zip(states, losses).map { state, loss in
            guard let loss else { return CoverageAreaCell(state: state, lossDb: nil, marginDb: nil) }
            return CoverageAreaCell(state: state, lossDb: loss, marginDb: try inputs.budget.reception(pathLossDb: loss).adjustedMarginDb)
        }
        var run = try CoverageNodeRun(nodes: nodes, catalogComplete: false, evaluationLimit: 200, radiusMeters: 1000)
        let first = run.accept(CoverageNodeEvaluation(runID: run.id, node: nodes[0], distanceMeters: 456, prediction: .unavailable(.processingLimit)))
        let second = run.accept(CoverageNodeEvaluation(runID: run.id, node: nodes[1], distanceMeters: 12_480, prediction: .unavailable(.outsideRadius)))
        let completed = run.complete()
        #expect(first && second && completed)
        let terrain = try CoverageTerrainDescriptor(dataset: "mapzen-skadi", revision: "direct-v1", tile: "N29W099",
            compressedSHA256: nil, packedBytes: 1_234_567, side: 3601,
            url: "https://elevation-tiles-prod.s3.amazonaws.com/v2/skadi/N29/N29W099.hgt.gz", verticalDatum: "EGM96",
            attribution: "Mapzen Terrain Tiles; source attribution: https://github.com/tilezen/joerd/blob/master/docs/attribution.md",
            sourceType: "mixed-source elevation; surface/bare-earth varies",
            originURL: "https://elevation-tiles-prod.s3.amazonaws.com/v2/skadi/N29/N29W099.hgt.gz",
            sourceETag: "\"0123456789abcdef0123456789abcdef\"")
        return CoverageStudyResult(executionID: UUID(), inputs: inputs,
            limits: CoverageStudyLimits(areaSamples: 120_000_000, areaGeometry: 120_000_000, nodeSamples: 30_000_000,
                                        nodeGeometry: 30_000_000, nodeModelCalls: 1000),
            area: CoverageAreaResult(cells: cells, chargedSamples: 30_100, chargedGeometry: 60_200, workLimited: true),
            nodes: CoverageNodeBatchResult(run: run, chargedTerrainEvaluations: 0, chargedGeometryEvaluations: 0, modelCalls: 0),
            terrainSources: CoverageStudyTerrainSources(descriptors: [terrain]),
            model: CoverageStudyModel(sourceRevision: "a911b6ecd58f8c16ac8ae4becf6bdbba1784e5a9",
                scenario: "P.1812-8; inland; zero clutter; vertical polarization; median atmosphere (45/325); coast distances 500 km; time/location 50%; location variability 0 dB"))
    }

    @Test func legacyPlistRestoresWithTheProductionReader() throws {
        let url = fixtureURL
        if !FileManager.default.fileExists(atPath: url.path) {
            let data = try SavedCoverageCalculation(result()).encoded()
            try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
            try data.write(to: url)
            Issue.record("Recorded \(Self.relativePath) (\(data.count) bytes) from the 0.10.6 encoder. Run again to verify it.")
            return
        }
        let data = try Data(contentsOf: url)
        #expect(data.starts(with: Array("bplist00".utf8)), "a binary property list, as 0.10.6 writes")
        let restored = try SavedCoverageCalculation.decode(data).restore(inputs: inputs(), nodes: nodes, catalogComplete: false)
        let expected = try result()

        #expect(restored.area.cells.map(\.state) == states)
        #expect(restored.area.cells.map(\.lossDb) == losses)
        #expect(restored.area.cells.map { $0.marginDb?.bitPattern } == expected.area.cells.map { $0.marginDb?.bitPattern })
        #expect(restored.area.chargedSamples == 30_100 && restored.area.chargedGeometry == 60_200 && restored.area.workLimited)
        #expect(restored.area.pendingCount == 1)
        #expect(restored.nodes.run.status(for: nodes[0]).reason == .processingLimit)
        #expect(restored.nodes.run.status(for: nodes[1]).reason == .outsideRadius)
        #expect(restored.nodes.run.evaluation(for: nodes[0])?.distanceMeters == 456)
        #expect(restored.model == expected.model)
        #expect(restored.terrainSources?.descriptors.map(\.cacheKey) == expected.terrainSources?.descriptors.map(\.cacheKey))
        #expect(restored.limits.areaSamples == 120_000_000 && restored.limits.nodeModelCalls == 1000)
        #expect(restored.hasPendingWork)

        // The recorded bytes still match what this build would write, value for value.
        let current = try SavedCoverageCalculation.decode(SavedCoverageCalculation(expected).encoded())
            .restore(inputs: inputs(), nodes: nodes, catalogComplete: false)
        #expect(current.area.cells.map(\.state) == restored.area.cells.map(\.state))
        #expect(current.area.cells.map { $0.marginDb?.bitPattern } == restored.area.cells.map { $0.marginDb?.bitPattern })
    }

    /// A different study's inputs must not accept this result (the native check the migration relies on).
    @Test func legacyPlistRefusesOtherInputs() throws {
        let url = fixtureURL
        guard FileManager.default.fileExists(atPath: url.path) else { return }
        let other = try CoverageStudyInputs(
            transmitter: CoverageTerrainCoordinate(latitude: 29.4241, longitude: -98.4936),
            grid: CoverageGrid.studyArea(radiusMeters: 1000, spacingMeters: 1000),
            budget: CoverageLinkBudget(transmitPowerDbm: 20, transmitGainDbi: 2.15, receiveGainDbi: 2.15,
                                       transmitLossDb: 1, receiveLossDb: 1, sensitivityDbm: -128, reserveDb: 5),
            frequencyMHz: 910.525, transmitHeightMeters: 6.096, receiveHeightMeters: 1.524)
        let calculation = try SavedCoverageCalculation.decode(Data(contentsOf: url))
        #expect(throws: (any Error).self) { try calculation.restore(inputs: other, nodes: nodes, catalogComplete: false) }
        #expect(throws: (any Error).self) { try calculation.restore(inputs: inputs(), nodes: Array(nodes.prefix(1)), catalogComplete: false) }
    }
}
