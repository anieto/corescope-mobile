import CryptoKit
import Foundation
import Testing
@testable import CoreScopeViewer

private struct GzipFixture: Decodable {
    let name: String
    let side: Int
    let valid: Bool
    let packedBytes: Int64
    let sourceSha256: String
    let decodedSha256: String
    let packedBase64: String

    func descriptor() throws -> CoverageTerrainDescriptor {
        try CoverageTerrainDescriptor(dataset: "gzip-test", revision: "r1", tile: "N00E000",
            compressedSHA256: sourceSha256, packedBytes: packedBytes, side: side,
            url: "https://example.com/tile.gz", verticalDatum: "EGM96", attribution: "Synthetic")
    }
    static func load() throws -> [Self] {
        var root = URL(fileURLWithPath: #filePath).resolvingSymlinksInPath().deletingLastPathComponent()
        while root.path != "/" {
            let file = root.appendingPathComponent("shared/fixtures/coverage/terrain-gzip.json")
            if FileManager.default.fileExists(atPath: file.path) {
                let decoder = JSONDecoder()
                decoder.keyDecodingStrategy = .convertFromSnakeCase
                return try decoder.decode([Self].self, from: Data(contentsOf: file))
            }
            root.deleteLastPathComponent()
        }
        throw CoverageTerrainDecodeFailure.io
    }
}

private final class DecodeWorkspace {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    var packed: URL { root.appendingPathComponent("input.gz") }
    init() throws { try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true) }
    func write(_ fixture: GzipFixture) throws {
        try #require(Data(base64Encoded: fixture.packedBase64)).write(to: packed)
    }
    var decodedDirectories: [String] {
        get throws { try FileManager.default.contentsOfDirectory(atPath: root.path).filter { $0.hasPrefix(".decode-") } }
    }
    deinit { try? FileManager.default.removeItem(at: root) }
}

struct CoverageTerrainDecoderTests {
    @Test func sharedValidAndCorruptGzipFixtures() throws {
        let fixtures = try GzipFixture.load()
        #expect(fixtures.count == 22)
        let workspace = try DecodeWorkspace()
        for fixture in fixtures {
            try workspace.write(fixture)
            let descriptor = try fixture.descriptor()
            if fixture.valid {
                let decoded = try CoverageTerrainDecoder.decode(descriptor, packedFile: workspace.packed, workspace: workspace.root)
                #expect(decoded.sha256 == fixture.decodedSha256, "\(fixture.name)")
                let reader = try LeasedTerrainReader.open(url: decoded.fileURL, side: fixture.side,
                    decodedSHA256: fixture.decodedSha256, release: {})
                reader.close()
                try decoded.close()
                try decoded.close()
            } else {
                #expect(throws: CoverageTerrainDecodeFailure.corrupt, "\(fixture.name)") {
                    try CoverageTerrainDecoder.decode(descriptor, packedFile: workspace.packed, workspace: workspace.root)
                }
            }
            #expect(try workspace.decodedDirectories.isEmpty, "\(fixture.name)")
        }
    }

    @Test func cancellationAtEveryCheckpointRemovesDecodedOutput() throws {
        let fixture = try #require(GzipFixture.load().first { $0.name == "multiple_input_output_chunks" })
        let workspace = try DecodeWorkspace()
        try workspace.write(fixture)
        let descriptor = try fixture.descriptor()
        var total = 0
        let success = try CoverageTerrainDecoder.decode(descriptor, packedFile: workspace.packed, workspace: workspace.root,
            cancelled: { total += 1; return false })
        try success.close()
        #expect(total > 5)
        for checkpoint in 1...total {
            var count = 0
            #expect(throws: CoverageTerrainDecodeFailure.cancelled) {
                try CoverageTerrainDecoder.decode(descriptor, packedFile: workspace.packed, workspace: workspace.root,
                    cancelled: { count += 1; return count == checkpoint })
            }
            #expect(try workspace.decodedDirectories.isEmpty)
        }
    }

    @Test func decodePublishReopenAndSignedRead() throws {
        let fixture = try #require(GzipFixture.load().first { $0.name == "signed_and_nodata" })
        let workspace = try DecodeWorkspace()
        try workspace.write(fixture)
        let descriptor = try fixture.descriptor()
        let cacheURL = workspace.root.appendingPathComponent("cache")
        let cache = try CoverageTerrainDiskCache(root: cacheURL, quota: 100000)
        let decoded = try CoverageTerrainDecoder.decode(descriptor, packedFile: workspace.packed, workspace: workspace.root)
        try cache.publish(descriptor, decodedFile: decoded.fileURL, decodedSHA256: decoded.sha256, fetchedAtMilliseconds: 1)
        try decoded.close()
        try cache.close()
        let reopened = try CoverageTerrainDiskCache(root: cacheURL, quota: 100000)
        defer { try? reopened.close() }
        let reader = try reopened.acquire(descriptor)
        #expect(try reader.read(start: 0, count: 4) == [-32768, -1, 0, 1])
        reader.close()
    }

    @Test func filesystemFailureDoesNotDeleteUnownedFiles() throws {
        let fixture = try #require(GzipFixture.load().first)
        let workspace = try DecodeWorkspace()
        try workspace.write(fixture)
        let sentinel = workspace.root.appendingPathComponent("not-a-directory")
        try Data([7]).write(to: sentinel)
        #expect(throws: (any Error).self) {
            try CoverageTerrainDecoder.decode(fixture.descriptor(), packedFile: workspace.packed, workspace: sentinel)
        }
        #expect(try Data(contentsOf: sentinel) == Data([7]))
        #expect(try workspace.decodedDirectories.isEmpty)
    }
}
