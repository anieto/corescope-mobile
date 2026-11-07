import CryptoKit
import Foundation
import Testing
@testable import CoreScopeViewer

private final class DiskCacheFixture {
    let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    var root: URL { folder.appendingPathComponent("cache") }
    var source: URL { folder.appendingPathComponent("source.hgt") }
    let bytes = Data([0x80, 0, 0xff, 0xff, 0, 0, 0, 1, 0, 2, 0, 3, 0, 4, 0, 5, 0, 6])
    var hash: String { SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined() }
    init() throws {
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        try bytes.write(to: source)
    }
    func descriptor(_ tile: String = "N00E000") throws -> CoverageTerrainDescriptor {
        try CoverageTerrainDescriptor(dataset: "test", revision: "r1", tile: tile,
            compressedSHA256: String(repeating: "0", count: 64), packedBytes: 20, side: 3,
            url: "https://example.com/tile", verticalDatum: "EGM96", attribution: "Test")
    }
    func publish(_ cache: CoverageTerrainDiskCache, _ descriptor: CoverageTerrainDescriptor, cancelled: () -> Bool = { false }) throws {
        try cache.publish(descriptor, decodedFile: source, decodedSHA256: hash, fetchedAtMilliseconds: 10, cancelled: cancelled)
    }
    deinit { try? FileManager.default.removeItem(at: folder) }
}

struct CoverageTerrainDiskCacheTests {
    @Test func preparedStudyOwnsTerrainThroughCompletionFailureAndCancellation() async throws {
        let fixture = try DiskCacheFixture()
        let descriptor = try fixture.descriptor()
        let cache = try CoverageTerrainDiskCache(root: fixture.root, quota: 100000)
        try fixture.publish(cache, descriptor)
        try cache.close()
        let grid = try CoverageGrid(radiusMeters: 1000, spacingMeters: 1000,
            minEastMeters: 1000, maxEastMeters: 1000, minNorthMeters: 0, maxNorthMeters: 0)
        let budget = try CoverageLinkBudget(transmitPowerDbm: 22, transmitGainDbi: 3, receiveGainDbi: 2,
            transmitLossDb: 1, receiveLossDb: 1, sensitivityDbm: -130, reserveDb: 5)
        let inputs = try CoverageStudyInputs(transmitter: .init(latitude: 0.25, longitude: 0.25),
            grid: grid, budget: budget, frequencyMHz: 915, transmitHeightMeters: 30, receiveHeightMeters: 1.5)
        let limits = CoverageStudyLimits(areaSamples: 1_000_000, areaGeometry: 1_000_000,
            nodeSamples: 0, nodeGeometry: 0, nodeModelCalls: 0)
        for mode in 0..<3 {
            let id = UUID()
            let root = fixture.root
            let task = Task {
                var stages: [CoveragePreparationStage] = []
                let output = try await CoveragePreparedStudy.evaluate(inputs: inputs, nodes: [],
                    catalogComplete: true, nodeLimit: 1, limits: limits, catalog: [descriptor],
                    samplingHaloMeters: 100, cacheRoot: root, cacheQuota: 100000, downloadLimit: 0,
                    offline: true, executionID: id, progress: { stage in
                        stages.append(stage)
                        if mode == 2 && stage == .calculating { withUnsafeCurrentTask { $0?.cancel() } }
                    }, predictLoss: { _, radio in
                        #expect(radio.frequencyMHz == 915)
                        if mode == 1 { throw CoverageLinkBudgetFailure.invalidInput }
                        return 140
                    })
                #expect(stages == [.validating, .preparingTerrain, .calculating])
                return output
            }
            switch await task.result {
            case .success(let output):
                #expect(mode == 0)
                #expect(output.executionID == id)
                #expect(output.area.cells.first?.state == .qualified)
                #expect(output.terrainSources?.descriptors.map(\.cacheKey) == [descriptor.cacheKey])
            case .failure(let error):
                if mode == 1 { #expect(error as? CoverageLinkBudgetFailure == .invalidInput) }
                else { #expect(mode == 2 && error is CancellationError) }
            }
            // Every path must release all pins and its process lock before returning.
            let reopened = try CoverageTerrainDiskCache(root: fixture.root, quota: 100000)
            #expect(reopened.inventory.allSatisfy { !$0.pinned })
            try reopened.close()
        }
    }

    @Test func publishRestartAndOfflineRead() throws {
        let fixture = try DiskCacheFixture()
        let descriptor = try fixture.descriptor()
        do {
            let cache = try CoverageTerrainDiskCache(root: fixture.root, quota: 100000)
            defer { try? cache.close() }
            try fixture.publish(cache, descriptor)
            #expect(try cache.preflight([descriptor], remainingDownloadBytes: 0).hasAllCachedTiles)
        }
        let cache = try CoverageTerrainDiskCache(root: fixture.root, quota: 100000)
        defer { try? cache.close() }
        let reader = try cache.acquire(descriptor)
        #expect(try reader.read(start: 0, count: 4) == [-32768, -1, 0, 1])
        reader.close()
        try cache.close()
        #expect(throws: CoverageTerrainCacheFailure.closed) { try cache.acquire(descriptor) }
    }

    @Test func pinsProtectReadsReplacementAndClose() throws {
        let fixture = try DiskCacheFixture()
        let a = try fixture.descriptor(), b = try fixture.descriptor("N00E001")
        let cache = try CoverageTerrainDiskCache(root: fixture.root, quota: 100000)
        defer { try? cache.close() }
        try fixture.publish(cache, a); try fixture.publish(cache, b)
        let first = try cache.acquire(a), second = try cache.acquire(a)
        #expect(throws: CoverageTerrainCacheFailure.busy) { try fixture.publish(cache, a) }
        #expect(throws: CoverageTerrainCacheFailure.busy) { try cache.close() }
        try cache.clearUnpinned()
        #expect(cache.inventory.count == 1)
        first.close()
        try cache.clearUnpinned()
        #expect(try second.read(start: 8, count: 1) == [6])
        second.close()
        try cache.clearUnpinned()
        #expect(cache.inventory.isEmpty)
    }

    @Test func evictionRespectsPinsAndPersistedLRU() throws {
        let fixture = try DiskCacheFixture()
        let a = try fixture.descriptor(), b = try fixture.descriptor("N00E001"), c = try fixture.descriptor("N00E002")
        // Exactly two committed payload reservations; the third must evict an unpinned entry.
        let quota: Int64 = 4096 + 2 * (8192 + 18)
        do {
            let cache = try CoverageTerrainDiskCache(root: fixture.root, quota: quota)
            defer { try? cache.close() }
            try fixture.publish(cache, a); try fixture.publish(cache, b)
            let reader = try cache.acquire(a)
            reader.close()
        }
        let cache = try CoverageTerrainDiskCache(root: fixture.root, quota: quota)
        defer { try? cache.close() }
        let pinned = try cache.acquire(a)
        defer { pinned.close() }
        try fixture.publish(cache, c)
        #expect(Set(cache.inventory.map(\.key)) == [a.cacheKey, c.cacheKey])
        #expect(throws: CoverageTerrainCacheFailure.missing) { try cache.acquire(b) }
        let otherPin = try cache.acquire(c)
        #expect(throws: CoverageTerrainCacheFailure.quota) { try fixture.publish(cache, b) }
        otherPin.close()
    }

    @Test func cancelledOrCorruptPublicationLeavesNoCommittedEntry() throws {
        let fixture = try DiskCacheFixture()
        let descriptor = try fixture.descriptor()
        let cache = try CoverageTerrainDiskCache(root: fixture.root, quota: 100000)
        defer { try? cache.close() }
        // Initial check, copy checks, reader hash checks, and final pre-commit check.
        var totalChecks = 0
        try fixture.publish(cache, descriptor, cancelled: { totalChecks += 1; return false })
        try cache.clearUnpinned()
        for checkpoint in 1...totalChecks {
            var checks = 0
            #expect(throws: (any Error).self) {
                try fixture.publish(cache, descriptor, cancelled: { checks += 1; return checks == checkpoint })
            }
            #expect(cache.inventory.isEmpty)
            #expect(try FileManager.default.contentsOfDirectory(atPath: fixture.root.path).allSatisfy { !$0.hasPrefix(".stage-") })
        }
        for bytes in [Data([0]), fixture.bytes + Data([0])] {
            try bytes.write(to: fixture.source)
            #expect(throws: TerrainReadFailure.corrupt) { try fixture.publish(cache, descriptor) }
        }
        try fixture.bytes.write(to: fixture.source)
        #expect(throws: TerrainReadFailure.corrupt) {
            try cache.publish(descriptor, decodedFile: fixture.source, decodedSHA256: String(repeating: "1", count: 64), fetchedAtMilliseconds: 10)
        }
        #expect(cache.inventory.isEmpty)
    }

    @Test func partialAcquisitionReleasesEveryPin() throws {
        let fixture = try DiskCacheFixture()
        let a = try fixture.descriptor(), b = try fixture.descriptor("N00E001")
        let cache = try CoverageTerrainDiskCache(root: fixture.root, quota: 100000)
        defer { try? cache.close() }
        try fixture.publish(cache, a)
        #expect(throws: CoverageTerrainCacheFailure.missing) { try cache.acquireMany([a, b]) }
        #expect(cache.inventory.allSatisfy { !$0.pinned })
        let readers = try cache.acquireMany([a, a])
        #expect(readers.count == 1)
        readers.values.forEach { $0.close() }
        try cache.clearUnpinned()
        #expect(cache.inventory.isEmpty)
    }

    @Test func everyLeaseRechecksPayloadAndFailedOpenReleases() throws {
        let fixture = try DiskCacheFixture()
        let a = try fixture.descriptor()
        let cache = try CoverageTerrainDiskCache(root: fixture.root, quota: 100000)
        defer { try? cache.close() }
        try fixture.publish(cache, a)
        let file = fixture.root.appendingPathComponent(a.cacheKey).appendingPathComponent("terrain.hgt")
        var corrupt = fixture.bytes
        corrupt[0] = 0
        try corrupt.write(to: file)
        #expect(throws: TerrainReadFailure.corrupt) { try cache.acquire(a) }
        #expect(cache.inventory.allSatisfy { !$0.pinned })
        try cache.clearUnpinned()
    }

    @Test func processLockAndUnownedDirectoryProtection() throws {
        let fixture = try DiskCacheFixture()
        let cache = try CoverageTerrainDiskCache(root: fixture.root, quota: 100000)
        #expect(throws: CoverageTerrainCacheFailure.busy) {
            try CoverageTerrainDiskCache(root: fixture.root, quota: 100000)
        }
        try cache.close()
        let other = fixture.folder.appendingPathComponent("unowned")
        try FileManager.default.createDirectory(at: other, withIntermediateDirectories: false)
        let file = other.appendingPathComponent("keep")
        try Data([1]).write(to: file)
        #expect(throws: CoverageTerrainCacheFailure.invalidCache) {
            try CoverageTerrainDiskCache(root: other, quota: 100000)
        }
        #expect(try Data(contentsOf: file) == Data([1]))
    }

    @Test func restartCleansStagesAndRejectsCorruptMetadata() throws {
        let fixture = try DiskCacheFixture()
        let a = try fixture.descriptor()
        do {
            let cache = try CoverageTerrainDiskCache(root: fixture.root, quota: 100000)
            try fixture.publish(cache, a)
            try cache.close()
        }
        let stage = fixture.root.appendingPathComponent(".stage-" + UUID().uuidString.lowercased())
        try FileManager.default.createDirectory(at: stage, withIntermediateDirectories: false)
        try Data([1]).write(to: stage.appendingPathComponent("partial"))
        let cache = try CoverageTerrainDiskCache(root: fixture.root, quota: 100000)
        #expect(!FileManager.default.fileExists(atPath: stage.path))
        try cache.close()
        let metadata = fixture.root.appendingPathComponent(a.cacheKey).appendingPathComponent("metadata.json")
        try Data(repeating: 32, count: 4097).write(to: metadata)
        #expect(throws: CoverageTerrainCacheFailure.invalidCache) {
            try CoverageTerrainDiskCache(root: fixture.root, quota: 100000)
        }
        #expect(FileManager.default.fileExists(atPath: metadata.path))
    }

    @Test func invalidReplacementPreservesCommittedTile() throws {
        let fixture = try DiskCacheFixture()
        let a = try fixture.descriptor()
        let cache = try CoverageTerrainDiskCache(root: fixture.root, quota: 100000)
        defer { try? cache.close() }
        try fixture.publish(cache, a)
        try Data([1]).write(to: fixture.source)
        #expect(throws: TerrainReadFailure.corrupt) { try fixture.publish(cache, a) }
        let reader = try cache.acquire(a)
        #expect(try reader.read(start: 8, count: 1) == [6])
        reader.close()
    }
    @Test func interruptedStageCleanupDoesNotFollowLinks() throws {
        let fixture = try DiskCacheFixture()
        let cache = try CoverageTerrainDiskCache(root: fixture.root, quota: 100000)
        try cache.close()
        let outside = fixture.folder.appendingPathComponent("outside")
        try FileManager.default.createDirectory(at: outside, withIntermediateDirectories: false)
        let sentinel = outside.appendingPathComponent("keep")
        try Data([7]).write(to: sentinel)
        let stage = fixture.root.appendingPathComponent(".stage-" + UUID().uuidString.lowercased())
        try FileManager.default.createDirectory(at: stage, withIntermediateDirectories: false)
        try FileManager.default.createSymbolicLink(at: stage.appendingPathComponent("link"), withDestinationURL: outside)
        let reopened = try CoverageTerrainDiskCache(root: fixture.root, quota: 100000)
        defer { try? reopened.close() }
        #expect(try Data(contentsOf: sentinel) == Data([7]))
        #expect(!FileManager.default.fileExists(atPath: stage.path))
    }

}
