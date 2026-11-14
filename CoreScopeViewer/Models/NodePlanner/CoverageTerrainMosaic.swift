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
    var readerPerformanceCounters: [String: Int] {
        ["terrainMappedReadCalls": readers.values.reduce(0) { $0 + $1.mappedReadCalls },
         "terrainMappedAddressBytes": readers.values.reduce(0) { $0 + $1.mappedBytes },
         "terrainMappedReaders": readers.values.filter { $0.mappedBytes > 0 }.count,
         "terrainFallbackReaders": readers.values.filter { $0.mappedBytes == 0 }.count,
         "terrainBlockHits": readers.values.reduce(0) { $0 + $1.blockHits },
         "terrainBlockMisses": readers.values.reduce(0) { $0 + $1.blockMisses },
         "terrainFileReadCalls": readers.values.reduce(0) { $0 + $1.fileReadCalls },
         "terrainFileReadBytes": readers.values.reduce(0) { $0 + $1.fileReadBytes },
         "terrainCachedBlockBytes": readers.values.reduce(0) { $0 + $1.cachedBlockBytes },
         "terrainBlockCapacityBytes": readers.values.reduce(0) { $0 + $1.blockCacheCapacityBytes }]
    }
    private var isClosed = false
    private let reusePreparedPaths: Bool
    private var pathKey: [Double] = []
    private(set) var profilePassCounts: [String: Int] = [:]
    private var cachedCrossings: CoverageTerrainCrossings?
    private var preparation = CoverageTerrainProfileSampler.Preparation()
    private var elevations: [Double: Double] = [:]
    private(set) var crossingCacheHits = 0
    private(set) var elevationCacheHits = 0
    private(set) var elevationReads = 0
    private(set) var crossingSeconds = 0.0
    private(set) var interpolationCalls = 0
    private(set) var timedInterpolationCalls = 0
    private(set) var timedInterpolationSeconds = 0.0
    private(set) var timedReaderSeconds = 0.0
    private(set) var timedReaderCalls = 0
    private(set) var timedCoordinateSeconds = 0.0
    private(set) var timedCoordinateCalls = 0

    private static func seconds(since start: ContinuousClock.Instant) -> Double {
        let duration = start.duration(to: .now).components
        return Double(duration.seconds) + Double(duration.attoseconds) / 1e18
    }

    init(readers: [CoverageTerrainTile: LeasedTerrainReader],
         reusePreparedPaths: Bool = !ProcessInfo.processInfo.arguments.contains("-CoverageDisablePathReuse")) throws {
        self.reusePreparedPaths = reusePreparedPaths
        guard readers.count <= Self.maximumTiles,
              Set(readers.values.map(ObjectIdentifier.init)).count == readers.count else {
            readers.values.forEach { $0.close() }
            throw TerrainReadFailure.invalidDescriptor
        }
        self.readers = readers
        // Keep the proven small cache as the fallback if read-only mapping is unavailable.
        readers.values.forEach { $0.configureBlockCache(blockCount: 4) }
    }

    /// Bilinear interpolation in the source grid. Only nonzero-weight contributors are required:
    /// an exact valid grid point stays valid even when an unused neighboring sample is nodata.
    /// Missing owner tiles never fall back to a neighboring tile's duplicate border.
    func sample(latitude: Double, longitude: Double,
                cancelled: () -> Bool = { false }) throws -> CoverageTerrainSample {
        guard !isClosed else { throw TerrainReadFailure.closed }
        if cancelled() { throw TerrainReadFailure.cancelled }
        // Time one in 1,024 interpolations, including its underlying reader calls.
        // Timing every tiny read would distort the workload we are measuring.
        interpolationCalls += 1
        let timingStart: ContinuousClock.Instant? = interpolationCalls % 1024 == 1 ? .now : nil
        defer {
            if let timingStart {
                timedInterpolationCalls += 1
                timedInterpolationSeconds += Self.seconds(since: timingStart)
            }
        }
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
        for rowOffset in 0..<2 {
            let weight = rowOffset == 0 ? 1 - dy : dy
            guard weight > 0 else { continue }
            let r = rowOffset == 0 ? row : min(row + 1, intervals)
            let samples: (Int16, Int16)
            if timingStart != nil {
                let readStart = ContinuousClock.now
                defer {
                    timedReaderCalls += 1
                    timedReaderSeconds += Self.seconds(since: readStart)
                }
                samples = try reader.readPair(start: r * reader.side + column, adjacent: dx > 0,
                                          cancelled: cancelled)
            } else {
                samples = try reader.readPair(start: r * reader.side + column, adjacent: dx > 0,
                                          cancelled: cancelled)
            }
            guard samples.0 != Int16.min else { return .noData }
            var rowElevation = Double(samples.0) * (1 - dx)
            if dx > 0 {
                guard samples.1 != Int16.min else { return .noData }
                rowElevation += Double(samples.1) * dx
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
        if cancelled() { throw CoverageTerrainProfileFailure.cancelled }
        let key = [path.latitude, path.longitude, path.bearingDegrees, path.distanceMeters]
        if spacingMeters.isFinite, (1...30).contains(spacingMeters) {
            profilePassCounts["profilePassesAt\(Int(spacingMeters))m", default: 0] += 1
        }
        if !reusePreparedPaths || pathKey != key {
            pathKey = key
            cachedCrossings = nil
            preparation = CoverageTerrainProfileSampler.Preparation()
            elevations.removeAll(keepingCapacity: true)
        }
        let crossings: CoverageTerrainCrossings
        if let cachedCrossings {
            // Preserve existing logical work limits even when work is served from cache.
            guard (1...200_000).contains(geometryLimit), cachedCrossings.geometryEvaluations <= geometryLimit else {
                throw CoverageTerrainProfileFailure.workLimit
            }
            crossings = cachedCrossings
            crossingCacheHits += 1
        } else {
            let crossingStart = ContinuousClock.now
            defer { crossingSeconds += Self.seconds(since: crossingStart) }
            crossings = try path.crossings(intervalsPerDegree: side - 1, geometryLimit: geometryLimit, cancelled: cancelled)
            cachedCrossings = crossings
        }
        let profile = try CoverageTerrainProfileSampler.build(distanceMeters: path.distanceMeters,
            cellCrossingsMeters: crossings.distancesMeters, spacingMeters: spacingMeters,
            sampleLimit: sampleLimit, cancelled: cancelled, preparation: preparation) { distance in
                if reusePreparedPaths, let cached = elevations[distance] {
                    elevationCacheHits += 1
                    return cached
                }
                elevationReads += 1
                let point: CoverageTerrainCoordinate
                if elevationReads % 1024 == 1 {
                    let coordinateStart = ContinuousClock.now
                    defer {
                        timedCoordinateCalls += 1
                        timedCoordinateSeconds += Self.seconds(since: coordinateStart)
                    }
                    point = try path.point(at: distance)
                } else {
                    point = try path.point(at: distance)
                }
                switch try sample(latitude: point.latitude, longitude: point.longitude, cancelled: cancelled) {
                case .elevationMeters(let height):
                    // Bound dictionary growth even on the longest paths; exact Double keys
                    // avoid merging distinct terrain positions or changing interpolation.
                    if reusePreparedPaths && elevations.count < 250_000 { elevations[distance] = height }
                    return height
                case .missingTile, .noData: return nil
                }
            }
        let located = CoverageTerrainProfile(distancesMeters: profile.distancesMeters,
            elevationsMeters: profile.elevationsMeters, terrainEvaluations: profile.terrainEvaluations,
            featureCount: profile.featureCount, pathCenterLatitude: try path.point(at: path.distanceMeters / 2).latitude)
        return CoverageTerrainSampledPath(profile: located, geometryEvaluations: crossings.geometryEvaluations)
    }

    func close() {
        elevations.removeAll()
        cachedCrossings = nil
        preparation = CoverageTerrainProfileSampler.Preparation()
        guard !isClosed else { return }
        isClosed = true
        readers.values.forEach { $0.close() }
        readers.removeAll()
    }

    deinit { close() }
}
