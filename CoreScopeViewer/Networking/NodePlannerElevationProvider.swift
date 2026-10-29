import Foundation

struct NodePlannerCoordinate: Codable, Equatable, Hashable, Sendable {
    let latitude: Double
    let longitude: Double

    init(latitude: Double, longitude: Double) throws {
        guard latitude.isFinite,
              longitude.isFinite,
              (-90...90).contains(latitude),
              (-180...180).contains(longitude) else {
            throw NodePlannerElevationError.invalidCoordinate
        }
        self.latitude = latitude
        self.longitude = longitude
    }
}

struct NodePlannerTerrainSample: Codable, Equatable, Sendable {
    let coordinate: NodePlannerCoordinate
    let elevationMeters: Double
}

enum NodePlannerElevationOrigin: String, Codable, Sendable {
    case network
    case cache
    case mixed
}

struct NodePlannerElevationProvenance: Codable, Equatable, Sendable {
    static let provider = "open-meteo"
    static let dataset = "copernicus-dem-2021-glo-90"
    static let nominalResolutionMeters = 90

    let fetchedAt: Date
    let origin: NodePlannerElevationOrigin
}

struct NodePlannerTerrainProfile: Codable, Equatable, Sendable {
    let samples: [NodePlannerTerrainSample]
    let provenance: NodePlannerElevationProvenance
}

enum NodePlannerElevationError: Error, Equatable, Sendable {
    case invalidCoordinate
    case insufficientCoordinates
    case invalidResponse
    case service(statusCode: Int)
    case unavailable
}

protocol NodePlannerElevationProviding: Sendable {
    func elevations(for coordinates: [NodePlannerCoordinate]) async throws -> NodePlannerTerrainProfile
}

enum NodePlannerPathSampler {
    static func coordinates(
        from start: NodePlannerCoordinate,
        to end: NodePlannerCoordinate,
        count requestedCount: Int
    ) -> [NodePlannerCoordinate] {
        let count = max(2, requestedCount)
        let longitudeDelta = shortestLongitudeDelta(from: start.longitude, to: end.longitude)

        return (0..<count).compactMap { index in
            let fraction = Double(index) / Double(count - 1)
            let latitude = start.latitude + fraction * (end.latitude - start.latitude)
            let longitude = normalizedLongitude(start.longitude + fraction * longitudeDelta)
            return try? NodePlannerCoordinate(latitude: latitude, longitude: longitude)
        }
    }

    private static func shortestLongitudeDelta(from start: Double, to end: Double) -> Double {
        var delta = end - start
        if delta > 180 {
            delta -= 360
        } else if delta < -180 {
            delta += 360
        }
        return delta
    }

    private static func normalizedLongitude(_ value: Double) -> Double {
        var longitude = value
        if longitude > 180 {
            longitude -= 360
        } else if longitude < -180 {
            longitude += 360
        }
        return longitude
    }
}

actor OpenMeteoElevationProvider: NodePlannerElevationProviding {
    private struct Response: Decodable {
        let elevation: [Double]
    }

    private struct CacheEntry: Sendable {
        let elevationMeters: Double
        let fetchedAt: Date
    }

    static let maximumBatchSize = 100

    private let session: URLSession
    private let endpoint: URL
    private let cacheLifetime: TimeInterval
    private let now: @Sendable () -> Date
    private let persistentCache: (any NodePlannerElevationCaching)?
    private var cache: [NodePlannerCoordinate: CacheEntry] = [:]

    init(
        session: URLSession = .shared,
        endpoint: URL = URL(string: "https://api.open-meteo.com/v1/elevation")!,
        cacheLifetime: TimeInterval = 30 * 24 * 60 * 60,
        persistentCache: (any NodePlannerElevationCaching)? = NodePlannerElevationDiskCache(),
        now: @escaping @Sendable () -> Date = Date.init
    ) {
        self.session = session
        self.endpoint = endpoint
        self.cacheLifetime = cacheLifetime
        self.persistentCache = persistentCache
        self.now = now
    }

    func elevations(for coordinates: [NodePlannerCoordinate]) async throws -> NodePlannerTerrainProfile {
        guard coordinates.count >= 2 else { throw NodePlannerElevationError.insufficientCoordinates }

        let requestTime = now()
        let cacheCutoff = requestTime.addingTimeInterval(-cacheLifetime)
        var resolved: [NodePlannerCoordinate: CacheEntry] = [:]
        var missing: [NodePlannerCoordinate] = []

        for coordinate in coordinates {
            if let entry = cache[coordinate], requestTime.timeIntervalSince(entry.fetchedAt) <= cacheLifetime {
                resolved[coordinate] = entry
            } else if let record = await persistentCache?.elevation(for: coordinate, newerThan: cacheCutoff) {
                let entry = CacheEntry(elevationMeters: record.elevationMeters, fetchedAt: record.fetchedAt)
                cache[coordinate] = entry
                resolved[coordinate] = entry
            } else {
                missing.append(coordinate)
            }
        }

        for batchStart in stride(from: 0, to: missing.count, by: Self.maximumBatchSize) {
            try Task.checkCancellation()
            let batchEnd = min(batchStart + Self.maximumBatchSize, missing.count)
            let batch = Array(missing[batchStart..<batchEnd])
            let elevations = try await fetch(batch)
            var storedElevations: [NodePlannerCachedElevation] = []

            for (coordinate, elevation) in zip(batch, elevations) {
                let entry = CacheEntry(elevationMeters: elevation, fetchedAt: requestTime)
                cache[coordinate] = entry
                resolved[coordinate] = entry
                storedElevations.append(
                    NodePlannerCachedElevation(
                        coordinate: coordinate,
                        elevationMeters: elevation,
                        fetchedAt: requestTime
                    )
                )
            }
            await persistentCache?.store(storedElevations)
        }

        let samples = try coordinates.map { coordinate in
            guard let entry = resolved[coordinate], entry.elevationMeters.isFinite else {
                throw NodePlannerElevationError.invalidResponse
            }
            return NodePlannerTerrainSample(coordinate: coordinate, elevationMeters: entry.elevationMeters)
        }
        let fetchedAt = coordinates.compactMap { resolved[$0]?.fetchedAt }.min() ?? requestTime
        let origin: NodePlannerElevationOrigin = if missing.isEmpty {
            .cache
        } else if missing.count == coordinates.count {
            .network
        } else {
            .mixed
        }

        return NodePlannerTerrainProfile(
            samples: samples,
            provenance: NodePlannerElevationProvenance(fetchedAt: fetchedAt, origin: origin)
        )
    }

    private func fetch(_ coordinates: [NodePlannerCoordinate]) async throws -> [Double] {
        guard !coordinates.isEmpty else { return [] }
        guard var components = URLComponents(url: endpoint, resolvingAgainstBaseURL: false) else {
            throw NodePlannerElevationError.unavailable
        }
        components.queryItems = [
            URLQueryItem(name: "latitude", value: coordinates.map { format($0.latitude) }.joined(separator: ",")),
            URLQueryItem(name: "longitude", value: coordinates.map { format($0.longitude) }.joined(separator: ","))
        ]
        guard let url = components.url else { throw NodePlannerElevationError.unavailable }

        let (data, response): (Data, URLResponse)
        do {
            (data, response) = try await session.data(from: url)
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            throw NodePlannerElevationError.unavailable
        }

        guard let httpResponse = response as? HTTPURLResponse else {
            throw NodePlannerElevationError.invalidResponse
        }
        guard (200..<300).contains(httpResponse.statusCode) else {
            throw NodePlannerElevationError.service(statusCode: httpResponse.statusCode)
        }
        guard let decoded = try? JSONDecoder().decode(Response.self, from: data),
              decoded.elevation.count == coordinates.count,
              decoded.elevation.allSatisfy(\.isFinite) else {
            throw NodePlannerElevationError.invalidResponse
        }
        return decoded.elevation
    }

    private func format(_ value: Double) -> String {
        value.formatted(
            .number
                .locale(Locale(identifier: "en_US_POSIX"))
                .grouping(.never)
                .precision(.fractionLength(0...8))
        )
    }
}
