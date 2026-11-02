import Foundation
import Testing
@testable import CoreScopeViewer

private struct DescriptorInput: Decodable {
    var dataset: String
    var revision: String
    var tile: String
    var compressedSha256: String
    var packedBytes: Int64
    var side: Int
    var url: String
    var verticalDatum: String
    var attribution: String
    var sourceType: String
    var originUrl: String

    func make() throws -> CoverageTerrainDescriptor {
        try CoverageTerrainDescriptor(dataset: dataset, revision: revision, tile: tile,
            compressedSHA256: compressedSha256, packedBytes: packedBytes, side: side, url: url,
            verticalDatum: verticalDatum, attribution: attribution, sourceType: sourceType, originURL: originUrl)
    }
}

private func coverageFixture<T: Decodable>(_ name: String, as type: T.Type) throws -> T {
    var root = URL(fileURLWithPath: #filePath).resolvingSymlinksInPath().deletingLastPathComponent()
    while root.path != "/" {
        let file = root.appendingPathComponent("shared/fixtures/coverage/\(name).json")
        if FileManager.default.fileExists(atPath: file.path) {
            let decoder = JSONDecoder()
            decoder.keyDecodingStrategy = .convertFromSnakeCase
            return try decoder.decode(T.self, from: Data(contentsOf: file))
        }
        root.deleteLastPathComponent()
    }
    throw TerrainReadFailure.io
}

private struct IdentityFixtures: Decodable {
    struct Vector: Decodable {
        let name: String
        let spec: DescriptorInput
        let canonicalUtf8Json: String
        let sha256Key: String
    }
    let vectors: [Vector]
}

private struct PreflightFixture: Decodable {
    struct Entry: Decodable { let key: String; let decodedBytes: Int64; let pinned: Bool }
    struct Expected: Decodable {
        let tiles: Int
        let uncachedTiles: Int
        let expectedDownloadBytes: Int64
        let maximumAttemptBytes: Int64
        let decodedPayloadBytes: Int64
        let largestStagingReservation: Int64
        let pinnedSetReservationBytes: Int64
        let worstPinnedStagingBytes: Int64
        let canPreparePinnedSet: Bool
        let fitsDownloadBudget: Bool
        let hasAllCachedTiles: Bool
        let cacheValidationPending: Bool

        var result: CoverageTerrainPreflight {
            CoverageTerrainPreflight(tiles: tiles, uncachedTiles: uncachedTiles,
                expectedDownloadBytes: expectedDownloadBytes, maximumAttemptBytes: maximumAttemptBytes,
                decodedPayloadBytes: decodedPayloadBytes, largestStagingReservation: largestStagingReservation,
                pinnedSetReservationBytes: pinnedSetReservationBytes, worstPinnedStagingBytes: worstPinnedStagingBytes,
                canPreparePinnedSet: canPreparePinnedSet, fitsDownloadBudget: fitsDownloadBudget,
                hasAllCachedTiles: hasAllCachedTiles)
        }
    }
    let name: String
    let requested: [DescriptorInput]
    let inventory: [Entry]
    let quota: Int64
    let remainingDownloadBytes: Int64
    let attempts: Int
    let expected: Expected
}

struct CoverageTerrainDescriptorTests {
    @Test func pipelineAccountsForBothDecodedCopiesAndWorkspace() throws {
        let tile = try #require(coverageFixture("cache-identity", as: IdentityFixtures.self).vectors.first).spec.make()
        let pinned = 4096 + tile.decodedBytes + 8192
        let peak = pinned + tile.packedBytes + tile.decodedBytes + 8192
        for quota in [peak - 1, peak] {
            let result = try CoverageTerrainPipelinePreflight.make(requested: [tile, tile], inventory: [],
                quota: quota, remainingDownloadBytes: 512_000_000)
            #expect(result.peakPipelineBytes == peak)
            #expect(result.fitsPipelineQuota == (quota == peak))
            #expect(result.cache.canPreparePinnedSet)
        }
        let cached = CoverageTerrainInventoryEntry(key: tile.cacheKey, decodedBytes: tile.decodedBytes, pinned: true)
        let offline = try CoverageTerrainPipelinePreflight.make(requested: [tile], inventory: [cached],
            quota: pinned, remainingDownloadBytes: 0)
        #expect(offline.temporaryWorkspaceBytes == 0)
        #expect(offline.peakPipelineBytes == pinned)
        #expect(offline.fitsPipelineQuota)
        let outside = CoverageTerrainInventoryEntry(key: String(repeating: "f", count: 64), decodedBytes: 18, pinned: true)
        let protected = try CoverageTerrainPipelinePreflight.make(requested: [tile], inventory: [outside],
            quota: peak, remainingDownloadBytes: 512_000_000)
        #expect(protected.peakPipelineBytes == peak + 18 + 8192)
        #expect(!protected.fitsPipelineQuota)
    }

    @Test func canonicalKeysMatchIndependentVectors() throws {
        let fixture = try coverageFixture("cache-identity", as: IdentityFixtures.self)
        #expect(fixture.vectors.count == 9)
        for vector in fixture.vectors {
            let descriptor = try vector.spec.make()
            #expect(descriptor.canonicalIdentity == Data(vector.canonicalUtf8Json.utf8), "\(vector.name)")
            #expect(descriptor.cacheKey == vector.sha256Key, "\(vector.name)")
        }
    }

    @Test func completePinnedSetPreflightMatchesRepositoryContract() throws {
        let cases = try coverageFixture("cache-preflight", as: [PreflightFixture].self)
        #expect(cases.count == 13)
        for fixture in cases {
            let result = try CoverageTerrainPreflight.make(requested: fixture.requested.map { try $0.make() },
                inventory: fixture.inventory.map { CoverageTerrainInventoryEntry(key: $0.key, decodedBytes: $0.decodedBytes, pinned: $0.pinned) },
                quota: fixture.quota, remainingDownloadBytes: fixture.remainingDownloadBytes, attempts: fixture.attempts)
            #expect(result == fixture.expected.result, "\(fixture.name)")
            #expect(result.cacheValidationPending == fixture.expected.cacheValidationPending)
        }
    }

    @Test func invalidDescriptorsCannotReachHashOrSizeArithmetic() throws {
        let base = try #require(coverageFixture("cache-identity", as: IdentityFixtures.self).vectors.first).spec
        let mutations: [(inout DescriptorInput) -> Void] = [
            { $0.dataset = "../terrain" }, { $0.revision = "r1\n" }, { $0.tile = "N90E000" },
            { $0.tile = "N00E180" }, { $0.tile = "N0E000" }, { $0.side = Int.max },
            { $0.side = 1 }, { $0.side = 8 }, { $0.packedBytes = Int64.max },
            { $0.packedBytes = 0 }, { $0.compressedSha256 = String(repeating: "A", count: 64) },
            { $0.compressedSha256 += "\n" }, { $0.attribution = "" }, { $0.verticalDatum = "EGM\n96" },
            { $0.url = "http://example.com/tile" }, { $0.url = "https://user:secret@example.com/tile" },
            { $0.url = "https://example.com/tile#fragment" }, { $0.url = "file:///tile" },
            { $0.url = "https://example.com/a b" }, { $0.originUrl = "http://localhost/tile" }
        ]
        for mutate in mutations {
            var input = base
            mutate(&input)
            #expect(throws: CoverageTerrainRepositoryFailure.invalidDescriptor) { try input.make() }
        }
    }

    @Test func sourceAndTransportChangesHaveDifferentIdentitySemantics() throws {
        var input = try #require(coverageFixture("cache-identity", as: IdentityFixtures.self).vectors.first).spec
        let original = try input.make().cacheKey
        input.url = "http://127.0.0.1:8080/replay"
        input.originUrl = "https://origin.example.com/source"
        #expect(try input.make().cacheKey == original)
        input.compressedSha256 = String(repeating: "1", count: 64)
        #expect(try input.make().cacheKey != original)
    }

    @Test func limitsAndContradictoryInventoryFailClosed() throws {
        let descriptor = try #require(coverageFixture("cache-identity", as: IdentityFixtures.self).vectors.first).spec.make()
        for (quota, budget, attempts) in [(Int64.max, Int64(0), 2), (8191, 0, 2), (100000, -1, 2), (100000, 512000001, 2), (100000, 0, 0)] {
            #expect(throws: CoverageTerrainRepositoryFailure.invalidLimits) {
                try CoverageTerrainPreflight.make(requested: [], inventory: [], quota: quota, remainingDownloadBytes: budget, attempts: attempts)
            }
        }
        #expect(throws: CoverageTerrainRepositoryFailure.tooManyTiles) {
            try CoverageTerrainPreflight.make(requested: Array(repeating: descriptor, count: 257), inventory: [], quota: 100000, remainingDownloadBytes: 1000)
        }
        let entry = CoverageTerrainInventoryEntry(key: descriptor.cacheKey, decodedBytes: descriptor.decodedBytes, pinned: true)
        let badInventories = [
            [entry, entry],
            [CoverageTerrainInventoryEntry(key: "bad", decodedBytes: 50, pinned: false)],
            [CoverageTerrainInventoryEntry(key: descriptor.cacheKey, decodedBytes: Int64.max, pinned: true)],
            [CoverageTerrainInventoryEntry(key: descriptor.cacheKey, decodedBytes: 18, pinned: true)]
        ]
        for inventory in badInventories {
            #expect(throws: CoverageTerrainRepositoryFailure.invalidInventory) {
                try CoverageTerrainPreflight.make(requested: [descriptor], inventory: inventory, quota: 100000, remainingDownloadBytes: 1000)
            }
        }
    }
}
