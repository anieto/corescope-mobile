import Foundation

enum BackendCapability: String, CaseIterable, Sendable {
    case nodeHealth
    case nodeReach
    case nodePaths
    case nodeAnalytics
    case observerAnalytics
    case channelDecryptOnPhone
    case serverChannels
    case mapDefaults
}

protocol AnalyzerBackend: Sendable {
    var cacheIdentifier: String { get }
    var capabilities: Set<BackendCapability> { get }

    func nodes(region: String?) async throws -> NodesResponse
    func nodeDetail(publicKey: String) async throws -> NodeDetailResponse
    func nodeHealth(publicKey: String) async throws -> NodeHealthResponse
    func nodePaths(publicKey: String) async throws -> NodePathsResponse
    func nodeReach(publicKey: String) async throws -> NodeReachResponse
    func nodeAnalytics(publicKey: String, days: Int) async throws -> NodeAnalyticsResponse
    func observers() async throws -> ObserversResponse
    func observerAnalytics(id: String) async throws -> ObserverAnalyticsResponse
    func packets(region: String?, limit: Int, payloadType: Int?) async throws -> PacketsResponse
    func packetDetail(hash: String) async throws -> PacketDetailResponse
    func channels(region: String?) async throws -> ChannelsResponse
    func channelMessages(id: String, offset: Int) async throws -> ChannelMessagesResponse
    func regions() async throws -> RegionsMap
    func iataCoordinates() async throws -> IataCoordsResponse
    func mapDefaults() async throws -> MapDefaults
}

enum AnalyzerBackendFactory {
    @MainActor
    static func makeLiveFeed(settings: AnalyzerSettings) -> any AnalyzerLiveFeed {
        CoreScopeLiveFeed(url: settings.webSocketURL)
    }

    static func make(settings: AnalyzerSettings) -> any AnalyzerBackend {
        CoreScopeBackend(client: APIClient(settings: settings))
    }
}
