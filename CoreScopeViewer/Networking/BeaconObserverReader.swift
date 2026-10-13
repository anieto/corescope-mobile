import Foundation

actor BeaconObserverReader {
    let client: BeaconAPIClient
    private var idsByKey: [String: String] = [:]
    private var keysByID: [String: String] = [:]

    init(client: BeaconAPIClient, verifiedServerInfo: BeaconServerInfo) throws {
        try verifiedServerInfo.requireSupported()
        self.client = client
    }

    func observerPage(
        region: String?, pageSize: Int = 200, cursor: AnalyzerCursor? = nil
    ) async throws -> AnalyzerPage<Observer> {
        try client.sourceSession.check()
        guard pageSize > 0 else { throw AnalyzerPagingError.invalidPageSize }
        var query = [URLQueryItem(name: "limit", value: String(min(pageSize, 200)))]
        if let region { query.append(URLQueryItem(name: "iatas", value: region)) }
        if let cursor {
            guard let data = Data(base64Encoded: cursor.value),
                  let saved = try? JSONDecoder().decode(Cursor.self, from: data),
                  saved.endpoint == "observers", saved.source == client.cacheIdentifier,
                  saved.sessionID == client.sourceSession.id, saved.region == region else {
                throw AnalyzerPagingError.invalidCursor
            }
            query.append(URLQueryItem(name: "cursor", value: saved.value.queryValue))
        }
        let response: Page = try await client.get(["observers"], query: query)
        try response.items.forEach { try $0.validate() }
        let next: AnalyzerCursor?
        if response.hasMore {
            guard !response.items.isEmpty, let value = response.nextCursor else {
                throw BeaconAPIError.malformedPage
            }
            let saved = Cursor(endpoint: "observers", source: client.cacheIdentifier,
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

    nonisolated func observers(region: String?, maximumPages: Int = 20) async throws -> [Observer] {
        // No item limit: reaching the request budget must fail, not imply a complete map.
        try await AnalyzerPaging.collect(
            limit: Int.max, maximumPages: min(maximumPages, 20), identity: { $0.id.lowercased() }
        ) { cursor, _ in
            try await observerPage(region: region, cursor: cursor)
        }
    }

    func observerDetail(id: String) async throws -> Observer {
        try client.sourceSession.check()
        guard UUID(uuidString: id) != nil else { throw APIError.invalidResponse }
        let identifier = id.lowercased()
        let detail: Observer
        do {
            detail = try await client.get(["observers", identifier])
        } catch BeaconAPIError.server(let status, let code) {
            if status == 404 { forget(id: identifier) }
            throw BeaconAPIError.server(status: status, code: code)
        }
        try client.sourceSession.check()
        try detail.validate()
        guard detail.id.lowercased() == identifier else {
            forget(id: identifier)
            throw APIError.invalidResponse
        }
        if let key = detail.publicKey {
            try learn(observerID: identifier, publicKey: key, sessionID: client.sourceSession.id)
        } else {
            forget(id: identifier)
        }
        return detail
    }

    func observerDetail(publicKey: String) async throws -> Observer? {
        try client.sourceSession.check()
        guard Self.isFullKey(publicKey) else { throw APIError.invalidResponse }
        let key = publicKey.lowercased()
        guard let identifier = idsByKey[key] else { return nil }
        do {
            let detail = try await observerDetail(id: identifier)
            guard detail.publicKey?.lowercased() == key else { return nil }
            return detail
        } catch BeaconAPIError.server(let status, let code) {
            if status == 404 { return nil }
            throw BeaconAPIError.server(status: status, code: code)
        }
    }

    // Live-feed callers must supply the session that produced the event.
    func learn(observerID: String, publicKey: String, sessionID: UUID) throws {
        try client.sourceSession.check()
        guard sessionID == client.sourceSession.id else { throw CancellationError() }
        guard UUID(uuidString: observerID) != nil, Self.isFullKey(publicKey) else {
            throw APIError.invalidResponse
        }
        let identifier = observerID.lowercased()
        let key = publicKey.lowercased()
        forget(id: identifier)
        if let previous = idsByKey[key] { forget(id: previous) }
        idsByKey[key] = identifier
        keysByID[identifier] = key
    }

    private func forget(id: String) {
        if let key = keysByID.removeValue(forKey: id) { idsByKey.removeValue(forKey: key) }
    }

    private static func isFullKey(_ value: String) -> Bool {
        value.utf8.count == 64 && value.utf8.allSatisfy {
            (48...57).contains($0) || (65...70).contains($0) || (97...102).contains($0)
        }
    }

    struct Observer: Codable, Sendable {
        let id: String
        let displayName: String?
        let observerType: String?
        let iata: String?
        let status: String?
        let publicKey: String?
        let radio: String?
        let firstSeen: Date?
        let lastSeen: Date?
        let hardwareModel: String?
        let firmwareVersion: String?
        let softwareVersion: String?
        let radioFreqMhz: Double?
        let radioSf: Int?
        let radioBwKhz: Double?
        let radioCr: Int?
        let batteryLevel: Double?
        let uptimeSeconds: Int?

        // Beacon's observationCount includes non-packet events; it is not a packet total.
        var packetCount: Int? { nil }

        var meshObserver: MeshObserver {
            MeshObserver(
                id: id.lowercased(), name: displayName, iata: iata,
                lastSeen: lastSeen, firstSeen: firstSeen, packetCount: nil,
                model: hardwareModel, firmware: firmwareVersion, clientVersion: softwareVersion,
                radio: radio, batteryMv: nil, uptimeSecs: uptimeSeconds, noiseFloor: nil,
                packetsLastHour: nil, lat: nil, lon: nil, nodeRole: nil
            )
        }

        func validate() throws {
            guard UUID(uuidString: id) != nil else { throw APIError.invalidResponse }
            if let publicKey, !BeaconObserverReader.isFullKey(publicKey) {
                throw APIError.invalidResponse
            }
        }
    }

    private struct Page: Decodable, Sendable {
        let items: [Observer]
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
