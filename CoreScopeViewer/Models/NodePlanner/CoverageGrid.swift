import Foundation

enum CoverageGridFailure: Error, Equatable { case invalidParameters, invalidBounds, unalignedExtent, cellBudget }
enum CoverageGridPointState: String, Sendable { case eligible, nearSite, outsideRadius }
struct CoverageGridPoint: Sendable {
    let eastMeters: Int
    let northMeters: Int
    let distanceMeters: Double
    let state: CoverageGridPointState
}

/// Local east/north sampling layout, not a coverage footprint or geographic raster projection.
struct CoverageGrid: Sendable {
    let radiusMeters: Int
    let spacingMeters: Int
    let minEastMeters: Int
    let maxNorthMeters: Int
    let columns: Int
    let rows: Int
    var count: Int { columns * rows }

    init(radiusMeters: Int, spacingMeters: Int, minEastMeters: Int, maxEastMeters: Int,
         minNorthMeters: Int, maxNorthMeters: Int) throws {
        guard (1000...100_000).contains(radiusMeters),
              [50, 100, 200, 500, 1000, 2000, 5000, 10_000, 20_000].contains(spacingMeters) else {
            throw CoverageGridFailure.invalidParameters
        }
        let bounds = [minEastMeters, maxEastMeters, minNorthMeters, maxNorthMeters]
        guard bounds.allSatisfy({ (-radiusMeters...radiusMeters).contains($0) }),
              minEastMeters <= maxEastMeters, minNorthMeters <= maxNorthMeters else {
            throw CoverageGridFailure.invalidBounds
        }
        let width = maxEastMeters - minEastMeters, height = maxNorthMeters - minNorthMeters
        guard width % spacingMeters == 0, height % spacingMeters == 0 else { throw CoverageGridFailure.unalignedExtent }
        let columns = width / spacingMeters + 1, rows = height / spacingMeters + 1
        guard columns * rows <= 60_000 else { throw CoverageGridFailure.cellBudget }
        self.radiusMeters = radiusMeters
        self.spacingMeters = spacingMeters
        self.minEastMeters = minEastMeters
        self.maxNorthMeters = maxNorthMeters
        self.columns = columns
        self.rows = rows
    }

    /// Keep the requested radius independent of the sample lattice (for example, whole miles).
    /// Unsampled boundary areas remain unresolved rather than extending the requested study.
    static func studyArea(radiusMeters: Int, spacingMeters: Int) throws -> Self {
        guard (1000...100_000).contains(radiusMeters),
              [50, 100, 200, 500, 1000, 2000, 5000, 10_000, 20_000].contains(spacingMeters) else {
            throw CoverageGridFailure.invalidParameters
        }
        let extent = (2 * radiusMeters) % spacingMeters == 0
            ? radiusMeters : (radiusMeters / spacingMeters) * spacingMeters
        return try Self(radiusMeters: radiusMeters, spacingMeters: spacingMeters,
            minEastMeters: -extent, maxEastMeters: extent,
            minNorthMeters: -extent, maxNorthMeters: extent)
    }

    /// North-to-south row order. Invalid indices cannot create a point outside the selected extent.
    func point(at index: Int) -> CoverageGridPoint? {
        guard (0..<count).contains(index) else { return nil }
        let east = minEastMeters + (index % columns) * spacingMeters
        let north = maxNorthMeters - (index / columns) * spacingMeters
        let distance = hypot(Double(east), Double(north))
        let state: CoverageGridPointState = distance < 250 ? .nearSite : (distance > Double(radiusMeters) ? .outsideRadius : .eligible)
        return CoverageGridPoint(eastMeters: east, northMeters: north, distanceMeters: distance, state: state)
    }

    /// Local offsets define spherical distance and initial bearing from the transmitter.
    /// Excluded samples have no RF path; malformed origins/indices throw instead of looking excluded.
    func path(at index: Int, transmitter: CoverageTerrainCoordinate) throws -> CoverageTerrainPath? {
        guard transmitter.latitude.isFinite, transmitter.longitude.isFinite,
              (-85...85).contains(transmitter.latitude), (-180...180).contains(transmitter.longitude),
              let point = point(at: index) else { throw CoverageGridFailure.invalidParameters }
        guard point.state == .eligible else { return nil }
        let angle = atan2(Double(point.eastMeters), Double(point.northMeters)) * 180 / Double.pi
        let bearing = angle - 360 * floor(angle / 360)
        let path = try CoverageTerrainPath(latitude: transmitter.latitude, longitude: transmitter.longitude,
            bearingDegrees: bearing, distanceMeters: point.distanceMeters)
        // Endpoint validation is early; profile crossing discovery also checks interior latitude extrema.
        _ = try path.point(at: path.distanceMeters)
        return path
    }

}
