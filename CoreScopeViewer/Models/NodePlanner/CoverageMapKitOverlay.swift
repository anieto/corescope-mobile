import Foundation
import MapKit

enum CoverageMapKitOverlayFailure: Error { case busy }

/// A single study's MapKit tile source. Attach using MKTileOverlayRenderer above roads.
/// The owner must remove this overlay and invalidate it on input changes or teardown;
/// never reuse an old overlay for a new study. MapKit owns its rendered-image cache.
final class CoverageMapKitOverlay: MKTileOverlay, @unchecked Sendable {
    private let store: CoverageTileStore
    private let worker = DispatchQueue(label: "org.nodescope.coverage.mapkit", qos: .userInitiated)
    private let slots = DispatchSemaphore(value: 64)

    init(raster: CoverageRaster, cacheBytes: Int) throws {
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
        // Bound both executing and queued callbacks. Each admitted request completes exactly once.
        guard slots.wait(timeout: .now()) == .success else {
            result(nil, CoverageMapKitOverlayFailure.busy)
            return
        }
        let zoom = path.z, x = path.x, y = path.y
        worker.async { [self] in
            let output = Result { try store.tile(zoom: zoom, x: x, y: y) }
            // Release admission before invoking client code, which may request another tile.
            slots.signal()
            switch output {
            case .success(let data): result(data, nil)
            case .failure(let error): result(nil, error)
            }
        }
    }
}
