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
