import Foundation
import Observation

/// Single shared live connection to the analyzer host, broadcasting
/// "packet" and "message" events (see "WebSocket Messages" in
/// docs/api-spec.md). Injected once via the environment so the map, live
/// feed, and channels screens all observe the same stream instead of each
/// opening their own socket. The socket itself lives in an `AnalyzerLiveFeed`.
@Observable
@MainActor
final class LiveFeedService {
    private(set) var recentEvents: [LiveEnvelope] = []
    private(set) var usesBrowseSnapshots = false
    private(set) var browseSnapshots: [LiveBrowseFilter: LiveBrowseSnapshot] = [:]
    private(set) var recoveryErrors: [LiveBrowseFilter: String] = [:]
    private(set) var beaconNodes: [String: BeaconLiveProtocol.NodeState] = [:]
    private(set) var beaconObservers: [String: BeaconLiveProtocol.ObserverStatus] = [:]
    private(set) var beaconNodeSequence = 0
    private(set) var beaconObserverSequence = 0
    private(set) var beaconNodeCatalog: [BeaconNodeReader.Node]?
    private(set) var beaconNodeCatalogID: UUID?
    private(set) var metadataRecoveryError: String?
    private(set) var metadataNeedsRecovery = false
    private(set) var observerNeedsRecovery = false
    private(set) var observerRecoveryError: String?
    private var observerCatalog: [BeaconObserverReader.Observer]?
    private(set) var eventSequence = 0
    private(set) var isConnected = false
    private(set) var lastError: String?

    private let maxEvents = 200
    private let maxMetadataEntries = 1_000
    private var nodeOrder: [String] = []
    private var observerOrder: [String] = []
    private let settings: AnalyzerSettings
    private let makeFeed: @MainActor (AnalyzerSettings) -> any AnalyzerLiveFeed
    private var feed: (any AnalyzerLiveFeed)?
    private var eventTask: Task<Void, Never>?
    private var connectedSource: String?
    private var connectedSession: UUID?
    private var generation = UUID()
    private var demands: [UUID: (sessionID: UUID, demand: LiveFeedDemand)] = [:]

    func setDemand(_ demand: LiveFeedDemand?, for consumer: UUID) {
        demands[consumer] = demand.map { (settings.sourceSession.id, $0) }
        applyDemands()
    }

    private func applyDemands() {
        let current = demands.values.filter { $0.sessionID == settings.sourceSession.id }.map(\.demand)
        // Keep the general packet feed; visible consumers can request extra metadata.
        let subscriptions = Set(current.map(\.subscription) + [LiveFeedSubscription()])
        let configuration = LiveFeedConfiguration(
            resolvePath: current.contains { $0.configuration.resolvePath },
            includeObserverKey: current.contains { $0.configuration.includeObserverKey },
            includeRepeats: current.contains { $0.configuration.includeRepeats },
            recoverObservers: current.contains { $0.configuration.recoverObservers }
        )
        feed?.subscribe(subscriptions)
        feed?.configure(configuration)
    }

    init(
        settings: AnalyzerSettings,
        makeFeed: @escaping @MainActor (AnalyzerSettings) -> any AnalyzerLiveFeed = AnalyzerBackendFactory.makeLiveFeed
    ) {
        self.settings = settings
        self.makeFeed = makeFeed
    }

    func connect() {
        disconnect()
        let source = settings.webSocketURL.absoluteString
        if connectedSource != source || connectedSession != settings.sourceSession.id {
            recentEvents = []
            connectedSource = source
            connectedSession = settings.sourceSession.id
        }
        lastError = nil
        let feed = makeFeed(settings)
        self.feed = feed
        usesBrowseSnapshots = feed.usesBrowseSnapshots
        applyDemands()
        let generation = self.generation
        let sourceSession = settings.sourceSession
        eventTask = Task { [weak self] in
            for await event in feed.events {
                guard let self, !Task.isCancelled, self.generation == generation, sourceSession.isCurrent else { return }
                switch event {
                case .observerCatalogInvalidated:
                    self.beaconObservers = [:]
                    self.observerOrder = []
                    self.observerNeedsRecovery = true
                    self.beaconObserverSequence &+= 1
                case .metadataInvalidated:
                    self.beaconNodes = [:]
                    self.beaconObservers = [:]
                    self.beaconObserverSequence &+= 1
                    self.nodeOrder = []
                    self.observerOrder = []
                    self.metadataNeedsRecovery = true
                    self.observerNeedsRecovery = true
                    self.beaconNodeSequence &+= 1
                case .nodeCatalog(let nodes):
                    self.beaconNodeCatalog = nodes
                    self.beaconNodeCatalogID = UUID()
                    self.metadataNeedsRecovery = false
                    self.metadataRecoveryError = nil
                    self.beaconNodeSequence &+= 1
                case .metadataRecoveryFailed(let message):
                    self.metadataRecoveryError = message
                case .observerCatalog(let observers):
                    self.observerCatalog = observers
                    self.observerNeedsRecovery = false
                    self.observerRecoveryError = nil
                    self.beaconObserverSequence &+= 1
                case .observerRecoveryFailed(let message):
                    self.observerRecoveryError = message
                case .nodeUpdated(let update):
                    let key = update.publicKey.lowercased()
                    var state = self.beaconNodes[key] ?? BeaconLiveProtocol.NodeState(update)
                    state.merge(update)
                    self.beaconNodes[key] = state
                    self.nodeOrder.removeAll { $0 == key }
                    self.nodeOrder.append(key)
                    if self.nodeOrder.count > self.maxMetadataEntries {
                        self.beaconNodes[self.nodeOrder.removeFirst()] = nil
                    }
                    self.beaconNodeSequence &+= 1
                case .observerStatus(let status):
                    let identifier = status.observerId.lowercased()
                    // Status is a full snapshot: missing optional fields must clear old values.
                    self.beaconObservers[identifier] = status
                    self.observerOrder.removeAll { $0 == identifier }
                    self.observerOrder.append(identifier)
                    if self.observerOrder.count > self.maxMetadataEntries {
                        self.beaconObservers[self.observerOrder.removeFirst()] = nil
                    }
                    self.beaconObserverSequence &+= 1
                case .browseSnapshot(let snapshot):
                    self.browseSnapshots[snapshot.filter] = snapshot
                case .browseRemoved(let filter):
                    self.browseSnapshots[filter] = nil
                    self.recoveryErrors[filter] = nil
                case .recoveryFailed(let filter, let message):
                    self.recoveryErrors[filter] = message
                case .recoveryCompleted(let filter):
                    self.recoveryErrors[filter] = nil
                case .packet(let envelope):
                    self.recentEvents.insert(envelope, at: 0)
                    self.eventSequence &+= 1
                    if self.recentEvents.count > self.maxEvents {
                        self.recentEvents.removeLast(self.recentEvents.count - self.maxEvents)
                    }
                case .connected:
                    self.applyDemands()
                    self.isConnected = true
                    self.lastError = nil
                case .disconnected(let message):
                    self.isConnected = false
                    self.lastError = message
                case .decodingFailed(let message):
                    self.lastError = "Decode error: \(message)"
                }
            }
        }
        feed.connect()
    }

    func resumeAfterForeground() {
        feed?.resumeAfterForeground()
    }

    func disconnect() {
        generation = UUID()
        eventTask?.cancel()
        eventTask = nil
        feed?.disconnect()
        feed = nil
        isConnected = false
        browseSnapshots = [:]
        recoveryErrors = [:]
        beaconNodes = [:]
        beaconNodeCatalog = nil
        beaconNodeCatalogID = nil
        metadataRecoveryError = nil
        metadataNeedsRecovery = false
        observerCatalog = nil
        observerNeedsRecovery = false
        observerRecoveryError = nil
        beaconObservers = [:]
        beaconObserverSequence &+= 1
        nodeOrder = []
        observerOrder = []
        beaconNodeSequence &+= 1
    }

    func beaconNodeStates(sourceSessionID: UUID) -> [String: BeaconLiveProtocol.NodeState]? {
        guard usesBrowseSnapshots, connectedSession == sourceSessionID,
              settings.sourceSession.id == sourceSessionID, settings.sourceSession.isCurrent else { return nil }
        return beaconNodes
    }

    func retryMetadataRecovery() {
        guard usesBrowseSnapshots, settings.sourceSession.isCurrent,
              connectedSession == settings.sourceSession.id else { return }
        applyDemands()
    }

    func beaconObserverStatuses(sourceSessionID: UUID) -> [String: BeaconLiveProtocol.ObserverStatus] {
        guard usesBrowseSnapshots, connectedSession == sourceSessionID,
              settings.sourceSession.id == sourceSessionID, settings.sourceSession.isCurrent else { return [:] }
        return beaconObservers
    }

    func recoveredObservers(sourceSessionID: UUID) -> [MeshObserver]? {
        guard usesBrowseSnapshots, connectedSession == sourceSessionID,
              settings.sourceSession.id == sourceSessionID, settings.sourceSession.isCurrent else { return nil }
        return observerCatalog?.map { record in
            var observer = record.meshObserver
            if !observerNeedsRecovery {
                switch record.status {
                case "online": observer.online = true
                case "offline": observer.online = false
                default: break
                }
            }
            return observer
        }
    }

    func refreshObservers() {
        guard usesBrowseSnapshots, connectedSession == settings.sourceSession.id,
              settings.sourceSession.isCurrent else { return }
        feed?.refreshObservers()
    }

    func packetDetail(hash: String, sourceSessionID: UUID) async throws -> PacketDetailResponse {
        let session = settings.sourceSession
        try session.check()
        guard session.id == sourceSessionID, let provider = feed as? any LivePacketDetailProvider else {
            throw CancellationError()
        }
        let generation = generation
        let response = try await provider.packetDetail(hash: hash)
        try session.check()
        guard self.generation == generation else { throw CancellationError() }
        return response
    }

    func observerDetail(id: String, sourceSessionID: UUID) async throws -> MeshObserver {
        let session = settings.sourceSession
        try session.check()
        guard session.id == sourceSessionID, let provider = feed as? any LiveObserverDetailProvider else {
            throw CancellationError()
        }
        let generation = generation
        let response = try await provider.observerDetail(id: id)
        try session.check()
        guard self.generation == generation else { throw CancellationError() }
        return response
    }
}
