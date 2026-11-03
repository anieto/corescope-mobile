import Foundation

enum CoverageTerrainFootprintFailure: Error, Equatable {
    case invalidRequest, tooManyTiles, ambiguousTile, inconsistentSources
    case missingTiles([CoverageTerrainTile])
}

/// Conservative spherical bounding box of the study disk plus an explicit sampling halo.
/// Uses the same Earth radius as the reviewed coverage studies; it is not an RF coverage outline.
struct CoverageTerrainFootprint: Sendable {
    static let earthRadiusMeters = 6_371_008.8
    let tiles: [CoverageTerrainTile]
    private init(tiles: [CoverageTerrainTile]) { self.tiles = tiles }

    static func make(latitude: Double, longitude: Double, radiusMeters: Double,
                     haloMeters: Double = 0, maximumTiles: Int = 256) throws -> Self {
        let limit = Double.pi * earthRadiusMeters
        guard latitude.isFinite, longitude.isFinite, radiusMeters.isFinite, haloMeters.isFinite,
              (-90...90).contains(latitude), (-180...180).contains(longitude),
              (0...limit).contains(radiusMeters), (0...limit).contains(haloMeters),
              radiusMeters + haloMeters <= limit, (1...256).contains(maximumTiles) else {
            throw CoverageTerrainFootprintFailure.invalidRequest
        }
        let distance = radiusMeters + haloMeters
        if distance == 0 {
            return Self(tiles: [try CoverageTerrainTile.containing(latitude: latitude, longitude: longitude)])
        }
        let radians = Double.pi / 180
        let phi = latitude * radians
        let angular = distance / earthRadiusMeters
        let south = max(-90, Int(floor((max(-Double.pi / 2, phi - angular) / radians).nextDown)))
        let north = min(89, Int(floor((min(Double.pi / 2, phi + angular) / radians).nextUp)))
        let longitudes: [Int]
        if phi - angular <= -Double.pi / 2 || phi + angular >= Double.pi / 2 {
            // A disk touching a pole can contain every longitude. Never silently truncate it.
            longitudes = Array(-180..<180)
        } else {
            let halfWidth = asin(min(1, max(0, sin(angular) / cos(phi)))) / radians
            let center = longitude == 180 ? -180 : longitude
            let west = Int(floor((center - halfWidth).nextDown))
            let east = Int(floor((center + halfWidth).nextUp))
            longitudes = Array(Set((west...east).map { (($0 + 180) % 360 + 360) % 360 - 180 })).sorted()
        }
        guard (north - south + 1) * longitudes.count <= maximumTiles else {
            throw CoverageTerrainFootprintFailure.tooManyTiles
        }
        var tiles: [CoverageTerrainTile] = []
        for latitude in south...north {
            for longitude in longitudes { tiles.append(try CoverageTerrainTile(south: latitude, west: longitude)) }
        }
        return Self(tiles: tiles)
    }

    /// Resolve a bounded source manifest without silently accepting gaps or mixed terrain sources.
    /// Identical identities on multiple mirrors reuse the first URL; conflicting identities fail.
    func resolve(catalog: [CoverageTerrainDescriptor]) throws -> [CoverageTerrainDescriptor] {
        guard catalog.count <= 4096 else { throw CoverageTerrainFootprintFailure.invalidRequest }
        let required = Set(tiles)
        var selected: [CoverageTerrainTile: CoverageTerrainDescriptor] = [:]
        for descriptor in catalog where required.contains(descriptor.coordinate) {
            if let existing = selected[descriptor.coordinate] {
                guard existing.cacheKey == descriptor.cacheKey else { throw CoverageTerrainFootprintFailure.ambiguousTile }
            } else { selected[descriptor.coordinate] = descriptor }
        }
        let missing = tiles.filter { selected[$0] == nil }
        guard missing.isEmpty else { throw CoverageTerrainFootprintFailure.missingTiles(missing) }
        let result = tiles.compactMap { selected[$0] }
        if let first = result.first {
            guard result.allSatisfy({
                $0.dataset == first.dataset && $0.revision == first.revision &&
                $0.verticalDatum == first.verticalDatum && $0.side == first.side &&
                $0.sourceType == first.sourceType
            }) else { throw CoverageTerrainFootprintFailure.inconsistentSources }
        }
        return result
    }
}
