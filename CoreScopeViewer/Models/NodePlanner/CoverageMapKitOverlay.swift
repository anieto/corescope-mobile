import Foundation
import MapKit
import ImageIO
import OSLog

/// A single study's MapKit tile source. Attach using MKTileOverlayRenderer above roads.
/// The owner must remove this overlay and invalidate it on input changes or teardown;
/// never reuse an old overlay for a new study. MapKit owns its rendered-image cache.
final class CoverageMapKitOverlay: MKTileOverlay, @unchecked Sendable {
    private let store: CoverageTileStore
    private let trace: CoverageRenderTrace
    private let worker: DispatchQueue
    // Accessed only on the serial worker. A redraw may request the same tile again.
    private var announcedTiles = Set<String>()
    private let tileReady: @MainActor @Sendable (Int, Int, Int) -> Void

    init(raster: CoverageRaster, cacheBytes: Int,
         trace: CoverageRenderTrace = CoverageRenderTrace(),
         worker: DispatchQueue = DispatchQueue(label: "org.nodescope.coverage.mapkit", qos: .userInitiated),
         tileReady: @escaping @MainActor @Sendable (Int, Int, Int) -> Void = { _, _, _ in }) throws {
        self.trace = trace
        self.worker = worker
        self.tileReady = tileReady
        store = try CoverageTileStore(raster: raster, byteLimit: cacheBytes)
        super.init(urlTemplate: nil)
        tileSize = CGSize(width: 256, height: 256)
        minimumZ = 0
        maximumZ = 22
        isGeometryFlipped = false
        canReplaceMapContent = false
    }

    func invalidate() {
        trace.record("overlay invalidated")
        store.invalidate()
    }

    override func loadTile(at path: MKTileOverlayPath, result: @escaping @Sendable (Data?, Error?) -> Void) {
        // MapKit can request more than 64 tiles during initial framing. Queue every callback:
        // returning a transient busy error leaves holes until a later map interaction retries them.
        // Rasterization remains serial and the encoded tile cache remains bounded.
        let zoom = path.z, x = path.x, y = path.y
        let request = UUID().uuidString.prefix(8)
        let started = Date()
        trace.record("request \(request) zoom=\(zoom) scale=\(path.contentScaleFactor)")

        worker.async { [self] in
            trace.record("start \(request) queuedMs=\(Int(Date().timeIntervalSince(started) * 1000))")
            let output = autoreleasepool { Result { try store.tile(zoom: zoom, x: x, y: y) } }
            switch output {
            case .success(let data):
                // Complete on the worker: MapKit may itself be waiting for this callback.
                trace.record("ready \(request) bytes=\(data.count) elapsedMs=\(Int(Date().timeIntervalSince(started) * 1000))")
                result(data, nil)
                trace.record("delivered \(request)")
                let key = "\(zoom)/\(x)/\(y)"
                if announcedTiles.insert(key).inserted {
                    // Never invalidate again for a cache hit: doing so creates a
                    // load → redraw → load feedback loop that can keep the overlay blank.
                    DispatchQueue.main.async { [self] in tileReady(zoom, x, y) }
                }
            case .failure(let error):
                trace.record("failed \(request): \(String(describing: error))")
                result(nil, error)
            }
        }
    }
}


/// Debug-only, bounded trace shared by one map and its overlay; no geographic coordinates or node IDs.
final class CoverageRenderTrace: @unchecked Sendable {
    private let logger = Logger(subsystem: "org.nodescope.coverage", category: "CoverageRendering")
    private let id = UUID().uuidString
    private let lock = NSLock()
    private var count = 0

    func record(_ message: @autoclosure () -> String) {
        #if DEBUG
        lock.lock()
        guard count < 1000 else { lock.unlock(); return }
        count += 1
        let last = count == 1000
        lock.unlock()
        let text = message()
        logger.notice("CoverageRendering [\(self.id, privacy: .public)] \(text, privacy: .public)")
        if last { logger.notice("CoverageRendering [\(self.id, privacy: .public)] trace limit reached") }
        #endif
    }
}


/// Owns decoded pixels and uses MapKit's asynchronous canDraw contract. Tile completion
/// makes pixels available before invalidating their rectangle; drawing never starts a reload.
final class CoverageRasterRenderer: MKOverlayRenderer, @unchecked Sendable {
    private struct Key: Hashable {
        let z, x, y: Int
        var mapRect: MKMapRect {
            let side = MKMapRect.world.size.width / pow(2, Double(z))
            return MKMapRect(x: Double(x) * side, y: Double(y) * side, width: side, height: side)
        }
    }

    private let source: CoverageMapKitOverlay
    private let trace: CoverageRenderTrace
    private let lock = NSLock()
    private var images: [Key: CGImage] = [:]
    private var recency: [Key] = []
    private var pending = Set<Key>()
    // 128 × 256 × 256 × 4 = 32 MiB of decoded pixels at most.
    private let imageLimit = 128

    init(source: CoverageMapKitOverlay, trace: CoverageRenderTrace) {
        self.source = source
        self.trace = trace
        super.init(overlay: source)
    }

    override func canDraw(_ mapRect: MKMapRect, zoomScale: MKZoomScale) -> Bool {
        let keys = keys(in: mapRect, zoomScale: zoomScale)
        var ready = true
        var requests: [Key] = []
        lock.lock()
        for key in keys where images[key] == nil {
            ready = false
            if pending.insert(key).inserted { requests.append(key) }
        }
        lock.unlock()
        for key in requests {
            let count = 1 << key.z
            let x = ((key.x % count) + count) % count
            source.loadTile(at: MKTileOverlayPath(x: x, y: key.y, z: key.z, contentScaleFactor: 1)) { [weak self] data, error in
                guard let self else { return }
                let image = data.flatMap { CGImageSourceCreateWithData($0 as CFData, nil) }
                    .flatMap { CGImageSourceCreateImageAtIndex($0, 0, nil) }
                self.lock.lock()
                self.pending.remove(key)
                if let image {
                    while self.images.count >= self.imageLimit, let oldest = self.recency.first {
                        self.recency.removeFirst()
                        self.images.removeValue(forKey: oldest)
                    }
                    self.images[key] = image
                    self.recency.append(key)
                }
                self.lock.unlock()
                guard image != nil else {
                    self.trace.record("decoded image unavailable: \(String(describing: error))")
                    return
                }
                self.trace.record("decoded image cached zoom=\(key.z)")
                DispatchQueue.main.async { [weak self] in
                    self?.setNeedsDisplay(key.mapRect)
                }
            }
        }
        trace.record("canDraw ready=\(ready) tiles=\(keys.count) newRequests=\(requests.count)")
        return ready
    }

    override func draw(_ mapRect: MKMapRect, zoomScale: MKZoomScale, in context: CGContext) {
        let keys = keys(in: mapRect, zoomScale: zoomScale)
        lock.lock()
        let available = keys.compactMap { key -> (Key, CGImage)? in
            guard let image = images[key] else { return nil }
            recency.removeAll { $0 == key }
            recency.append(key)
            return (key, image)
        }
        lock.unlock()
        context.saveGState()
        context.clip(to: rect(for: mapRect))
        context.interpolationQuality = .none
        for (key, image) in available {
            let destination = rect(for: key.mapRect)
            context.saveGState()
            context.translateBy(x: destination.minX, y: destination.maxY)
            context.scaleBy(x: 1, y: -1)
            context.draw(image, in: CGRect(origin: .zero, size: destination.size))
            context.restoreGState()
        }
        context.restoreGState()
        trace.record("draw images=\(available.count)/\(keys.count)")
        // A cache eviction between canDraw and draw must not leave a permanently empty patch.
        if available.count != keys.count { _ = canDraw(mapRect, zoomScale: zoomScale) }
    }

    private func keys(in mapRect: MKMapRect, zoomScale: MKZoomScale) -> [Key] {
        guard zoomScale.isFinite, zoomScale > 0, !mapRect.isNull, !mapRect.isEmpty,
              mapRect.minX.isFinite, mapRect.maxX.isFinite,
              mapRect.minY.isFinite, mapRect.maxY.isFinite else { return [] }
        let requestedZoom = min(22, max(0, Int(floor(log2(zoomScale * MKMapRect.world.size.width / 256)))))
        // Keep a single draw's working set below the decoded-image cache capacity.
        let fittingZoom = max(0, Int(floor(log2(MKMapRect.world.size.width * 8 / max(mapRect.size.width, mapRect.size.height)))))
        let z = min(requestedZoom, fittingZoom)
        let count = 1 << z
        let side = MKMapRect.world.size.width / Double(count)
        let minX = Int(floor(mapRect.minX / side))
        let maxX = Int(ceil(mapRect.maxX / side)) - 1
        let minY = max(0, Int(floor(mapRect.minY / side)))
        let maxY = min(count - 1, Int(ceil(mapRect.maxY / side)) - 1)
        guard minX <= maxX, minY <= maxY else { return [] }
        return (minY...maxY).flatMap { y in (minX...maxX).map { x in Key(z: z, x: x, y: y) } }
    }
}
