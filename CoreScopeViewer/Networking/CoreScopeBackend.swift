import Foundation

struct CoreScopeBackend: AnalyzerBackend {
    private let client: APIClient
    let sourceSession: AnalyzerSourceSession

    init(client: APIClient, sourceSession: AnalyzerSourceSession = AnalyzerSourceSession()) {
        self.client = client
        self.sourceSession = sourceSession
    }

    var cacheIdentifier: String { "corescope|\(client.cacheIdentifier)" }

    let capabilities: Set<BackendCapability> = [
        .neighbors, .nodeHealth, .nodeReach, .nodePaths, .nodeAnalytics,
        .observerAnalytics, .channelDecryptOnPhone, .serverChannels, .mapDefaults
    ]

    func nodes(region: String?) async throws -> NodesResponse {
        try await get("/api/nodes", query: Self.listQuery(limit: 5000, region: region))
    }

    func nodeDetail(publicKey: String) async throws -> NodeDetailResponse {
        try await get("/api/nodes/\(publicKey.urlPathComponentEncoded)")
    }

    func nodeHealth(publicKey: String) async throws -> NodeHealthResponse {
        try await get("/api/nodes/\(publicKey.urlPathComponentEncoded)/health")
    }

    func nodePaths(publicKey: String) async throws -> NodePathsResponse {
        try await get("/api/nodes/\(publicKey.urlPathComponentEncoded)/paths")
    }

    func nodeReach(publicKey: String) async throws -> NodeReachResponse {
        try await get("/api/nodes/\(publicKey.urlPathComponentEncoded)/reach")
    }

    func neighbors(publicKey: String) async throws -> NeighborCollection {
        let reach = try await nodeReach(publicKey: publicKey)
        return NeighborCollection(
            neighbors: reach.links.map { link in
                NeighborInsight(
                    identity: NeighborIdentity(
                        publicKey: link.pubkey,
                        displayPrefix: String(link.pubkey.prefix(8)).lowercased(),
                        sourceID: nil
                    ),
                    name: link.name,
                    role: link.role,
                    lastHeard: nil,
                    observationCount: nil,
                    signal: NeighborSignal(snr: nil, rssi: nil),
                    direction: link.bidir ? .bidirectional : .unknown,
                    latitude: link.lat,
                    longitude: link.lon
                )
            },
            completeness: .complete,
            explanation: nil
        )
    }

    func nodeAnalytics(publicKey: String, days: Int) async throws -> NodeAnalyticsResponse {
        try await get(
            "/api/nodes/\(publicKey.urlPathComponentEncoded)/analytics",
            query: [URLQueryItem(name: "days", value: String(days))]
        )
    }

    func observers() async throws -> ObserversResponse {
        try await get("/api/observers")
    }

    func observerAnalytics(id: String) async throws -> ObserverAnalyticsResponse {
        try await get("/api/observers/\(id.urlPathComponentEncoded)/analytics")
    }

    func packetPage(region: String?, pageSize: Int, payloadType: Int?, cursor: AnalyzerCursor?) async throws -> AnalyzerPage<Packet> {
        try sourceSession.check()
        guard pageSize > 0 else { throw AnalyzerPagingError.invalidPageSize }
        let scope = "\(cacheIdentifier)|\(sourceSession.id)|packets|\(region ?? "")|\(payloadType.map(String.init) ?? "")"
        let offset: Int
        if let cursor {
            guard let data = Data(base64Encoded: cursor.value),
                  let saved = try? JSONDecoder().decode(PacketCursor.self, from: data),
                  saved.scope == scope, saved.offset >= 0 else { throw AnalyzerPagingError.invalidCursor }
            offset = saved.offset
        } else {
            offset = 0
        }
        var query = Self.packetQuery(region: region, limit: pageSize, payloadType: payloadType)
        if offset > 0 { query.append(URLQueryItem(name: "offset", value: String(offset))) }
        let response: PacketsResponse = try await get("/api/packets", query: query)
        try sourceSession.check()
        let nextOffset = offset + response.packets.count
        let next: AnalyzerCursor?
        if !response.packets.isEmpty && nextOffset < response.total {
            let data = try JSONEncoder().encode(PacketCursor(scope: scope, offset: nextOffset))
            next = AnalyzerCursor(value: data.base64EncodedString())
        } else {
            next = nil
        }
        return AnalyzerPage(items: response.packets, nextCursor: next, total: response.total)
    }

    private struct PacketCursor: Codable {
        let scope: String
        let offset: Int
    }

    func packetDetail(hash: String) async throws -> PacketDetailResponse {
        try await get("/api/packets/\(hash.urlPathComponentEncoded)")
    }

    func channels(region: String?) async throws -> ChannelsResponse {
        let query = region.map { [URLQueryItem(name: "region", value: $0)] } ?? []
        return try await get("/api/channels", query: query)
    }

    func channelMessages(id: String, offset: Int) async throws -> ChannelMessagesResponse {
        try await get(
            "/api/channels/\(id.urlPathComponentEncoded)/messages",
            query: [
                URLQueryItem(name: "limit", value: "500"),
                URLQueryItem(name: "offset", value: String(offset))
            ]
        )
    }

    func regions() async throws -> RegionsMap {
        try await get("/api/config/regions")
    }

    func regionQuickPicks() async throws -> [RegionQuickPick] {
        let response: RegionQuickPicksResponse = try await get("/api/config/region-quick-picks")
        return response.quickPicks
    }

    func iataCoordinates() async throws -> IataCoordsResponse {
        try await get("/api/iata-coords")
    }

    func mapDefaults() async throws -> MapDefaults {
        try await get("/api/config/map")
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

    private func get<Value: Decodable & Sendable>(_ path: String, query: [URLQueryItem] = []) async throws -> Value {
        try sourceSession.check()
        let request = Task<Value, Error> { try await client.get(path, query: query) }
        let cancellationID = sourceSession.registerCancellation { request.cancel() }
        defer { sourceSession.removeCancellation(cancellationID) }
        do {
            let value = try await withTaskCancellationHandler {
                try await request.value
            } onCancel: {
                request.cancel()
            }
            try sourceSession.check()
            return value
        } catch {
            try sourceSession.check()
            throw error
        }
    }
}
