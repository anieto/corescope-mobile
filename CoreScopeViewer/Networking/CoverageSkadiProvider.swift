import Foundation

private final class CoverageSkadiHTTPPolicy: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping @Sendable (URLRequest?) -> Void) {
        completionHandler(nil)
    }
}

/// Direct AWS discovery. ETags identify representations; they are not independent content checksums.
enum CoverageSkadiProvider {
    static func endpoint(tile: CoverageTerrainTile, legacy: Bool = false) throws -> URL {
        let name = String(format: "%@%02d%@%03d", tile.south < 0 ? "S" : "N", abs(tile.south),
                          tile.west < 0 ? "W" : "E", abs(tile.west))
        guard let url = URL(string: "https://elevation-tiles-prod.s3.amazonaws.com/\(legacy ? "" : "v2/")skadi/\(name.prefix(3))/\(name).hgt.gz") else { throw CoverageTerrainDownloadFailure.corrupt }
        return url
    }

    static func descriptor(tile: CoverageTerrainTile, endpoint: URL, status: Int,
                           length: String?, etag: String?, encoding: String?) throws -> CoverageTerrainDescriptor {
        guard status == 200 else { throw CoverageTerrainDownloadFailure.httpStatus(status) }
        let current = try self.endpoint(tile: tile), legacy = try self.endpoint(tile: tile, legacy: true)
        guard endpoint == current || endpoint == legacy,
              let length, !length.isEmpty, length.utf8.allSatisfy({ (48...57).contains($0) }),
              let count = Int64(length), let etag,
              (encoding ?? "identity").lowercased() == "identity" else {
            throw CoverageTerrainDownloadFailure.corrupt
        }
        let name = endpoint.deletingPathExtension().deletingPathExtension().lastPathComponent
        return try CoverageTerrainDescriptor(dataset: "mapzen-skadi", revision: "direct-v1", tile: name,
            compressedSHA256: nil, packedBytes: count, side: 3601, url: endpoint.absoluteString,
            verticalDatum: "EGM96",
            attribution: "Mapzen Terrain Tiles; source attribution: https://github.com/tilezen/joerd/blob/master/docs/attribution.md",
            sourceType: "mixed-source elevation; surface/bare-earth varies", originURL: endpoint.absoluteString, sourceETag: etag)
    }

    /// Serialized HEAD requests only. A 404 permits v1 fallback, never synthetic sea-level terrain.
    static func discover(footprint: CoverageTerrainFootprint, timeout: TimeInterval = 15,
                         protocolClasses: [AnyClass]? = nil) async throws -> [CoverageTerrainDescriptor] {
        guard (0.05...60).contains(timeout) else { throw CoverageTerrainDownloadFailure.invalidLimits }
        let configuration = URLSessionConfiguration.ephemeral
        configuration.urlCache = nil
        configuration.httpCookieStorage = nil
        configuration.urlCredentialStorage = nil
        configuration.timeoutIntervalForRequest = timeout
        configuration.timeoutIntervalForResource = timeout
        configuration.protocolClasses = protocolClasses
        let session = URLSession(configuration: configuration)
        defer { session.invalidateAndCancel() }
        return try await withTaskCancellationHandler {
            var result: [CoverageTerrainDescriptor] = []
            for tile in footprint.tiles {
                for legacy in [false, true] {
                    try Task.checkCancellation()
                    let url = try endpoint(tile: tile, legacy: legacy)
                    var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData)
                    request.httpMethod = "HEAD"
                    request.setValue("identity", forHTTPHeaderField: "Accept-Encoding")
                    let (bytes, response) = try await session.bytes(for: request, delegate: CoverageSkadiHTTPPolicy())
                    bytes.task.cancel()
                    guard let http = response as? HTTPURLResponse else { throw CoverageTerrainDownloadFailure.corrupt }
                    if http.statusCode == 404 && !legacy { continue }
                    result.append(try descriptor(tile: tile, endpoint: url, status: http.statusCode,
                        length: http.value(forHTTPHeaderField: "Content-Length"), etag: http.value(forHTTPHeaderField: "ETag"),
                        encoding: http.value(forHTTPHeaderField: "Content-Encoding")))
                    break
                }
            }
            try Task.checkCancellation()
            return try footprint.resolve(catalog: result)
        } onCancel: { session.invalidateAndCancel() }
    }
}
