import Foundation
import Testing
@testable import CoreScopeViewer

struct BeaconFeatureExplanationTests {
    @Test func unsupportedErrorsUseFeatureSpecificExplanations() {
        let cases: [(String, BackendCapability)] = [
            ("Node analytics", .nodeAnalytics), ("Observer analytics", .observerAnalytics),
            ("Channels", .serverChannels), ("Channel messages", .serverChannels),
            ("Node health", .nodeHealth), ("Node paths", .nodePaths),
            ("Node reach", .nodeReach), ("Map defaults", .mapDefaults)
        ]
        for (feature, capability) in cases {
            #expect(BeaconBackendError.unsupported(feature).errorDescription == capability.beaconExplanation)
        }
    }

    @Test func channelLimitationsHaveDistinctExplanations() {
        #expect(BackendCapability.serverChannels.beaconExplanation != BackendCapability.channelDecryptOnPhone.beaconExplanation)
        #expect(BeaconBackendError.nodeUnavailable.errorDescription != BackendCapability.nodeAnalytics.beaconExplanation)
    }
}

private final class SourceDiscoveryFixtureProtocol: URLProtocol, @unchecked Sendable {
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func stopLoading() {}

    override func startLoading() {
        guard let url = request.url, ["/api/v1/info", "/api/config/map"].contains(url.path) else {
            client?.urlProtocol(self, didFailWithError: URLError(.badURL))
            return
        }
        let status = url.path == "/api/v1/info" ? 404 : 200
        guard let response = HTTPURLResponse(url: url, statusCode: status, httpVersion: nil, headerFields: nil) else { return }
        let body = request.value(forHTTPHeaderField: "X-Valid-CoreScope") == "true"
            ? #"{"center":[30,-98],"zoom":8}"# : #"{"unrelated":true}"#
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(body.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }
}

private actor BeaconNodeRecoveryProbe {
    private(set) var count = 0
    private var pending: CheckedContinuation<[BeaconNodeReader.Node], Error>?

    func fetch() async throws -> [BeaconNodeReader.Node] {
        count += 1
        return try await withCheckedThrowingContinuation { pending = $0 }
    }

    func succeed() {
        pending?.resume(returning: [])
        pending = nil
    }

    func fail() {
        pending?.resume(throwing: URLError(.notConnectedToInternet))
        pending = nil
    }

    func waitForCount(_ expected: Int) async throws {
        for _ in 0..<2000 {
            if count >= expected { return }
            try await Task.sleep(for: .milliseconds(1))
        }
        throw URLError(.timedOut)
    }
}

private actor BeaconObserverRecoveryProbe {
    private(set) var count = 0
    private var pending: CheckedContinuation<[BeaconObserverReader.Observer], Error>?

    func fetch() async throws -> [BeaconObserverReader.Observer] {
        count += 1
        return try await withCheckedThrowingContinuation { pending = $0 }
    }

    func complete(_ records: [BeaconObserverReader.Observer]) {
        pending?.resume(returning: records)
        pending = nil
    }

    func fail() {
        pending?.resume(throwing: URLError(.notConnectedToInternet))
        pending = nil
    }

    func waitForCount(_ expected: Int) async throws {
        for _ in 0..<2000 {
            if count >= expected { return }
            try await Task.sleep(for: .milliseconds(1))
        }
        throw URLError(.timedOut)
    }
}

struct BeaconAPITests {
    @Test @MainActor func listedBeaconNeverFallsBackToCoreScope() async throws {
        let suite = "ListedBeacon-\(UUID())"
        let defaults = try #require(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [SourceDiscoveryFixtureProtocol.self]
        configuration.httpAdditionalHeaders = ["X-Valid-CoreScope": "true"]
        let session = URLSession(configuration: configuration)
        defer { session.invalidateAndCancel() }
        for knownCoreScope in [false, true] {
            let settings = AnalyzerSettings()
            await settings.resolveSource(knownCoreScope: knownCoreScope, requiresBeacon: true,
                                         catalog: BeaconServerCatalog(suiteName: suite), session: session)
            #expect(!settings.sourceIsReady)
            #expect(settings.backendSelectionError != nil)
            #expect(settings.beaconBackend == nil)
        }
    }

    @Test @MainActor func absentBeaconEndpointRequiresPositiveCoreScopeResponse() async throws {
        let suite = "CoreScopeDetection-\(UUID())"
        let defaults = try #require(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        for valid in [true, false] {
            let configuration = URLSessionConfiguration.ephemeral
            configuration.protocolClasses = [SourceDiscoveryFixtureProtocol.self]
            configuration.httpAdditionalHeaders = ["X-Valid-CoreScope": String(valid)]
            let session = URLSession(configuration: configuration)
            defer { session.invalidateAndCancel() }
            let settings = AnalyzerSettings()
            await settings.resolveSource(knownCoreScope: false, catalog: BeaconServerCatalog(suiteName: suite), session: session)
            #expect(settings.sourceIsReady == valid)
            #expect((settings.backendSelectionError == nil) == valid)
            #expect(settings.beaconBackend == nil)
        }
    }

    @Test @MainActor func startupRestoresRememberedBeaconBeforeOpeningScreens() async throws {
        let suite = "BeaconStartup-\(UUID())"
        let defaults = try #require(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let catalog = BeaconServerCatalog(suiteName: suite)
        let healthy = Self.session(targets: ["/api/v1/info"], body: #"{"serverVersion":"2.0.3"}"#)
        let offline = Self.session(targets: [])
        defer { healthy.invalidateAndCancel(); offline.invalidateAndCancel() }
        let first = AnalyzerSettings()
        #expect(!first.sourceIsReady)
        await first.resolveSource(knownCoreScope: false, catalog: catalog, session: healthy)
        #expect(first.sourceIsReady && first.beaconBackend != nil && first.backendSelectionError == nil)
        let restarted = AnalyzerSettings()
        let originalID = restarted.sourceSession.id
        await restarted.resolveSource(knownCoreScope: true, catalog: catalog, session: offline)
        #expect(restarted.sourceIsReady && restarted.beaconBackend != nil)
        #expect(restarted.sourceSession.id != originalID)
    }

    @Test @MainActor func knownLegacySourceDoesNotRequireBeaconProbe() async throws {
        let suite = "CoreScopeStartup-\(UUID())"
        let defaults = try #require(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let session = Self.session(targets: [])
        defer { session.invalidateAndCancel() }
        let settings = AnalyzerSettings()
        await settings.resolveSource(knownCoreScope: true, catalog: BeaconServerCatalog(suiteName: suite), session: session)
        #expect(settings.sourceIsReady && settings.beaconBackend == nil && settings.backendSelectionError == nil)
    }

    @Test @MainActor func unknownSourceFailureKeepsStartupGatedAndCanRetry() async throws {
        let suite = "BeaconStartupFailure-\(UUID())"
        let defaults = try #require(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let catalog = BeaconServerCatalog(suiteName: suite)
        let failing = Self.session(targets: ["/api/v1/info"], status: 503)
        let healthy = Self.session(targets: ["/api/v1/info"], body: #"{"serverVersion":"2.0.3"}"#)
        defer { failing.invalidateAndCancel(); healthy.invalidateAndCancel() }
        let settings = AnalyzerSettings()
        await settings.resolveSource(knownCoreScope: false, catalog: catalog, session: failing)
        #expect(!settings.sourceIsReady && settings.backendSelectionError != nil && settings.beaconBackend == nil)
        let resolutionID = settings.sourceResolutionID
        settings.requestSourceResolution()
        #expect(settings.sourceResolutionID != resolutionID && settings.backendSelectionError == nil)
        await settings.resolveSource(knownCoreScope: false, catalog: catalog, session: healthy)
        #expect(settings.sourceIsReady && settings.beaconBackend != nil)
    }

    @Test @MainActor func unsupportedRememberedBeaconDoesNotOpenCoreScopeScreens() async throws {
        let suite = "BeaconStartupUnsupported-\(UUID())"
        let defaults = try #require(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let catalog = BeaconServerCatalog(suiteName: suite)
        let session = Self.session(targets: ["/api/v1/info"], body: #"{"serverVersion":"2.0.2"}"#)
        defer { session.invalidateAndCancel() }
        let settings = AnalyzerSettings()
        let client = try BeaconAPIClient(baseURL: settings.baseURL, sourceSession: settings.sourceSession, session: session)
        _ = try await catalog.probe(using: client)
        await settings.resolveSource(knownCoreScope: true, catalog: catalog, session: session)
        #expect(!settings.sourceIsReady && settings.backendSelectionError != nil && settings.beaconBackend == nil)
    }

    @Test @MainActor func beaconDiagnosticsUsesInfoWithoutCoreScopeCapabilityRequests() async throws {
        let settings = AnalyzerSettings()
        let session = Self.session(targets: ["/api/v1/info"], body: #"{"serverVersion":"2.0.5","minAppVersion":"99.0.0"}"#)
        defer { session.invalidateAndCancel() }
        try settings.selectBeacon(verifiedServerInfo: .init(serverVersion: "2.0.3"), expectedSessionID: settings.sourceSession.id, session: session)
        let sourceID = settings.sourceSession.id
        let report = await AnalyzerDiagnosticsService(settings: settings).run()
        #expect(report.backendType == "Beacon" && report.serverVersion == "2.0.5")
        #expect(report.connectionLevel == .success && report.compatibilityLevel == .success)
        #expect(report.capabilities.count == AnalyzerCapability.allCases.count)
        #expect(report.capabilities.allSatisfy { $0.level == .warning })
        #expect(settings.sourceSession.id == sourceID)
        #expect(settings.beaconBackend?.serverInfo.serverVersion == "2.0.3")
    }

    @Test @MainActor func beaconDiagnosticsReportsUnsupportedVersionWithoutChangingSelection() async throws {
        let settings = AnalyzerSettings()
        let session = Self.session(targets: ["/api/v1/info"], body: #"{"serverVersion":"2.0.2"}"#)
        defer { session.invalidateAndCancel() }
        try settings.selectBeacon(verifiedServerInfo: .init(serverVersion: "2.0.3"), expectedSessionID: settings.sourceSession.id, session: session)
        let sourceID = settings.sourceSession.id
        let report = await AnalyzerDiagnosticsService(settings: settings).run()
        #expect(report.connectionLevel == .success && report.compatibilityLevel == .failure)
        #expect(report.serverVersion == "2.0.2")
        #expect(settings.sourceSession.id == sourceID && settings.beaconBackend != nil)
    }

    @Test @MainActor func failedBeaconDiagnosticsKeepsKnownTypeAndVersion() async throws {
        let settings = AnalyzerSettings()
        let session = Self.session(targets: ["/api/v1/info"], status: 503)
        defer { session.invalidateAndCancel() }
        try settings.selectBeacon(verifiedServerInfo: .init(serverVersion: "2.0.3"), expectedSessionID: settings.sourceSession.id, session: session)
        let report = await AnalyzerDiagnosticsService(settings: settings).run()
        #expect(report.backendType == "Beacon" && report.serverVersion == "2.0.3")
        #expect(report.compatibilityLevel == .warning)
        #expect(AnalyzerBackendFactory.make(settings: settings) is BeaconBackend)
    }

    @Test @MainActor func detectionCommitsBothFactoriesAndReconnectsWithSharedIdentity() async throws {
        let suite = "BeaconSelection-\(UUID())"
        let defaults = try #require(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let settings = AnalyzerSettings()
        let original = settings.sourceSession
        let catalog = BeaconServerCatalog(suiteName: suite)
        let session = Self.session(targets: ["/api/v1/info"], body: #"{"serverVersion":"2.0.3"}"#)
        defer { session.invalidateAndCancel() }
        try await AnalyzerBackendFactory.detectAndSelectBeacon(settings: settings, catalog: catalog, session: session)
        #expect(!original.isCurrent && settings.sourceSession.id != original.id)
        let backend = try #require(AnalyzerBackendFactory.make(settings: settings) as? BeaconBackend)
        #expect(backend.sourceSession.id == settings.sourceSession.id)
        let first = try #require(AnalyzerBackendFactory.makeLiveFeed(settings: settings) as? BeaconLiveFeed)
        first.disconnect()
        let second = try #require(AnalyzerBackendFactory.makeLiveFeed(settings: settings) as? BeaconLiveFeed)
        defer { second.disconnect() }
        #expect(first !== second)
        #expect(first.observers === backend.observerReader && second.observers === backend.observerReader)
        let selectedID = settings.sourceSession.id
        try await AnalyzerBackendFactory.detectAndSelectBeacon(settings: settings, catalog: catalog, session: session)
        #expect(settings.sourceSession.id == selectedID)
    }

    @Test @MainActor func failedRecheckDoesNotChangeSelectedBeaconOrItsSession() async throws {
        let suite = "BeaconSelectionFailure-\(UUID())"
        let defaults = try #require(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let settings = AnalyzerSettings()
        let catalog = BeaconServerCatalog(suiteName: suite)
        let session = Self.session(targets: ["/api/v1/info"], status: 503)
        defer { session.invalidateAndCancel() }
        try settings.selectBeacon(verifiedServerInfo: .init(serverVersion: "2.0.3"), expectedSessionID: settings.sourceSession.id, session: session)
        let selected = settings.sourceSession
        await #expect(throws: BeaconAPIError.self) {
            try await AnalyzerBackendFactory.detectAndSelectBeacon(settings: settings, catalog: catalog, session: session, refreshKnown: true)
        }
        #expect(settings.sourceSession.id == selected.id && selected.isCurrent)
        #expect(AnalyzerBackendFactory.make(settings: settings) is BeaconBackend)
        #expect(throws: BeaconAPIError.self) {
            try settings.selectBeacon(verifiedServerInfo: .init(serverVersion: "2.0.2"), expectedSessionID: selected.id)
        }
        #expect(settings.sourceSession.id == selected.id)
    }

    @Test @MainActor func renewingSelectedBeaconRebuildsReadersWithoutCoreScopeFallback() throws {
        let settings = AnalyzerSettings()
        try settings.selectBeacon(verifiedServerInfo: .init(serverVersion: "2.0.3"), expectedSessionID: settings.sourceSession.id)
        let first = try #require(settings.beaconBackend)
        settings.renewSourceSession()
        let next = try #require(AnalyzerBackendFactory.make(settings: settings) as? BeaconBackend)
        #expect(!first.sourceSession.isCurrent)
        #expect(next.sourceSession.id == settings.sourceSession.id && next.sourceSession.isCurrent)
        #expect(next.observerReader !== first.observerReader)
        #expect(next.cacheIdentifier == first.cacheIdentifier)
        #expect(settings.backendSelectionError == nil)
        #expect(throws: CancellationError.self) {
            try settings.selectBeacon(verifiedServerInfo: .init(serverVersion: "2.0.5"), expectedSessionID: first.sourceSession.id)
        }
        #expect(settings.beaconBackend?.serverInfo.serverVersion == "2.0.3")
    }

    @Test @MainActor func beaconPreparationUsesPositiveDetectionWithoutChangingActiveSource() async throws {
        let suite = "BeaconPreparation-\(UUID())"
        let defaults = try #require(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let settings = AnalyzerSettings()
        let originalSession = settings.sourceSession
        let catalog = BeaconServerCatalog(suiteName: suite)
        let healthy = Self.session(targets: ["/api/v1/info"], body: #"{"serverVersion":"2.0.3","minAppVersion":"99.0.0"}"#)
        let offline = Self.session(targets: [])
        defer { healthy.invalidateAndCancel(); offline.invalidateAndCancel() }
        let prepared = try await AnalyzerBackendFactory.prepareBeacon(settings: settings, catalog: catalog, session: healthy)
        defer { prepared.feed.disconnect() }
        #expect(prepared.backend.sourceSession.id == originalSession.id)
        #expect(prepared.backend.observerReader === prepared.feed.observers)
        #expect(settings.sourceSession.id == originalSession.id && originalSession.isCurrent)
        #expect(AnalyzerBackendFactory.make(settings: settings) is CoreScopeBackend)
        let restored = try await AnalyzerBackendFactory.prepareBeacon(settings: settings, catalog: catalog, session: offline)
        restored.feed.disconnect()
        #expect(restored.backend.cacheIdentifier == prepared.backend.cacheIdentifier)
    }

    @Test func cachedBeaconResolutionRejectsObsoleteSessionsAndOtherHosts() async throws {
        let suite = "BeaconResolution-\(UUID())"
        let defaults = try #require(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let catalog = BeaconServerCatalog(suiteName: suite)
        let healthy = Self.session(targets: ["/api/v1/info"], body: #"{"serverVersion":"2.0.3"}"#)
        let failing = Self.session(targets: ["/api/v1/info"], status: 503)
        defer { healthy.invalidateAndCancel(); failing.invalidateAndCancel() }
        let client = try Self.client(healthy)
        #expect(try await catalog.resolve(using: client).serverVersion == "2.0.3")
        let failedRecheck = try Self.client(failing)
        await #expect(throws: BeaconAPIError.self) { _ = try await catalog.resolve(using: failedRecheck, refreshKnown: true) }
        #expect(try await catalog.resolve(using: failedRecheck).serverVersion == "2.0.3")
        let otherHost = try Self.client(failing, host: "other.invalid")
        await #expect(throws: BeaconAPIError.self) { _ = try await catalog.resolve(using: otherHost) }
        #expect(await catalog.knownInfo(for: otherHost) == nil)
        client.sourceSession.invalidate()
        await #expect(throws: CancellationError.self) { _ = try await catalog.resolve(using: client) }
    }

    @Test func positiveUnsupportedBeaconResolutionNeverFallsBackToCoreScope() async throws {
        let suite = "BeaconUnsupported-\(UUID())"
        let defaults = try #require(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let catalog = BeaconServerCatalog(suiteName: suite)
        let session = Self.session(targets: ["/api/v1/info"], body: #"{"serverVersion":"2.0.2"}"#)
        defer { session.invalidateAndCancel() }
        let client = try Self.client(session)
        await #expect(throws: BeaconAPIError.self) { _ = try await catalog.resolve(using: client) }
        #expect(await catalog.knownInfo(for: client)?.serverVersion == "2.0.2")
        session.invalidateAndCancel()
        await #expect(throws: BeaconAPIError.self) { _ = try await catalog.resolve(using: client) }
    }

    @Test @MainActor func beaconFactorySharesObserverReaderAndRejectsMismatchedSessions() async throws {
        let session = Self.session(targets: [])
        defer { session.invalidateAndCancel() }
        let client = try Self.client(session)
        let socket = BeaconTestSocket()
        let info = BeaconServerInfo(serverVersion: "2.0.3")
        let transport = try BeaconLiveTransport(client: client, verifiedServerInfo: info, makeSocket: { _ in socket })
        let pair = try AnalyzerBackendFactory.makeBeacon(client: client, verifiedServerInfo: info, transport: transport)
        defer { pair.feed.disconnect() }
        #expect(pair.feed.observers === pair.backend.observerReader)
        #expect(pair.backend.sourceSession.id == client.sourceSession.id)
        #expect(pair.backend.cacheIdentifier == client.cacheIdentifier)
        #expect(pair.backend.capabilities == [.neighbors, .nodeAnalytics])
        let other = try BeaconObserverReader(client: Self.client(session), verifiedServerInfo: info)
        #expect(throws: APIError.self) {
            _ = try BeaconLiveFeed(client: client, verifiedServerInfo: info, transport: transport, observerReader: other)
        }
        #expect(throws: BeaconAPIError.self) {
            _ = try BeaconBackend(client: client, verifiedServerInfo: .init(serverVersion: "2.0.2"))
        }
    }

    @Test func beaconBackendProjectsNodesAndObserversWithoutInventingMetadata() async throws {
        let routes: [String: Any] = [
            "/api/v1/nodes?limit=200": try Self.fixtureBody("node-by-pubkey"),
            "/api/v1/observers?limit=200": try Self.fixtureBody("observers-page")
        ]
        let session = Self.session(targets: Array(routes.keys),
                                   routes: String(decoding: try JSONSerialization.data(withJSONObject: routes), as: UTF8.self))
        defer { session.invalidateAndCancel() }
        let backend: any AnalyzerBackend = try BeaconBackend(client: Self.client(session), verifiedServerInfo: .init(serverVersion: "2.0.3"))
        let nodes = try await backend.nodes(region: nil)
        #expect(nodes.total == 1 && nodes.counts.repeaters == 1)
        #expect(nodes.nodes.first?.firstSeen == nil && nodes.nodes.first?.lastHeard != nil)
        let observers = try await backend.observers()
        #expect(observers.observers.count == 5 && observers.serverTime == nil)
        #expect(observers.observers.allSatisfy { $0.packetCount == nil && $0.lastSeen == nil })
    }

    @Test func beaconBackendBasicDetailUsesKeyLookupWithoutInventingAdverts() async throws {
        let fixture = try Self.fixtureBody("node-detail")
        let key = try #require(fixture["publicKey"] as? String)
        let identifier = try #require(fixture["id"] as? String)
        let session = Self.session(targets: ["/api/v1/nodes?pubkey=\(key)", "/api/v1/nodes/\(identifier)"],
                                   fixture: "node-by-pubkey", detailFixture: "node-detail")
        defer { session.invalidateAndCancel() }
        let backend = try BeaconBackend(client: Self.client(session), verifiedServerInfo: .init(serverVersion: "2.0.3"))
        let detail = try await backend.nodeDetail(publicKey: key)
        #expect(detail.node.publicKey == key && detail.node.firstSeen != nil)
        #expect(detail.recentAdverts == nil && detail.node.advertCount == nil)
    }

    @Test func beaconBackendRejectsUnsupportedEndpointsWithoutRequests() async throws {
        let session = Self.session(targets: [])
        defer { session.invalidateAndCancel() }
        let backend = try BeaconBackend(client: Self.client(session), verifiedServerInfo: .init(serverVersion: "2.0.3"))
        await #expect(throws: BeaconBackendError.self) { _ = try await backend.nodeHealth(publicKey: "node") }
        await #expect(throws: BeaconBackendError.self) { _ = try await backend.nodePaths(publicKey: "node") }
        await #expect(throws: BeaconBackendError.self) { _ = try await backend.nodeReach(publicKey: "node") }
        await #expect(throws: BeaconBackendError.self) { _ = try await backend.observerAnalytics(id: "observer") }
        await #expect(throws: BeaconBackendError.self) { _ = try await backend.channels(region: nil) }
        await #expect(throws: BeaconBackendError.self) { _ = try await backend.channelMessages(id: "1", offset: 0) }
        await #expect(throws: BeaconBackendError.self) { _ = try await backend.mapDefaults() }
        backend.sourceSession.invalidate()
        await #expect(throws: CancellationError.self) { _ = try await backend.nodes(region: nil) }
        await #expect(throws: CancellationError.self) { _ = try await backend.mapDefaults() }
    }

    @Test func beaconBackendExposesRegionNamesAndCoordinates() async throws {
        let session = try Self.regionSession()
        defer { session.invalidateAndCancel() }
        let backend = try BeaconBackend(client: Self.client(session), verifiedServerInfo: .init(serverVersion: "2.0.3"))
        #expect(try await backend.regions()["SAT"] == "Observer 011")
        #expect(try await backend.iataCoordinates().coords["SAT"]?.lat == 39.5337)
    }

    @Test @MainActor func observerDetailLoadsByUUIDAndLearnsKeyOnDemand() async throws {
        let settings = AnalyzerSettings()
        let body = try Self.fixtureBody("observer-detail")
        let identifier = try #require(body["id"] as? String)
        let key = try #require(body["publicKey"] as? String)
        let session = Self.session(targets: ["/api/v1/observers/\(identifier)"], fixture: "observer-detail")
        defer { session.invalidateAndCancel() }
        let client = try BeaconAPIClient(baseURL: settings.baseURL, sourceSession: settings.sourceSession, session: session)
        let socket = BeaconTestSocket()
        let transport = try BeaconLiveTransport(client: client, verifiedServerInfo: .init(serverVersion: "2.0.3"), makeSocket: { _ in socket })
        let feed = try BeaconLiveFeed(client: client, verifiedServerInfo: .init(serverVersion: "2.0.3"), transport: transport)
        let service = LiveFeedService(settings: settings) { _ in feed }
        defer { service.disconnect() }
        service.connect()
        #expect(try await feed.observers.observerDetail(publicKey: key) == nil)
        let model = ObserversViewModel()
        await model.loadBeaconDetail(id: identifier, sourceSessionID: settings.sourceSession.id, service: service)
        #expect(model.beaconDetail?.id == identifier)
        #expect(model.beaconDetail?.model == body["hardwareModel"] as? String)
        #expect(model.beaconDetail?.firstSeen != nil && model.beaconDetail?.lastSeen != nil)
        #expect(model.beaconDetail?.packetCount == nil && model.analytics == nil)
        #expect(model.errorMessage == nil && !model.isLoading)
        #expect(try await feed.observers.observerDetail(publicKey: key)?.id == identifier)
    }

    @Test @MainActor func observerDetailRetriesAndClearsWhenSourceChanges() async throws {
        let settings = AnalyzerSettings()
        let feed = BeaconDetailTestFeed()
        let service = LiveFeedService(settings: settings) { _ in feed }
        defer { service.disconnect() }
        service.connect()
        let model = ObserversViewModel()
        let first = Task { await model.loadBeaconDetail(id: "observer", sourceSessionID: settings.sourceSession.id, service: service) }
        try await Self.waitUntil { feed.pendingObserver != nil }
        feed.pendingObserver?.resume(throwing: BeaconAPIError.server(status: 404, code: "not_found"))
        feed.pendingObserver = nil
        await first.value
        #expect(model.errorMessage != nil && model.beaconDetail == nil && !model.isLoading)
        let retry = Task { await model.loadBeaconDetail(id: "observer", sourceSessionID: settings.sourceSession.id, service: service) }
        try await Self.waitUntil { feed.pendingObserver != nil }
        let observer = try JSONDecoder().decode(MeshObserver.self, from: Data(#"{"id":"observer","model":"Fixture"}"#.utf8))
        feed.pendingObserver?.resume(returning: observer)
        feed.pendingObserver = nil
        await retry.value
        #expect(model.beaconDetail?.model == "Fixture" && model.errorMessage == nil)
        settings.renewSourceSession()
        await model.loadBeaconDetail(id: "observer", sourceSessionID: settings.sourceSession.id, service: service)
        #expect(model.beaconDetail == nil && model.errorMessage != nil)
        #expect(feed.observerRequests == 2)
    }

    @Test @MainActor func observerDetailRejectsLateDisconnectedResponse() async throws {
        let settings = AnalyzerSettings()
        let feed = BeaconDetailTestFeed()
        let service = LiveFeedService(settings: settings) { _ in feed }
        defer { service.disconnect() }
        service.connect()
        let model = ObserversViewModel()
        let request = Task { await model.loadBeaconDetail(id: "observer", sourceSessionID: settings.sourceSession.id, service: service) }
        try await Self.waitUntil { feed.pendingObserver != nil }
        service.disconnect()
        let observer = try JSONDecoder().decode(MeshObserver.self, from: Data(#"{"id":"observer"}"#.utf8))
        feed.pendingObserver?.resume(returning: observer)
        feed.pendingObserver = nil
        await request.value
        #expect(model.beaconDetail == nil && model.errorMessage != nil && !model.isLoading)
    }

    @Test @MainActor func observerRecoveryUsesListReaderWithoutDetailFanoutOrExtraWireFlags() async throws {
        let settings = AnalyzerSettings()
        let routes: [String: Any] = [
            "/api/v1/packets?limit=200": ["items": [], "hasMore": false],
            "/api/v1/observers?limit=200": try Self.fixtureBody("observers-page")
        ]
        let session = Self.session(targets: Array(routes.keys),
                                   routes: String(decoding: try JSONSerialization.data(withJSONObject: routes), as: UTF8.self))
        defer { session.invalidateAndCancel() }
        let client = try BeaconAPIClient(baseURL: settings.baseURL, sourceSession: settings.sourceSession, session: session)
        let socket = BeaconTestSocket()
        let transport = try BeaconLiveTransport(client: client, verifiedServerInfo: .init(serverVersion: "2.0.3"), makeSocket: { _ in socket })
        let feed = try BeaconLiveFeed(client: client, verifiedServerInfo: .init(serverVersion: "2.0.3"), transport: transport)
        let service = LiveFeedService(settings: settings) { _ in feed }
        defer { service.disconnect() }
        service.setDemand(.init(configuration: .init(recoverObservers: true)), for: UUID())
        service.connect()
        socket.push(#"{"v":1,"type":"hello"}"#)
        try await Self.waitUntil {
            service.recoveredObservers(sourceSessionID: settings.sourceSession.id)?.count == 5
        }
        #expect(service.observerRecoveryError == nil && !service.observerNeedsRecovery)
        #expect(service.recoveredObservers(sourceSessionID: UUID()) == nil)
        let command = try #require(socket.sent.first { $0["type"] as? String == "configure" })
        #expect(command["recoverObservers"] == nil)
        #expect(command["resolvePath"] as? Bool == false)
        settings.renewSourceSession()
        #expect(service.recoveredObservers(sourceSessionID: settings.sourceSession.id) == nil)
    }

    @Test @MainActor func observerRecoverySharesDemandPreservesLiveStatusAndRetriesFailures() async throws {
        let settings = AnalyzerSettings()
        let session = Self.session(targets: ["/api/v1/packets?limit=200"], body: #"{"items":[],"hasMore":false}"#)
        defer { session.invalidateAndCancel() }
        let client = try BeaconAPIClient(baseURL: settings.baseURL, sourceSession: settings.sourceSession, session: session)
        let socket = BeaconTestSocket()
        let probe = BeaconObserverRecoveryProbe()
        let transport = try BeaconLiveTransport(client: client, verifiedServerInfo: .init(serverVersion: "2.0.3"), makeSocket: { _ in socket })
        let feed = try BeaconLiveFeed(client: client, verifiedServerInfo: .init(serverVersion: "2.0.3"), transport: transport,
                                      fetchObservers: { try await probe.fetch() })
        let service = LiveFeedService(settings: settings) { _ in feed }
        defer { service.disconnect() }
        let body = try Self.fixtureBody("observers-page")
        let records = try JSONDecoder().decode([BeaconObserverReader.Observer].self,
                                               from: JSONSerialization.data(withJSONObject: try #require(body["items"])))
        let identifier = try #require(records.first?.id).lowercased()
        service.connect()
        socket.push(#"{"v":1,"type":"hello"}"#)
        try await Self.waitUntil { service.observerNeedsRecovery }
        #expect(await probe.count == 0)
        let firstConsumer = UUID()
        service.setDemand(.init(configuration: .init(recoverObservers: true)), for: firstConsumer)
        service.setDemand(.init(configuration: .init(recoverObservers: true)), for: UUID())
        try await probe.waitForCount(1)
        service.setDemand(nil, for: firstConsumer)
        let sequence = service.beaconObserverSequence
        socket.push(#"{"v":1,"type":"lagged","droppedCount":1}"#)
        try await Self.waitUntil { service.beaconObserverSequence > sequence }
        await probe.complete(records)
        try await probe.waitForCount(2)
        #expect(service.recoveredObservers(sourceSessionID: settings.sourceSession.id) == nil)
        socket.push("{\"v\":1,\"type\":\"event\",\"event\":\"observerStatus\",\"data\":{\"observerId\":\"\(identifier)\",\"online\":false,\"batteryMv\":3900}}")
        try await Self.waitUntil { service.beaconObservers[identifier] != nil }
        await probe.complete(records)
        try await Self.waitUntil { !service.observerNeedsRecovery }
        let catalog = try #require(service.recoveredObservers(sourceSessionID: settings.sourceSession.id))
        #expect(catalog.count == records.count && catalog.first?.online == true)
        let status = try #require(service.beaconObserverStatuses(sourceSessionID: settings.sourceSession.id)[identifier])
        let projected = status.applying(to: try #require(catalog.first))
        #expect(projected.online == false && projected.batteryMv == 3900 && projected.lastSeen == nil)
        service.refreshObservers()
        try await probe.waitForCount(3)
        try await Self.waitUntil { service.observerNeedsRecovery }
        #expect(service.recoveredObservers(sourceSessionID: settings.sourceSession.id)?.first?.online == nil)
        #expect(service.beaconObservers.isEmpty)
        await probe.fail()
        try await Self.waitUntil { service.observerRecoveryError != nil }
        #expect(service.recoveredObservers(sourceSessionID: settings.sourceSession.id)?.count == records.count)
        service.refreshObservers()
        try await probe.waitForCount(4)
        await probe.complete(records)
        try await Self.waitUntil { !service.observerNeedsRecovery }
        #expect(service.observerRecoveryError == nil)
        service.refreshObservers()
        try await probe.waitForCount(5)
        service.disconnect()
        await probe.complete(records)
        await Task.yield()
        #expect(service.recoveredObservers(sourceSessionID: settings.sourceSession.id) == nil)
    }

    @Test func observerStatusProjectionClearsMissingFieldsWithoutInventingActivity() throws {
        let identifier = "00000000-0000-0000-0000-000000000001"
        let observer = try JSONDecoder().decode(MeshObserver.self, from: Data("{\"id\":\"\(identifier)\",\"name\":\"Old\",\"battery_mv\":4000,\"uptime_secs\":50,\"radio\":\"Old radio\"}".utf8))
        let status = BeaconLiveProtocol.ObserverStatus(observerId: identifier.uppercased(), displayName: nil, iata: nil,
                                                       online: true, batteryMv: nil, uptimeSeconds: nil, lastStatusAt: Date())
        let projected = status.applying(to: observer)
        #expect(projected.online == true && projected.lastStatusAt != nil)
        #expect(projected.name == nil && projected.radio == nil && projected.batteryMv == nil && projected.uptimeSecs == nil)
        #expect(projected.firstSeen == nil && projected.lastSeen == nil && !projected.isRecentlySeen)
        #expect(projected.packetCount == nil && projected.packetsLastHour == nil)
        let other = try JSONDecoder().decode(MeshObserver.self, from: Data(#"{"id":"other"}"#.utf8))
        #expect(status.applying(to: other) == other)
    }

    @Test func beaconObserverMappingPreservesUnknownsAndDoesNotConvertBatteryPercentToMillivolts() throws {
        let object: [String: Any] = [
            "id": "00000000-0000-0000-0000-000000000001", "displayName": "Station",
            "status": "online", "batteryLevel": 80, "iata": "SAT"
        ]
        let record = try JSONDecoder().decode(BeaconObserverReader.Observer.self, from: JSONSerialization.data(withJSONObject: object))
        let mapped = record.meshObserver
        #expect(mapped.name == "Station" && mapped.iata == "SAT")
        #expect(mapped.firstSeen == nil && mapped.lastSeen == nil)
        #expect(mapped.packetCount == nil && mapped.packetsLastHour == nil && mapped.batteryMv == nil)
        #expect(mapped.lat == nil && mapped.lon == nil && mapped.online == nil)
    }

    @Test @MainActor func recoveredNodeCatalogReplacesMapAndRejectsOldSessions() throws {
        let settings = AnalyzerSettings()
        let model = MapViewModel()
        model.configure(settings: settings)
        let object: [String: Any] = [
            "id": "00000000-0000-0000-0000-000000000001",
            "publicKey": String(repeating: "ab", count: 32), "nodeType": 2,
            "lat": 30, "lng": -98, "iatas": [["iata": "SAT", "lastHeard": 2000]]
        ]
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .millisecondsSince1970
        let node = try decoder.decode(BeaconNodeReader.Node.self, from: JSONSerialization.data(withJSONObject: object))
        let sessionID = settings.sourceSession.id
        let catalogID = UUID()
        model.applyBeaconNodes([:], region: "SAT", sourceSessionID: sessionID, catalog: [node], catalogID: catalogID)
        #expect(model.nodes.count == 1)
        #expect(model.nodesByPubkey[node.publicKey]?.lat == 30)
        model.nodes = []
        model.applyBeaconNodes([:], region: "SAT", sourceSessionID: sessionID, catalog: [node], catalogID: catalogID)
        #expect(model.nodes.isEmpty)
        model.applyBeaconNodes([:], region: "AUS", sourceSessionID: sessionID, catalog: [node])
        #expect(model.nodes.isEmpty)
        model.applyBeaconNodes([:], region: nil, sourceSessionID: sessionID, catalog: [node])
        #expect(model.nodes.count == 1)
        settings.renewSourceSession()
        model.applyBeaconNodes([:], region: nil, sourceSessionID: sessionID, catalog: [])
        #expect(model.nodes.count == 1)
    }

    @Test @MainActor func nodeRecoveryIsDemandGatedCoalescedAndKeepsConcurrentUpdates() async throws {
        let settings = AnalyzerSettings()
        let session = Self.session(targets: ["/api/v1/packets?limit=200"], body: #"{"items":[],"hasMore":false}"#)
        defer { session.invalidateAndCancel() }
        let client = try BeaconAPIClient(baseURL: settings.baseURL, sourceSession: settings.sourceSession, session: session)
        let socket = BeaconTestSocket()
        let probe = BeaconNodeRecoveryProbe()
        let transport = try BeaconLiveTransport(client: client, verifiedServerInfo: .init(serverVersion: "2.0.3"), makeSocket: { _ in socket })
        let feed = try BeaconLiveFeed(client: client, verifiedServerInfo: .init(serverVersion: "2.0.3"), transport: transport,
                                      fetchNodes: { try await probe.fetch() })
        let service = LiveFeedService(settings: settings) { _ in feed }
        defer { service.disconnect() }
        service.connect()
        socket.push(#"{"v":1,"type":"hello"}"#)
        try await Self.waitUntil { service.metadataNeedsRecovery }
        #expect(await probe.count == 0)
        service.setDemand(.init(configuration: .init(resolvePath: true)), for: UUID())
        try await probe.waitForCount(1)
        let sequence = service.beaconNodeSequence
        socket.push(#"{"v":1,"type":"lagged","droppedCount":1}"#)
        try await Self.waitUntil { service.beaconNodeSequence > sequence }
        await probe.succeed()
        try await probe.waitForCount(2)
        #expect(service.beaconNodeCatalog == nil)
        let key = String(repeating: "ab", count: 32)
        socket.push("{\"v\":1,\"type\":\"event\",\"event\":\"nodeUpdate\",\"data\":{\"nodeId\":\"00000000-0000-0000-0000-000000000001\",\"publicKey\":\"\(key)\",\"nodeType\":2}}")
        try await Self.waitUntil { service.beaconNodes[key] != nil }
        await probe.succeed()
        try await Self.waitUntil { !service.metadataNeedsRecovery }
        #expect(service.beaconNodeCatalog?.isEmpty == true)
        #expect(service.beaconNodes[key] != nil)
        #expect(await probe.count == 2)
    }

    @Test @MainActor func nodeRecoveryFailureRetriesAndRejectsDisconnectedResults() async throws {
        let settings = AnalyzerSettings()
        let session = Self.session(targets: ["/api/v1/packets?limit=200"], body: #"{"items":[],"hasMore":false}"#)
        defer { session.invalidateAndCancel() }
        let client = try BeaconAPIClient(baseURL: settings.baseURL, sourceSession: settings.sourceSession, session: session)
        let socket = BeaconTestSocket()
        let probe = BeaconNodeRecoveryProbe()
        let transport = try BeaconLiveTransport(client: client, verifiedServerInfo: .init(serverVersion: "2.0.3"), makeSocket: { _ in socket })
        let feed = try BeaconLiveFeed(client: client, verifiedServerInfo: .init(serverVersion: "2.0.3"), transport: transport,
                                      fetchNodes: { try await probe.fetch() })
        let service = LiveFeedService(settings: settings) { _ in feed }
        defer { service.disconnect() }
        service.setDemand(.init(configuration: .init(resolvePath: true)), for: UUID())
        service.connect()
        socket.push(#"{"v":1,"type":"hello"}"#)
        try await probe.waitForCount(1)
        await probe.fail()
        try await Self.waitUntil { service.metadataRecoveryError != nil }
        #expect(service.metadataNeedsRecovery)
        service.retryMetadataRecovery()
        try await probe.waitForCount(2)
        await probe.succeed()
        try await Self.waitUntil { !service.metadataNeedsRecovery }
        #expect(service.metadataRecoveryError == nil)
        socket.push(#"{"v":1,"type":"lagged","droppedCount":1}"#)
        try await probe.waitForCount(3)
        service.disconnect()
        await probe.succeed()
        await Task.yield()
        #expect(service.beaconNodeCatalog == nil && service.beaconNodes.isEmpty)
    }

    private static func nodeUpdate(_ fields: [String: Any] = [:]) throws -> BeaconLiveProtocol.NodeUpdate {
        var payload: [String: Any] = [
            "nodeId": "00000000-0000-0000-0000-000000000001",
            "publicKey": String(repeating: "ab", count: 32), "nodeType": 2
        ]
        payload.merge(fields) { _, value in value }
        let frame: [String: Any] = ["v": 1, "type": "event", "event": "nodeUpdate", "data": payload]
        guard case .node(let update) = try BeaconLiveProtocol.decode(JSONSerialization.data(withJSONObject: frame)) else {
            throw APIError.invalidResponse
        }
        return update
    }

    @Test func liveNodePositionsDistinguishOmissionFromClearing() throws {
        #expect(try Self.nodeUpdate().position == .unchanged)
        #expect(try Self.nodeUpdate(["lat": 30, "lng": -98]).position == .coordinate(latitude: 30, longitude: -98))
        for coordinates: [String: Any] in [
            ["lat": NSNull(), "lng": NSNull()], ["lat": 0, "lng": 0], ["lat": 91, "lng": -98]
        ] {
            #expect(try Self.nodeUpdate(coordinates).position == .clear)
        }
        #expect(throws: APIError.self) { try Self.nodeUpdate(["lat": 30]) }
        #expect(throws: APIError.self) { try Self.nodeUpdate(["publicKey": "ab"]) }
        #expect(throws: APIError.self) { try Self.nodeUpdate(["nodeId": "not-a-uuid"]) }
    }

    @Test func liveNodeStateMergesMembershipAndPreservesExplicitClear() throws {
        var state = BeaconLiveProtocol.NodeState(try Self.nodeUpdate([
            "lat": 30, "lng": -98, "iatas": [["iata": "SAT", "lastHeard": 2000]]
        ]))
        state.merge(try Self.nodeUpdate(["iatas": [["iata": "AUS", "lastHeard": 3000]]]))
        #expect(state.position == .coordinate(latitude: 30, longitude: -98))
        #expect(Set(state.regions.keys) == ["SAT", "AUS"])
        state.merge(try Self.nodeUpdate(["lat": NSNull(), "lng": NSNull()]))
        state.merge(try Self.nodeUpdate(["iatas": [["iata": "sat", "lastHeard": 1000]]]))
        #expect(state.position == .clear)
        #expect(state.regions["SAT"]?.lastHeard == Date(timeIntervalSince1970: 2))
        #expect(state.regions.count == 2)
    }

    @Test func liveSubscriptionIncludesNodeAndObserverMetadata() throws {
        var subscriptions = BeaconLiveProtocol.Subscriptions()
        _ = subscriptions.update(subscriptions: [LiveFeedSubscription()], configuration: .init())
        let command = try #require(subscriptions.hello().first { $0.type == "subscribe" })
        let object = try #require(JSONSerialization.jsonObject(with: command.encoded()) as? [String: Any])
        let scope = try #require(object["scope"] as? [String: Any])
        #expect(Set(scope["events"] as? [String] ?? []) == ["packetObservation", "observerStatus", "nodeUpdate"])
    }

    @Test @MainActor func liveMapNodesPreserveDatesClearPositionsAndRespectRegions() throws {
        let key = String(repeating: "ab", count: 32)
        let initial = BeaconLiveProtocol.NodeState(try Self.nodeUpdate([
            "name": "Original", "lat": 30, "lng": -98,
            "iatas": [["iata": "SAT", "lastHeard": 1000]]
        ])).applying(to: nil)
        var state = BeaconLiveProtocol.NodeState(try Self.nodeUpdate([
            "name": "Updated", "iatas": [["iata": "AUS", "lastHeard": 2000]]
        ]))
        var merged = MapViewModel.mergingBeaconNodes([initial], states: [key: state], region: "SAT")
        #expect(merged.count == 1 && merged[0].name == "Updated")
        #expect(merged[0].lat == 30 && merged[0].lon == -98)
        #expect(merged[0].firstSeen == nil && merged[0].lastSeen == nil)
        #expect(merged[0].lastHeard == Date(timeIntervalSince1970: 2))
        #expect(MapViewModel.mergingBeaconNodes([], states: [key: state], region: "SAT").isEmpty)
        #expect(MapViewModel.mergingBeaconNodes([], states: [key: state], region: "AUS").count == 1)
        #expect(MapViewModel.mergingBeaconNodes([], states: [key: state], region: nil).count == 1)
        state.merge(try Self.nodeUpdate([
            "lat": NSNull(), "lng": NSNull(), "iatas": [["iata": "SAT", "lastHeard": 3000]]
        ]))
        merged = MapViewModel.mergingBeaconNodes(merged, states: [key: state], region: "SAT")
        #expect(merged[0].coordinate == nil)
        #expect(merged[0].name == "Updated")
        #expect(MapViewModel.mergingBeaconNodes(merged, states: [key: state], region: "SAT") == merged)
    }

    @Test func liveMapOlderPositionDoesNotOverwriteNewerRESTOrInventFreshness() throws {
        let initial = BeaconLiveProtocol.NodeState(try Self.nodeUpdate([
            "name": "REST", "lat": 31, "lng": -97,
            "iatas": [["iata": "SAT", "lastHeard": 5000]]
        ])).applying(to: nil)
        var state = BeaconLiveProtocol.NodeState(try Self.nodeUpdate([
            "name": "Older", "lat": 30, "lng": -98,
            "iatas": [["iata": "SAT", "lastHeard": 1000]]
        ]))
        #expect(state.applying(to: initial) == initial)
        state.merge(try Self.nodeUpdate(["iatas": [["iata": "SAT", "lastHeard": 6000]]]))
        let updated = state.applying(to: initial)
        #expect(updated.lat == 31 && updated.lon == -97)
        #expect(updated.name == "REST")
        #expect(updated.lastHeard == Date(timeIntervalSince1970: 6))
        state.merge(try Self.nodeUpdate([
            "lat": NSNull(), "lng": NSNull(), "iatas": [["iata": "SAT", "lastHeard": 500]]
        ]))
        #expect(state.applying(to: initial).lat == 31)
    }

    @Test func beaconNodeMappingRejectsInvalidCoordinatesAndPreservesDetailDates() throws {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .millisecondsSince1970
        for position: [String: Any] in [
            [:], ["lat": NSNull(), "lng": NSNull()], ["lat": 0, "lng": 0],
            ["lat": 91, "lng": -98], ["lat": 30, "lng": 181], ["lat": 30]
        ] {
            var object: [String: Any] = [
                "id": "00000000-0000-0000-0000-000000000001",
                "publicKey": String(repeating: "AB", count: 32), "nodeType": 2,
                "firstSeen": 1000, "lastSeen": 2000
            ]
            object.merge(position) { _, value in value }
            let node = try decoder.decode(BeaconNodeReader.Node.self, from: JSONSerialization.data(withJSONObject: object))
            #expect(node.meshNode.coordinate == nil)
            #expect(node.meshNode.firstSeen == Date(timeIntervalSince1970: 1))
            #expect(node.meshNode.lastSeen == Date(timeIntervalSince1970: 2))
        }
        let object: [String: Any] = [
            "id": "00000000-0000-0000-0000-000000000001",
            "publicKey": String(repeating: "AB", count: 32), "nodeType": 99, "lat": 0, "lng": -98
        ]
        let node = try decoder.decode(BeaconNodeReader.Node.self, from: JSONSerialization.data(withJSONObject: object))
        #expect(node.meshNode.coordinate?.latitude == 0)
        #expect(node.meshNode.coordinate?.longitude == -98)
        #expect(node.meshNode.role == "unknown")
    }

    @Test(arguments: ["2.0.3", "2.0.5", "2.1.0", "3.0.0", "2.0.3+build"])
    func acceptsSupportedVersions(version: String) throws {
        try BeaconServerInfo(serverVersion: version).requireSupported()
    }

    @Test(arguments: ["", "unknown", "2", "2.0", "2.0.2", "1.99.0", "2.0.3-rc1", "-2.0.3", "+2.0.3", "2.0.3+", "02.0.3"])
    func rejectsUnsupportedOrUnrecognizedVersions(version: String) {
        #expect(throws: BeaconAPIError.self) {
            try BeaconServerInfo(serverVersion: version).requireSupported()
        }
    }

    @Test func ignoresBeaconMobileVersionFloor() async throws {
        let session = Self.session(targets: ["/api/v1/info"], body: #"{"serverVersion":"2.0.3","minAppVersion":{"not":"our version policy"}}"#)
        defer { session.invalidateAndCancel() }
        let info = try await Self.client(session).serverInfo()
        try info.requireSupported()
        #expect(info.serverVersion == "2.0.3")
    }

    @Test func encodesEachPathSegment() async throws {
        let session = Self.session(targets: ["/api/v1/packets/a%2Fb%3F%23"], body: #"{"serverVersion":"2.0.3"}"#)
        defer { session.invalidateAndCancel() }
        let info: BeaconServerInfo = try await Self.client(session).get(["packets", "a/b?#"])
        #expect(info.serverVersion == "2.0.3")
    }

    @Test func reportsStructuredErrors() async throws {
        let session = Self.session(targets: ["/api/v1/packets/missing"], status: 404, body: #"{"error":{"code":"not_found","message":"not shown"}}"#)
        defer { session.invalidateAndCancel() }
        do {
            let _: BeaconServerInfo = try await Self.client(session).get(["packets", "missing"])
            Issue.record("Expected the server error")
        } catch BeaconAPIError.server(let status, let code) {
            #expect(status == 404)
            #expect(code == "not_found")
        }
    }

    @Test func invalidatedSourcesNeverStartRequests() async throws {
        let session = Self.session(targets: [])
        defer { session.invalidateAndCancel() }
        let client = try Self.client(session)
        client.sourceSession.invalidate()
        await #expect(throws: CancellationError.self) { _ = try await client.serverInfo() }
    }

    @Test func sharesCooldownAcrossClientsButNotServers() async throws {
        let gate = BeaconRateLimit()
        let throttled = Self.session(targets: ["/api/v1/info"], status: 429, retryAfter: "60")
        let healthy = Self.session(targets: ["/api/v1/info"], body: #"{"serverVersion":"2.0.3"}"#)
        defer { throttled.invalidateAndCancel(); healthy.invalidateAndCancel() }
        let first = try Self.client(throttled, gate: gate)
        let second = try Self.client(healthy, gate: gate)
        for client in [first, second] {
            do {
                _ = try await client.serverInfo()
                Issue.record("Expected shared cooldown")
            } catch BeaconAPIError.rateLimited(let deadline) {
                #expect(deadline == Date(timeIntervalSince1970: 1060))
            }
        }
        let other = try Self.client(healthy, gate: gate, host: "other.invalid")
        #expect(try await other.serverInfo().serverVersion == "2.0.3")
        try await gate.check(origin: first.cacheIdentifier, now: Date(timeIntervalSince1970: 1061))
    }

    @Test func retryAfterDatesAndFallbackAreBounded() {
        let now = Date(timeIntervalSince1970: 0)
        #expect(BeaconRateLimit.retryDate("Thu, 01 Jan 1970 00:01:00 GMT", now: now) == Date(timeIntervalSince1970: 60))
        #expect(BeaconRateLimit.retryDate("invalid", now: now) == Date(timeIntervalSince1970: 30))
        #expect(BeaconRateLimit.retryDate("-1", now: now) == Date(timeIntervalSince1970: 30))
        #expect(BeaconRateLimit.retryDate("1e300", now: now) == .distantFuture)
    }

    @Test(arguments: [404, 429, 503])
    func failedProbeRetainsRememberedServer(status: Int) async throws {
        let suite = "BeaconAPITests-\(UUID())"
        let defaults = try #require(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let catalog = BeaconServerCatalog(suiteName: suite)
        let healthy = Self.session(targets: ["/api/v1/info"], body: #"{"serverVersion":"2.0.3"}"#)
        let failing = Self.session(targets: ["/api/v1/info"], status: status)
        defer { healthy.invalidateAndCancel(); failing.invalidateAndCancel() }
        let good = try Self.client(healthy)
        let bad = try Self.client(failing)
        _ = try await catalog.probe(using: good)
        await #expect(throws: BeaconAPIError.self) { _ = try await catalog.probe(using: bad) }
        #expect(await catalog.knownInfo(for: bad)?.serverVersion == "2.0.3")
        let reopened = BeaconServerCatalog(suiteName: suite)
        #expect(await reopened.knownInfo(for: good)?.serverVersion == "2.0.3")
    }

    @Test func packetFixturePreservesOrderDatesAndOpaqueCursor() async throws {
        let target = "/api/v1/packets?limit=200&iatas=SAT&payloadType=5"
        let fixture = try Self.fixtureBody("packets-page")
        let nextValue = try #require(fixture["nextCursor"] as? Int64)
        let session = Self.session(targets: [target, target + "&cursor=\(nextValue)"], fixture: "packets-page")
        defer { session.invalidateAndCancel() }
        let reader = try BeaconPacketReader(client: Self.client(session), verifiedServerInfo: .init(serverVersion: "2.0.3"))
        let page = try await reader.packetPage(region: "SAT", pageSize: 1000, payloadType: 5, cursor: nil)
        let items = try #require(fixture["items"] as? [[String: Any]])
        #expect(page.items.map(\.hash) == items.compactMap { $0["packetHash"] as? String })
        #expect(page.total == nil)
        #expect(page.items.allSatisfy { $0.serverID == nil && $0.rawHex == nil })
        let first = try #require(page.items.first)
        let millis = try #require(items.first?["firstHeardAt"] as? Double)
        #expect(first.firstSeen.timeIntervalSince1970 == millis / 1000)
        let cursor = try #require(page.nextCursor)
        let repeatedPage = try await reader.packetPage(region: "SAT", pageSize: 1000, payloadType: 5, cursor: nil)
        #expect(repeatedPage.nextCursor == cursor)
        let nextPage = try await reader.packetPage(region: "SAT", pageSize: 200, payloadType: 5, cursor: cursor)
        #expect(nextPage.nextCursor == cursor)
        await #expect(throws: AnalyzerPagingError.self) {
            _ = try await reader.packetPage(region: "DFW", pageSize: 200, payloadType: 5, cursor: cursor)
        }
    }

    @Test func missingContinuationFailsInsteadOfTruncating() async throws {
        let body = #"{"items":[{"packetHash":"hash","payloadType":5,"routeType":1,"firstHeardAt":1000,"lastHeardAt":2000,"observationCount":1}],"hasMore":true}"#
        let session = Self.session(targets: ["/api/v1/packets?limit=1"], body: body)
        defer { session.invalidateAndCancel() }
        let reader = try BeaconPacketReader(client: Self.client(session), verifiedServerInfo: .init(serverVersion: "2.0.3"))
        await #expect(throws: BeaconAPIError.self) {
            _ = try await reader.packetPage(region: nil, pageSize: 1, payloadType: nil, cursor: nil)
        }
    }

    @Test func packetDetailUsesRecordedObservationsNotInventedPacketIDs() async throws {
        let body = try Self.fixtureBody("packet-detail")
        let hash = try #require(body["packetHash"] as? String)
        let session = Self.session(targets: ["/api/v1/packets/\(hash)"], fixture: "packet-detail")
        defer { session.invalidateAndCancel() }
        let reader = try BeaconPacketReader(client: Self.client(session), verifiedServerInfo: .init(serverVersion: "2.0.3"))
        let detail = try await reader.packetDetail(hash: hash)
        #expect(detail.packet.id == hash)
        #expect(detail.packet.serverID == nil)
        #expect(detail.packet.rawHex == nil)
        #expect(detail.observationCount == body["observationCount"] as? Int)
        #expect(detail.observations.count == (body["observations"] as? [Any])?.count)
        #expect(detail.observations.first?.id == 437387)
    }

    @Test func onlySingleHighConfidenceFullKeysAreConfirmed() {
        let key = String(repeating: "AB", count: 32)
        let node = BeaconPacketReader.ResolvedHop.Node(publicKey: key)
        #expect(BeaconPacketReader.ResolvedHop(confidence: "high", nodes: [node]).confirmedKey == key.lowercased())
        #expect(BeaconPacketReader.ResolvedHop(confidence: "ambiguous", nodes: [node]).confirmedKey == nil)
        #expect(BeaconPacketReader.ResolvedHop(confidence: "high", nodes: [node, node]).confirmedKey == nil)
        #expect(BeaconPacketReader.ResolvedHop(confidence: "high", nodes: [.init(publicKey: "ab")]).confirmedKey == nil)
        #expect(BeaconPacketReader.ResolvedHop(confidence: "none", nodes: []).confirmedKey == nil)
    }

    @Test func nodeSummaryPreservesUnknownDatesAndUsesNewestRegion() async throws {
        let session = Self.session(targets: ["/api/v1/nodes?limit=200"], fixture: "node-by-pubkey")
        defer { session.invalidateAndCancel() }
        let reader = try BeaconNodeReader(client: Self.client(session), verifiedServerInfo: .init(serverVersion: "2.0.3"))
        let page = try await reader.nodePage(region: nil, pageSize: 1000)
        let node = try #require(page.items.first)
        #expect(node.firstSeen == nil && node.lastSeen == nil)
        #expect(node.lastHeard == Date(timeIntervalSince1970: 1791666758.959))
        #expect(node.role == "repeater")
        #expect(node.supportsMultibytePaths == nil)
        let mapped = node.meshNode
        #expect(mapped.firstSeen == nil && mapped.lastSeen == nil)
        #expect(mapped.latestActivity == node.lastHeard)
        #expect(mapped.publicKey == node.publicKey.lowercased())
        #expect(mapped.role == node.role)
        #expect(mapped.advertCount == nil && mapped.hashSize == nil && mapped.hashSizeInconsistent == nil)
        #expect(page.total == nil && page.nextCursor == nil)
    }

    @Test func nodeDetailLooksUpKeyThenServerID() async throws {
        let fixture = try Self.fixtureBody("node-detail")
        let key = try #require(fixture["publicKey"] as? String)
        let identifier = try #require(fixture["id"] as? String)
        let lookup = "/api/v1/nodes?pubkey=\(key)"
        let detail = "/api/v1/nodes/\(identifier)"
        let session = Self.session(targets: [lookup, detail], fixture: "node-by-pubkey", detailFixture: "node-detail")
        defer { session.invalidateAndCancel() }
        let reader = try BeaconNodeReader(client: Self.client(session), verifiedServerInfo: .init(serverVersion: "2.0.3"))
        let node = try #require(await reader.nodeDetail(publicKey: key.uppercased()))
        #expect(node.id == identifier)
        #expect(node.firstSeen == Date(timeIntervalSince1970: 1791475962.702))
        #expect(node.lastSeen == Date(timeIntervalSince1970: 1791666758.956))
        #expect(node.supportsMultibytePaths == true)
        #expect(node.supportsMultibyteTraces == false)
    }

    @Test func nodeNeighborsResolveFullKeyAndPreserveUnknownDirection() async throws {
        let node = try Self.fixtureBody("node-detail")
        let key = try #require(node["publicKey"] as? String)
        let identifier = try #require(node["id"] as? String)
        let lookup = "/api/v1/nodes?pubkey=\(key)"
        let neighborsPath = "/api/v1/nodes/\(identifier)/neighbors"
        let neighborRoot = try #require(JSONSerialization.jsonObject(
            with: Data(contentsOf: Self.fixtureURL("node-neighbors"))
        ) as? [String: Any])
        let neighbors = try #require(neighborRoot["body"] as? [[String: Any]])
        let routes = String(decoding: try JSONSerialization.data(withJSONObject: [
            lookup: try Self.fixtureBody("node-by-pubkey"),
            neighborsPath: neighbors
        ]), as: UTF8.self)
        let session = Self.session(targets: [lookup, neighborsPath], routes: routes)
        defer { session.invalidateAndCancel() }
        let reader = try BeaconNodeReader(
            client: Self.client(session),
            verifiedServerInfo: .init(serverVersion: "2.0.3")
        )

        let result = try #require(await reader.neighbors(publicKey: key.uppercased()))
        let neighbor = try #require(result.neighbors.first)
        #expect(result.completeness == .complete)
        #expect(neighbor.identity.linkablePublicKey != nil)
        #expect(neighbor.identity.displayPrefix?.count == 8)
        #expect(neighbor.direction == .unknown)
        #expect(neighbor.signal.snr == -5.75)
        #expect(neighbor.signal.rssi == nil)
        #expect(neighbor.observationCount == 2)
    }

    @Test func nodeObservationHistoryUsesBoundedPagesAndObservationSemantics() async throws {
        let node = try Self.fixtureBody("node-detail")
        let key = try #require(node["publicKey"] as? String)
        let identifier = try #require(node["id"] as? String)
        let lookup = "/api/v1/nodes?pubkey=\(key)"
        let observationsPath = "/api/v1/nodes/\(identifier)/observations?limit=200"
        let routes = String(decoding: try JSONSerialization.data(withJSONObject: [
            lookup: try Self.fixtureBody("node-by-pubkey"),
            observationsPath: try Self.fixtureBody("node-observations")
        ]), as: UTF8.self)
        let session = Self.session(targets: [lookup, observationsPath], routes: routes)
        defer { session.invalidateAndCancel() }
        let reader = try BeaconNodeReader(
            client: Self.client(session),
            verifiedServerInfo: .init(serverVersion: "2.0.3")
        )

        let history = try #require(await reader.observationHistory(
            publicKey: key,
            since: Date(timeIntervalSince1970: 0)
        ))
        #expect(history.isComplete)
        #expect(history.requestCount == 2)
        #expect(history.observations.count == 8)
        #expect(history.observations.allSatisfy { $0.payloadType == 4 && $0.hopCount == 0 })
        #expect(history.observations.first?.heardAt == Date(timeIntervalSince1970: 1791666758.828))
    }

    @Test func beaconBackendDerivesNodeAnalyticsWithoutPacketFanout() async throws {
        let node = try Self.fixtureBody("node-detail")
        let key = try #require(node["publicKey"] as? String)
        let identifier = try #require(node["id"] as? String)
        let lookup = "/api/v1/nodes?pubkey=\(key)"
        let observationsPath = "/api/v1/nodes/\(identifier)/observations?limit=200"
        let routes = String(decoding: try JSONSerialization.data(withJSONObject: [
            lookup: try Self.fixtureBody("node-by-pubkey"),
            observationsPath: try Self.fixtureBody("node-observations")
        ]), as: UTF8.self)
        let session = Self.session(targets: [lookup, observationsPath], routes: routes)
        defer { session.invalidateAndCancel() }
        let backend = try BeaconBackend(
            client: Self.client(session),
            verifiedServerInfo: .init(serverVersion: "2.0.3")
        )

        let analytics = try await backend.nodeAnalytics(publicKey: key, days: 30)
        #expect(analytics.context?.countUnit == .observations)
        #expect(analytics.context?.completeness == .partial)
        #expect(analytics.timeRange.days == 7)
        #expect(analytics.computedStats.totalPackets == 8)
        #expect(analytics.computedStats.availabilityPct == nil)
        #expect(analytics.computedStats.signalGrade == nil)
        #expect(analytics.computedStats.uniqueObservers == nil)
        #expect(analytics.packetTypeBreakdown.first?.payloadType == 4)
        #expect(analytics.hopDistribution.first?.hops == "0")
        #expect(analytics.observerCoverage.isEmpty && analytics.peerInteractions.isEmpty)
    }

    @Test func missingNodeDoesNotScanOrFetchDetail() async throws {
        let key = String(repeating: "ab", count: 32)
        let session = Self.session(targets: ["/api/v1/nodes?pubkey=\(key)"], body: #"{"items":[],"hasMore":false}"#)
        defer { session.invalidateAndCancel() }
        let reader = try BeaconNodeReader(client: Self.client(session), verifiedServerInfo: .init(serverVersion: "2.0.3"))
        #expect(try await reader.nodeDetail(publicKey: key) == nil)
        await #expect(throws: APIError.self) { _ = try await reader.nodeDetail(publicKey: "ab") }
    }

    @Test func nodePagesWalkRecordedCursorsWithoutTruncation() async throws {
        let fixture = try Self.fixtureBody("nodes-page")
        let pages = try #require(fixture["pages"] as? [[String: Any]])
        let items = try #require(fixture["items"] as? [[String: Any]])
        let targets = pages.map { page in
            "/api/v1/nodes?limit=200" + ((page["cursor"] as? NSNumber).map { "&cursor=\($0)" } ?? "")
        }
        let session = Self.session(targets: targets, fixture: "nodes-page")
        defer { session.invalidateAndCancel() }
        let reader = try BeaconNodeReader(client: Self.client(session), verifiedServerInfo: .init(serverVersion: "2.0.3"))
        let nodes = try await reader.nodes(region: nil)
        #expect(nodes.count == items.count)
        #expect(nodes.map(\.publicKey) == items.compactMap { $0["publicKey"] as? String })
        await #expect(throws: AnalyzerPagingError.self) { _ = try await reader.nodes(region: nil, maximumPages: 1) }
    }

    @Test func nodeCursorsAreBoundToEndpointSourceSessionAndRegion() async throws {
        let session = Self.session(targets: ["/api/v1/nodes?limit=200&iatas=SAT"], fixture: "nodes-page")
        defer { session.invalidateAndCancel() }
        let client = try Self.client(session)
        let reader = try BeaconNodeReader(client: client, verifiedServerInfo: .init(serverVersion: "2.0.3"))
        let cursor = try #require(await reader.nodePage(region: "SAT").nextCursor)
        await #expect(throws: AnalyzerPagingError.self) {
            _ = try await reader.nodePage(region: "DFW", cursor: cursor)
        }
        for host in ["beacon.invalid", "other.invalid"] {
            let other = try BeaconNodeReader(client: Self.client(session, host: host), verifiedServerInfo: .init(serverVersion: "2.0.3"))
            await #expect(throws: AnalyzerPagingError.self) {
                _ = try await other.nodePage(region: "SAT", cursor: cursor)
            }
        }
        let packets = try BeaconPacketReader(client: client, verifiedServerInfo: .init(serverVersion: "2.0.3"))
        await #expect(throws: AnalyzerPagingError.self) {
            _ = try await packets.packetPage(region: "SAT", pageSize: 200, payloadType: nil, cursor: cursor)
        }
        client.sourceSession.invalidate()
        await #expect(throws: CancellationError.self) { _ = try await reader.nodePage(region: "SAT") }
    }

    @Test func nodeLookupRejectsMismatchedIdentity() async throws {
        let key = String(repeating: "ab", count: 32)
        let session = Self.session(targets: ["/api/v1/nodes?pubkey=\(key)"], fixture: "node-by-pubkey")
        defer { session.invalidateAndCancel() }
        let reader = try BeaconNodeReader(client: Self.client(session), verifiedServerInfo: .init(serverVersion: "2.0.3"))
        await #expect(throws: APIError.self) { _ = try await reader.nodeDetail(publicKey: key) }
    }

    @Test func nodePagingRejectsMissingAndRepeatedContinuations() async throws {
        var fixture = try Self.fixtureBody("node-by-pubkey")
        fixture["hasMore"] = true
        for cursor in [nil, "opaque-continuation"] as [String?] {
            fixture["nextCursor"] = cursor
            let body = String(decoding: try JSONSerialization.data(withJSONObject: fixture), as: UTF8.self)
            let target = "/api/v1/nodes?limit=200"
            let session = Self.session(targets: [target, target + "&cursor=opaque-continuation"], body: body)
            defer { session.invalidateAndCancel() }
            let reader = try BeaconNodeReader(client: Self.client(session), verifiedServerInfo: .init(serverVersion: "2.0.3"))
            if cursor == nil {
                await #expect(throws: BeaconAPIError.self) { _ = try await reader.nodes(region: nil) }
            } else {
                do {
                    _ = try await reader.nodes(region: nil)
                    Issue.record("Expected repeated cursor error")
                } catch AnalyzerPagingError.repeatedCursor {}
            }
        }
    }

    @Test func nodeRegionsUseNewestHeardDateAndUnknownTypesStayUnknown() throws {
        var fixture = try Self.fixtureBody("node-detail")
        fixture["nodeType"] = 999
        fixture["iatas"] = [["iata": "SAT", "lastHeard": 3000], ["iata": "DFW", "lastHeard": 1000]]
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .millisecondsSince1970
        let node = try decoder.decode(BeaconNodeReader.Node.self, from: JSONSerialization.data(withJSONObject: fixture))
        #expect(node.lastHeard == Date(timeIntervalSince1970: 3))
        #expect(node.role == "unknown")
    }

    @Test func observerSummariesKeepServerIDsAndUnknownMetadata() async throws {
        let session = Self.session(targets: ["/api/v1/observers?limit=200&iatas=SAT"], fixture: "observers-page")
        defer { session.invalidateAndCancel() }
        let reader = try BeaconObserverReader(client: Self.client(session), verifiedServerInfo: .init(serverVersion: "2.0.3"))
        let observers = try await reader.observers(region: "SAT")
        let fixture = try Self.fixtureBody("observers-page")
        let items = try #require(fixture["items"] as? [[String: Any]])
        #expect(observers.map(\.id) == items.compactMap { $0["id"] as? String })
        #expect(observers.allSatisfy { $0.publicKey == nil && $0.firstSeen == nil && $0.lastSeen == nil && $0.packetCount == nil })
    }

    @Test func observerDetailLearnsKeyWithoutFanout() async throws {
        let fixture = try Self.fixtureBody("observer-detail")
        let identifier = try #require(fixture["id"] as? String)
        let key = try #require(fixture["publicKey"] as? String)
        let session = Self.session(targets: ["/api/v1/observers/\(identifier)"], fixture: "observer-detail")
        defer { session.invalidateAndCancel() }
        let reader = try BeaconObserverReader(client: Self.client(session), verifiedServerInfo: .init(serverVersion: "2.0.3"))
        #expect(try await reader.observerDetail(publicKey: key) == nil)
        let detail = try await reader.observerDetail(id: identifier.uppercased())
        #expect(detail.firstSeen == Date(timeIntervalSince1970: 1791472508.367))
        #expect(detail.lastSeen == Date(timeIntervalSince1970: 1791666786.053))
        #expect(detail.hardwareModel == "Heltec V4.3 OLED")
        #expect(detail.batteryLevel == 4.244)
        #expect(detail.packetCount == nil)
        #expect(try await reader.observerDetail(publicKey: key.uppercased())?.id == identifier)
        let other = try BeaconObserverReader(client: Self.client(session, host: "other.invalid"), verifiedServerInfo: .init(serverVersion: "2.0.3"))
        #expect(try await other.observerDetail(publicKey: key) == nil)
    }

    @Test func observerLiveLearningIsSessionBoundAndReplacesOldKeys() async throws {
        let fixture = try Self.fixtureBody("observer-detail")
        let identifier = try #require(fixture["id"] as? String)
        let key = try #require(fixture["publicKey"] as? String)
        let oldKey = String(repeating: "ab", count: 32)
        let session = Self.session(targets: ["/api/v1/observers/\(identifier)"], fixture: "observer-detail")
        defer { session.invalidateAndCancel() }
        let client = try Self.client(session)
        let reader = try BeaconObserverReader(client: client, verifiedServerInfo: .init(serverVersion: "2.0.3"))
        await #expect(throws: CancellationError.self) {
            try await reader.learn(observerID: identifier, publicKey: key, sessionID: UUID())
        }
        #expect(try await reader.observerDetail(publicKey: key) == nil)
        try await reader.learn(observerID: identifier, publicKey: oldKey, sessionID: client.sourceSession.id)
        try await reader.learn(observerID: identifier, publicKey: key.uppercased(), sessionID: client.sourceSession.id)
        #expect(try await reader.observerDetail(publicKey: oldKey) == nil)
        #expect(try await reader.observerDetail(publicKey: key)?.id == identifier)
        client.sourceSession.invalidate()
        await #expect(throws: CancellationError.self) {
            _ = try await reader.observerDetail(publicKey: key)
        }
    }

    @Test func staleObserverMappingsNeverReturnAnotherKey() async throws {
        let fixture = try Self.fixtureBody("observer-detail")
        let identifier = try #require(fixture["id"] as? String)
        let oldKey = String(repeating: "ab", count: 32)
        let session = Self.session(targets: ["/api/v1/observers/\(identifier)"], fixture: "observer-detail")
        defer { session.invalidateAndCancel() }
        let client = try Self.client(session)
        let reader = try BeaconObserverReader(client: client, verifiedServerInfo: .init(serverVersion: "2.0.3"))
        try await reader.learn(observerID: identifier, publicKey: oldKey, sessionID: client.sourceSession.id)
        #expect(try await reader.observerDetail(publicKey: oldKey) == nil)
        #expect(try await reader.observerDetail(publicKey: oldKey) == nil)
    }

    @Test func observer404EvictsLearnedMapping() async throws {
        let identifier = UUID().uuidString.lowercased()
        let key = String(repeating: "ab", count: 32)
        let session = Self.session(targets: ["/api/v1/observers/\(identifier)"], status: 404)
        defer { session.invalidateAndCancel() }
        let client = try Self.client(session)
        let reader = try BeaconObserverReader(client: client, verifiedServerInfo: .init(serverVersion: "2.0.3"))
        try await reader.learn(observerID: identifier, publicKey: key, sessionID: client.sourceSession.id)
        #expect(try await reader.observerDetail(publicKey: key) == nil)
        session.invalidateAndCancel()
        #expect(try await reader.observerDetail(publicKey: key) == nil)
    }

    @Test func observerIdentityMismatchFailsClosed() async throws {
        let identifier = UUID().uuidString.lowercased()
        let session = Self.session(targets: ["/api/v1/observers/\(identifier)"], fixture: "observer-detail")
        defer { session.invalidateAndCancel() }
        let reader = try BeaconObserverReader(client: Self.client(session), verifiedServerInfo: .init(serverVersion: "2.0.3"))
        await #expect(throws: APIError.self) { _ = try await reader.observerDetail(id: identifier) }
        await #expect(throws: APIError.self) { _ = try await reader.observerDetail(id: "not-a-uuid") }
        await #expect(throws: APIError.self) { _ = try await reader.observerDetail(publicKey: "ab") }
    }

    @Test func observerUUIDChangeReplacesReverseMapping() async throws {
        let fixture = try Self.fixtureBody("observer-detail")
        let identifier = try #require(fixture["id"] as? String)
        let key = try #require(fixture["publicKey"] as? String)
        let previousID = UUID().uuidString.lowercased()
        let otherKey = String(repeating: "ab", count: 32)
        let session = Self.session(targets: ["/api/v1/observers/\(identifier)"], fixture: "observer-detail")
        defer { session.invalidateAndCancel() }
        let client = try Self.client(session)
        let reader = try BeaconObserverReader(client: client, verifiedServerInfo: .init(serverVersion: "2.0.3"))
        try await reader.learn(observerID: previousID, publicKey: key, sessionID: client.sourceSession.id)
        try await reader.learn(observerID: identifier, publicKey: key, sessionID: client.sourceSession.id)
        try await reader.learn(observerID: previousID, publicKey: otherKey, sessionID: client.sourceSession.id)
        #expect(try await reader.observerDetail(publicKey: key)?.id == identifier)
    }

    @Test func observerTemporaryFailureDoesNotEraseMapping() async throws {
        let identifier = UUID().uuidString.lowercased()
        let key = String(repeating: "ab", count: 32)
        let session = Self.session(targets: ["/api/v1/observers/\(identifier)"], status: 503)
        defer { session.invalidateAndCancel() }
        let client = try Self.client(session)
        let reader = try BeaconObserverReader(client: client, verifiedServerInfo: .init(serverVersion: "2.0.3"))
        try await reader.learn(observerID: identifier, publicKey: key, sessionID: client.sourceSession.id)
        for _ in 0..<2 {
            do {
                _ = try await reader.observerDetail(publicKey: key)
                Issue.record("Expected retained mapping to propagate server failure")
            } catch BeaconAPIError.server(let status, _) {
                #expect(status == 503)
            }
        }
    }

    @Test func observerCursorsPreserveOpaqueValuesAndRejectOtherRequests() async throws {
        var fixture = try Self.fixtureBody("observers-page")
        fixture["hasMore"] = true
        fixture["nextCursor"] = "opaque"
        let body = String(decoding: try JSONSerialization.data(withJSONObject: fixture), as: UTF8.self)
        let target = "/api/v1/observers?limit=200&iatas=SAT"
        let session = Self.session(targets: [target, target + "&cursor=opaque"], body: body)
        defer { session.invalidateAndCancel() }
        let client = try Self.client(session)
        let reader = try BeaconObserverReader(client: client, verifiedServerInfo: .init(serverVersion: "2.0.3"))
        let cursor = try #require(await reader.observerPage(region: "SAT", pageSize: 1000).nextCursor)
        #expect(try await reader.observerPage(region: "SAT", cursor: cursor).nextCursor == cursor)
        await #expect(throws: AnalyzerPagingError.self) { _ = try await reader.observerPage(region: "DFW", cursor: cursor) }
        let nodes = try BeaconNodeReader(client: client, verifiedServerInfo: .init(serverVersion: "2.0.3"))
        await #expect(throws: AnalyzerPagingError.self) { _ = try await nodes.nodePage(region: "SAT", cursor: cursor) }
        let other = try BeaconObserverReader(client: Self.client(session), verifiedServerInfo: .init(serverVersion: "2.0.3"))
        await #expect(throws: AnalyzerPagingError.self) { _ = try await other.observerPage(region: "SAT", cursor: cursor) }
        do {
            _ = try await reader.observers(region: "SAT")
            Issue.record("Expected repeated cursor error")
        } catch AnalyzerPagingError.repeatedCursor {}
        do {
            _ = try await reader.observers(region: "SAT", maximumPages: 1)
            Issue.record("Expected page budget error")
        } catch AnalyzerPagingError.pageBudgetExceeded {}
    }

    @Test func observerMissingContinuationFailsInsteadOfTruncating() async throws {
        var fixture = try Self.fixtureBody("observers-page")
        fixture["hasMore"] = true
        let body = String(decoding: try JSONSerialization.data(withJSONObject: fixture), as: UTF8.self)
        let session = Self.session(targets: ["/api/v1/observers?limit=200"], body: body)
        defer { session.invalidateAndCancel() }
        let reader = try BeaconObserverReader(client: Self.client(session), verifiedServerInfo: .init(serverVersion: "2.0.3"))
        await #expect(throws: BeaconAPIError.self) { _ = try await reader.observers(region: nil) }
    }

    @Test func regionCatalogUsesFixturesAndCachesMemberDetails() async throws {
        let session = try Self.regionSession()
        defer { session.invalidateAndCancel() }
        let client = try Self.client(session)
        let reader = try BeaconRegionReader(client: client, verifiedServerInfo: .init(serverVersion: "2.0.3"))
        let catalog = try await reader.catalog()
        #expect(catalog.iatas.count == 13)
        #expect(catalog.regions.map(\.id) == [1, 2, 3, 4, 5])
        #expect(catalog.regions.first?.iatas == ["SAT", "AUS"])
        #expect(catalog.names["SAT"] == "Observer 011")
        #expect(catalog.coordinates.coords["SAT"]?.lat == 39.5337)
        #expect(catalog.coordinates.coords["SAT"]?.radiusKm == nil)
        let frame = try #require(catalog.frame(regionID: 1))
        #expect(abs(frame.latitude - (39.5337 + 40.1975) / 2) < 0.000001)
        #expect(catalog.frame(codes: []) == nil)
        #expect(catalog.frame(codes: ["XXX"]) == nil)
        #expect(try await reader.catalog(forceRefresh: true).regions.count == 5)
        session.invalidateAndCancel()
        #expect(try await reader.catalog().regions.count == 5)
        client.sourceSession.invalidate()
        await #expect(throws: CancellationError.self) { _ = try await reader.catalog() }
    }

    @Test func regionCatalogRejectsMismatchedDetailAndExcessBudget() async throws {
        for overrides in [
            ["/api/v1/regions/1": ["id": 99, "slug": "texas", "name": "Wrong", "iatas": ["SAT"]]] as [String: Any],
            ["/api/v1/regions": (1...19).map { ["id": $0, "slug": "region-\($0)", "name": "Group"] }] as [String: Any]
        ] {
            let session = try Self.regionSession(overrides: overrides)
            defer { session.invalidateAndCancel() }
            let reader = try BeaconRegionReader(client: Self.client(session), verifiedServerInfo: .init(serverVersion: "2.0.3"))
            await #expect(throws: (any Error).self) { _ = try await reader.catalog() }
        }
    }

    @Test func regionCatalogSupportsEmptyServersAndRejectsDuplicateCodes() async throws {
        let empty = try Self.regionSession(overrides: ["/api/v1/iatas": [], "/api/v1/regions": []])
        defer { empty.invalidateAndCancel() }
        let reader = try BeaconRegionReader(client: Self.client(empty), verifiedServerInfo: .init(serverVersion: "2.0.3"))
        let catalog = try await reader.catalog()
        #expect(catalog.names.isEmpty && catalog.regions.isEmpty && catalog.frame() == nil)
        let duplicate = try Self.regionSession(overrides: ["/api/v1/iatas": [["iata": "SAT"], ["iata": "sat"]]])
        defer { duplicate.invalidateAndCancel() }
        let invalid = try BeaconRegionReader(client: Self.client(duplicate), verifiedServerInfo: .init(serverVersion: "2.0.3"))
        await #expect(throws: APIError.self) { _ = try await invalid.catalog() }
    }

    @Test func regionFramingHandlesDatelineAndMissingCoordinates() throws {
        let frame = try #require(BeaconRegionReader.Frame.enclosing([
            IataCoordinate(lat: 10, lon: 179, radiusKm: nil),
            IataCoordinate(lat: 12, lon: -179, radiusKm: nil)
        ]))
        #expect(abs(frame.longitude) == 180)
        #expect(abs(frame.longitudeDelta - 2.4) < 0.000001)
        #expect(frame.latitude == 11)
        #expect(BeaconRegionReader.Frame.enclosing([]) == nil)
        let catalog = BeaconRegionReader.Catalog(iatas: [
            .init(iata: "SAT", displayName: nil, lat: nil, lon: nil),
            .init(iata: "AUS", displayName: nil, lat: 100, lon: 10),
            .init(iata: "DFW", displayName: nil, lat: 0, lon: 0)
        ], regions: [])
        #expect(catalog.names["SAT"] == "SAT")
        #expect(catalog.coordinates.coords.count == 1)
        #expect(catalog.frame(codes: ["SAT", "AUS"]) == nil)
        #expect(catalog.frame(codes: ["dfw"])?.latitude == 0)
    }

    @Test func regionHintsAreFallbackNotInventedServerDefaults() {
        let catalog = BeaconRegionReader.Catalog(iatas: [], regions: [
            .init(id: 1, slug: "hint", name: "Hint", iatas: [], centerLat: 45, centerLng: -75, zoomLevel: 8),
            .init(id: 2, slug: "unknown", name: "Unknown", iatas: [], centerLat: nil, centerLng: nil, zoomLevel: nil)
        ])
        #expect(catalog.frame(regionID: 1)?.latitude == 45)
        #expect(catalog.frame(regionID: 2) == nil)
        #expect(catalog.frame(regionID: 3) == nil)
        #expect(catalog.frame() == nil)
    }

    private static func regionSession(overrides: [String: Any] = [:]) throws -> URLSession {
        func body(_ name: String) throws -> Any {
            let root = try #require(JSONSerialization.jsonObject(with: Data(contentsOf: fixtureURL(name))) as? [String: Any])
            return try #require(root["body"])
        }
        let summaries = try #require(body("regions") as? [[String: Any]])
        var routes: [String: Any] = ["/api/v1/iatas": try body("iatas"), "/api/v1/regions": summaries]
        // Phase 0 did not retain region detail captures; members are synthetic contract examples.
        for summary in summaries {
            var detail = summary
            detail["iatas"] = ["SAT", "AUS"]
            let identifier = try #require(summary["id"] as? Int)
            routes["/api/v1/regions/\(identifier)"] = detail
        }
        routes.merge(overrides) { _, replacement in replacement }
        let encoded = String(decoding: try JSONSerialization.data(withJSONObject: routes), as: UTF8.self)
        return session(targets: Array(routes.keys), routes: encoded)
    }

    @Test func liveFixtureDecodesEvenNonFirstObservationsAndOptionalRoutes() throws {
        let root = try #require(JSONSerialization.jsonObject(with: Data(contentsOf: Self.fixtureURL("ws-session"))) as? [String: Any])
        let frames = try #require(root["frames"] as? [[String: Any]])
        var packetCount = 0
        var expectedPackets = 0
        var nonFirstCount = 0
        for frame in frames where frame["dir"] as? String == "in" {
            let message = try #require(frame["msg"] as? [String: Any])
            if message["event"] as? String == "packetObservation" { expectedPackets += 1 }
            let incoming = try BeaconLiveProtocol.decode(JSONSerialization.data(withJSONObject: message))
            if case .packet(let packet) = incoming {
                packetCount += 1
                let data = try #require(message["data"] as? [String: Any])
                let header = try #require(data["packet"] as? [String: Any])
                let hearing = try #require(data["observation"] as? [String: Any])
                if header["isFirstObservation"] as? Bool == false { nonFirstCount += 1 }
                #expect(packet.observation.heardAt.timeIntervalSince1970 == (hearing["heardAt"] as? Double).map { $0 / 1000 })
                #expect(packet.resolvedPath?.count == (hearing["resolvedPath"] as? [Any])?.count)
                #expect(packet.observationIdentity.hasPrefix(packet.packetHash + "|"))
                var withoutRoutes = message
                var changedData = data
                var changedHearing = hearing
                changedHearing.removeValue(forKey: "resolvedPath")
                changedHearing.removeValue(forKey: "observerPublicKey")
                changedData["observation"] = changedHearing
                withoutRoutes["data"] = changedData
                guard case .packet(let minimal) = try BeaconLiveProtocol.decode(JSONSerialization.data(withJSONObject: withoutRoutes)) else {
                    Issue.record("Expected packet without optional route fields")
                    continue
                }
                #expect(minimal.resolvedPath == nil && minimal.observation.observerPublicKey == nil)
            }
        }
        #expect(packetCount == expectedPackets && packetCount > 0)
        #expect(nonFirstCount > 0)
    }

    @Test func liveConfigurationAlwaysSendsAllFlags() throws {
        let command = BeaconLiveProtocol.Command.configure(.init(resolvePath: true, includeObserverKey: false, includeRepeats: false))
        let object = try #require(JSONSerialization.jsonObject(with: command.encoded()) as? [String: Any])
        #expect(object["v"] as? Int == 1)
        #expect(object["resolvePath"] as? Bool == true)
        #expect(object["includeObserverKey"] as? Bool == false)
        #expect(object["includeRepeats"] as? Bool == false)
        #expect(object["scope"] == nil)
        let ping = try #require(JSONSerialization.jsonObject(with: BeaconLiveProtocol.Command.ping().encoded()) as? [String: Any])
        #expect(ping["type"] as? String == "ping")
        #expect(BeaconLiveProtocol.pingInterval == 30)
        #expect([-1, 0, 1, 2, 3, 4, 100].map(BeaconLiveProtocol.reconnectDelay) == [1, 1, 2, 5, 10, 30, 30])
    }

    @Test func liveSubscriptionsRemoveLateAcknowledgementsAndRestoreAfterReconnect() throws {
        var state = BeaconLiveProtocol.Subscriptions()
        let subscription = LiveFeedSubscription(regions: ["SAT"], payloadTypes: [4, 5])
        let configuration = LiveFeedConfiguration(resolvePath: true, includeObserverKey: true)
        #expect(state.update(subscriptions: [subscription], configuration: configuration).isEmpty)
        let initial = state.hello()
        #expect(initial.filter { $0.type == "configure" }.count == 1)
        let subscribe = try #require(initial.first { $0.type == "subscribe" })
        #expect(subscribe.scope?.iatas == ["SAT"])
        #expect(subscribe.scope?.payloadTypes == [4, 5])
        #expect(state.update(subscriptions: [subscription], configuration: configuration).isEmpty)
        #expect(state.update(subscriptions: [], configuration: configuration).isEmpty)
        let remove = state.acknowledge(requestID: subscribe.id, subscriptionID: "server-sub")
        #expect(remove.first?.type == "unsubscribe" && remove.first?.subscriptionId == "server-sub")
        #expect(state.acknowledge(requestID: subscribe.id, subscriptionID: "server-sub").isEmpty)
        let second = try #require(state.update(subscriptions: [subscription], configuration: configuration).first)
        #expect(state.acknowledge(requestID: second.id, subscriptionID: "second-sub").isEmpty)
        state.disconnected()
        #expect(state.acknowledge(requestID: second.id, subscriptionID: "second-sub").isEmpty)
        let restored = state.hello()
        #expect(restored.filter { $0.type == "subscribe" }.count == 1)
        #expect(restored.first { $0.type == "configure" }?.resolvePath == true)
        #expect(restored.first { $0.type == "subscribe" }?.id != second.id)
    }

    @Test func liveActiveSubscriptionRemovalAndFlagDisableAreExplicit() throws {
        var state = BeaconLiveProtocol.Subscriptions()
        let subscription = LiveFeedSubscription()
        _ = state.update(subscriptions: [subscription], configuration: .init(resolvePath: true))
        let command = try #require(state.hello().first { $0.type == "subscribe" })
        _ = state.acknowledge(requestID: command.id, subscriptionID: "active")
        let removed = state.update(subscriptions: [], configuration: .init())
        #expect(removed.first { $0.type == "unsubscribe" }?.subscriptionId == "active")
        #expect(removed.first { $0.type == "configure" }?.resolvePath == false)
    }

    @Test func liveLaggedNoticesRequestRecoveryAndUnknownEventsAreIgnored() throws {
        guard case .recoveryRequired = try BeaconLiveProtocol.decode(Data(#"{"v":1,"type":"lagged","droppedCount":2}"#.utf8)) else {
            Issue.record("Lagged notice must request REST recovery")
            return
        }
        guard case .ignored = try BeaconLiveProtocol.decode(Data(#"{"v":1,"type":"event","event":"futureEvent","data":{}}"#.utf8)) else {
            Issue.record("Unknown event must be ignored")
            return
        }
        #expect(throws: APIError.self) { _ = try BeaconLiveProtocol.decode(Data(#"{"v":2,"type":"hello"}"#.utf8)) }
        #expect(throws: APIError.self) { _ = try BeaconLiveProtocol.decode(Data(#"{"v":1,"type":"subscribed"}"#.utf8)) }
    }

    @Test @MainActor func liveTransportSendsDemandAndStopsOnSourceSwitch() async throws {
        let session = Self.session(targets: [])
        defer { session.invalidateAndCancel() }
        let client = try Self.client(session)
        let socket = BeaconTestSocket()
        var connectedURL: URL?
        let transport = try BeaconLiveTransport(client: client, verifiedServerInfo: .init(serverVersion: "2.0.3"), makeSocket: {
            connectedURL = $0
            return socket
        })
        defer { transport.disconnect() }
        transport.update(subscriptions: [.init(regions: ["SAT"])], configuration: .init(resolvePath: true))
        transport.connect()
        socket.push(#"{"v":1,"type":"hello"}"#)
        try await Self.waitUntil { socket.sent.count >= 2 }
        #expect(connectedURL?.absoluteString == "wss://beacon.invalid/ws")
        #expect(socket.sent.contains { $0["type"] as? String == "configure" && $0["resolvePath"] as? Bool == true })
        #expect(socket.sent.contains { $0["type"] as? String == "subscribe" })
        client.sourceSession.invalidate()
        try await Self.waitUntil { socket.closed }
    }

    @Test @MainActor func liveTransportPingsAndReconnectsAfterMissingHello() async throws {
        let session = Self.session(targets: [])
        defer { session.invalidateAndCancel() }
        let clock = BeaconTestClock()
        let first = BeaconTestSocket()
        let second = BeaconTestSocket()
        var connections = 0
        let transport = try BeaconLiveTransport(
            client: Self.client(session), verifiedServerInfo: .init(serverVersion: "2.0.3"),
            makeSocket: { _ in
                connections += 1
                if connections == 1 { return first }
                second.push(#"{"v":1,"type":"hello"}"#)
                return second
            },
            sleep: { seconds in
                try await Task.sleep(for: .milliseconds(5))
                clock.advance(seconds)
            }, now: { clock.now }
        )
        defer { transport.disconnect() }
        transport.update(subscriptions: [.init()], configuration: .init())
        transport.connect()
        try await Self.waitUntil { second.sent.contains { $0["type"] as? String == "ping" } }
        #expect(first.closed)
        #expect(connections == 2)
        #expect(second.sent.contains { $0["type"] as? String == "subscribe" })
    }

    @Test @MainActor func liveTransport429SharesCooldownWithREST() async throws {
        let session = Self.session(targets: [])
        defer { session.invalidateAndCancel() }
        let gate = BeaconRateLimit()
        let client = try Self.client(session, gate: gate)
        let socket = BeaconTestSocket()
        socket.response = HTTPURLResponse(url: try #require(URL(string: "https://beacon.invalid/ws")), statusCode: 429,
                                          httpVersion: nil, headerFields: ["Retry-After": "120"])
        socket.fail = true
        let delays = BeaconTestClock()
        let transport = try BeaconLiveTransport(
            client: client, verifiedServerInfo: .init(serverVersion: "2.0.3"),
            makeSocket: { _ in socket },
            sleep: { seconds in
                delays.record(seconds)
                try await Task.sleep(for: .seconds(1000))
            }, now: { Date(timeIntervalSince1970: 1000) }
        )
        defer { transport.disconnect() }
        transport.connect()
        try await Self.waitUntil { delays.delays.contains(120) }
        await #expect(throws: BeaconAPIError.self) { try await client.checkCooldown() }
        #expect(socket.closed)
    }

    @Test @MainActor func liveRecoveryMergesByHashWithoutInventingFirstSeen() async throws {
        let session = Self.session(targets: ["/api/v1/packets?limit=200&iatas=SAT&payloadType=5"], fixture: "packets-page")
        defer { session.invalidateAndCancel() }
        let reader = try BeaconPacketReader(client: Self.client(session), verifiedServerInfo: .init(serverVersion: "2.0.3"))
        let recovery = BeaconLiveRecovery(reader: reader, region: "SAT", payloadType: 5)
        let fixture = try Self.fixtureBody("packets-page")
        let items = try #require(fixture["items"] as? [[String: Any]])
        let hash = try #require(items.first?["packetHash"] as? String)
        let event = try Self.liveObservation(hash: hash, count: 99999, milliseconds: 1999999999999)
        try recovery.receive(event)
        try recovery.receive(event)
        #expect(recovery.rows.count == 1 && recovery.rows.first?.firstSeen == nil)
        try await recovery.recover()
        #expect(recovery.rows.map(\.hash) == items.compactMap { $0["packetHash"] as? String })
        #expect(recovery.rows.first?.observationCount == 99999)
        #expect(recovery.rows.first?.lastHeard == Date(timeIntervalSince1970: 1999999999.999))
        #expect(recovery.rows.first?.firstSeen != nil)
        #expect(recovery.possiblyIncomplete)
        reader.client.sourceSession.invalidate()
        await #expect(throws: CancellationError.self) { try await recovery.recover() }
    }

    @Test @MainActor func liveRecoveryKeepsNewerEventsArrivingDuringREST() async throws {
        let source = AnalyzerSourceSession()
        let fixture = try Self.fixtureBody("packet-detail")
        let hash = try #require(fixture["packetHash"] as? String)
        let session = Self.session(targets: ["/api/v1/packets/\(hash)"], fixture: "packet-detail")
        defer { session.invalidateAndCancel() }
        let reader = try BeaconPacketReader(client: Self.client(session), verifiedServerInfo: .init(serverVersion: "2.0.3"))
        let packet = try await reader.packetDetail(hash: hash).packet
        let recovery = BeaconLiveRecovery(sourceSession: source, region: nil, payloadType: nil) {
            try await Task.sleep(for: .milliseconds(20))
            return AnalyzerPage(items: [packet], nextCursor: nil, total: nil)
        }
        let refresh = Task { try await recovery.recover() }
        try await Self.waitUntil { recovery.recovering }
        try recovery.receive(Self.liveObservation(hash: "live-only", count: 1, milliseconds: 1999999999999))
        try await refresh.value
        #expect(recovery.rows.map(\.hash) == ["live-only", hash])
        #expect(recovery.rows.first?.firstSeen == nil)
    }

    @Test @MainActor func liveRecoveryRejectsLateSourceResultsAndKeepsFailuresVisible() async throws {
        let source = AnalyzerSourceSession()
        let recovery = BeaconLiveRecovery(sourceSession: source, region: nil, payloadType: nil) {
            try await Task.sleep(for: .milliseconds(20))
            return AnalyzerPage(items: [], nextCursor: nil, total: nil)
        }
        let task = Task { try await recovery.recover() }
        try await Self.waitUntil { recovery.recovering }
        source.invalidate()
        await #expect(throws: CancellationError.self) { try await task.value }
        #expect(recovery.rows.isEmpty && recovery.possiblyIncomplete && !recovery.recovering)
        let failing = BeaconLiveRecovery(sourceSession: AnalyzerSourceSession(), region: nil, payloadType: nil) {
            throw URLError(.notConnectedToInternet)
        }
        try failing.receive(Self.liveObservation(hash: "kept", count: 2, milliseconds: 1000))
        await #expect(throws: URLError.self) { try await failing.recover() }
        #expect(failing.rows.map(\.hash) == ["kept"] && failing.possiblyIncomplete)
    }

    @Test @MainActor func beaconFeedIntegratesSharedDemandsRecoveryAndObserverLearning() async throws {
        let settings = AnalyzerSettings()
        let detail = try Self.fixtureBody("observer-detail")
        let identifier = try #require(detail["id"] as? String)
        let key = try #require(detail["publicKey"] as? String)
        let routes: [String: Any] = [
            "/api/v1/packets?limit=200": ["items": [], "hasMore": false],
            "/api/v1/packets?limit=200&iatas=SAT&payloadType=5": ["items": [], "hasMore": false],
            "/api/v1/observers/\(identifier)": detail
        ]
        let session = Self.session(targets: Array(routes.keys),
                                   routes: String(decoding: try JSONSerialization.data(withJSONObject: routes), as: UTF8.self))
        defer { session.invalidateAndCancel() }
        let client = try BeaconAPIClient(baseURL: settings.baseURL, sourceSession: settings.sourceSession, session: session)
        let socket = BeaconTestSocket()
        var socketCount = 0
        let transport = try BeaconLiveTransport(client: client, verifiedServerInfo: .init(serverVersion: "2.0.3"), makeSocket: { _ in
            socketCount += 1
            return socket
        })
        let feed = try BeaconLiveFeed(client: client, verifiedServerInfo: .init(serverVersion: "2.0.3"), transport: transport)
        let service = LiveFeedService(settings: settings) { _ in feed }
        defer { service.disconnect() }
        let consumer = UUID()
        let filter = LiveBrowseFilter(region: "SAT", payloadType: 5)
        service.setDemand(.init(subscription: .init(regions: ["SAT"], payloadTypes: [5]),
                                configuration: .init(resolvePath: true, includeObserverKey: true)), for: consumer)
        service.connect()
        socket.push(#"{"v":1,"type":"hello"}"#)
        try await Self.waitUntil { service.isConnected && service.browseSnapshots.count == 2 }
        let message: [String: Any] = [
            "v": 1, "type": "event", "event": "packetObservation",
            "data": ["packetHash": "live-new",
                     "packet": ["payloadType": 5, "routeType": 1, "observationCount": 3, "isFirstObservation": false],
                     "observation": [
                        "observerId": identifier,
                        "observerPublicKey": key,
                        "iata": "SAT",
                        "heardAt": 1999999999999,
                        "resolvedPath": [["confidence": "high", "nodes": [["publicKey": key]]]]
                     ]]
        ]
        let encoded = String(decoding: try JSONSerialization.data(withJSONObject: message), as: UTF8.self)
        socket.push(encoded)
        socket.push(encoded)
        try await Self.waitUntil { service.browseSnapshots[filter]?.rows.first?.hash == "live-new" }
        #expect(service.browseSnapshots[filter]?.rows.count == 1)
        #expect(service.browseSnapshots[filter]?.rows.first?.observationCount == 3)
        #expect(service.browseSnapshots[filter]?.rows.first?.firstSeen == nil)
        try await Self.waitUntil { service.recentEvents.first?.data?.hash == "live-new" }
        let liveEvent = try #require(service.recentEvents.first)
        #expect(liveEvent.type == "packet")
        #expect(liveEvent.data?.observerId == identifier)
        #expect(liveEvent.data?.observationCount == 3)
        #expect(liveEvent.data?.decoded?.header?.payloadType == 5)
        #expect(liveEvent.data?.resolvedPath == [key.lowercased()])
        #expect(service.usesBrowseSnapshots)
        #expect(try await feed.observers.observerDetail(publicKey: key)?.id == identifier)
        #expect(socketCount == 1)
        let nodeMessage: [String: Any] = [
            "v": 1, "type": "event", "event": "nodeUpdate",
            "data": ["nodeId": identifier, "publicKey": key, "nodeType": 2, "lat": 30, "lng": -98]
        ]
        socket.push(String(decoding: try JSONSerialization.data(withJSONObject: nodeMessage), as: UTF8.self))
        let statusMessage: [String: Any] = [
            "v": 1, "type": "event", "event": "observerStatus",
            "data": ["observerId": identifier, "online": true, "batteryMv": 4000]
        ]
        socket.push(String(decoding: try JSONSerialization.data(withJSONObject: statusMessage), as: UTF8.self))
        try await Self.waitUntil {
            service.beaconNodes[key.lowercased()]?.position == .coordinate(latitude: 30, longitude: -98)
                && service.beaconObservers[identifier.lowercased()]?.batteryMv == 4000
        }
        socket.push(#"{"v":1,"type":"lagged","droppedCount":1}"#)
        try await Self.waitUntil { service.browseSnapshots[filter]?.possiblyIncomplete == true }
        service.setDemand(nil, for: consumer)
        try await Self.waitUntil { service.browseSnapshots[filter] == nil }
        try await Self.waitUntil { socket.sent.contains { $0["type"] as? String == "configure" && $0["resolvePath"] as? Bool == false } }
        service.disconnect()
        #expect(socket.closed && service.browseSnapshots.isEmpty && !service.isConnected)
        #expect(service.beaconNodes.isEmpty && service.beaconObservers.isEmpty)
    }

    @Test @MainActor func beaconRecoveryErrorsStayVisibleUntilProjectionRemoval() async throws {
        let settings = AnalyzerSettings()
        let session = Self.session(targets: ["/api/v1/packets?limit=200", "/api/v1/packets?limit=200&iatas=SAT"], status: 503)
        defer { session.invalidateAndCancel() }
        let client = try BeaconAPIClient(baseURL: settings.baseURL, sourceSession: settings.sourceSession, session: session)
        let socket = BeaconTestSocket()
        let transport = try BeaconLiveTransport(client: client, verifiedServerInfo: .init(serverVersion: "2.0.3"), makeSocket: { _ in socket })
        let feed = try BeaconLiveFeed(client: client, verifiedServerInfo: .init(serverVersion: "2.0.3"), transport: transport)
        let service = LiveFeedService(settings: settings) { _ in feed }
        defer { service.disconnect() }
        let consumer = UUID()
        let filter = LiveBrowseFilter(region: "SAT", payloadType: nil)
        service.setDemand(.init(subscription: .init(regions: ["SAT"])), for: consumer)
        service.connect()
        socket.push(#"{"v":1,"type":"hello"}"#)
        try await Self.waitUntil { service.recoveryErrors.count == 2 }
        #expect(service.browseSnapshots[filter]?.possiblyIncomplete == true)
        #expect(service.isConnected)
        service.setDemand(nil, for: consumer)
        try await Self.waitUntil { service.recoveryErrors[filter] == nil && service.browseSnapshots[filter] == nil }
        service.disconnect()
        #expect(service.recoveryErrors.isEmpty)
    }

    @Test @MainActor func beaconPacketDetailLoadsOnDemandAndRejectsUncertainReplay() async throws {
        for uncertain in [false, true] {
            let settings = AnalyzerSettings()
            var fixture = try Self.fixtureBody("packet-detail")
            let hash = try #require(fixture["packetHash"] as? String)
            var observations = try #require(fixture["observations"] as? [[String: Any]])
            let firstKey = String(repeating: "ab", count: 32)
            let secondKey = String(repeating: "cd", count: 32)
            for index in observations.indices {
                observations[index]["resolvedPath"] = [
                    ["confidence": "high", "nodes": [["publicKey": firstKey]]],
                    ["confidence": uncertain ? "ambiguous" : "high", "nodes": [["publicKey": secondKey]]]
                ]
            }
            fixture["observations"] = observations
            let session = Self.session(targets: ["/api/v1/packets/\(hash)"],
                                       body: String(decoding: try JSONSerialization.data(withJSONObject: fixture), as: UTF8.self))
            defer { session.invalidateAndCancel() }
            let client = try BeaconAPIClient(baseURL: settings.baseURL, sourceSession: settings.sourceSession, session: session)
            let socket = BeaconTestSocket()
            let transport = try BeaconLiveTransport(client: client, verifiedServerInfo: .init(serverVersion: "2.0.3"), makeSocket: { _ in socket })
            let feed = try BeaconLiveFeed(client: client, verifiedServerInfo: .init(serverVersion: "2.0.3"), transport: transport)
            let service = LiveFeedService(settings: settings) { _ in feed }
            defer { service.disconnect() }
            service.connect()
            let model = BeaconPacketDetailViewModel()
            await model.load(hash: hash, sourceSessionID: settings.sourceSession.id, service: service)
            #expect(model.detail?.packet.hash == hash && model.errorMessage == nil)
            #expect(model.detail?.packet.serverID == nil && model.detail?.packet.rawHex == nil)
            #expect(model.routes.isEmpty == uncertain)
            if !uncertain { #expect(model.routes.first?.keys == [firstKey, secondKey]) }
            settings.renewSourceSession()
            await model.load(hash: hash, sourceSessionID: settings.sourceSession.id, service: service)
            #expect(model.detail == nil && model.routes.isEmpty && model.errorMessage != nil)
        }
    }

    @Test @MainActor func beaconPacketDetail404IsVisibleWithoutDiscardingSummaryIdentity() async throws {
        let settings = AnalyzerSettings()
        let session = Self.session(targets: ["/api/v1/packets/missing"], status: 404)
        defer { session.invalidateAndCancel() }
        let client = try BeaconAPIClient(baseURL: settings.baseURL, sourceSession: settings.sourceSession, session: session)
        let transport = try BeaconLiveTransport(client: client, verifiedServerInfo: .init(serverVersion: "2.0.3"), makeSocket: { _ in BeaconTestSocket() })
        let feed = try BeaconLiveFeed(client: client, verifiedServerInfo: .init(serverVersion: "2.0.3"), transport: transport)
        let service = LiveFeedService(settings: settings) { _ in feed }
        defer { service.disconnect() }
        service.connect()
        let model = BeaconPacketDetailViewModel()
        await model.load(hash: "missing", sourceSessionID: settings.sourceSession.id, service: service)
        #expect(model.detail == nil && model.routes.isEmpty && model.errorMessage != nil && !model.isLoading)
        #expect(model.belongsToSource(settings.sourceSession.id))
    }

    @Test @MainActor func packetDetailServiceRejectsResultAfterFeedDisconnect() async throws {
        let settings = AnalyzerSettings()
        let feed = BeaconDetailTestFeed()
        let service = LiveFeedService(settings: settings) { _ in feed }
        defer { service.disconnect() }
        service.connect()
        let request = Task { try await service.packetDetail(hash: "test", sourceSessionID: settings.sourceSession.id) }
        try await Self.waitUntil { feed.pending != nil }
        service.disconnect()
        let packet = Packet(serverID: nil, rawHex: nil, hash: "test", firstSeen: .now,
                            timestamp: .now, routeType: 1, payloadType: 5, payloadVersion: nil,
                            decodedJson: nil, observationCount: 1, observerId: nil, observerName: nil,
                            snr: nil, rssi: nil, pathJson: nil, direction: nil, score: nil)
        feed.pending?.resume(returning: PacketDetailResponse(packet: packet, path: [], observationCount: 1, observations: []))
        feed.pending = nil
        await #expect(throws: CancellationError.self) { _ = try await request.value }
    }

    private static func liveObservation(hash: String, count: Int, milliseconds: Int64) throws -> BeaconLiveProtocol.Observation {
        let data: [String: Any] = ["v": 1, "type": "event", "event": "packetObservation",
                                  "data": ["packetHash": hash,
                                           "packet": ["payloadType": 5, "routeType": 1, "observationCount": count],
                                           "observation": ["observerId": "f6c9d02e-eee0-7882-1a89-9bc1dc6d1170",
                                                           "iata": "SAT", "heardAt": milliseconds]]]
        guard case .packet(let observation) = try BeaconLiveProtocol.decode(JSONSerialization.data(withJSONObject: data)) else {
            throw APIError.invalidResponse
        }
        return observation
    }

    @Test func phaseThreeFixturesPreserveCoverageAndPagingSemantics() throws {
        let rawActivity = try Self.fixtureBody("observer-activity-24h")
        #expect(rawActivity["interval"] as? String == "15m")
        #expect((rawActivity["points"] as? [[String: Any]])?.isEmpty == false)
        #expect(rawActivity["rolledUntil"] == nil)
        #expect(rawActivity["rawFrom"] == nil)

        let hourlyActivity = try Self.fixtureBody("observer-activity-7d")
        #expect(hourlyActivity["interval"] as? String == "1h")
        #expect(hourlyActivity["rolledUntil"] is NSNumber)
        #expect(hourlyActivity["rawFrom"] is NSNumber)

        let telemetry = try Self.fixtureBody("observer-telemetry-24h")
        let telemetryPoints = try #require(telemetry["points"] as? [[String: Any]])
        let point = try #require(telemetryPoints.first)
        #expect(point["batteryMv"] is NSNumber)
        #expect(point["noiseFloorDb"] is NSNumber)
        #expect(point["airtimeTxSecs"] is NSNumber)
        #expect(point["airtimeRxSecs"] is NSNumber)

        let firstPage = try Self.fixtureBody("channels-keyknown-page-1")
        let secondPage = try Self.fixtureBody("channels-keyknown-page-2")
        #expect(firstPage["nextPageCursor"] is String)
        let firstIDs = Set(try #require(firstPage["items"] as? [[String: Any]]).compactMap { $0["id"] as? Int })
        let secondIDs = Set(try #require(secondPage["items"] as? [[String: Any]]).compactMap { $0["id"] as? Int })
        #expect(firstIDs.isDisjoint(with: secondIDs))
    }

    @Test func phaseThreeEmptyHistoryFixturesAreSuccessfulAndComplete() throws {
        let observations = try Self.fixtureBody("node-observations-empty")
        #expect((observations["items"] as? [Any])?.isEmpty == true)
        #expect(observations["hasMore"] as? Bool == false)

        let activity = try Self.fixtureBody("observer-activity-empty")
        #expect((activity["points"] as? [Any])?.isEmpty == true)
        #expect((activity["payloadTypes"] as? [Any])?.isEmpty == true)
        let summary = try #require(activity["summary"] as? [String: Any])
        #expect(summary["recordedPackets"] as? Int == 0)

        let telemetry = try Self.fixtureBody("observer-telemetry-empty")
        #expect((telemetry["points"] as? [Any])?.isEmpty == true)
    }

    @MainActor private static func waitUntil(_ condition: () -> Bool) async throws {
        for _ in 0..<2000 {
            if condition() { return }
            try await Task.sleep(for: .milliseconds(1))
        }
        throw URLError(.timedOut)
    }

    private static func client(
        _ session: URLSession, gate: BeaconRateLimit = BeaconRateLimit(), host: String = "beacon.invalid"
    ) throws -> BeaconAPIClient {
        try BeaconAPIClient(
            baseURL: #require(URL(string: "https://\(host)")), sourceSession: AnalyzerSourceSession(),
            session: session, rateLimit: gate, now: { Date(timeIntervalSince1970: 1000) }
        )
    }

    private static func fixtureURL(_ name: String) -> URL {
        URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("shared/fixtures/beacon/\(name).json")
    }

    private static func fixtureBody(_ name: String) throws -> [String: Any] {
        let root = try #require(JSONSerialization.jsonObject(with: Data(contentsOf: fixtureURL(name))) as? [String: Any])
        return try #require(root["body"] as? [String: Any])
    }

    private static func session(
        targets: [String], status: Int = 200, body: String = "{}", fixture: String? = nil,
        retryAfter: String? = nil, detailFixture: String? = nil, routes: String? = nil
    ) -> URLSession {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [BeaconFixtureProtocol.self]
        var headers = [
            "X-Test-Targets": targets.joined(separator: "|"),
            "X-Test-Status": String(status), "X-Test-Body": body
        ]
        if let fixture { headers["X-Test-File"] = fixtureURL(fixture).path }
        if let detailFixture { headers["X-Test-Detail-File"] = fixtureURL(detailFixture).path }
        if let retryAfter { headers["X-Test-Retry"] = retryAfter }
        if let routes { headers["X-Test-Routes"] = routes }
        configuration.httpAdditionalHeaders = headers
        return URLSession(configuration: configuration)
    }
}

@MainActor
private final class BeaconDetailTestFeed: AnalyzerLiveFeed, LivePacketDetailProvider, LiveObserverDetailProvider {
    let events: AsyncStream<AnalyzerLiveEvent>
    private let continuation: AsyncStream<AnalyzerLiveEvent>.Continuation
    var pending: CheckedContinuation<PacketDetailResponse, Error>?
    var pendingObserver: CheckedContinuation<MeshObserver, Error>?
    var observerRequests = 0

    init() {
        let stream = AsyncStream<AnalyzerLiveEvent>.makeStream()
        events = stream.stream
        continuation = stream.continuation
    }

    func connect() {}
    func disconnect() { continuation.finish() }
    func subscribe(_ subscriptions: Set<LiveFeedSubscription>) {}
    func configure(_ configuration: LiveFeedConfiguration) {}
    func packetDetail(hash: String) async throws -> PacketDetailResponse {
        try await withCheckedThrowingContinuation { pending = $0 }
    }
    func observerDetail(id: String) async throws -> MeshObserver {
        observerRequests += 1
        return try await withCheckedThrowingContinuation { pendingObserver = $0 }
    }
}

@MainActor
private final class BeaconTestSocket: BeaconSocket {
    var response: HTTPURLResponse?
    var closeCode = 0
    var fail = false
    var closed = false
    var sent: [[String: Any]] = []
    private var queued: [Data] = []
    private var receiver: CheckedContinuation<Data, Error>?

    func push(_ text: String) {
        let data = Data(text.utf8)
        if let receiver {
            self.receiver = nil
            receiver.resume(returning: data)
        } else {
            queued.append(data)
        }
    }

    func receive() async throws -> Data {
        if closed || fail { throw URLError(.networkConnectionLost) }
        if !queued.isEmpty { return queued.removeFirst() }
        return try await withCheckedThrowingContinuation { receiver = $0 }
    }

    func send(_ data: Data) async throws {
        if closed { throw URLError(.cancelled) }
        let object = try #require(JSONSerialization.jsonObject(with: data) as? [String: Any])
        sent.append(object)
        let identifier = try #require(object["id"] as? String)
        let type = try #require(object["type"] as? String)
        let reply: [String: Any]
        if type == "subscribe" {
            reply = ["v": 1, "type": "subscribed", "id": identifier, "subscriptionId": UUID().uuidString]
        } else {
            reply = ["v": 1, "type": type == "ping" ? "pong" : type == "configure" ? "configured" : "unsubscribed", "id": identifier]
        }
        push(String(decoding: try JSONSerialization.data(withJSONObject: reply), as: UTF8.self))
    }

    func close() {
        closed = true
        receiver?.resume(throwing: URLError(.cancelled))
        receiver = nil
    }
}

private final class BeaconTestClock: @unchecked Sendable {
    private let lock = NSLock()
    private var date = Date(timeIntervalSince1970: 1000)
    private var recorded: [TimeInterval] = []
    var now: Date { lock.withLock { date } }
    var delays: [TimeInterval] { lock.withLock { recorded } }
    func advance(_ seconds: TimeInterval) { lock.withLock { date.addTimeInterval(seconds) } }
    func record(_ seconds: TimeInterval) { lock.withLock { recorded.append(seconds) } }
}

private final class BeaconFixtureProtocol: URLProtocol, @unchecked Sendable {
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func stopLoading() {}

    override func startLoading() {
        do {
            let url = try #require(request.url)
            let components = try #require(URLComponents(url: url, resolvingAgainstBaseURL: false))
            let target = components.percentEncodedPath + (components.percentEncodedQuery.map { "?\($0)" } ?? "")
            let targets = request.value(forHTTPHeaderField: "X-Test-Targets")?.components(separatedBy: "|") ?? []
            guard targets.contains(target) else { throw URLError(.badURL) }
            let status = request.value(forHTTPHeaderField: "X-Test-Status").flatMap(Int.init) ?? 200
            var headers: [String: String] = ["Content-Type": "application/json"]
            if let retry = request.value(forHTTPHeaderField: "X-Test-Retry") { headers["Retry-After"] = retry }
            let response = try #require(HTTPURLResponse(url: url, statusCode: status, httpVersion: nil, headerFields: headers))
            let data: Data
            let fixtureHeader = components.path.hasPrefix("/api/v1/nodes/") ? "X-Test-Detail-File" : "X-Test-File"
            if let encoded = request.value(forHTTPHeaderField: "X-Test-Routes") {
                let routes = try #require(JSONSerialization.jsonObject(with: Data(encoded.utf8)) as? [String: Any])
                data = try JSONSerialization.data(withJSONObject: #require(routes[target]))
            } else if let path = request.value(forHTTPHeaderField: fixtureHeader) {
                let root = try #require(JSONSerialization.jsonObject(with: Data(contentsOf: URL(fileURLWithPath: path))) as? [String: Any])
                var body = try #require(root["body"] as? [String: Any])
                if let pages = body["pages"] as? [[String: Any]] {
                    let cursor = components.queryItems?.first { $0.name == "cursor" }?.value
                    let index = try #require(pages.firstIndex { ($0["cursor"] as? NSNumber)?.stringValue == cursor })
                    let items = try #require(body["items"] as? [[String: Any]])
                    // The sanitized capture retains six samples, not all 878 nodes.
                    // Repeat samples across recorded cursors to exercise overlap deduplication.
                    body = ["items": items,
                            "hasMore": try #require(pages[index]["hasMore"]),
                            "nextCursor": pages[index]["next"] ?? NSNull()]
                }
                data = try JSONSerialization.data(withJSONObject: body)
            } else {
                data = Data((request.value(forHTTPHeaderField: "X-Test-Body") ?? "{}").utf8)
            }
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: data)
            client?.urlProtocolDidFinishLoading(self)
        } catch {
            client?.urlProtocol(self, didFailWithError: error)
        }
    }
}
