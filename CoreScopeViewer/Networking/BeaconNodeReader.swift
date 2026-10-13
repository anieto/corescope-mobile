import Foundation

struct BeaconNodeReader: Sendable {
    let client: BeaconAPIClient

    init(client: BeaconAPIClient, verifiedServerInfo: BeaconServerInfo) throws {
        try verifiedServerInfo.requireSupported()
        self.client = client
    }

    func nodePage(
        region: String?, pageSize: Int = 200, cursor: AnalyzerCursor? = nil
    ) async throws -> AnalyzerPage<Node> {
        try client.sourceSession.check()
        guard pageSize > 0 else { throw AnalyzerPagingError.invalidPageSize }
        var query = [URLQueryItem(name: "limit", value: String(min(pageSize, 200)))]
        if let region { query.append(URLQueryItem(name: "iatas", value: region)) }
        if let cursor {
            guard let data = Data(base64Encoded: cursor.value),
                  let saved = try? JSONDecoder().decode(Cursor.self, from: data),
                  saved.endpoint == "nodes", saved.source == client.cacheIdentifier,
                  saved.sessionID == client.sourceSession.id, saved.region == region else {
                throw AnalyzerPagingError.invalidCursor
            }
            query.append(URLQueryItem(name: "cursor", value: saved.value.queryValue))
        }
        let response: Page = try await client.get(["nodes"], query: query)
        try response.items.forEach { try $0.validate() }
        let next: AnalyzerCursor?
        if response.hasMore {
            guard !response.items.isEmpty, let value = response.nextCursor else {
                throw BeaconAPIError.malformedPage
            }
            let saved = Cursor(endpoint: "nodes", source: client.cacheIdentifier,
                               sessionID: client.sourceSession.id, region: region, value: value)
            let encoder = JSONEncoder()
            encoder.outputFormatting = [.sortedKeys]
            next = AnalyzerCursor(value: try encoder.encode(saved).base64EncodedString())
        } else {
            next = nil
        }
        try client.sourceSession.check()
        return AnalyzerPage(items: response.items, nextCursor: next, total: nil)
    }

    func nodes(region: String?, maximumPages: Int = 20) async throws -> [Node] {
        // No item limit: reaching the request budget must fail, not imply a complete map.
        try await AnalyzerPaging.collect(
            limit: Int.max, maximumPages: min(maximumPages, 20), identity: { $0.publicKey.lowercased() }
        ) { cursor, _ in
            try await nodePage(region: region, cursor: cursor)
        }
    }

    func nodeDetail(publicKey: String) async throws -> Node? {
        try client.sourceSession.check()
        guard Self.isFullKey(publicKey) else { throw APIError.invalidResponse }
        let key = publicKey.lowercased()
        let lookup: Page = try await client.get(["nodes"], query: [
            URLQueryItem(name: "pubkey", value: key)
        ])
        guard !lookup.hasMore, lookup.items.count <= 1 else { throw BeaconAPIError.malformedPage }
        guard let summary = lookup.items.first else { return nil }
        try summary.validate()
        guard summary.publicKey.lowercased() == key else { throw APIError.invalidResponse }
        let detail: Node = try await client.get(["nodes", summary.id])
        try detail.validate()
        guard detail.id == summary.id, detail.publicKey.lowercased() == key else {
            throw APIError.invalidResponse
        }
        try client.sourceSession.check()
        return detail
    }

    private static func isFullKey(_ value: String) -> Bool {
        value.utf8.count == 64 && value.utf8.allSatisfy {
            (48...57).contains($0) || (65...70).contains($0) || (97...102).contains($0)
        }
    }

    struct Node: Codable, Sendable {
        struct Region: Codable, Sendable {
            let iata: String
            let lastHeard: Date?
        }

        let id: String
        let publicKey: String
        let nodeType: Int
        let name: String?
        let lat: Double?
        let lng: Double?
        let iatas: [Region]?
        let firstSeen: Date?
        let lastSeen: Date?
        let supportsMultibytePaths: Bool?
        let supportsMultibyteTraces: Bool?

        var lastHeard: Date? { iatas?.compactMap(\.lastHeard).max() }

        var meshNode: MeshNode {
            let validPosition = lat.map { $0.isFinite && (-90...90).contains($0) } == true
                && lng.map { $0.isFinite && (-180...180).contains($0) } == true
                && (lat != 0 || lng != 0)
            return MeshNode(
                publicKey: publicKey.lowercased(), name: name, role: role,
                lat: validPosition ? lat : nil, lon: validPosition ? lng : nil,
                lastSeen: lastSeen, firstSeen: firstSeen, advertCount: nil,
                hashSize: nil, hashSizeInconsistent: nil, lastHeard: lastHeard, defaultScope: nil
            )
        }

        var role: String {
            switch nodeType {
            case 1: "companion"
            case 2: "repeater"
            case 3: "room"
            case 4: "sensor"
            default: "unknown"
            }
        }

        func validate() throws {
            guard UUID(uuidString: id) != nil, BeaconNodeReader.isFullKey(publicKey) else {
                throw APIError.invalidResponse
            }
        }
    }

    private struct Page: Decodable, Sendable {
        let items: [Node]
        let hasMore: Bool
        let nextCursor: CursorValue?
    }

    private struct Cursor: Codable {
        let endpoint: String
        let source: String
        let sessionID: UUID
        let region: String?
        let value: CursorValue
    }

    private enum CursorValue: Codable, Sendable {
        case integer(Int64)
        case text(String)

        init(from decoder: Decoder) throws {
            let container = try decoder.singleValueContainer()
            if let value = try? container.decode(Int64.self) {
                self = .integer(value)
            } else {
                let value = try container.decode(String.self)
                guard !value.isEmpty else { throw BeaconAPIError.malformedPage }
                self = .text(value)
            }
        }

        func encode(to encoder: Encoder) throws {
            var container = encoder.singleValueContainer()
            switch self {
            case .integer(let value): try container.encode(value)
            case .text(let value): try container.encode(value)
            }
        }

        var queryValue: String {
            switch self {
            case .integer(let value): String(value)
            case .text(let value): value
            }
        }
    }
}
