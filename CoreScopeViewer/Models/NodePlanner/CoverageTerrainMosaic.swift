import Foundation

/// Southwest corner of a one-degree HGT tile. A repository must supply one consistent dataset,
/// revision and vertical datum for the whole mosaic; coordinates do not establish provenance.
struct CoverageTerrainTile: Hashable, Sendable {
    let south: Int
    let west: Int

    init(south: Int, west: Int) throws {
        guard (-90..<90).contains(south), (-180..<180).contains(west) else {
            throw TerrainReadFailure.invalidDescriptor
        }
        self.south = south
        self.west = west
    }

    /// North/east tile owns an exact seam. +180 is canonicalized to -180.
    /// The north pole uses the northern edge of the last latitude tile.
    static func containing(latitude: Double, longitude: Double) throws -> CoverageTerrainTile {
        guard latitude.isFinite, longitude.isFinite,
              (-90...90).contains(latitude), (-180...180).contains(longitude) else {
            throw TerrainReadFailure.invalidRange
        }
        return try CoverageTerrainTile(south: min(89, Int(floor(latitude))),
                                       west: Int(floor(longitude == 180 ? -180 : longitude)))
    }
}

enum CoverageTerrainSample: Equatable, Sendable {
    case elevationMeters(Double)
    case missingTile(CoverageTerrainTile)
    case noData
}

/// Serialized background-worker owner; deliberately not Sendable. Takes ownership of all readers,
/// including on failed construction. They must remain immutable and pinned until close.
/// This is point sampling, not a terrain-profile decimation or ridge-preservation algorithm.
final class CoverageTerrainMosaic {
    static let maximumTiles = 256
    private var readers: [CoverageTerrainTile: LeasedTerrainReader]
    private var isClosed = false

    init(readers: [CoverageTerrainTile: LeasedTerrainReader]) throws {
        guard readers.count <= Self.maximumTiles,
              Set(readers.values.map(ObjectIdentifier.init)).count == readers.count else {
            readers.values.forEach { $0.close() }
            throw TerrainReadFailure.invalidDescriptor
        }
        self.readers = readers
    }

    /// Bilinear interpolation in the source grid. Only nonzero-weight contributors are required:
    /// an exact valid grid point stays valid even when an unused neighboring sample is nodata.
    /// Missing owner tiles never fall back to a neighboring tile's duplicate border.
    func sample(latitude: Double, longitude: Double,
                cancelled: () -> Bool = { false }) throws -> CoverageTerrainSample {
        guard !isClosed else { throw TerrainReadFailure.closed }
        if cancelled() { throw TerrainReadFailure.cancelled }
        let tile = try CoverageTerrainTile.containing(latitude: latitude, longitude: longitude)
        guard let reader = readers[tile] else { return .missingTile(tile) }
        let longitude = longitude == 180 ? -180 : longitude
        let intervals = reader.side - 1
        let x = min(Double(intervals), max(0, (longitude - Double(tile.west)) * Double(intervals)))
        let y = min(Double(intervals), max(0, (Double(tile.south + 1) - latitude) * Double(intervals)))
        let column = Int(floor(x))
        let row = Int(floor(y))
        let dx = x - Double(column)
        let dy = y - Double(row)
        var elevation = 0.0
        // At most two reads of two samples each; never loads a whole tile.
        for (r, weight) in [(row, 1 - dy), (min(row + 1, intervals), dy)] where weight > 0 {
            let samples = try reader.read(start: r * reader.side + column, count: dx > 0 ? 2 : 1,
                                          cancelled: cancelled)
            guard samples[0] != Int16.min else { return .noData }
            var rowElevation = Double(samples[0]) * (1 - dx)
            if dx > 0 {
                guard samples[1] != Int16.min else { return .noData }
                rowElevation += Double(samples[1]) * dx
            }
            elevation += rowElevation * weight
        }
        if cancelled() { throw TerrainReadFailure.cancelled }
        return .elevationMeters(elevation)
    }

    /// Build a feature-preserving path without transferring or releasing this mosaic's pins.
    func profile(path: CoverageTerrainPath, spacingMeters: Double, sampleLimit: Int = 250_000,
                 geometryLimit: Int = 200_000, cancelled: () -> Bool = { false }) throws -> CoverageTerrainSampledPath {
        guard !isClosed else { throw TerrainReadFailure.closed }
        let dimensions = Set(readers.values.map(\.side))
        guard let side = dimensions.first else { throw CoverageTerrainProfileFailure.missingTerrain }
        guard dimensions.count == 1 else { throw CoverageTerrainProfileFailure.invalidInput }
        let crossings = try path.crossings(intervalsPerDegree: side - 1, geometryLimit: geometryLimit, cancelled: cancelled)
        let profile = try CoverageTerrainProfileSampler.build(distanceMeters: path.distanceMeters,
            cellCrossingsMeters: crossings.distancesMeters, spacingMeters: spacingMeters,
            sampleLimit: sampleLimit, cancelled: cancelled) { distance in
                let point = try path.point(at: distance)
                switch try sample(latitude: point.latitude, longitude: point.longitude, cancelled: cancelled) {
                case .elevationMeters(let height): return height
                case .missingTile, .noData: return nil
                }
            }
        return CoverageTerrainSampledPath(profile: profile, geometryEvaluations: crossings.geometryEvaluations)
    }

    func close() {
        guard !isClosed else { return }
        isClosed = true
        readers.values.forEach { $0.close() }
        readers.removeAll()
    }

    deinit { close() }
}
