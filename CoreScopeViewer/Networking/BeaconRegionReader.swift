import Foundation

actor BeaconRegionReader {
    struct IATA: Codable, Sendable {
        let iata: String
        let displayName: String?
        let lat: Double?
        let lon: Double?

        var coordinate: IataCoordinate? {
            guard let lat, let lon, Self.valid(latitude: lat, longitude: lon) else { return nil }
            return IataCoordinate(lat: lat, lon: lon, radiusKm: nil)
        }

        static func valid(latitude: Double, longitude: Double) -> Bool {
            latitude.isFinite && longitude.isFinite &&
                (-90...90).contains(latitude) && (-180...180).contains(longitude)
        }
    }

    struct Region: Codable, Sendable {
        let id: Int
        let slug: String
        let name: String
        let iatas: [String]
        let centerLat: Double?
        let centerLng: Double?
        let zoomLevel: Int?
    }

    struct Catalog: Sendable {
        let iatas: [IATA]
        let regions: [Region]

        var names: RegionsMap {
            Dictionary(uniqueKeysWithValues: iatas.map { ($0.iata.uppercased(), $0.displayName ?? $0.iata) })
        }

        var coordinates: IataCoordsResponse {
            IataCoordsResponse(coords: Dictionary(uniqueKeysWithValues: iatas.compactMap { entry in
                entry.coordinate.map { (entry.iata.uppercased(), $0) }
            }))
        }

        func frame(codes: [String]? = nil) -> Frame? {
            let selected = codes.map { Set($0.map { $0.uppercased() }) }
            let points = iatas.filter { selected?.contains($0.iata.uppercased()) ?? true }
                .compactMap(\.coordinate)
            return Frame.enclosing(points)
        }

        func frame(regionID: Int) -> Frame? {
            guard let region = regions.first(where: { $0.id == regionID }) else { return nil }
            if let frame = frame(codes: region.iatas) { return frame }
            guard let latitude = region.centerLat, let longitude = region.centerLng,
                  IATA.valid(latitude: latitude, longitude: longitude) else { return nil }
            return Frame.enclosing([IataCoordinate(lat: latitude, lon: longitude, radiusKm: nil)])
        }
    }

    struct Frame: Equatable, Sendable {
        let latitude: Double
        let longitude: Double
        let latitudeDelta: Double
        let longitudeDelta: Double

        static func enclosing(_ points: [IataCoordinate]) -> Frame? {
            let valid = points.filter { IATA.valid(latitude: $0.lat, longitude: $0.lon) }
            guard let south = valid.map(\.lat).min(), let north = valid.map(\.lat).max() else { return nil }
            let longitudes = valid.map { $0.lon < 0 ? $0.lon + 360 : $0.lon }.sorted()
            var largestGap = -Double.infinity
            var start = 0.0
            for index in longitudes.indices {
                let next = index + 1 < longitudes.count ? longitudes[index + 1] : longitudes[0] + 360
                let gap = next - longitudes[index]
                if gap > largestGap {
                    largestGap = gap
                    start = next.truncatingRemainder(dividingBy: 360)
                }
            }
            // Exclude the largest empty longitude arc so dateline groups stay local.
            let width = 360 - largestGap
            let center = (start + width / 2).truncatingRemainder(dividingBy: 360)
            return Frame(
                latitude: (south + north) / 2, longitude: center > 180 ? center - 360 : center,
                latitudeDelta: min(180, max(1, (north - south) * 1.2)),
                longitudeDelta: min(360, max(1, width * 1.2))
            )
        }
    }

    private struct Summary: Decodable, Sendable {
        let id: Int
        let slug: String
        let name: String
    }

    private let client: BeaconAPIClient
    private let now: @Sendable () -> Date
    private var cached: (catalog: Catalog, expires: Date)?
    private var pending: Task<Catalog, Error>?

    init(
        client: BeaconAPIClient, verifiedServerInfo: BeaconServerInfo,
        now: @escaping @Sendable () -> Date = { .now }
    ) throws {
        try verifiedServerInfo.requireSupported()
        self.client = client
        self.now = now
    }

    func catalog(forceRefresh: Bool = false) async throws -> Catalog {
        try client.sourceSession.check()
        if !forceRefresh, let cached, cached.expires > now() { return cached.catalog }
        if let pending {
            let result = try await pending.value
            try client.sourceSession.check()
            return result
        }
        let client = client
        let task = Task { try await Self.fetch(client: client) }
        pending = task
        defer { pending = nil }
        let result = try await task.value
        try client.sourceSession.check()
        cached = (result, now().addingTimeInterval(300))
        return result
    }

    private static func fetch(client: BeaconAPIClient) async throws -> Catalog {
        let iatas: [IATA] = try await client.get(["iatas"])
        let summaries: [Summary] = try await client.get(["regions"])
        guard summaries.count <= 18 else { throw AnalyzerPagingError.pageBudgetExceeded }
        guard Set(iatas.map { $0.iata.uppercased() }).count == iatas.count,
              iatas.allSatisfy({ validCode($0.iata) }),
              Set(summaries.map(\.id)).count == summaries.count,
              Set(summaries.map(\.slug)).count == summaries.count,
              summaries.allSatisfy({ $0.id > 0 && !$0.slug.isEmpty }) else {
            throw APIError.invalidResponse
        }
        var regions: [Region] = []
        for summary in summaries {
            let detail: Region = try await client.get(["regions", String(summary.id)])
            guard detail.id == summary.id, detail.slug == summary.slug,
                  detail.iatas.allSatisfy({ validCode($0) }) else { throw APIError.invalidResponse }
            regions.append(detail)
        }
        try client.sourceSession.check()
        return Catalog(iatas: iatas, regions: regions)
    }

    private static func validCode(_ value: String) -> Bool {
        value.utf8.count == 3 && value.utf8.allSatisfy {
            (65...90).contains($0) || (97...122).contains($0)
        }
    }
}
