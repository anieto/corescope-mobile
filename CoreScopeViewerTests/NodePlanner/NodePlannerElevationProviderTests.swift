import Foundation
import Testing
@testable import CoreScopeViewer

@Suite(.serialized)
struct NodePlannerElevationProviderTests {
    @Test func samplesEndpointsAndCrossesDateLineByShortPath() throws {
        let start = try NodePlannerCoordinate(latitude: 10, longitude: 179)
        let end = try NodePlannerCoordinate(latitude: 20, longitude: -179)

        let samples = NodePlannerPathSampler.coordinates(from: start, to: end, count: 3)

        #expect(samples.count == 3)
        #expect(samples.first == start)
        #expect(samples.last == end)
        #expect(samples[1].latitude == 15)
        #expect(abs(abs(samples[1].longitude) - 180) < 0.000_001)
    }

    @Test func fetchesInBatchesAndPreservesOrder() async throws {
        ElevationFixtureProtocol.reset()
        let coordinates = try (0..<101).map {
            try NodePlannerCoordinate(latitude: Double($0) / 10, longitude: -100)
        }
        let provider = OpenMeteoElevationProvider(session: fixtureSession())

        let profile = try await provider.elevations(for: coordinates)

        #expect(profile.samples.count == 101)
        #expect(profile.samples.map(\.coordinate) == coordinates)
        #expect(profile.samples.first?.elevationMeters == coordinates.first?.latitude)
        #expect(profile.samples.last?.elevationMeters == coordinates.last?.latitude)
        #expect(profile.provenance.origin == .network)
        #expect(ElevationFixtureProtocol.requests == 2)
    }

    @Test func reusesFreshCachedElevations() async throws {
        ElevationFixtureProtocol.reset()
        let coordinates = [
            try NodePlannerCoordinate(latitude: 30, longitude: -97),
            try NodePlannerCoordinate(latitude: 31, longitude: -98)
        ]
        let provider = OpenMeteoElevationProvider(session: fixtureSession())

        let first = try await provider.elevations(for: coordinates)
        let second = try await provider.elevations(for: coordinates)

        #expect(first.provenance.origin == .network)
        #expect(second.provenance.origin == .cache)
        #expect(first.samples == second.samples)
        #expect(ElevationFixtureProtocol.requests == 1)
    }

    @Test func rejectsMismatchedResponseCount() async throws {
        ElevationFixtureProtocol.reset(malformed: true)
        let coordinates = [
            try NodePlannerCoordinate(latitude: 30, longitude: -97),
            try NodePlannerCoordinate(latitude: 31, longitude: -98)
        ]
        let provider = OpenMeteoElevationProvider(session: fixtureSession())

        await #expect(throws: NodePlannerElevationError.invalidResponse) {
            try await provider.elevations(for: coordinates)
        }
    }

    private func fixtureSession() -> URLSession {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [ElevationFixtureProtocol.self]
        return URLSession(configuration: configuration)
    }
}

private final class ElevationFixtureProtocol: URLProtocol, @unchecked Sendable {
    private static let lock = NSLock()
    nonisolated(unsafe) private static var requestCount = 0
    nonisolated(unsafe) private static var returnsMalformedResponse = false

    static var requests: Int {
        lock.withLock { requestCount }
    }

    static func reset(malformed: Bool = false) {
        lock.withLock {
            requestCount = 0
            returnsMalformedResponse = malformed
        }
    }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func stopLoading() {}

    override func startLoading() {
        Self.lock.withLock { Self.requestCount += 1 }
        guard let url = request.url,
              let components = URLComponents(url: url, resolvingAgainstBaseURL: false),
              let latitudeValue = components.queryItems?.first(where: { $0.name == "latitude" })?.value else {
            client?.urlProtocol(self, didFailWithError: URLError(.badURL))
            return
        }

        let latitudes = latitudeValue.split(separator: ",").compactMap { Double($0) }
        let malformed = Self.lock.withLock { Self.returnsMalformedResponse }
        let values = malformed ? Array(latitudes.dropLast()) : latitudes
        let body = "{\"elevation\":[\(values.map { String($0) }.joined(separator: ","))]}"
        guard let response = HTTPURLResponse(url: url, statusCode: 200, httpVersion: nil, headerFields: nil) else {
            return
        }
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(body.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }
}
