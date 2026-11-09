import CryptoKit
import Foundation
import Testing
@testable import CoreScopeViewer

private final class SkadiHTTPStub: URLProtocol, @unchecked Sendable {
    static let gzip = Data(base64Encoded: "H4sIAAAAAAAC/2Ng/P+vgYGBBQAP46E6CAAAAA==") ?? Data()
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        guard let url = request.url else { return }
        if url.path.contains("N04") { return }
        var status = 200
        if url.path.contains("N01") && url.path.hasPrefix("/v2/") { status = 404 }
        if url.path.contains("N02") { status = 404 }
        if url.path.contains("N03") { status = 503 }
        let head = request.httpMethod == "HEAD"
        if !head && !(["\"fixture\"", "\"wrong-response\""]).contains(request.value(forHTTPHeaderField: "If-Match") ?? "") { status = 412 }
        let headers = ["Content-Length": String(Self.gzip.count), "ETag": "\"fixture\""]
        guard let response = HTTPURLResponse(url: url, statusCode: status, httpVersion: "HTTP/1.1", headerFields: headers) else { return }
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        if !head { client?.urlProtocol(self, didLoad: Self.gzip) }
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}

struct CoverageSkadiProviderTests {
    @Test func metadataIsVersionedAndUnpinned() throws {
        let tile = try CoverageTerrainTile(south: -1, west: -180)
        let url = try CoverageSkadiProvider.endpoint(tile: tile)
        #expect(url.absoluteString.hasSuffix("/v2/skadi/S01/S01W180.hgt.gz"))
        let source = try CoverageSkadiProvider.descriptor(tile: tile, endpoint: url, status: 200, length: "28", etag: "\"first\"", encoding: nil)
        let changed = try CoverageSkadiProvider.descriptor(tile: tile, endpoint: url, status: 200, length: "28", etag: "\"second\"", encoding: nil)
        #expect(source.compressedSHA256 == nil && source.sourceETag == "\"first\"")
        #expect(source.cacheKey != changed.cacheKey && source.side == 3601)
        for etag in [nil, "", "weak", "W/\"weak\"", "\"bad\r\nheader\""] as [String?] {
            #expect(throws: (any Error).self) {
                try CoverageSkadiProvider.descriptor(tile: tile, endpoint: url, status: 200, length: "28", etag: etag, encoding: nil)
            }
        }
        for length in ["", "-1", "0", "40000001", "1e3", "9999999999999999999999"] {
            #expect(throws: (any Error).self) {
                try CoverageSkadiProvider.descriptor(tile: tile, endpoint: url, status: 200, length: length, etag: "\"first\"", encoding: nil)
            }
        }
    }

    @Test func discoveryFallbackMissingAndCancellation() async throws {
        for latitude in [0.5, 1.5] {
            let footprint = try CoverageTerrainFootprint.make(latitude: latitude, longitude: 0.5, radiusMeters: 1000)
            let catalog = try await CoverageSkadiProvider.discover(footprint: footprint, protocolClasses: [SkadiHTTPStub.self])
            #expect(catalog.count == 1)
            #expect(catalog[0].url.contains("/v2/") == (latitude < 1))
        }
        for (latitude, status) in [(2.5, 404), (3.5, 503)] {
            let footprint = try CoverageTerrainFootprint.make(latitude: latitude, longitude: 0.5, radiusMeters: 1000)
            await #expect(throws: CoverageTerrainDownloadFailure.httpStatus(status)) {
                try await CoverageSkadiProvider.discover(footprint: footprint, protocolClasses: [SkadiHTTPStub.self])
            }
        }
        let footprint = try CoverageTerrainFootprint.make(latitude: 4.5, longitude: 0.5, radiusMeters: 1000)
        let task = Task { try await CoverageSkadiProvider.discover(footprint: footprint, protocolClasses: [SkadiHTTPStub.self]) }
        try await Task.sleep(for: .milliseconds(50))
        task.cancel()
        do { _ = try await task.value; Issue.record("Cancelled discovery completed") } catch {}
    }

    @Test func conditionalDownloadAndLocalDigestProtectDecode() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let url = try CoverageSkadiProvider.endpoint(tile: CoverageTerrainTile(south: 0, west: 0)).absoluteString
        func source(_ etag: String) throws -> CoverageTerrainDescriptor {
            try CoverageTerrainDescriptor(dataset: "test", revision: "1", tile: "N00E000",
                compressedSHA256: nil, packedBytes: Int64(SkadiHTTPStub.gzip.count), side: 2, url: url,
                verticalDatum: "EGM96", attribution: "test", originURL: url, sourceETag: etag)
        }
        let descriptor = try source("\"fixture\"")
        let downloaded = try await CoverageTerrainDownloader.download(descriptor, workspace: root,
            budget: CoverageTerrainDownloadBudget(limit: 1000), protocolClasses: [SkadiHTTPStub.self])
        defer { try? downloaded.close() }
        #expect(downloaded.sha256 == SHA256.hash(data: SkadiHTTPStub.gzip).map { String(format: "%02x", $0) }.joined())
        let decoded = try CoverageTerrainDecoder.decode(descriptor, packedFile: downloaded.fileURL, workspace: root,
            downloadedSHA256: downloaded.sha256)
        defer { try? decoded.close() }
        #expect(throws: CoverageTerrainDecodeFailure.corrupt) {
            try CoverageTerrainDecoder.decode(descriptor, packedFile: downloaded.fileURL, workspace: root, downloadedSHA256: String(repeating: "0", count: 64))
        }
        #expect(throws: CoverageTerrainDecodeFailure.corrupt) {
            try CoverageTerrainDecoder.decode(descriptor, packedFile: downloaded.fileURL, workspace: root)
        }
        await #expect(throws: CoverageTerrainDownloadFailure.httpStatus(412)) {
            try await CoverageTerrainDownloader.download(source("\"old\""), workspace: root,
                budget: CoverageTerrainDownloadBudget(limit: 1000), protocolClasses: [SkadiHTTPStub.self])
        }
        await #expect(throws: CoverageTerrainDownloadFailure.corrupt) {
            try await CoverageTerrainDownloader.download(source("\"wrong-response\""), workspace: root,
                budget: CoverageTerrainDownloadBudget(limit: 1000), protocolClasses: [SkadiHTTPStub.self])
        }
        let cache = try CoverageTerrainDiskCache(root: root.appendingPathComponent("cache"), quota: 100000)
        try cache.publish(descriptor, decodedFile: decoded.fileURL, decodedSHA256: decoded.sha256,
            fetchedAtMilliseconds: 1, downloadedSHA256: downloaded.sha256)
        try cache.close()
        let reopened = try CoverageTerrainDiskCache(root: root.appendingPathComponent("cache"), quota: 100000)
        let reader = try reopened.acquire(descriptor)
        #expect(try reader.read(start: 0, count: 4) == [1, -2, -32768, 4])
        reader.close()
        try reopened.close()
    }

    @Test(.enabled(if: ProcessInfo.processInfo.environment["NODESCOPE_SKADI_LIVE"] == "1"))
    func liveAWSDiscoveryPreparationAndOfflineReuse() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let footprint = try CoverageTerrainFootprint.make(latitude: 50.5, longitude: -115.5, radiusMeters: 1000)
        let sources = try await CoverageSkadiProvider.discover(footprint: footprint)
        #expect(sources.count == 1 && sources[0].tile == "N50W116")
        let cache = try CoverageTerrainDiskCache(root: root, quota: 100_000_000)
        let budget = try CoverageTerrainDownloadBudget(limit: 40_000_001)
        let mosaic = try await cache.prepareMosaic(footprint: footprint, catalog: sources, budget: budget)
        mosaic.close()
        #expect(budget.receivedBytes == sources[0].packedBytes)
        try cache.close()
        let reopened = try CoverageTerrainDiskCache(root: root, quota: 100_000_000)
        let offline = try await reopened.prepareMosaic(footprint: footprint, catalog: sources,
            budget: CoverageTerrainDownloadBudget(limit: 0), offline: true)
        offline.close()
        try reopened.close()
        let grid = try CoverageGrid(radiusMeters: 1000, spacingMeters: 1000,
            minEastMeters: -1000, maxEastMeters: 1000, minNorthMeters: -1000, maxNorthMeters: 1000)
        let inputs = try CoverageStudyInputs(transmitter: .init(latitude: 50.5, longitude: -115.5), grid: grid,
            budget: CoverageLinkBudget(transmitPowerDbm: 22, transmitGainDbi: 2, receiveGainDbi: 2,
                transmitLossDb: 1, receiveLossDb: 1, sensitivityDbm: -130, reserveDb: 5),
            frequencyMHz: 915, transmitHeightMeters: 10, receiveHeightMeters: 1.5)
        let study = try await CoveragePreparedStudy.evaluate(inputs: inputs, nodes: [], catalogComplete: false,
            nodeLimit: 200, limits: CoverageStudyLimits(areaSamples: 2_000_000, areaGeometry: 2_000_000,
                nodeSamples: 0, nodeGeometry: 0, nodeModelCalls: 0), catalog: sources, samplingHaloMeters: 100,
            cacheRoot: root, cacheQuota: 100_000_000, downloadLimit: 0, offline: true, executionID: UUID())
        #expect(study.model?.sourceRevision == CoverageRFKernel.sourceRevision)
        #expect(study.area.cells.contains { $0.state == .qualified })
        _ = try CoverageRaster(grid: study.grid, transmitter: study.transmitter, area: study.area)
        print("SKADI RF Swift: " + study.area.cells.map { $0.lossDb.map(String.init(describing:)) ?? $0.state.rawValue }.joined(separator: ","))
        print("SKADI LIVE Swift: \(sources[0].tile), \(sources[0].packedBytes) bytes, ETag \(sources[0].sourceETag ?? ""), verified decode and offline reuse")
    }
}
