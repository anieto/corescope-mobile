import Foundation

struct BeaconNodeReader: Sendable {
    let client: BeaconAPIClient

    init(client: BeaconAPIClient, verifiedServerInfo: BeaconServerInfo) throws {
        try verifiedServerInfo.requireSupported()
        self.client = client
    }

    private init(budgeting reader: BeaconNodeReader, budget: MyNodesRequestBudget, node: String) {
        client = reader.client.budgetingRequests(budget, node: node)
    }

    /// Dashboard history gets one exact-key lookup and at most three observation pages.
    /// A depleted shared batch returns the pages already read with partial coverage.
    func myNodesObservationHistory(
        publicKey: String, since: Date, budget: MyNodesRequestBudget
    ) async throws -> ObservationHistory? {
        let reader = BeaconNodeReader(budgeting: self, budget: budget, node: publicKey)
        return try await reader.observationHistory(publicKey: publicKey, since: since, maximumPages: 3)
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
        guard let summary = try await nodeSummary(publicKey: publicKey) else { return nil }
        let key = publicKey.lowercased()
        let detail: Node = try await client.get(["nodes", summary.id])
        try detail.validate()
        guard detail.id == summary.id, detail.publicKey.lowercased() == key else {
            throw APIError.invalidResponse
        }
        try client.sourceSession.check()
        return detail
    }

    func neighbors(publicKey: String) async throws -> NeighborCollection? {
        guard let summary = try await nodeSummary(publicKey: publicKey) else { return nil }
        let records: [Neighbor] = try await client.get(["nodes", summary.id, "neighbors"])
        try records.forEach { try $0.validate() }
        try client.sourceSession.check()
        return NeighborCollection(
            neighbors: records.map(\.insight),
            completeness: .complete,
            explanation: nil
        )
    }

    func nodeSummary(publicKey: String) async throws -> Node? {
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
        try client.sourceSession.check()
        return summary
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

    struct Neighbor: Codable, Sendable {
        let id: String
        let name: String?
        let publicKey: String
        let nodeType: Int?
        let nodeTypeName: String?
        let lat: Double?
        let lng: Double?
        let observationCount: Int?
        let lastSeen: Date?
        let snr: Double?

        var insight: NeighborInsight {
            NeighborInsight(
                identity: NeighborIdentity(
                    publicKey: publicKey.lowercased(),
                    displayPrefix: String(publicKey.prefix(8)).lowercased(),
                    sourceID: id.lowercased()
                ),
                name: name,
                role: nodeTypeName,
                lastHeard: lastSeen,
                observationCount: observationCount,
                signal: NeighborSignal(snr: snr, rssi: nil),
                direction: .unknown,
                latitude: lat,
                longitude: lng
            )
        }

        func validate() throws {
            guard UUID(uuidString: id) != nil,
                  BeaconNodeReader.isFullKey(publicKey),
                  observationCount.map({ $0 >= 0 }) != false,
                  snr.map(\.isFinite) != false,
                  lat.map(\.isFinite) != false,
                  lng.map(\.isFinite) != false else {
                throw APIError.invalidResponse
            }
        }
    }

    struct Observation: Codable, Sendable, Identifiable, Equatable {
        let id: Int64
        let packetHash: String
        let payloadType: Int
        let heardAt: Date
        let rssi: Double?
        let snr: Double?
        let hopCount: Int?

        func validate() throws {
            guard id >= 0,
                  !packetHash.isEmpty,
                  payloadType >= 0,
                  rssi.map(\.isFinite) != false,
                  snr.map(\.isFinite) != false,
                  hopCount.map({ $0 >= 0 }) != false else {
                throw APIError.invalidResponse
            }
        }
    }

    struct ObservationHistory: Sendable {
        let node: Node
        let observations: [Observation]
        let isComplete: Bool
        let requestCount: Int
    }

    /// How far a later page's timestamps may exceed an earlier page's.
    static let ingestionGrace: TimeInterval = 60 * 60

    func observationHistory(
        publicKey: String,
        since: Date,
        maximumPages: Int = 19
    ) async throws -> ObservationHistory? {
        guard maximumPages > 0 else { throw AnalyzerPagingError.invalidPageSize }
        guard let node = try await nodeSummary(publicKey: publicKey) else { return nil }
        var observations: [Observation] = []
        var cursor: CursorValue?
        var seenCursors: Set<String> = []
        var seenObservationIDs: Set<Int64> = []
        var isComplete = false
        var pages = 0

        while pages < min(maximumPages, 19) {
            var query = [URLQueryItem(name: "limit", value: "200")]
            if let cursor { query.append(URLQueryItem(name: "cursor", value: cursor.queryValue)) }
            let page: ObservationPage
            do {
                page = try await client.get(["nodes", node.id, "observations"], query: query)
            } catch MyNodesBudgetError.exhausted {
                break
            } catch MyNodesBudgetError.nodeExhausted {
                break
            }
            try page.items.forEach { try $0.validate() }
            for observation in page.items {
                guard seenObservationIDs.insert(observation.id).inserted else { continue }
                if observation.heardAt >= since { observations.append(observation) }
            }
            pages += 1

            // Beacon cursors follow observation IDs, not heardAt. Delayed
            // ingestion can therefore make timestamps non-monotonic within a
            // page, so a page never proves completion by itself. Measured on
            // the three live servers (2026-10-14) the worst inversion was under
            // a minute, so a page wholly older than `since` by more than
            // `ingestionGrace` ends the walk. Without that, a 24-hour view
            // downloaded the server's entire retained history.
            if !page.hasMore || page.items.max(by: { $0.heardAt < $1.heardAt })
                .map({ $0.heardAt < since.addingTimeInterval(-Self.ingestionGrace) }) == true {
                isComplete = true
                break
            }
            guard !page.items.isEmpty, let next = page.nextCursor,
                  seenCursors.insert(next.queryValue).inserted else {
                throw BeaconAPIError.malformedPage
            }
            cursor = next
        }

        try client.sourceSession.check()
        return ObservationHistory(
            node: node,
            observations: observations.sorted { $0.heardAt > $1.heardAt },
            isComplete: isComplete,
            requestCount: pages + 1
        )
    }

    private struct Page: Decodable, Sendable {
        let items: [Node]
        let hasMore: Bool
        let nextCursor: CursorValue?
    }

    private struct ObservationPage: Decodable, Sendable {
        let items: [Observation]
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
