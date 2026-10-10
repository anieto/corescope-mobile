import Foundation

struct CoreScopeBackend: AnalyzerBackend {
    private let client: APIClient

    init(client: APIClient) {
        self.client = client
    }

    var cacheIdentifier: String { client.cacheIdentifier }

    let capabilities: Set<BackendCapability> = [
        .nodeHealth, .nodeReach, .nodePaths, .nodeAnalytics,
        .observerAnalytics, .channelDecryptOnPhone, .serverChannels, .mapDefaults
    ]

    func nodes(region: String?) async throws -> NodesResponse {
        try await client.get("/api/nodes", query: Self.listQuery(limit: 5000, region: region))
    }

    func nodeDetail(publicKey: String) async throws -> NodeDetailResponse {
        try await client.get("/api/nodes/\(publicKey.urlPathComponentEncoded)")
    }

    func nodeHealth(publicKey: String) async throws -> NodeHealthResponse {
        try await client.get("/api/nodes/\(publicKey.urlPathComponentEncoded)/health")
    }

    func nodePaths(publicKey: String) async throws -> NodePathsResponse {
        try await client.get("/api/nodes/\(publicKey.urlPathComponentEncoded)/paths")
    }

    func nodeReach(publicKey: String) async throws -> NodeReachResponse {
        try await client.get("/api/nodes/\(publicKey.urlPathComponentEncoded)/reach")
    }

    func nodeAnalytics(publicKey: String, days: Int) async throws -> NodeAnalyticsResponse {
        try await client.get(
            "/api/nodes/\(publicKey.urlPathComponentEncoded)/analytics",
            query: [URLQueryItem(name: "days", value: String(days))]
        )
    }

    func observers() async throws -> ObserversResponse {
        try await client.get("/api/observers")
    }

    func observerAnalytics(id: String) async throws -> ObserverAnalyticsResponse {
        try await client.get("/api/observers/\(id.urlPathComponentEncoded)/analytics")
    }

    func packets(region: String?, limit: Int, payloadType: Int?) async throws -> PacketsResponse {
        try await client.get(
            "/api/packets",
            query: Self.packetQuery(region: region, limit: limit, payloadType: payloadType)
        )
    }

    func packetDetail(hash: String) async throws -> PacketDetailResponse {
        try await client.get("/api/packets/\(hash.urlPathComponentEncoded)")
    }

    func channels(region: String?) async throws -> ChannelsResponse {
        let query = region.map { [URLQueryItem(name: "region", value: $0)] } ?? []
        return try await client.get("/api/channels", query: query)
    }

    func channelMessages(id: String, offset: Int) async throws -> ChannelMessagesResponse {
        try await client.get(
            "/api/channels/\(id.urlPathComponentEncoded)/messages",
            query: [
                URLQueryItem(name: "limit", value: "500"),
                URLQueryItem(name: "offset", value: String(offset))
            ]
        )
    }

    func regions() async throws -> RegionsMap {
        try await client.get("/api/config/regions")
    }

    func iataCoordinates() async throws -> IataCoordsResponse {
        try await client.get("/api/iata-coords")
    }

    func mapDefaults() async throws -> MapDefaults {
        try await client.get("/api/config/map")
    }

    static func packetQuery(region: String?, limit: Int, payloadType: Int?) -> [URLQueryItem] {
        var query = [URLQueryItem(name: "limit", value: String(limit))]
        if let payloadType {
            query.append(URLQueryItem(name: "type", value: String(payloadType)))
        }
        if let region {
            query.append(URLQueryItem(name: "region", value: region))
        }
        return query
    }

    private static func listQuery(limit: Int, region: String?) -> [URLQueryItem] {
        var query = [URLQueryItem(name: "limit", value: String(limit))]
        if let region {
            query.append(URLQueryItem(name: "region", value: region))
        }
        return query
    }
}
