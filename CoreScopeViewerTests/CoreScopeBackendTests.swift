import Foundation
import Testing
@testable import CoreScopeViewer

struct CoreScopeBackendTests {
    enum RequestCase: String, CaseIterable, Sendable {
        case nodes, regionalNodes, node, health, paths, reach, analytics
        case observers, observerAnalytics, packets, regionalPackets, channelPackets
        case packet, channels, regionalChannels, messages, regions, coordinates, map

        var target: String {
            switch self {
            case .nodes: "/api/nodes?limit=5000"
            case .regionalNodes: "/api/nodes?limit=5000&region=SAT"
            case .node: "/api/nodes/key%2Ftest"
            case .health: "/api/nodes/key%2Ftest/health"
            case .paths: "/api/nodes/key%2Ftest/paths"
            case .reach: "/api/nodes/key%2Ftest/reach"
            case .analytics: "/api/nodes/key%2Ftest/analytics?days=7"
            case .observers: "/api/observers"
            case .observerAnalytics: "/api/observers/observer%2Ftest/analytics"
            case .packets: "/api/packets?limit=1000"
            case .regionalPackets: "/api/packets?limit=200&region=SAT"
            case .channelPackets: "/api/packets?limit=1000&type=5&region=SAT"
            case .packet: "/api/packets/hash%2Ftest"
            case .channels: "/api/channels"
            case .regionalChannels: "/api/channels?region=SAT"
            case .messages: "/api/channels/%23Public%3F%2F/messages?limit=500&offset=500"
            case .regions: "/api/config/regions"
            case .coordinates: "/api/iata-coords"
            case .map: "/api/config/map"
            }
        }

        func perform(using backend: any AnalyzerBackend) async throws {
            switch self {
            case .nodes: _ = try await backend.nodes(region: nil)
            case .regionalNodes: _ = try await backend.nodes(region: "SAT")
            case .node: _ = try await backend.nodeDetail(publicKey: "key/test")
            case .health: _ = try await backend.nodeHealth(publicKey: "key/test")
            case .paths: _ = try await backend.nodePaths(publicKey: "key/test")
            case .reach: _ = try await backend.nodeReach(publicKey: "key/test")
            case .analytics: _ = try await backend.nodeAnalytics(publicKey: "key/test", days: 7)
            case .observers: _ = try await backend.observers()
            case .observerAnalytics: _ = try await backend.observerAnalytics(id: "observer/test")
            case .packets: _ = try await backend.packets(region: nil, limit: 1000, payloadType: nil)
            case .regionalPackets: _ = try await backend.packets(region: "SAT", limit: 200, payloadType: nil)
            case .channelPackets: _ = try await backend.packets(region: "SAT", limit: 1000, payloadType: 5)
            case .packet: _ = try await backend.packetDetail(hash: "hash/test")
            case .channels: _ = try await backend.channels(region: nil)
            case .regionalChannels: _ = try await backend.channels(region: "SAT")
            case .messages: _ = try await backend.channelMessages(id: "#Public?/", offset: 500)
            case .regions: _ = try await backend.regions()
            case .coordinates: _ = try await backend.iataCoordinates()
            case .map: _ = try await backend.mapDefaults()
            }
        }
    }

    @Test(arguments: RequestCase.allCases)
    func preservesRequestContract(request: RequestCase) async throws {
        let session = Self.session(target: request.target, status: 418)
        defer { session.invalidateAndCancel() }
        let backend = try Self.backend(session: session)
        do {
            try await request.perform(using: backend)
            Issue.record("Expected the fixture HTTP error")
        } catch APIError.http(let status) {
            #expect(status == 418)
        }
    }

    @Test func preservesDecodedValuesAndCacheIdentity() async throws {
        let session = Self.session(
            target: "/api/config/map",
            body: #"{"center":[29.4,-98.5],"zoom":8}"#
        )
        defer { session.invalidateAndCancel() }
        let backend = try Self.backend(session: session)
        let result = try await backend.mapDefaults()
        #expect(result.center == [29.4, -98.5])
        #expect(result.zoom == 8)
        #expect(backend.cacheIdentifier == "https://backend.invalid")
        #expect(backend.capabilities == Set(BackendCapability.allCases))
    }

    @Test(arguments: ["2026-10-10T12:00:00Z", "2026-10-10T12:00:00.000Z"])
    func preservesObserverDateDecoding(timestamp: String) async throws {
        let session = Self.session(
            target: "/api/observers",
            body: "{\"observers\":[],\"server_time\":\"\(timestamp)\"}"
        )
        defer { session.invalidateAndCancel() }
        let result = try await Self.backend(session: session).observers()
        #expect(result.observers.isEmpty)
        #expect(result.serverTime == Date(timeIntervalSince1970: 1791633600))
    }

    @Test func preservesDecodingFailures() async throws {
        let session = Self.session(target: "/api/config/map", body: "{}")
        defer { session.invalidateAndCancel() }
        do {
            _ = try await Self.backend(session: session).mapDefaults()
            Issue.record("Expected a decoding error")
        } catch APIError.decoding {
        }
    }

    @Test func ignoresHeartbeatAndPreservesLivePacketFields() throws {
        #expect(try CoreScopeLiveFeed.decode(.string(#"{"type":"heartbeat"}"#)) == nil)
        let json = #"{"type":"packet","data":{"id":42,"hash":"abc","observer_id":"observer","observation_count":3,"resolved_path":["key",null]}}"#
        let textEvent = try #require(try CoreScopeLiveFeed.decode(.string(json)))
        let binaryEvent = try #require(try CoreScopeLiveFeed.decode(.data(Data(json.utf8))))
        #expect(textEvent.type == "packet")
        #expect(textEvent.id == 42)
        #expect(textEvent.data?.hash == "abc")
        #expect(textEvent.data?.observerId == "observer")
        #expect(textEvent.data?.observationCount == 3)
        #expect(textEvent.data?.resolvedPath == ["key", nil])
        #expect(binaryEvent.data?.hash == textEvent.data?.hash)
    }

    private static func backend(session: URLSession) throws -> CoreScopeBackend {
        let url = try #require(URL(string: "https://backend.invalid"))
        return CoreScopeBackend(client: APIClient(baseURL: url, session: session))
    }

    private static func session(target: String, status: Int = 200, body: String = "{}") -> URLSession {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [BackendFixtureProtocol.self]
        configuration.httpAdditionalHeaders = [
            "X-Fixture-Target": "https://backend.invalid" + target,
            "X-Fixture-Status": String(status),
            "X-Fixture-Body": body
        ]
        return URLSession(configuration: configuration)
    }
}

@MainActor
struct LiveFeedServiceTests {
    @Test func boundsEventsAndDiscardsDisconnectedFeedUpdates() async throws {
        let first = FixtureLiveFeed()
        let second = FixtureLiveFeed()
        var connections = 0
        let service = LiveFeedService(settings: AnalyzerSettings()) { _ in
            connections += 1
            return connections == 1 ? first : second
        }
        defer {
            service.disconnect()
            first.continuation.finish()
            second.continuation.finish()
        }
        service.connect()
        first.continuation.yield(.connected)
        for identifier in 1...205 {
            let json = "{\"type\":\"packet\",\"data\":{\"id\":\(identifier)}}"
            let event = try #require(try CoreScopeLiveFeed.decode(.string(json)))
            first.continuation.yield(.packet(event))
        }
        try await waitUntil { service.eventSequence == 205 }
        #expect(service.isConnected)
        #expect(service.recentEvents.count == 200)
        #expect(service.recentEvents.first?.id == 205)
        #expect(service.recentEvents.last?.id == 6)
        service.disconnect()
        #expect(first.disconnected)
        #expect(!service.isConnected)
        service.connect()
        first.continuation.yield(.disconnected("stale connection"))
        second.continuation.yield(.connected)
        try await waitUntil { service.isConnected }
        #expect(service.lastError == nil)
        #expect(service.eventSequence == 205)
        #expect(service.recentEvents.count == 200)
    }

    private func waitUntil(_ predicate: () -> Bool) async throws {
        for _ in 0..<120 {
            if predicate() { return }
            try await Task.sleep(for: .milliseconds(25))
        }
        #expect(predicate())
    }
}

@MainActor
private final class FixtureLiveFeed: AnalyzerLiveFeed {
    let events: AsyncStream<AnalyzerLiveEvent>
    let continuation: AsyncStream<AnalyzerLiveEvent>.Continuation
    private(set) var disconnected = false

    init() {
        let stream = AsyncStream<AnalyzerLiveEvent>.makeStream()
        events = stream.stream
        continuation = stream.continuation
    }

    func connect() {}
    func disconnect() { disconnected = true }
}

private final class BackendFixtureProtocol: URLProtocol, @unchecked Sendable {
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        guard let url = request.url,
              url.absoluteString == request.value(forHTTPHeaderField: "X-Fixture-Target"),
              let status = request.value(forHTTPHeaderField: "X-Fixture-Status").flatMap(Int.init),
              let response = HTTPURLResponse(url: url, statusCode: status, httpVersion: nil, headerFields: nil)
        else {
            client?.urlProtocol(self, didFailWithError: URLError(.badURL))
            return
        }
        let body = request.value(forHTTPHeaderField: "X-Fixture-Body") ?? "{}"
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(body.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}
