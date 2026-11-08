import CryptoKit
import Foundation
import Testing
@testable import CoreScopeViewer

private struct MosaicFixtures: Decodable {
    struct Tile: Decodable { let south: Int; let west: Int; let side: Int; let samples: [Int16] }
    struct Sample: Decodable {
        let name: String
        let latitude: Double
        let longitude: Double
        let state: String
        let meters: Double?
        let south: Int?
        let west: Int?
    }
    let tiles: [Tile]
    let cases: [Sample]

    static func load() throws -> Self {
        var root = URL(fileURLWithPath: #filePath).resolvingSymlinksInPath().deletingLastPathComponent()
        while root.path != "/" {
            let file = root.appendingPathComponent("shared/fixtures/coverage/terrain-mosaic.json")
            if FileManager.default.fileExists(atPath: file.path) {
                return try JSONDecoder().decode(Self.self, from: Data(contentsOf: file))
            }
            root.deleteLastPathComponent()
        }
        throw TerrainReadFailure.io
    }
}

private final class MosaicFiles {
    let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    var released = 0
    init() throws { try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true) }

    func reader(samples: [Int16], side: Int = 3, digest: String? = nil,
                cancelled: () -> Bool = { false }) throws -> LeasedTerrainReader {
        let url = folder.appendingPathComponent(UUID().uuidString)
        let data = Data(samples.flatMap { value -> [UInt8] in
            let bits = UInt16(bitPattern: value)
            return [UInt8(bits >> 8), UInt8(bits & 255)]
        })
        try data.write(to: url)
        return try LeasedTerrainReader.open(url: url, side: side,
            decodedSHA256: digest ?? SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined(),
            cancelled: cancelled, release: { self.released += 1 })
    }
    deinit { try? FileManager.default.removeItem(at: folder) }
}

struct CoverageTerrainMosaicTests {
    @Test func combinedStudyReservesNodeWorkAndDiscardsCancelledArea() throws {
        let files = try MosaicFiles()
        let reader = try files.reader(samples: Array(repeating: 200, count: 121 * 121), side: 121)
        let mosaic = try CoverageTerrainMosaic(readers: [CoverageTerrainTile(south: 0, west: 0): reader])
        defer { mosaic.close() }
        let origin = CoverageTerrainCoordinate(latitude: 0.5, longitude: 0.5)
        let grid = try CoverageGrid(radiusMeters: 5000, spacingMeters: 1000,
            minEastMeters: 1000, maxEastMeters: 1000, minNorthMeters: 0, maxNorthMeters: 0)
        let budget = try CoverageLinkBudget(transmitPowerDbm: 22, transmitGainDbi: 3, receiveGainDbi: 2,
            transmitLossDb: 1, receiveLossDb: 1, sensitivityDbm: -130, reserveDb: 5)
        let node = CoverageNodeSnapshot(sourceID: "source", nodeID: "node", latitude: 0.5, longitude: 0.52)
        for areaSamples in [337, 1_000_000] {
            let limits = CoverageStudyLimits(areaSamples: areaSamples, areaGeometry: 1_000_000,
                nodeSamples: 1_000_000, nodeGeometry: 1_000_000, nodeModelCalls: 5)
            let result = try CoverageStudy.evaluate(inputs: CoverageStudyInputs(transmitter: origin, grid: grid, budget: budget,
                frequencyMHz: 915, transmitHeightMeters: 30, receiveHeightMeters: 1.5),
                nodes: [node], catalogComplete: true, nodeLimit: 1, limits: limits, mosaic: mosaic) { _, inputs in
                    #expect(inputs.frequencyMHz == 915 && inputs.transmitHeightMeters == 30 && inputs.receiveHeightMeters == 1.5)
                    return 140
                }
            #expect(result.inputs.frequencyMHz == 915)
            #expect(result.area.workLimited == (areaSamples == 337))
            #expect(result.nodes.run.status(for: node).state == .inCoverage)
            #expect(result.area.chargedSamples <= Int64(limits.areaSamples))
            #expect(result.nodes.chargedTerrainEvaluations <= limits.nodeSamples)
        }
        let limits = CoverageStudyLimits(areaSamples: 1_000_000, areaGeometry: 1_000_000,
            nodeSamples: 1_000_000, nodeGeometry: 1_000_000, nodeModelCalls: 5)
        var cancelled = false
        #expect(throws: CoverageTerrainProfileFailure.cancelled) {
            try CoverageStudy.evaluate(inputs: CoverageStudyInputs(transmitter: origin, grid: grid, budget: budget,
                frequencyMHz: 915, transmitHeightMeters: 30, receiveHeightMeters: 1.5),
                nodes: [node], catalogComplete: true, nodeLimit: 1, limits: limits, mosaic: mosaic,
                cancelled: { cancelled }) { profile, _ in
                    if let distance = profile.distancesMeters.last, distance > 1500 { cancelled = true }
                    return 140
                }
        }
        let empty = try CoverageStudy.evaluate(inputs: CoverageStudyInputs(transmitter: origin, grid: grid, budget: budget,
                frequencyMHz: 915, transmitHeightMeters: 30, receiveHeightMeters: 1.5),
            nodes: [], catalogComplete: false, nodeLimit: 1, limits: limits, mosaic: mosaic) { _, inputs in
                    #expect(inputs.frequencyMHz == 915 && inputs.transmitHeightMeters == 30 && inputs.receiveHeightMeters == 1.5)
                    return 140
                }
        #expect(empty.area.cells[0].state == .qualified)
        #expect(empty.nodes.run.receivedCount == 0)
        #expect(files.released == 0)
        #expect(throws: CoverageTerrainProfileFailure.invalidInput) {
            try CoverageStudyLimits(areaSamples: 150_000_000, areaGeometry: 0,
                nodeSamples: 1, nodeGeometry: 0, nodeModelCalls: 0).validate()
        }
    }

    @Test func exactNodesUseFileBackedTerrainWithoutTakingOwnership() throws {
        let files = try MosaicFiles()
        let reader = try files.reader(samples: Array(repeating: -20, count: 121 * 121), side: 121)
        let mosaic = try CoverageTerrainMosaic(readers: [CoverageTerrainTile(south: 0, west: 0): reader])
        defer { mosaic.close() }
        let budget = try CoverageLinkBudget(transmitPowerDbm: 22, transmitGainDbi: 3, receiveGainDbi: 2,
            transmitLossDb: 1, receiveLossDb: 1, sensitivityDbm: -130, reserveDb: 5)
        let node = CoverageNodeSnapshot(sourceID: "source", nodeID: "node", latitude: 0.5, longitude: 0.52)
        let run = UUID()
        var calls = 0
        let result = try CoverageNodeEvaluator.evaluate(runID: run,
            transmitter: CoverageTerrainCoordinate(latitude: 0.5, longitude: 0.5), node: node,
            radiusMeters: 5000, budget: budget, mosaic: mosaic) { profile in
                calls += 1
                #expect(profile.elevationsMeters.allSatisfy { abs($0 + 20) < 1e-9 })
                return 140
            }
        #expect(calls == 3)
        #expect(CoverageNodeStatus.resolve(node: node, activeRunID: run, radiusMeters: 5000, evaluation: result).state == .inCoverage)
        let missing = CoverageNodeSnapshot(sourceID: "source", nodeID: "missing", latitude: 0.5, longitude: 1.01)
        let unavailable = try CoverageNodeEvaluator.evaluate(runID: run,
            transmitter: CoverageTerrainCoordinate(latitude: 0.5, longitude: 0.99), node: missing,
            radiusMeters: 5000, budget: budget, mosaic: mosaic) { _ in
                Issue.record("Missing tile reached the model")
                return 140
            }
        #expect(CoverageNodeStatus.resolve(node: missing, activeRunID: run, radiusMeters: 5000, evaluation: unavailable).reason == .missingTerrain)
        var cancelled = false
        #expect(throws: CoverageTerrainProfileFailure.cancelled) {
            try CoverageNodeEvaluator.evaluate(runID: run,
                transmitter: CoverageTerrainCoordinate(latitude: 0.5, longitude: 0.5), node: node,
                radiusMeters: 5000, budget: budget, mosaic: mosaic, cancelled: { cancelled }) { _ in
                    cancelled = true
                    return 140
                }
        }
        #expect(files.released == 0)
        mosaic.close()
        #expect(files.released == 1)
    }

    @Test func geographicProfileReadsTerrainAndRetainsPins() throws {
        let files = try MosaicFiles()
        let reader = try files.reader(samples: Array(repeating: 200, count: 121 * 121), side: 121)
        let mosaic = try CoverageTerrainMosaic(readers: [CoverageTerrainTile(south: 0, west: 0): reader])
        let path = try CoverageTerrainPath(latitude: 0.5, longitude: 0.5, bearingDegrees: 90, distanceMeters: 2000)
        let result = try mosaic.profile(path: path, spacingMeters: 30)
        #expect(result.profile.elevationsMeters.allSatisfy { abs($0 - 200) < 1e-9 })
        #expect(result.profile.distancesMeters.last == 2000)
        #expect(result.profile.pathCenterLatitude == (try path.point(at: 1000).latitude))
        #expect(result.geometryEvaluations > 0)
        // A controlled model response tests the integration, not physical RF accuracy.
        let converged = try CoverageRFConvergence.evaluate(distanceMeters: path.distanceMeters, maximumLossDb: 152,
            profile: { spacing, samples, geometry in
                try mosaic.profile(path: path, spacingMeters: spacing, sampleLimit: samples, geometryLimit: geometry)
            }, predictLoss: { _ in 100 })
        #expect(converged.state == .qualified)
        #expect(converged.passes == 3)
        #expect(files.released == 0)
        let missing = try CoverageTerrainPath(latitude: 0.5, longitude: 0.999, bearingDegrees: 90, distanceMeters: 2000)
        #expect(throws: CoverageTerrainProfileFailure.missingTerrain) { try mosaic.profile(path: missing, spacingMeters: 30) }
        #expect(files.released == 0)
        mosaic.close()
        #expect(files.released == 1)
    }

    @Test func sharedGeographicFixtures() throws {
        let fixture = try MosaicFixtures.load()
        #expect(fixture.cases.count == 18)
        let files = try MosaicFiles()
        var readers: [CoverageTerrainTile: LeasedTerrainReader] = [:]
        defer { readers.values.forEach { $0.close() } }
        for tile in fixture.tiles {
            readers[try CoverageTerrainTile(south: tile.south, west: tile.west)] =
                try files.reader(samples: tile.samples, side: tile.side)
        }
        let mosaic = try CoverageTerrainMosaic(readers: readers)
        defer { mosaic.close() }
        for sample in fixture.cases {
            let actual = try mosaic.sample(latitude: sample.latitude, longitude: sample.longitude)
            switch sample.state {
            case "elevation":
                #expect(actual == .elevationMeters(try #require(sample.meters)), "\(sample.name)")
            case "nodata":
                #expect(actual == .noData, "\(sample.name)")
            case "missing":
                #expect(actual == .missingTile(try CoverageTerrainTile(
                    south: #require(sample.south), west: #require(sample.west))), "\(sample.name)")
            default: Issue.record("Unknown fixture state")
            }
        }
        #expect(files.released == 0)
        mosaic.close()
        mosaic.close()
        #expect(files.released == fixture.tiles.count)
        #expect(throws: TerrainReadFailure.closed) { try mosaic.sample(latitude: 0, longitude: 0) }
    }

    @Test func invalidCoordinatesAndEmptyMosaic() throws {
        let mosaic = try CoverageTerrainMosaic(readers: [:])
        defer { mosaic.close() }
        for (lat, lon) in [(Double.nan, 0.0), (0, Double.infinity), (91, 0), (-91, 0), (0, 181), (0, -181)] {
            #expect(throws: TerrainReadFailure.invalidRange) { try mosaic.sample(latitude: lat, longitude: lon) }
        }
        #expect(try mosaic.sample(latitude: 0, longitude: 0) == .missingTile(CoverageTerrainTile(south: 0, west: 0)))
        #expect(throws: TerrainReadFailure.cancelled) {
            try mosaic.sample(latitude: 0, longitude: 0, cancelled: { true })
        }
    }

    @Test func cancellationNeverReturnsPartialElevationAndKeepsPins() throws {
        let files = try MosaicFiles()
        let reader = try files.reader(samples: Array(repeating: 20, count: 9))
        let mosaic = try CoverageTerrainMosaic(readers: [CoverageTerrainTile(south: 0, west: 0): reader])
        defer { mosaic.close() }
        for checkpoint in 1...8 {
            var checks = 0
            #expect(throws: TerrainReadFailure.cancelled) {
                try mosaic.sample(latitude: 0.25, longitude: 0.25, cancelled: {
                    checks += 1
                    return checks == checkpoint
                })
            }
            #expect(files.released == 0)
        }
        #expect(try mosaic.sample(latitude: 0.25, longitude: 0.25) == .elevationMeters(20))
    }

    @Test func failedReaderOpenReleasesAndNeverReturnsZeroTerrain() throws {
        let files = try MosaicFiles()
        #expect(throws: TerrainReadFailure.corrupt) {
            try files.reader(samples: Array(repeating: 1, count: 9), digest: String(repeating: "0", count: 64))
        }
        #expect(throws: TerrainReadFailure.corrupt) { try files.reader(samples: [1, 2]) }
        #expect(throws: TerrainReadFailure.invalidDescriptor) { try files.reader(samples: [1], side: Int.max) }
        #expect(throws: TerrainReadFailure.cancelled) {
            try files.reader(samples: Array(repeating: 1, count: 9), cancelled: { true })
        }
        #expect(files.released == 4)
    }

    @Test func duplicateReaderOwnershipIsRejectedAndReleasedOnce() throws {
        let files = try MosaicFiles()
        let reader = try files.reader(samples: Array(repeating: 1, count: 9))
        #expect(throws: TerrainReadFailure.invalidDescriptor) {
            try CoverageTerrainMosaic(readers: [
                CoverageTerrainTile(south: 0, west: 0): reader,
                CoverageTerrainTile(south: 0, west: 1): reader
            ])
        }
        #expect(files.released == 1)
    }

    @Test func readerCorruptionIsFailureNotMissingTerrain() throws {
        let files = try MosaicFiles()
        let reader = try files.reader(samples: Array(repeating: 1, count: 9))
        let mosaic = try CoverageTerrainMosaic(readers: [CoverageTerrainTile(south: 0, west: 0): reader])
        defer { mosaic.close() }
        let file = try #require(FileManager.default.contentsOfDirectory(at: files.folder, includingPropertiesForKeys: nil).first)
        let writer = try FileHandle(forWritingTo: file)
        try writer.truncate(atOffset: 2)
        try writer.close()
        #expect(throws: TerrainReadFailure.corrupt) { try mosaic.sample(latitude: 0.25, longitude: 0.25) }
        #expect(files.released == 0)
    }
    @Test func boundedReaderAndMosaicOwnership() throws {
        let files = try MosaicFiles()
        let reader = try files.reader(samples: [-32768, -32767, -23, -1, 0, 1, 256, 32767, 17])
        #expect(try reader.read(start: 2, count: 4) == [-23, -1, 0, 1])
        for (start, count) in [(-1, 1), (0, 0), (9, 1), (8, 2), (0, 32769), (Int.max, 1), (0, Int.max)] {
            #expect(throws: TerrainReadFailure.invalidRange) { try reader.read(start: start, count: count) }
        }
        reader.close()
        #expect(files.released == 1)
        var readers: [CoverageTerrainTile: LeasedTerrainReader] = [:]
        defer { readers.values.forEach { $0.close() } }
        for index in 0...256 {
            readers[try CoverageTerrainTile(south: index / 180, west: index % 180)] =
                try files.reader(samples: Array(repeating: 1, count: 9))
        }
        #expect(throws: TerrainReadFailure.invalidDescriptor) { try CoverageTerrainMosaic(readers: readers) }
        #expect(files.released == 258)
    }

}
