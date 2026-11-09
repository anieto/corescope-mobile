import Foundation

enum CoverageRasterFailure: Error, Equatable { case invalidResult, invalidTile, cancelled }
enum CoverageRasterClass: String, Sendable {
    case outsideAnalysis, unsupportedNearSite, unresolved, belowThreshold, aboveThreshold
}

/// Immutable nearest-sample rasterization in Web Mercator. It does not interpolate RF evidence.
/// Render on a worker; map adapters encode the returned straight-alpha RGBA bytes.
struct CoverageRaster: Sendable {
    let grid: CoverageGrid
    let transmitter: CoverageTerrainCoordinate
    private let cells: [CoverageAreaCell]

    init(grid: CoverageGrid, transmitter: CoverageTerrainCoordinate, area: CoverageAreaResult) throws {
        guard transmitter.latitude.isFinite, transmitter.longitude.isFinite,
              (-85...85).contains(transmitter.latitude), (-180...180).contains(transmitter.longitude),
              area.cells.count == grid.count else { throw CoverageRasterFailure.invalidResult }
        for (index, cell) in area.cells.enumerated() {
            if cell.state == .qualified {
                guard grid.point(at: index)?.state == .eligible,
                      let loss = cell.lossDb, loss.isFinite,
                      let margin = cell.marginDb, margin.isFinite else { throw CoverageRasterFailure.invalidResult }
            } else if cell.lossDb != nil || cell.marginDb != nil { throw CoverageRasterFailure.invalidResult }
        }
        self.grid = grid
        self.transmitter = transmitter
        cells = area.cells
    }

    func classification(latitude: Double, longitude: Double) -> CoverageRasterClass {
        guard latitude.isFinite, longitude.isFinite, (-85...85).contains(latitude),
              (-180...180).contains(longitude) else { return .outsideAnalysis }
        let radians = Double.pi / 180
        let phi = transmitter.latitude * radians, target = latitude * radians
        let delta = (longitude - transmitter.longitude) * radians
        let h = pow(sin((target - phi) / 2), 2) + cos(phi) * cos(target) * pow(sin(delta / 2), 2)
        let distance = 2 * asin(sqrt(min(1, max(0, h)))) * CoverageTerrainFootprint.earthRadiusMeters
        guard distance <= Double(grid.radiusMeters) else { return .outsideAnalysis }
        if distance < 250 { return .unsupportedNearSite }
        let bearing = atan2(sin(delta) * cos(target),
                            cos(phi) * sin(target) - sin(phi) * cos(target) * cos(delta))
        let east = distance * sin(bearing), north = distance * cos(bearing)
        let column = Int(floor((east - Double(grid.minEastMeters)) / Double(grid.spacingMeters) + 0.5))
        let row = Int(floor((Double(grid.maxNorthMeters) - north) / Double(grid.spacingMeters) + 0.5))
        guard (0..<grid.columns).contains(column), (0..<grid.rows).contains(row) else { return .outsideAnalysis }
        let cell = cells[row * grid.columns + column]
        switch cell.state {
        // The pixel is inside the radius, but its nearest sample may be outside it.
        // No prediction exists there; transparency would imply a below-threshold result.
        case .outsideRadius: return .unresolved
        case .nearSite: return .unsupportedNearSite
        case .qualified: return (cell.marginDb ?? -.infinity) >= 0 ? .aboveThreshold : .belowThreshold
        default: return .unresolved
        }
    }

    /// Transparent pixels are either outside analysis or qualified below threshold; unknowns hatch gray.
    /// A legend must explain those distinctions. This palette does not imply signal-strength precision.
    static func rgba(_ state: CoverageRasterClass, column: Int, row: Int) -> UInt32 {
        switch state {
        case .aboveThreshold: return 0x26B85AA0
        case .unresolved, .unsupportedNearSite: return (column + row) % 8 < 2 ? 0x66666690 : 0x00000000
        case .outsideAnalysis, .belowThreshold: return 0x00000000
        }
    }

    func tile(zoom: Int, x: Int, y: Int, cancelled: () -> Bool = { false }) throws -> Data {
        guard (0...22).contains(zoom) else { throw CoverageRasterFailure.invalidTile }
        let count = 1 << zoom
        guard (0..<count).contains(x), (0..<count).contains(y) else { throw CoverageRasterFailure.invalidTile }
        var output = Data(capacity: 256 * 256 * 4)
        let scale = Double(count * 256)
        for row in 0..<256 {
            if cancelled() { throw CoverageRasterFailure.cancelled }
            let worldY = (Double(y * 256 + row) + 0.5) / scale
            let latitude = atan(sinh(Double.pi * (1 - 2 * worldY))) * 180 / Double.pi
            for column in 0..<256 {
                let longitude = (Double(x * 256 + column) + 0.5) / scale * 360 - 180
                let color = Self.rgba(classification(latitude: latitude, longitude: longitude), column: column, row: row)
                output.append(UInt8((color >> 24) & 255))
                output.append(UInt8((color >> 16) & 255))
                output.append(UInt8((color >> 8) & 255))
                output.append(UInt8(color & 255))
            }
        }
        if cancelled() { throw CoverageRasterFailure.cancelled }
        return output
    }
}
