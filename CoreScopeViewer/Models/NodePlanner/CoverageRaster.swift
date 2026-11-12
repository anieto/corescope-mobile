import Foundation

enum CoverageRasterFailure: Error, Equatable { case invalidResult, invalidTile, cancelled }
enum CoverageRasterClass: String, Sendable {
    case outsideAnalysis, unsupportedNearSite, unresolved, belowThreshold, limitedHeadroom, aboveThreshold

    /// These are display bands, not probabilities of packet delivery. Classify before rounding.
    static func margin(_ value: Double) -> Self {
        guard value.isFinite else { return .unresolved }
        if value < 0 { return .belowThreshold }
        return value <= 10 ? .limitedHeadroom : .aboveThreshold
    }
}

struct CoverageRasterSample: Sendable {
    let state: CoverageRasterClass
    var lossDb: Double? = nil
    var marginDb: Double? = nil
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
        sample(latitude: latitude, longitude: longitude).state
    }

    /// Inspection reports the nearby grid sample, never an exact-coordinate prediction.
    func sample(latitude: Double, longitude: Double) -> CoverageRasterSample {
        guard latitude.isFinite, longitude.isFinite, (-85...85).contains(latitude),
              (-180...180).contains(longitude) else { return .init(state: .outsideAnalysis) }
        let radians = Double.pi / 180
        let phi = transmitter.latitude * radians, target = latitude * radians
        let delta = (longitude - transmitter.longitude) * radians
        let h = pow(sin((target - phi) / 2), 2) + cos(phi) * cos(target) * pow(sin(delta / 2), 2)
        let distance = 2 * asin(sqrt(min(1, max(0, h)))) * CoverageTerrainFootprint.earthRadiusMeters
        guard distance <= Double(grid.radiusMeters) else { return .init(state: .outsideAnalysis) }
        if distance < 250 { return .init(state: .unsupportedNearSite) }
        let bearing = atan2(sin(delta) * cos(target),
                            cos(phi) * sin(target) - sin(phi) * cos(target) * cos(delta))
        let east = distance * sin(bearing), north = distance * cos(bearing)
        let column = Int(floor((east - Double(grid.minEastMeters)) / Double(grid.spacingMeters) + 0.5))
        let row = Int(floor((Double(grid.maxNorthMeters) - north) / Double(grid.spacingMeters) + 0.5))
        guard (0..<grid.columns).contains(column), (0..<grid.rows).contains(row) else { return .init(state: .unresolved) }
        let cell = cells[row * grid.columns + column]
        switch cell.state {
        // The pixel is inside the radius, but its nearest sample may be outside it.
        // No prediction exists there; transparency would imply a below-threshold result.
        case .outsideRadius: return .init(state: .unresolved)
        case .nearSite: return .init(state: .unsupportedNearSite)
        case .qualified: return .init(state: .margin(cell.marginDb ?? .nan), lossDb: cell.lossDb, marginDb: cell.marginDb)
        default: return .init(state: .unresolved)
        }
    }

    /// Only outside-analysis pixels are entirely clear. Unknowns hatch gray; qualified samples
    /// show adjusted-margin bands without implying measurement precision or a working return link.
    static func rgba(_ state: CoverageRasterClass, column: Int, row: Int) -> UInt32 {
        switch state {
        case .aboveThreshold: return 0x26B85AA0
        case .limitedHeadroom: return 0xE8A317A0
        case .belowThreshold: return 0xD84A4A90
        case .unresolved, .unsupportedNearSite: return (column + row) % 8 < 2 ? 0x66666690 : 0x00000000
        case .outsideAnalysis: return 0x00000000
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
