import Foundation

@MainActor
final class BeaconLiveFeed: AnalyzerLiveFeed, LivePacketDetailProvider, LiveObserverDetailProvider {
    let usesBrowseSnapshots = true
    let events: AsyncStream<AnalyzerLiveEvent>
    let observers: BeaconObserverReader
    private let continuation: AsyncStream<AnalyzerLiveEvent>.Continuation
    private let client: BeaconAPIClient
    private let reader: BeaconPacketReader
    private let transport: BeaconLiveTransport
    private var subscriptions: Set<LiveFeedSubscription> = []
    private var configuration = LiveFeedConfiguration()
    private var projections: [LiveBrowseFilter: BeaconLiveRecovery] = [:]
    private var recoveryTasks: [LiveBrowseFilter: Task<Void, Never>] = [:]
    private var eventTask: Task<Void, Never>?
    private var connected = false
    private var ended = false
    private let fetchNodes: @Sendable () async throws -> [BeaconNodeReader.Node]
    private var nodeRecoveryTask: Task<Void, Never>?
    private var metadataRevision = 0
    private var needsNodeRecovery = true
    private let fetchObservers: @Sendable () async throws -> [BeaconObserverReader.Observer]
    private var observerRecoveryTask: Task<Void, Never>?
    private var needsObserverRecovery = true
    private var observerRevision = 0

    init(
        client: BeaconAPIClient, verifiedServerInfo: BeaconServerInfo,
        transport: BeaconLiveTransport? = nil,
        fetchNodes: (@Sendable () async throws -> [BeaconNodeReader.Node])? = nil,
        fetchObservers: (@Sendable () async throws -> [BeaconObserverReader.Observer])? = nil,
        observerReader: BeaconObserverReader? = nil
    ) throws {
        self.client = client
        reader = try BeaconPacketReader(client: client, verifiedServerInfo: verifiedServerInfo)
        if let observerReader {
            guard observerReader.client.sourceSession.id == client.sourceSession.id,
                  observerReader.client.cacheIdentifier == client.cacheIdentifier else { throw APIError.invalidResponse }
            observers = observerReader
        } else {
            observers = try BeaconObserverReader(client: client, verifiedServerInfo: verifiedServerInfo)
        }
        let observerReader = observers
        self.fetchObservers = fetchObservers ?? { try await observerReader.observers(region: nil) }
        let nodes = try BeaconNodeReader(client: client, verifiedServerInfo: verifiedServerInfo)
        self.fetchNodes = fetchNodes ?? { try await nodes.nodes(region: nil) }
        self.transport = try transport ?? BeaconLiveTransport(client: client, verifiedServerInfo: verifiedServerInfo)
        let stream = AsyncStream<AnalyzerLiveEvent>.makeStream()
        events = stream.stream
        continuation = stream.continuation
    }

    func subscribe(_ subscriptions: Set<LiveFeedSubscription>) {
        guard !ended, client.sourceSession.isCurrent, self.subscriptions != subscriptions else { return }
        var filters = Set<LiveBrowseFilter>()
        for subscription in subscriptions {
            let codes = subscription.regions.map { $0.uppercased() }.sorted()
            let region = codes.isEmpty ? nil : codes.joined(separator: ",")
            let types: [Int?] = subscription.payloadTypes.isEmpty ? [nil] : subscription.payloadTypes.sorted().map { $0 }
            for type in types { filters.insert(LiveBrowseFilter(region: region, payloadType: type)) }
        }
        guard filters.count <= 20 else {
            continuation.yield(.decodingFailed("Too many live filters; reduce the number of active views."))
            return
        }
        self.subscriptions = subscriptions
        for filter in Array(projections.keys) where !filters.contains(filter) {
            recoveryTasks.removeValue(forKey: filter)?.cancel()
            projections[filter] = nil
            continuation.yield(.browseRemoved(filter))
        }
        for filter in filters where projections[filter] == nil {
            projections[filter] = BeaconLiveRecovery(reader: reader, region: filter.region, payloadType: filter.payloadType)
            if connected { recover(filter) }
        }
        transport.update(subscriptions: subscriptions, configuration: configuration)
    }

    func configure(_ configuration: LiveFeedConfiguration) {
        guard !ended, client.sourceSession.isCurrent else { return }
        self.configuration = configuration
        transport.update(subscriptions: subscriptions, configuration: configuration)
        recoverNodesIfNeeded()
        recoverObserversIfNeeded()
    }

    func packetDetail(hash: String) async throws -> PacketDetailResponse {
        try client.sourceSession.check()
        guard !ended else { throw CancellationError() }
        let detail = try await reader.packetDetail(hash: hash)
        try client.sourceSession.check()
        guard !ended else { throw CancellationError() }
        return detail
    }

    func refreshObservers() {
        guard !ended, client.sourceSession.isCurrent else { return }
        observerRevision &+= 1
        needsObserverRecovery = true
        continuation.yield(.observerCatalogInvalidated)
        recoverObserversIfNeeded()
    }

    func observerDetail(id: String) async throws -> MeshObserver {
        try client.sourceSession.check()
        guard !ended else { throw CancellationError() }
        let detail = try await observers.observerDetail(id: id)
        try client.sourceSession.check()
        guard !ended else { throw CancellationError() }
        return detail.meshObserver
    }

    func connect() {
        guard eventTask == nil, !ended, client.sourceSession.isCurrent else { return }
        eventTask = Task { [weak self, transport] in
            for await event in transport.events {
                guard let self, !Task.isCancelled, !self.ended, self.client.sourceSession.isCurrent else { return }
                switch event {
                case .disconnected(let message):
                    self.connected = false
                    self.invalidateMetadata()
                    for (filter, projection) in self.projections {
                        projection.markGap()
                        self.publish(filter, projection)
                    }
                    self.continuation.yield(.disconnected(message))
                case .frame(let frame):
                    await self.handle(frame)
                }
            }
        }
        transport.connect()
    }

    func disconnect() {
        guard !ended else { return }
        ended = true
        connected = false
        eventTask?.cancel()
        eventTask = nil
        nodeRecoveryTask?.cancel()
        nodeRecoveryTask = nil
        observerRecoveryTask?.cancel()
        observerRecoveryTask = nil
        recoveryTasks.values.forEach { $0.cancel() }
        recoveryTasks = [:]
        projections = [:]
        transport.disconnect()
        continuation.finish()
    }

    private func handle(_ frame: BeaconLiveProtocol.Incoming) async {
        switch frame {
        case .hello:
            connected = true
            continuation.yield(.connected)
        case .recoveryRequired:
            invalidateMetadata()
            recoverNodesIfNeeded()
            recoverObserversIfNeeded()
            for filter in projections.keys { recover(filter) }
        case .packet(let observation):
            for (filter, projection) in projections {
                do {
                    try projection.receive(observation)
                    publish(filter, projection)
                } catch { return }
            }
            continuation.yield(.packet(observation.liveEnvelope))
            if let key = observation.observation.observerPublicKey {
                do {
                    try await observers.learn(
                        observerID: observation.observation.observerId, publicKey: key,
                        sessionID: client.sourceSession.id
                    )
                } catch {
                    guard !ended, client.sourceSession.isCurrent else { return }
                    continuation.yield(.decodingFailed("Invalid observer identity in live event."))
                }
            }
        case .node(let update):
            continuation.yield(.nodeUpdated(update))
        case .observer(let status):
            continuation.yield(.observerStatus(status))
        case .subscribed, .acknowledged, .ignored:
            break
        }
    }

    private func invalidateMetadata() {
        metadataRevision &+= 1
        needsNodeRecovery = true
        needsObserverRecovery = true
        observerRevision &+= 1
        continuation.yield(.metadataInvalidated)
    }

    private func recoverNodesIfNeeded() {
        guard connected, configuration.resolvePath, needsNodeRecovery, nodeRecoveryTask == nil,
              !ended, client.sourceSession.isCurrent else { return }
        nodeRecoveryTask = Task { [weak self] in
            guard let self else { return }
            defer { self.nodeRecoveryTask = nil }
            while self.connected, self.configuration.resolvePath, self.needsNodeRecovery {
                let revision = self.metadataRevision
                do {
                    let nodes = try await self.fetchNodes()
                    try self.client.sourceSession.check()
                    guard !Task.isCancelled, !self.ended else { return }
                    guard revision == self.metadataRevision else { continue }
                    self.needsNodeRecovery = false
                    self.continuation.yield(.nodeCatalog(nodes))
                } catch {
                    guard !Task.isCancelled, !self.ended, self.client.sourceSession.isCurrent else { return }
                    guard revision == self.metadataRevision else { continue }
                    self.continuation.yield(.metadataRecoveryFailed(error.localizedDescription))
                    return
                }
            }
        }
    }

    private func recoverObserversIfNeeded() {
        guard connected, configuration.recoverObservers, needsObserverRecovery, observerRecoveryTask == nil,
              !ended, client.sourceSession.isCurrent else { return }
        observerRecoveryTask = Task { [weak self] in
            guard let self else { return }
            defer { self.observerRecoveryTask = nil }
            while self.connected, self.configuration.recoverObservers, self.needsObserverRecovery {
                let revision = self.observerRevision
                do {
                    let observers = try await self.fetchObservers()
                    try self.client.sourceSession.check()
                    guard !Task.isCancelled, !self.ended else { return }
                    guard revision == self.observerRevision else { continue }
                    self.needsObserverRecovery = false
                    self.continuation.yield(.observerCatalog(observers))
                } catch {
                    guard !Task.isCancelled, !self.ended, self.client.sourceSession.isCurrent else { return }
                    guard revision == self.observerRevision else { continue }
                    self.continuation.yield(.observerRecoveryFailed(error.localizedDescription))
                    return
                }
            }
        }
    }

    private func recover(_ filter: LiveBrowseFilter) {
        guard let projection = projections[filter] else { return }
        projection.markGap()
        publish(filter, projection)
        guard recoveryTasks[filter] == nil else { return }
        recoveryTasks[filter] = Task { [weak self] in
            guard let self else { return }
            defer {
                if self.projections[filter] === projection { self.recoveryTasks[filter] = nil }
            }
            do {
                try await projection.recover()
                guard !Task.isCancelled, !self.ended, self.client.sourceSession.isCurrent,
                      self.projections[filter] === projection else { return }
                self.publish(filter, projection)
                self.continuation.yield(.recoveryCompleted(filter))
            } catch {
                guard !Task.isCancelled, !self.ended, self.client.sourceSession.isCurrent,
                      self.projections[filter] === projection else { return }
                self.continuation.yield(.recoveryFailed(filter, error.localizedDescription))
            }
        }
    }

    private func publish(_ filter: LiveBrowseFilter, _ projection: BeaconLiveRecovery) {
        continuation.yield(.browseSnapshot(LiveBrowseSnapshot(
            filter: filter, rows: projection.rows, possiblyIncomplete: projection.possiblyIncomplete
        )))
    }
}

private extension BeaconLiveProtocol.Observation {
    var liveEnvelope: LiveEnvelope {
        LiveEnvelope(
            type: "packet",
            data: LivePacketData(
                id: observationIdentity.hashValue,
                raw: nil,
                decoded: LiveDecoded(
                    header: LiveHeader(routeType: packet.routeType, payloadType: packet.payloadType),
                    path: nil,
                    payload: nil
                ),
                snr: observation.snr,
                rssi: observation.rssi,
                hash: packetHash,
                observerId: observation.observerId,
                observerName: observation.observerName,
                pathJson: nil,
                observationCount: packet.observationCount,
                resolvedPath: resolvedPath
            ),
            receivedAt: observation.heardAt
        )
    }
}
