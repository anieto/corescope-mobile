import Foundation

enum CoverageTileStoreFailure: Error, Equatable { case invalidCapacity, inactive }

/// One immutable study's PNG tiles. Call from bounded map workers, never the UI thread.
/// Invalidate and replace the store when inputs change; an invalidated store cannot restart.
/// Locks protect all mutable state. Rendering is serialized without holding the state lock,
/// so invalidation can interrupt the rasterizer at its next cancellation checkpoint.
final class CoverageTileStore: @unchecked Sendable {
    private struct Key: Hashable { let zoom, x, y: Int }
    private let raster: CoverageRaster
    private let byteLimit: Int
    private let stateLock = NSLock()
    private let renderLock = NSLock()
    private var active = true
    private var cache: [Key: Data] = [:]
    private var recency: [Key] = []
    private var bytes = 0

    init(raster: CoverageRaster, byteLimit: Int) throws {
        guard (0...8 * 1024 * 1024).contains(byteLimit) else {
            throw CoverageTileStoreFailure.invalidCapacity
        }
        self.raster = raster
        self.byteLimit = byteLimit
    }

    func invalidate() {
        stateLock.lock()
        defer { stateLock.unlock() }
        active = false
        cache.removeAll()
        recency.removeAll()
        bytes = 0
    }

    var cachedBytes: Int {
        stateLock.lock()
        defer { stateLock.unlock() }
        return bytes
    }

    private var isInactive: Bool {
        stateLock.lock()
        defer { stateLock.unlock() }
        return !active
    }

    func tile(zoom: Int, x: Int, y: Int, cancelled: () -> Bool = { false }) throws -> Data {
        renderLock.lock()
        defer { renderLock.unlock() }
        if cancelled() { throw CoverageRasterFailure.cancelled }
        let key = Key(zoom: zoom, x: x, y: y)
        stateLock.lock()
        guard active else {
            stateLock.unlock()
            throw CoverageTileStoreFailure.inactive
        }
        if let data = cache[key] {
            recency.removeAll { $0 == key }
            recency.append(key)
            stateLock.unlock()
            return data
        }
        stateLock.unlock()

        let data = try raster.pngTile(zoom: zoom, x: x, y: y) { cancelled() || self.isInactive }
        if cancelled() { throw CoverageRasterFailure.cancelled }
        stateLock.lock()
        defer { stateLock.unlock() }
        guard active else { throw CoverageTileStoreFailure.inactive }
        // Oversized tiles are returned but never retained. The cap covers encoded cache bytes,
        // not raster/encoder workspace or data retained by a map provider.
        if data.count <= byteLimit {
            while bytes + data.count > byteLimit || cache.count >= 64, let oldest = recency.first {
                recency.removeFirst()
                bytes -= cache.removeValue(forKey: oldest)?.count ?? 0
            }
            cache[key] = data
            recency.append(key)
            bytes += data.count
        }
        return data
    }
}
