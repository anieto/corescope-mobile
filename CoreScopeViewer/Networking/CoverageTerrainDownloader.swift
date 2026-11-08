import CryptoKit
import Foundation

enum CoverageTerrainDownloadFailure: Error, Equatable {
    case invalidLimits, downloadLimit, corrupt, httpStatus(Int)
}

/// Counts consumed response-body bytes, including failed attempts; excludes HTTP/TLS overhead.
/// Reservations prevent concurrent attempts from spending the same remaining allowance.
final class CoverageTerrainDownloadBudget: @unchecked Sendable {
    private let lock = NSLock()
    private var available: Int64
    private var consumed: Int64 = 0
    init(limit: Int64) throws {
        guard (0...512_000_000).contains(limit) else { throw CoverageTerrainDownloadFailure.invalidLimits }
        available = limit
    }
    var remainingBytes: Int64 { lock.withLock { available } }
    var receivedBytes: Int64 { lock.withLock { consumed } }
    fileprivate func reserve(_ count: Int64) throws {
        try lock.withLock {
            guard count <= available else { throw CoverageTerrainDownloadFailure.downloadLimit }
            available -= count
        }
    }
    fileprivate func finish(reserved: Int64, received: Int64) {
        lock.withLock { available += reserved - received; consumed += received }
    }
}

final class DownloadedCoverageTerrain: Sendable {
    let fileURL: URL
    let sha256: String
    private let directory: URL
    fileprivate init(directory: URL, sha256: String) {
        self.sha256 = sha256
        self.directory = directory
        fileURL = directory.appendingPathComponent("terrain.hgt.gz")
    }
    /// Caller must not close while the decoder is reading.
    func close() throws {
        if FileManager.default.fileExists(atPath: directory.path) { try FileManager.default.removeItem(at: directory) }
    }
    deinit { try? close() }
}

private final class CoverageTerrainHTTPPolicy: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping @Sendable (URLRequest?) -> Void) {
        completionHandler(nil)
    }
}

/// A single bounded HTTP attempt. The coordinator owns retry policy and whole-pipeline disk reservations.
enum CoverageTerrainDownloader {
    static func download(_ descriptor: CoverageTerrainDescriptor, workspace: URL,
                         budget: CoverageTerrainDownloadBudget, idleTimeout: TimeInterval = 15,
                         attemptTimeout: TimeInterval = 60,
                         protocolClasses: [AnyClass]? = nil) async throws -> DownloadedCoverageTerrain {
        guard (0.05...30).contains(idleTimeout), (0.05...120).contains(attemptTimeout) else {
            throw CoverageTerrainDownloadFailure.invalidLimits
        }
        try Task.checkCancellation()
        let reserved = descriptor.packedBytes + 1
        try budget.reserve(reserved)
        var received: Int64 = 0
        defer { budget.finish(reserved: reserved, received: received) }
        let directory = workspace.appendingPathComponent(".download-" + UUID().uuidString.lowercased())
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: false)
        var transferred = false
        defer { if !transferred { try? FileManager.default.removeItem(at: directory) } }
        let destination = directory.appendingPathComponent("terrain.hgt.gz")
        guard FileManager.default.createFile(atPath: destination.path, contents: nil) else {
            throw CocoaError(.fileWriteUnknown)
        }
        let output = try FileHandle(forWritingTo: destination)
        defer { try? output.close() }
        let configuration = URLSessionConfiguration.ephemeral
        configuration.urlCache = nil
        configuration.httpCookieStorage = nil
        configuration.urlCredentialStorage = nil
        configuration.connectionProxyDictionary = [:]
        configuration.timeoutIntervalForRequest = idleTimeout
        configuration.timeoutIntervalForResource = attemptTimeout
        configuration.protocolClasses = protocolClasses
        let session = URLSession(configuration: configuration)
        defer { session.invalidateAndCancel() }
        guard let url = URL(string: descriptor.url) else { throw CoverageTerrainDownloadFailure.corrupt }
        var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData)
        request.setValue("identity", forHTTPHeaderField: "Accept-Encoding")
        request.setValue("NodeScope-Terrain/1", forHTTPHeaderField: "User-Agent")
        if let etag = descriptor.sourceETag { request.setValue(etag, forHTTPHeaderField: "If-Match") }
        return try await withTaskCancellationHandler {
            let (bytes, response) = try await session.bytes(for: request, delegate: CoverageTerrainHTTPPolicy())
            defer { bytes.task.cancel() }
            guard let http = response as? HTTPURLResponse else { throw CoverageTerrainDownloadFailure.corrupt }
            guard http.statusCode == 200 else { throw CoverageTerrainDownloadFailure.httpStatus(http.statusCode) }
            if let etag = descriptor.sourceETag, http.value(forHTTPHeaderField: "ETag") != etag {
                throw CoverageTerrainDownloadFailure.corrupt
            }
            let encoding = http.value(forHTTPHeaderField: "Content-Encoding") ?? "identity"
            guard encoding.lowercased() == "identity" else { throw CoverageTerrainDownloadFailure.corrupt }
            if let length = http.value(forHTTPHeaderField: "Content-Length") {
                guard !length.isEmpty, length.utf8.allSatisfy({ (48...57).contains($0) }),
                      Int64(length) == descriptor.packedBytes else { throw CoverageTerrainDownloadFailure.corrupt }
            }
            var chunk = Data()
            chunk.reserveCapacity(65536)
            var hash = SHA256()
            for try await byte in bytes {
                try Task.checkCancellation()
                received += 1
                guard received <= descriptor.packedBytes else { throw CoverageTerrainDownloadFailure.corrupt }
                chunk.append(byte)
                if chunk.count == 65536 {
                    try output.write(contentsOf: chunk)
                    hash.update(data: chunk)
                    chunk.removeAll(keepingCapacity: true)
                }
            }
            try Task.checkCancellation()
            guard received == descriptor.packedBytes else { throw CoverageTerrainDownloadFailure.corrupt }
            try output.write(contentsOf: chunk)
            hash.update(data: chunk)
            let digest = hash.finalize().map({ String(format: "%02x", $0) }).joined()
            if let expected = descriptor.compressedSHA256, digest != expected {
                throw CoverageTerrainDownloadFailure.corrupt
            }
            try output.synchronize()
            try Task.checkCancellation()
            transferred = true
            return DownloadedCoverageTerrain(directory: directory, sha256: digest)
        } onCancel: {
            session.invalidateAndCancel()
        }
    }
}
