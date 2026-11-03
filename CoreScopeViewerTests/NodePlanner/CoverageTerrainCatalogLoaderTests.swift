import Foundation
import Testing
@testable import CoreScopeViewer

private final class CatalogHTTPStub: URLProtocol, @unchecked Sendable {
    static let body = Data("""
    {"version":1,"dataset":"test","revision":"1","vertical_datum":"test","attribution":"test",
    "source_type":"synthetic","side":2,"tiles":[{"tile":"N00E000","sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
    "bytes":100,"url":"https://example.com/tile"}]}
    """.utf8)
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        guard let url = request.url else { return }
        if url.path == "/stall" { return }
        var body = Self.body
        var status = 200
        var headers = [String: String]()
        switch url.path {
        case "/oversized": body = Data(repeating: 32, count: CoverageTerrainCatalog.maximumBytes + 1)
        case "/declared-large": headers["Content-Length"] = String(CoverageTerrainCatalog.maximumBytes + 1)
        case "/length": headers["Content-Length"] = "1"
        case "/encoding": headers["Content-Encoding"] = "gzip"
        case "/missing": status = 404
        case "/redirect": status = 302; headers["Location"] = "https://example.invalid/other"
        case "/invalid": body = Data("{}".utf8)
        default: headers["Content-Length"] = String(body.count)
        }
        guard let response = HTTPURLResponse(url: url, statusCode: status, httpVersion: "HTTP/1.1", headerFields: headers) else { return }
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: body)
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}

struct CoverageTerrainCatalogLoaderTests {
    private func load(_ path: String) async throws -> CoverageTerrainCatalog {
        try await CoverageTerrainCatalogLoader.load(from: #require(URL(string: "https://example.invalid/" + path)),
            protocolClasses: [CatalogHTTPStub.self])
    }

    @Test func fetchAndResolve() async throws {
        let catalog = try await load("valid")
        let area = try CoverageTerrainFootprint.make(latitude: 0.5, longitude: 0.5, radiusMeters: 1000)
        #expect(try catalog.resolve(area).map(\.tile) == ["N00E000"])
    }

    @Test func rejectsBadResponses() async throws {
        for path in ["oversized", "declared-large", "length", "encoding", "missing", "redirect", "invalid"] {
            do { _ = try await load(path); Issue.record("Accepted invalid catalog response: \(path)") }
            catch {
                switch path {
                case "oversized": #expect(error as? CoverageTerrainCatalogFailure == .oversized)
                case "invalid": #expect(error as? CoverageTerrainCatalogFailure == .invalidDocument)
                case "missing": #expect(error as? CoverageTerrainCatalogLoadFailure == .httpStatus(404))
                case "redirect": #expect(error as? CoverageTerrainCatalogLoadFailure == .httpStatus(302))
                default: #expect(error as? CoverageTerrainCatalogLoadFailure == .invalidResponse)
                }
            }
        }
    }

    @Test func cancellationAndInvalidRequests() async throws {
        let task = Task { try await load("stall") }
        try await Task.sleep(for: .milliseconds(50))
        task.cancel()
        do { _ = try await task.value; Issue.record("Cancelled catalog was returned") } catch {}
        for address in ["http://example.com/catalog", "https://user@example.com/catalog", "https://example.com/catalog#fragment"] {
            do {
                _ = try await CoverageTerrainCatalogLoader.load(from: #require(URL(string: address)),
                    protocolClasses: [CatalogHTTPStub.self])
                Issue.record("Invalid endpoint accepted")
            } catch { #expect(error as? CoverageTerrainCatalogLoadFailure == .invalidRequest) }
        }
    }
}
