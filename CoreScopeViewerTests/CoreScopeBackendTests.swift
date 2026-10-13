import Foundation
import Testing
@testable import CoreScopeViewer

struct CoreScopeBackendTests {
    @Test func sourceInvalidationCancelsRegisteredWork() async throws {
        let source = AnalyzerSourceSession()
        let task = Task {
            try await Task.sleep(for: .seconds(30))
        }
        let identifier = source.registerCancellation { task.cancel() }
        source.invalidate()
        await #expect(throws: CancellationError.self) { try await task.value }
        source.removeCancellation(identifier)
        #expect(!source.isCurrent)
    }

    @Test func cacheClearDoesNotResurrectAnOldRefresh() async throws {
        let cache = APIResponseCache()
        let started = AsyncStream<Void>.makeStream()
        let release = AsyncStream<Void>.makeStream()
        let task = Task {
            try await cache.refresh(for: "test-clear-\(UUID())") {
                started.continuation.yield(())
                for await _ in release.stream { break }
                return "stale"
            }
        }
        for await _ in started.stream { break }
        await cache.clear()
        release.continuation.yield(())
        release.continuation.finish()
        started.continuation.finish()
        await #expect(throws: CancellationError.self) { _ = try await task.value }
    }
    enum RequestCase: String, CaseIterable, Sendable {
        case nodes, regionalNodes, node, health, paths, reach, neighbors, analytics
        case observers, observerAnalytics, packets, regionalPackets, channelPackets
        case packet, channels, regionalChannels, messages, regions, coordinates, map

        var target: String {
            switch self {
            case .nodes: "/api/nodes?limit=5000"
            case .regionalNodes: "/api/nodes?limit=5000&region=SAT"
            case .node: "/api/nodes/key%2Ftest"
            case .health: "/api/nodes/key%2Ftest/health"
            case .paths: "/api/nodes/key%2Ftest/paths"
            case .reach, .neighbors: "/api/nodes/key%2Ftest/reach"
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
            case .neighbors: _ = try await backend.neighbors(publicKey: "key/test")
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
        #expect(backend.cacheIdentifier == "corescope|https://backend.invalid")
        #expect(backend.capabilities == Set(BackendCapability.allCases))
    }

    @Test func neutralNeighborsReuseCoreScopeReachWithoutInventingSignal() async throws {
        let key = String(repeating: "ab", count: 32)
        let body = """
        {
          "node":{"pubkey":"node","name":"Node","role":"repeater","lat":null,"lon":null,"first_seen":"2026-10-01T00:00:00Z"},
          "window":{"days":7,"since":"2026-10-06T00:00:00Z"},
          "reliable_tokens":[],
          "importance":{"neighbor_degree":1,"degree_rank":1,"nodes_with_edges":1,"relay_observations":1,"bidirectional_links":1,"direct_observers":0},
          "direct_observers":[],
          "links":[{"pubkey":"\(key)","name":"Neighbor","role":"repeater","lat":30,"lon":-98,"we_hear":2,"they_hear":3,"bottleneck":2,"bidir":true,"distance_km":4.5}]
        }
        """
        let session = Self.session(
            target: "/api/nodes/key%2Fneutral/reach",
            body: body.replacingOccurrences(of: "\n", with: "")
        )
        defer { session.invalidateAndCancel() }

        let result = try await Self.backend(session: session).neighbors(publicKey: "key/neutral")
        let neighbor = try #require(result.neighbors.first)
        #expect(neighbor.identity.linkablePublicKey == key)
        #expect(neighbor.direction == .bidirectional)
        #expect(neighbor.signal.snr == nil && neighbor.signal.rssi == nil)
        #expect(neighbor.observationCount == nil && neighbor.lastHeard == nil)
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

    @Test func invalidatedSessionRejectsRequests() async throws {
        let session = Self.session(target: "/api/config/map")
        defer { session.invalidateAndCancel() }
        let backend = try Self.backend(session: session)
        backend.sourceSession.invalidate()
        await #expect(throws: CancellationError.self) {
            _ = try await backend.mapDefaults()
        }
    }

    @Test func rejectsInvalidPacketCursor() async throws {
        let session = Self.session(target: "/api/packets?limit=200")
        defer { session.invalidateAndCancel() }
        let backend = try Self.backend(session: session)
        await #expect(throws: AnalyzerPagingError.self) {
            _ = try await backend.packetPage(
                region: nil, pageSize: 200, payloadType: nil,
                cursor: AnalyzerCursor(value: "not-a-cursor")
            )
        }
    }

    @Test func pagingPreservesOrderAndDeduplicatesOverlaps() async throws {
        var requestedCursors: [AnalyzerCursor?] = []
        var requestedSizes: [Int] = []
        let next = AnalyzerCursor(value: "server-generated")
        let result = try await AnalyzerPaging.collect(limit: 4, identity: { $0 }) { cursor, size in
            requestedCursors.append(cursor)
            requestedSizes.append(size)
            return cursor == nil
                ? AnalyzerPage(items: [3, 1], nextCursor: next, total: nil)
                : AnalyzerPage(items: [1, 4, 2], nextCursor: nil, total: nil)
        }
        #expect(result == [3, 1, 4, 2])
        #expect(requestedCursors == [nil, next])
        #expect(requestedSizes == [4, 2])
    }

    @Test func pagingRejectsRepeatedCursor() async throws {
        await #expect(throws: AnalyzerPagingError.self) {
            _ = try await AnalyzerPaging.collect(limit: 3, identity: { $0 }) { _, _ in
                AnalyzerPage(items: [1], nextCursor: AnalyzerCursor(value: "same"), total: nil)
            }
        }
    }

    @Test func pagingEnforcesRequestBudget() async throws {
        var requests = 0
        await #expect(throws: AnalyzerPagingError.self) {
            _ = try await AnalyzerPaging.collect(limit: 10, maximumPages: 2, identity: { $0 }) { _, _ in
                requests += 1
                return AnalyzerPage(items: [requests], nextCursor: AnalyzerCursor(value: String(requests)), total: nil)
            }
        }
        #expect(requests == 2)
    }

    @Test func emptyPagingDoesNotFetch() async throws {
        let result: [Int] = try await AnalyzerPaging.collect(limit: 0, identity: { $0 }) { _, _ in
            Issue.record("Empty requests must not fetch")
            return AnalyzerPage(items: [], nextCursor: nil, total: nil)
        }
        #expect(result.isEmpty)
    }

    @Test func packetCursorRejectsDifferentFiltersAndSessions() async throws {
        let body = #"{"packets":[{"id":1,"hash":"first","first_seen":"2026-10-10T12:00:00Z","timestamp":"2026-10-10T12:00:00Z","route_type":1,"payload_type":5,"observation_count":1}],"total":2}"#
        let session = Self.session(target: "/api/packets?limit=1&region=SAT", body: body)
        defer { session.invalidateAndCancel() }
        let backend = try Self.backend(session: session)
        let page = try await backend.packetPage(region: "SAT", pageSize: 1, payloadType: nil, cursor: nil)
        let cursor = try #require(page.nextCursor)
        #expect(page.items.map(\.hash) == ["first"])
        #expect(page.total == 2)
        await #expect(throws: AnalyzerPagingError.self) {
            _ = try await backend.packetPage(region: "DFW", pageSize: 1, payloadType: nil, cursor: cursor)
        }
        let newSessionBackend = try Self.backend(session: session)
        await #expect(throws: AnalyzerPagingError.self) {
            _ = try await newSessionBackend.packetPage(region: "SAT", pageSize: 1, payloadType: nil, cursor: cursor)
        }
    }

    @Test func emptyPageTerminatesEvenWithNonzeroTotal() async throws {
        let session = Self.session(target: "/api/packets?limit=200", body: #"{"packets":[],"total":42}"#)
        defer { session.invalidateAndCancel() }
        let page = try await Self.backend(session: session).packetPage(
            region: nil, pageSize: 200, payloadType: nil, cursor: nil
        )
        #expect(page.items.isEmpty)
        #expect(page.nextCursor == nil)
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
    @Test func beaconMetadataReplacesStatusAndResetsWithSourceSession() async throws {
        let settings = AnalyzerSettings()
        let first = FixtureLiveFeed()
        let second = FixtureLiveFeed()
        var connections = 0
        let service = LiveFeedService(settings: settings) { _ in
            connections += 1
            return connections == 1 ? first : second
        }
        defer {
            service.disconnect()
            first.continuation.finish()
            second.continuation.finish()
        }
        let identifier = "00000000-0000-0000-0000-000000000001"
        let key = String(repeating: "ab", count: 32)
        let json = "{\"v\":1,\"type\":\"event\",\"event\":\"nodeUpdate\",\"data\":{\"nodeId\":\"\(identifier)\",\"publicKey\":\"\(key)\",\"nodeType\":2}}"
        guard case .node(let update) = try BeaconLiveProtocol.decode(Data(json.utf8)) else {
            Issue.record("Expected node update")
            return
        }
        let initial = BeaconLiveProtocol.ObserverStatus(observerId: identifier, displayName: "Station", iata: "SAT", online: true,
                                                       batteryMv: 4100, uptimeSeconds: 100, lastStatusAt: Date())
        let replacement = BeaconLiveProtocol.ObserverStatus(observerId: identifier, displayName: nil, iata: nil, online: false,
                                                           batteryMv: nil, uptimeSeconds: nil, lastStatusAt: nil)
        service.connect()
        first.continuation.yield(.nodeUpdated(update))
        first.continuation.yield(.observerStatus(initial))
        try await waitUntil { service.beaconNodes[key] != nil && service.beaconObservers[identifier]?.batteryMv == 4100 }
        first.continuation.yield(.observerStatus(replacement))
        try await waitUntil { service.beaconObservers[identifier]?.online == false }
        #expect(service.beaconObservers[identifier]?.batteryMv == nil)
        #expect(service.beaconObservers[identifier]?.iata == nil)
        #expect(service.beaconObservers[identifier]?.lastStatusAt == nil)
        settings.renewSourceSession()
        service.connect()
        #expect(service.beaconNodes.isEmpty && service.beaconObservers.isEmpty)
        first.continuation.yield(.nodeUpdated(update))
        first.continuation.yield(.observerStatus(initial))
        second.continuation.yield(.connected)
        try await waitUntil { service.isConnected }
        #expect(service.beaconNodes.isEmpty && service.beaconObservers.isEmpty)
    }

    @Test func browsePresentationModeFollowsFeedRatherThanSnapshotArrival() {
        let beacon = FixtureLiveFeed()
        beacon.usesBrowseSnapshots = true
        let coreScope = FixtureLiveFeed()
        var connections = 0
        let service = LiveFeedService(settings: AnalyzerSettings()) { _ in
            connections += 1
            return connections == 1 ? beacon : coreScope
        }
        defer {
            service.disconnect()
            beacon.continuation.finish()
            coreScope.continuation.finish()
        }
        service.connect()
        #expect(service.usesBrowseSnapshots)
        #expect(service.browseSnapshots.isEmpty)
        service.connect()
        #expect(!service.usesBrowseSnapshots)
        #expect(service.browseSnapshots.isEmpty)
    }

    @Test func browseSnapshotsResetOnSameHostSessionChangeAndRejectOldEvents() async throws {
        let settings = AnalyzerSettings()
        let first = FixtureLiveFeed()
        let second = FixtureLiveFeed()
        var connections = 0
        let service = LiveFeedService(settings: settings) { _ in
            connections += 1
            return connections == 1 ? first : second
        }
        defer {
            service.disconnect()
            first.continuation.finish()
            second.continuation.finish()
        }
        let filter = LiveBrowseFilter(region: "SAT", payloadType: nil)
        let snapshot = LiveBrowseSnapshot(filter: filter, rows: [], possiblyIncomplete: true)
        service.connect()
        first.continuation.yield(.browseSnapshot(snapshot))
        first.continuation.yield(.recoveryFailed(filter, "Offline"))
        try await waitUntil { service.browseSnapshots.count == 1 && service.recoveryErrors[filter] != nil }
        first.continuation.yield(.recoveryCompleted(filter))
        try await waitUntil { service.recoveryErrors[filter] == nil }
        settings.renewSourceSession()
        service.connect()
        #expect(service.browseSnapshots.isEmpty)
        first.continuation.yield(.browseSnapshot(snapshot))
        first.continuation.yield(.recoveryFailed(filter, "Stale"))
        second.continuation.yield(.connected)
        try await waitUntil { service.isConnected }
        #expect(service.browseSnapshots.isEmpty && service.recoveryErrors.isEmpty)
    }

    @Test func demandsMergeAcrossWindowsAndSurviveReconnect() async throws {
        let feed = FixtureLiveFeed()
        let service = LiveFeedService(settings: AnalyzerSettings()) { _ in feed }
        defer { service.disconnect() }
        let first = UUID()
        let second = UUID()
        service.connect()
        service.setDemand(LiveFeedDemand(configuration: LiveFeedConfiguration(resolvePath: true)), for: first)
        service.setDemand(LiveFeedDemand(configuration: LiveFeedConfiguration(resolvePath: true, includeObserverKey: true)), for: second)
        #expect(feed.configuration.resolvePath)
        #expect(feed.configuration.includeObserverKey)
        service.setDemand(nil, for: first)
        #expect(feed.configuration.resolvePath)
        feed.configure(LiveFeedConfiguration())
        feed.continuation.yield(.connected)
        try await waitUntil { feed.configuration.resolvePath }
        service.setDemand(nil, for: second)
        #expect(feed.configuration == LiveFeedConfiguration())
        #expect(feed.subscriptions == [LiveFeedSubscription()])
    }
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
    var usesBrowseSnapshots = false
    let events: AsyncStream<AnalyzerLiveEvent>
    let continuation: AsyncStream<AnalyzerLiveEvent>.Continuation
    private(set) var disconnected = false
    private(set) var subscriptions: Set<LiveFeedSubscription> = []
    private(set) var configuration = LiveFeedConfiguration()

    func subscribe(_ subscriptions: Set<LiveFeedSubscription>) { self.subscriptions = subscriptions }
    func configure(_ configuration: LiveFeedConfiguration) { self.configuration = configuration }

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
