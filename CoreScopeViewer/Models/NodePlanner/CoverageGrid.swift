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

    /// North-to-south row order. Invalid indices cannot create a point outside the selected extent.
    func point(at index: Int) -> CoverageGridPoint? {
        guard (0..<count).contains(index) else { return nil }
        let east = minEastMeters + (index % columns) * spacingMeters
        let north = maxNorthMeters - (index / columns) * spacingMeters
        let distance = hypot(Double(east), Double(north))
        let state: CoverageGridPointState = distance < 250 ? .nearSite : (distance > Double(radiusMeters) ? .outsideRadius : .eligible)
        return CoverageGridPoint(eastMeters: east, northMeters: north, distanceMeters: distance, state: state)
    }
}
