import CryptoKit
import Foundation
import Testing
@testable import CoreScopeViewer

private final class TerrainHTTPStub: URLProtocol, @unchecked Sendable {
    static let gzip = Data(base64Encoded: "H4sIAAAAAAAC/2Ng/P+vgYGBBQAP46E6CAAAAA==") ?? Data()
    static let payload = Data((0..<150000).map { UInt8($0 % 251) })
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        guard let url = request.url else { return }
        if url.path == "/stall" { return }
        var body = url.path == "/gzip" ? Self.gzip : Self.payload
        var status = 200
        var headers = [String: String]()
        switch url.path {
        case "/short": body.removeLast()
        case "/long": body.append(0)
        case "/hash": body[0] = 255
        case "/encoding": headers["Content-Encoding"] = "br"
        case "/length": headers["Content-Length"] = "1"
        case "/missing": status = 404
        case "/redirect": status = 302; headers["Location"] = "https://example.invalid/other"
        default: break
        }
        guard let response = HTTPURLResponse(url: url, statusCode: status, httpVersion: "HTTP/1.1", headerFields: headers) else { return }
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: body)
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}

struct CoverageTerrainDownloaderTests {
    private func descriptor(_ path: String) throws -> CoverageTerrainDescriptor {
        try CoverageTerrainDescriptor(dataset: "test", revision: "1", tile: "N00E000",
            compressedSHA256: SHA256.hash(data: TerrainHTTPStub.payload).map { String(format: "%02x", $0) }.joined(),
            packedBytes: Int64(TerrainHTTPStub.payload.count), side: 2,
            url: "https://example.invalid/" + path, verticalDatum: "test", attribution: "test")
    }
    private func workspace() throws -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: false)
        return url
    }
    @Test func verifiedDownloadAndBudgetReuse() async throws {
        let root = try workspace()
        defer { try? FileManager.default.removeItem(at: root) }
        let spec = try descriptor("valid")
        let budget = try CoverageTerrainDownloadBudget(limit: spec.packedBytes * 2 + 1)
        for _ in 0..<2 {
            let result = try await CoverageTerrainDownloader.download(spec, workspace: root, budget: budget,
                protocolClasses: [TerrainHTTPStub.self])
            #expect(try Data(contentsOf: result.fileURL) == TerrainHTTPStub.payload)
            try result.close()
        }
        #expect(budget.receivedBytes == spec.packedBytes * 2)
        do {
            _ = try await CoverageTerrainDownloader.download(spec, workspace: root, budget: budget,
                protocolClasses: [TerrainHTTPStub.self])
            Issue.record("Exhausted budget accepted")
        } catch { #expect(error as? CoverageTerrainDownloadFailure == .downloadLimit) }
        #expect(try FileManager.default.contentsOfDirectory(atPath: root.path).isEmpty)
    }
    @Test func invalidResponsesLeaveNoArtifact() async throws {
        let root = try workspace()
        defer { try? FileManager.default.removeItem(at: root) }
        for path in ["short", "long", "hash", "encoding", "length", "missing", "redirect"] {
            let spec = try descriptor(path)
            let budget = try CoverageTerrainDownloadBudget(limit: spec.packedBytes + 1)
            do {
                _ = try await CoverageTerrainDownloader.download(spec, workspace: root, budget: budget,
                    protocolClasses: [TerrainHTTPStub.self])
                Issue.record("Accepted invalid response: \(path)")
            } catch {
                let expected: CoverageTerrainDownloadFailure = path == "missing" ? .httpStatus(404) :
                    path == "redirect" ? .httpStatus(302) : .corrupt
                #expect(error as? CoverageTerrainDownloadFailure == expected)
            }
            let expectedBytes: Int64 = path == "short" ? spec.packedBytes - 1 :
                path == "long" ? spec.packedBytes + 1 : path == "hash" ? spec.packedBytes : 0
            #expect(budget.receivedBytes == expectedBytes)
            #expect(try FileManager.default.contentsOfDirectory(atPath: root.path).isEmpty)
        }
    }
    @Test func cancellationStopsPendingRequestAndCleansWorkspace() async throws {
        let root = try workspace()
        defer { try? FileManager.default.removeItem(at: root) }
        let spec = try descriptor("stall")
        let budget = try CoverageTerrainDownloadBudget(limit: spec.packedBytes + 1)
        let task = Task {
            try await CoverageTerrainDownloader.download(spec, workspace: root, budget: budget,
                protocolClasses: [TerrainHTTPStub.self])
        }
        try await Task.sleep(for: .milliseconds(50))
        task.cancel()
        do { _ = try await task.value; Issue.record("Cancelled request succeeded") } catch {}
        #expect(budget.receivedBytes == 0)
        #expect(try FileManager.default.contentsOfDirectory(atPath: root.path).isEmpty)
    }
}

struct CoverageTerrainPreparationTests {
    private func descriptor(_ tile: String = "N00E000") throws -> CoverageTerrainDescriptor {
        try CoverageTerrainDescriptor(dataset: "prepare", revision: "1", tile: tile,
            compressedSHA256: SHA256.hash(data: TerrainHTTPStub.gzip).map { String(format: "%02x", $0) }.joined(),
            packedBytes: Int64(TerrainHTTPStub.gzip.count), side: 2,
            url: "https://example.invalid/gzip", verticalDatum: "test", attribution: "test")
    }
    private func workspace() throws -> URL {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: false)
        return root
    }
    private func download(_ spec: CoverageTerrainDescriptor, _ root: URL,
                          _ budget: CoverageTerrainDownloadBudget) async throws -> DownloadedCoverageTerrain {
        try await CoverageTerrainDownloader.download(spec, workspace: root, budget: budget,
            protocolClasses: [TerrainHTTPStub.self])
    }

    @Test func downloadDecodePublishAndOfflineReopen() async throws {
        let root = try workspace()
        defer { try? FileManager.default.removeItem(at: root) }
        let spec = try descriptor()
        let cacheURL = root.appendingPathComponent("cache")
        let cache = try CoverageTerrainDiskCache(root: cacheURL, quota: 100000)
        let budget = try CoverageTerrainDownloadBudget(limit: 1000)
        let readers = try await cache.prepare([spec, spec], budget: budget, download: download)
        #expect(readers.count == 1)
        #expect(try readers[spec.cacheKey]?.read(start: 0, count: 4) == [1, -2, -32768, 4])
        #expect(budget.receivedBytes == spec.packedBytes)
        #expect(cache.inventory.allSatisfy { $0.pinned })
        readers.values.forEach { $0.close() }
        try cache.close()
        let reopened = try CoverageTerrainDiskCache(root: cacheURL, quota: 100000)
        let footprint = try CoverageTerrainFootprint.make(latitude: 0.5, longitude: 0.5, radiusMeters: 1000)
        let offline = try await reopened.prepareMosaic(footprint: footprint, catalog: [spec],
            budget: CoverageTerrainDownloadBudget(limit: 0), offline: true,
            download: { _, _, _ in Issue.record("Offline network access"); throw CoverageTerrainCacheFailure.io })
        #expect(try offline.sample(latitude: 0.5, longitude: 0.5) == .noData)
        offline.close()
        try reopened.close()
        #expect(try FileManager.default.contentsOfDirectory(atPath: root.path) == ["cache"])
    }

    @Test func rejectedPreflightDoesNotDownload() async throws {
        let root = try workspace()
        defer { try? FileManager.default.removeItem(at: root) }
        let spec = try descriptor()
        let peak = 4096 + 8 + 8192 + spec.packedBytes + 8 + 8192
        let cache = try CoverageTerrainDiskCache(root: root.appendingPathComponent("cache"), quota: peak - 1)
        defer { try? cache.close() }
        do {
            _ = try await cache.prepare([spec], budget: CoverageTerrainDownloadBudget(limit: 1000),
                download: { _, _, _ in Issue.record("Quota rejection downloaded"); throw CoverageTerrainCacheFailure.io })
            Issue.record("Insufficient pipeline quota accepted")
        } catch { #expect(error as? CoverageTerrainCacheFailure == .quota) }
        #expect(cache.inventory.isEmpty)
    }

    @Test func partialFailureReleasesPinsAndKeepsCompletedTileReusable() async throws {
        let root = try workspace()
        defer { try? FileManager.default.removeItem(at: root) }
        let first = try descriptor(), second = try descriptor("N00E001")
        let cache = try CoverageTerrainDiskCache(root: root.appendingPathComponent("cache"), quota: 100000)
        var calls = 0
        do {
            _ = try await cache.prepare([first, second], budget: CoverageTerrainDownloadBudget(limit: 1000)) {
                spec, directory, budget in
                calls += 1
                if calls == 2 { throw CoverageTerrainDownloadFailure.httpStatus(404) }
                return try await download(spec, directory, budget)
            }
            Issue.record("Partial set escaped")
        } catch { #expect(error as? CoverageTerrainDownloadFailure == .httpStatus(404)) }
        #expect(calls == 2)
        #expect(cache.inventory.count == 1)
        #expect(cache.inventory.allSatisfy { !$0.pinned })
        let reader = try cache.acquire(first)
        reader.close()
        try cache.close()
        #expect(try FileManager.default.contentsOfDirectory(atPath: root.path) == ["cache"])
    }

    @Test func retryPolicyAndCancellationReleaseOwnership() async throws {
        let root = try workspace()
        defer { try? FileManager.default.removeItem(at: root) }
        let spec = try descriptor()
        let cache = try CoverageTerrainDiskCache(root: root.appendingPathComponent("cache"), quota: 100000)
        var calls = 0
        let readers = try await cache.prepare([spec], budget: CoverageTerrainDownloadBudget(limit: 1000)) {
            spec, directory, budget in
            calls += 1
            if calls == 1 { throw CoverageTerrainDownloadFailure.httpStatus(503) }
            return try await download(spec, directory, budget)
        }
        #expect(calls == 2)
        readers.values.forEach { $0.close() }
        let second = try descriptor("N00E001")
        do {
            _ = try await cache.prepare([spec, second], budget: CoverageTerrainDownloadBudget(limit: 1000),
                download: { _, _, _ in throw CancellationError() })
            Issue.record("Cancellation accepted")
        } catch { #expect(error is CancellationError) }
        #expect(cache.inventory.allSatisfy { !$0.pinned })
        try cache.close()
    }
    @Test func corruptPayloadRepairsOnlineButOfflineAndBudgetFailurePreserveIt() async throws {
        let root = try workspace()
        defer { try? FileManager.default.removeItem(at: root) }
        let spec = try descriptor()
        let cacheRoot = root.appendingPathComponent("cache")
        let cache = try CoverageTerrainDiskCache(root: cacheRoot, quota: 100000)
        let first = try await cache.prepare([spec], budget: CoverageTerrainDownloadBudget(limit: 1000), download: download)
        let terrain = cacheRoot.appendingPathComponent(spec.cacheKey).appendingPathComponent("terrain.hgt")
        let corrupt = Data(repeating: 0, count: 8)
        try corrupt.write(to: terrain)
        do {
            _ = try await cache.prepare([spec], budget: CoverageTerrainDownloadBudget(limit: 1000), download: download)
            Issue.record("Pinned corrupt tile replaced")
        } catch { #expect(error as? CoverageTerrainCacheFailure == .busy) }
        first.values.forEach { $0.close() }
        do {
            _ = try await cache.prepare([spec], budget: CoverageTerrainDownloadBudget(limit: 0), offline: true,
                download: { _, _, _ in Issue.record("Offline repair downloaded"); throw CoverageTerrainCacheFailure.io })
            Issue.record("Corrupt offline tile accepted")
        } catch { #expect(error as? TerrainReadFailure == .corrupt) }
        do {
            _ = try await cache.prepare([spec], budget: CoverageTerrainDownloadBudget(limit: 0), download: download)
            Issue.record("Unfunded repair accepted")
        } catch { #expect(error as? CoverageTerrainDownloadFailure == .downloadLimit) }
        #expect(try Data(contentsOf: terrain) == corrupt)
        let repaired = try await cache.prepare([spec], budget: CoverageTerrainDownloadBudget(limit: 1000), download: download)
        #expect(try repaired[spec.cacheKey]?.read(start: 0, count: 4) == [1, -2, -32768, 4])
        repaired.values.forEach { $0.close() }
        try cache.close()
    }

    @Test func workspaceRecoveryCleansOwnedStagesWithoutFollowingLinks() async throws {
        let root = try workspace()
        defer { try? FileManager.default.removeItem(at: root) }
        let spec = try descriptor()
        let cacheRoot = root.appendingPathComponent("cache")
        let cache = try CoverageTerrainDiskCache(root: cacheRoot, quota: 100000)
        let readers = try await cache.prepare([spec], budget: CoverageTerrainDownloadBudget(limit: 1000), download: download)
        readers.values.forEach { $0.close() }
        try cache.close()
        let sentinel = root.appendingPathComponent("sentinel")
        try Data([7]).write(to: sentinel)
        let work = cacheRoot.appendingPathComponent(".workspace")
        for prefix in [".download-", ".decode-"] {
            let stage = work.appendingPathComponent(prefix + UUID().uuidString.lowercased())
            try FileManager.default.createDirectory(at: stage, withIntermediateDirectories: false)
            try FileManager.default.createSymbolicLink(at: stage.appendingPathComponent("link"), withDestinationURL: sentinel)
        }
        let reopened = try CoverageTerrainDiskCache(root: cacheRoot, quota: 100000)
        #expect(try FileManager.default.contentsOfDirectory(atPath: work.path) == ["owner"])
        #expect(try Data(contentsOf: sentinel) == Data([7]))
        try reopened.close()
        let unknown = work.appendingPathComponent("unrecognized")
        try Data([9]).write(to: unknown)
        #expect(throws: CoverageTerrainCacheFailure.invalidCache) {
            try CoverageTerrainDiskCache(root: cacheRoot, quota: 100000)
        }
        #expect(try Data(contentsOf: unknown) == Data([9]))
    }

}
