import Foundation

enum CoverageTerrainCatalogLoadFailure: Error, Equatable {
    case invalidRequest, invalidResponse, httpStatus(Int)
}

private final class CoverageCatalogHTTPPolicy: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask,
                    willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest,
                    completionHandler: @escaping @Sendable (URLRequest?) -> Void) {
        completionHandler(nil)
    }
}

/// Fetches one catalog from a caller-configured trusted HTTPS endpoint. No automatic retry or redirects.
enum CoverageTerrainCatalogLoader {
    static func load(from endpoint: URL, timeout: TimeInterval = 30,
                     protocolClasses: [AnyClass]? = nil) async throws -> CoverageTerrainCatalog {
        guard endpoint.scheme == "https", let host = endpoint.host, !host.isEmpty,
              endpoint.user == nil, endpoint.password == nil, endpoint.fragment == nil,
              (0.05...60).contains(timeout) else {
            throw CoverageTerrainCatalogLoadFailure.invalidRequest
        }
        try Task.checkCancellation()
        let configuration = URLSessionConfiguration.ephemeral
        configuration.urlCache = nil
        configuration.httpCookieStorage = nil
        configuration.urlCredentialStorage = nil
        configuration.timeoutIntervalForRequest = timeout
        configuration.timeoutIntervalForResource = timeout
        configuration.protocolClasses = protocolClasses
        let session = URLSession(configuration: configuration)
        defer { session.invalidateAndCancel() }
        var request = URLRequest(url: endpoint, cachePolicy: .reloadIgnoringLocalCacheData)
        request.setValue("identity", forHTTPHeaderField: "Accept-Encoding")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        return try await withTaskCancellationHandler {
            let (bytes, response) = try await session.bytes(for: request, delegate: CoverageCatalogHTTPPolicy())
            defer { bytes.task.cancel() }
            guard let http = response as? HTTPURLResponse else {
                throw CoverageTerrainCatalogLoadFailure.invalidResponse
            }
            guard http.statusCode == 200 else { throw CoverageTerrainCatalogLoadFailure.httpStatus(http.statusCode) }
            guard (http.value(forHTTPHeaderField: "Content-Encoding") ?? "identity").lowercased() == "identity" else {
                throw CoverageTerrainCatalogLoadFailure.invalidResponse
            }
            var expected: Int?
            if let value = http.value(forHTTPHeaderField: "Content-Length") {
                guard !value.isEmpty, value.utf8.allSatisfy({ (48...57).contains($0) }),
                      let count = Int(value), count <= CoverageTerrainCatalog.maximumBytes else {
                    throw CoverageTerrainCatalogLoadFailure.invalidResponse
                }
                expected = count
            }
            var data = Data()
            for try await byte in bytes {
                try Task.checkCancellation()
                guard data.count < CoverageTerrainCatalog.maximumBytes else {
                    throw CoverageTerrainCatalogFailure.oversized
                }
                data.append(byte)
            }
            if let expected, expected != data.count { throw CoverageTerrainCatalogLoadFailure.invalidResponse }
            try Task.checkCancellation()
            let result = try CoverageTerrainCatalog(data: data)
            try Task.checkCancellation()
            return result
        } onCancel: { session.invalidateAndCancel() }
    }
}
