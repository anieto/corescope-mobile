import CryptoKit
import Foundation
import Testing
@testable import CoreScopeViewer

private final class TerrainHTTPStub: URLProtocol, @unchecked Sendable {
    static let payload = Data((0..<150000).map { UInt8($0 % 251) })
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        guard let url = request.url else { return }
        if url.path == "/stall" { return }
        var body = Self.payload
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
