import Foundation
import MapKit

/// A single study's MapKit tile source. Attach using MKTileOverlayRenderer above roads.
/// The owner must remove this overlay and invalidate it on input changes or teardown;
/// never reuse an old overlay for a new study. MapKit owns its rendered-image cache.
final class CoverageMapKitOverlay: MKTileOverlay, @unchecked Sendable {
    private let store: CoverageTileStore
    private let worker: DispatchQueue
    private let tileReady: @MainActor @Sendable (Int, Int, Int) -> Void

    init(raster: CoverageRaster, cacheBytes: Int,
         worker: DispatchQueue = DispatchQueue(label: "org.nodescope.coverage.mapkit", qos: .userInitiated),
         tileReady: @escaping @MainActor @Sendable (Int, Int, Int) -> Void = { _, _, _ in }) throws {
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
        store.invalidate()
    }

    override func loadTile(at path: MKTileOverlayPath, result: @escaping @Sendable (Data?, Error?) -> Void) {
        // MapKit can request more than 64 tiles during initial framing. Queue every callback:
        // returning a transient busy error leaves holes until a later map interaction retries them.
        // Rasterization remains serial and the encoded tile cache remains bounded.
        let zoom = path.z, x = path.x, y = path.y
        worker.async { [self] in
            let output = autoreleasepool { Result { try store.tile(zoom: zoom, x: x, y: y) } }
            // Publish on the map's executor, then invalidate the completed tile on the next
            // main-queue turn so the renderer has consumed its data before drawing again.
            DispatchQueue.main.async { [self] in
                switch output {
                case .success(let data):
                    result(data, nil)
                    DispatchQueue.main.async { [self] in tileReady(zoom, x, y) }
                case .failure(let error): result(nil, error)
                }
            }
        }
    }
}
