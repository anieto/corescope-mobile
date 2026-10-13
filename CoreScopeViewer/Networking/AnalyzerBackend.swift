import Foundation

enum BackendCapability: String, CaseIterable, Sendable {
    case neighbors
    case nodeHealth
    case nodeReach
    case nodePaths
    case nodeAnalytics
    case observerAnalytics
    case channelDecryptOnPhone
    case serverChannels
    case mapDefaults

    var beaconExplanation: String {
        switch self {
        case .neighbors:
            String(localized: "Neighbor information isn’t available from this source.")
        case .nodeAnalytics:
            String(localized: "Node analytics for Beacon servers aren’t supported in this version of NodeScope. Support is planned for a future update.")
        case .observerAnalytics:
            String(localized: "Observer analytics for Beacon servers aren’t supported in this version of NodeScope. Activity and telemetry support is planned for a future update.")
        case .serverChannels:
            String(localized: "Channel messages from Beacon servers aren’t supported in this version of NodeScope. Support for server-decrypted channels is planned for a future update.")
        case .channelDecryptOnPhone:
            String(localized: "On-device channel decryption isn’t currently supported for Beacon sources. Future support depends on changes to Beacon’s API.")
        case .nodeHealth, .nodeReach, .nodePaths:
            String(localized: "These detailed statistics are currently available only with CoreScope servers. Node analytics and a neighbors view for Beacon servers are planned for a future update.")
        case .mapDefaults:
            String(localized: "Beacon servers don’t provide map defaults. NodeScope uses region coordinates to frame the map instead.")
        }
    }
}

protocol AnalyzerBackend: Sendable {
    var sourceSession: AnalyzerSourceSession { get }
    var cacheIdentifier: String { get }
    var capabilities: Set<BackendCapability> { get }

    func nodes(region: String?) async throws -> NodesResponse
    func nodeDetail(publicKey: String) async throws -> NodeDetailResponse
    func nodeHealth(publicKey: String) async throws -> NodeHealthResponse
    func nodePaths(publicKey: String) async throws -> NodePathsResponse
    func nodeReach(publicKey: String) async throws -> NodeReachResponse
    func neighbors(publicKey: String) async throws -> NeighborCollection
    func nodeAnalytics(publicKey: String, days: Int) async throws -> NodeAnalyticsResponse
    func observers() async throws -> ObserversResponse
    func observerAnalytics(id: String) async throws -> ObserverAnalyticsResponse
    func packetPage(region: String?, pageSize: Int, payloadType: Int?, cursor: AnalyzerCursor?) async throws -> AnalyzerPage<Packet>
    func packetDetail(hash: String) async throws -> PacketDetailResponse
    func channels(region: String?) async throws -> ChannelsResponse
    func channelMessages(id: String, offset: Int) async throws -> ChannelMessagesResponse
    func regions() async throws -> RegionsMap
    func iataCoordinates() async throws -> IataCoordsResponse
    func mapDefaults() async throws -> MapDefaults
}

extension AnalyzerBackend {
    func packets(region: String?, limit: Int, payloadType: Int?) async throws -> [Packet] {
        try sourceSession.check()
        let packets = try await AnalyzerPaging.collect(limit: limit, identity: { (packet: Packet) in
            packet.id
        }) { cursor, count in
            try sourceSession.check()
            let page = try await packetPage(region: region, pageSize: count, payloadType: payloadType, cursor: cursor)
            try sourceSession.check()
            return page
        }
        try sourceSession.check()
        return packets
    }
}

enum AnalyzerBackendFactory {
    @MainActor
    static func detectAndSelectBeacon(
        settings: AnalyzerSettings, catalog: BeaconServerCatalog,
        session: URLSession = .shared, refreshKnown: Bool = false
    ) async throws {
        let sourceSession = settings.sourceSession
        let client = try BeaconAPIClient(baseURL: settings.baseURL, sourceSession: sourceSession, session: session)
        let info = try await catalog.resolve(using: client, refreshKnown: refreshKnown)
        try sourceSession.check()
        try settings.selectBeacon(verifiedServerInfo: info, expectedSessionID: sourceSession.id, session: session)
    }

    @MainActor
    static func prepareBeacon(
        settings: AnalyzerSettings, catalog: BeaconServerCatalog,
        session: URLSession = .shared, refreshKnown: Bool = false
    ) async throws -> (backend: BeaconBackend, feed: BeaconLiveFeed) {
        let sourceSession = settings.sourceSession
        let client = try BeaconAPIClient(baseURL: settings.baseURL, sourceSession: sourceSession, session: session)
        let info = try await catalog.resolve(using: client, refreshKnown: refreshKnown)
        try sourceSession.check()
        guard settings.sourceSession.id == sourceSession.id else { throw CancellationError() }
        return try makeBeacon(client: client, verifiedServerInfo: info)
    }

    @MainActor
    static func makeBeacon(
        client: BeaconAPIClient, verifiedServerInfo: BeaconServerInfo,
        transport: BeaconLiveTransport? = nil
    ) throws -> (backend: BeaconBackend, feed: BeaconLiveFeed) {
        let backend = try BeaconBackend(client: client, verifiedServerInfo: verifiedServerInfo)
        let feed = try BeaconLiveFeed(client: client, verifiedServerInfo: verifiedServerInfo,
                                      transport: transport, observerReader: backend.observerReader)
        return (backend, feed)
    }

    @MainActor
    static func makeLiveFeed(settings: AnalyzerSettings) -> any AnalyzerLiveFeed {
        if let backend = settings.beaconBackend {
            do {
                try backend.sourceSession.check()
                return try BeaconLiveFeed(client: backend.client, verifiedServerInfo: backend.serverInfo,
                                          observerReader: backend.observerReader)
            } catch { return UnavailableBeaconLiveFeed(message: error.localizedDescription) }
        }
        return CoreScopeLiveFeed(url: settings.webSocketURL)
    }

    static func make(settings: AnalyzerSettings) -> any AnalyzerBackend {
        if let backend = settings.beaconBackend { return backend }
        return CoreScopeBackend(client: APIClient(settings: settings), sourceSession: settings.sourceSession)
    }
}

enum BeaconBackendError: Error, LocalizedError {
    case unsupported(String)
    case nodeUnavailable

    var errorDescription: String? {
        switch self {
        case .unsupported(let feature):
            switch feature {
            case "Node analytics": BackendCapability.nodeAnalytics.beaconExplanation
            case "Observer analytics": BackendCapability.observerAnalytics.beaconExplanation
            case "Channels", "Channel messages": BackendCapability.serverChannels.beaconExplanation
            case "Node health", "Node paths", "Node reach": BackendCapability.nodeHealth.beaconExplanation
            case "Map defaults": BackendCapability.mapDefaults.beaconExplanation
            default: String(localized: "\(feature) isn’t supported for Beacon servers in this version of NodeScope.")
            }
        case .nodeUnavailable: "This node is not available on this server."
        }
    }
}

struct BeaconBackend: AnalyzerBackend {
    let client: BeaconAPIClient
    let serverInfo: BeaconServerInfo
    let observerReader: BeaconObserverReader
    private let nodeReader: BeaconNodeReader
    private let packetReader: BeaconPacketReader
    private let regionReader: BeaconRegionReader

    var sourceSession: AnalyzerSourceSession { client.sourceSession }
    var cacheIdentifier: String { client.cacheIdentifier }
    let capabilities: Set<BackendCapability> = [.neighbors]

    init(client: BeaconAPIClient, verifiedServerInfo: BeaconServerInfo) throws {
        try verifiedServerInfo.requireSupported()
        self.client = client
        serverInfo = verifiedServerInfo
        nodeReader = try BeaconNodeReader(client: client, verifiedServerInfo: verifiedServerInfo)
        observerReader = try BeaconObserverReader(client: client, verifiedServerInfo: verifiedServerInfo)
        packetReader = try BeaconPacketReader(client: client, verifiedServerInfo: verifiedServerInfo)
        regionReader = try BeaconRegionReader(client: client, verifiedServerInfo: verifiedServerInfo)
    }

    func nodes(region: String?) async throws -> NodesResponse {
        let nodes = try await nodeReader.nodes(region: region).map(\.meshNode)
        try sourceSession.check()
        return NodesResponse(nodes: nodes, total: nodes.count, counts: RoleCounts(
            repeaters: nodes.count { $0.role == "repeater" }, rooms: nodes.count { $0.role == "room" },
            companions: nodes.count { $0.role == "companion" }, sensors: nodes.count { $0.role == "sensor" }
        ))
    }

    func nodeDetail(publicKey: String) async throws -> NodeDetailResponse {
        guard let node = try await nodeReader.nodeDetail(publicKey: publicKey) else { throw BeaconBackendError.nodeUnavailable }
        try sourceSession.check()
        return NodeDetailResponse(node: node.meshNode, recentAdverts: nil)
    }

    func neighbors(publicKey: String) async throws -> NeighborCollection {
        guard let neighbors = try await nodeReader.neighbors(publicKey: publicKey) else {
            throw BeaconBackendError.nodeUnavailable
        }
        return neighbors
    }

    func observers() async throws -> ObserversResponse {
        let observers = try await observerReader.observers(region: nil).map(\.meshObserver)
        try sourceSession.check()
        return ObserversResponse(observers: observers, serverTime: nil)
    }

    func packetPage(region: String?, pageSize: Int, payloadType: Int?, cursor: AnalyzerCursor?) async throws -> AnalyzerPage<Packet> {
        try await packetReader.packetPage(region: region, pageSize: pageSize, payloadType: payloadType, cursor: cursor)
    }

    func packetDetail(hash: String) async throws -> PacketDetailResponse {
        try await packetReader.packetDetail(hash: hash)
    }

    func regions() async throws -> RegionsMap { try await regionReader.catalog().names }
    func iataCoordinates() async throws -> IataCoordsResponse { try await regionReader.catalog().coordinates }

    private func unavailable(_ feature: String) throws -> Never {
        try sourceSession.check()
        throw BeaconBackendError.unsupported(feature)
    }

    func nodeHealth(publicKey: String) async throws -> NodeHealthResponse { try unavailable("Node health") }
    func nodePaths(publicKey: String) async throws -> NodePathsResponse { try unavailable("Node paths") }
    func nodeReach(publicKey: String) async throws -> NodeReachResponse { try unavailable("Node reach") }
    func nodeAnalytics(publicKey: String, days: Int) async throws -> NodeAnalyticsResponse { try unavailable("Node analytics") }
    func observerAnalytics(id: String) async throws -> ObserverAnalyticsResponse { try unavailable("Observer analytics") }
    func channels(region: String?) async throws -> ChannelsResponse { try unavailable("Channels") }
    func channelMessages(id: String, offset: Int) async throws -> ChannelMessagesResponse { try unavailable("Channel messages") }
    func mapDefaults() async throws -> MapDefaults { try unavailable("Map defaults") }
}
