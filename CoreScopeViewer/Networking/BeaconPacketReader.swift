import Foundation

struct BeaconPacketReader: Sendable {
    let client: BeaconAPIClient

    init(client: BeaconAPIClient, verifiedServerInfo: BeaconServerInfo) throws {
        try verifiedServerInfo.requireSupported()
        self.client = client
    }

    func packetPage(
        region: String?, pageSize: Int, payloadType: Int?, cursor: AnalyzerCursor?
    ) async throws -> AnalyzerPage<Packet> {
        try client.sourceSession.check()
        guard pageSize > 0 else { throw AnalyzerPagingError.invalidPageSize }
        var query = [URLQueryItem(name: "limit", value: String(min(pageSize, 200)))]
        if let region { query.append(URLQueryItem(name: "iatas", value: region)) }
        if let payloadType { query.append(URLQueryItem(name: "payloadType", value: String(payloadType))) }
        if let cursor {
            guard let data = Data(base64Encoded: cursor.value),
                  let saved = try? JSONDecoder().decode(Cursor.self, from: data),
                  saved.endpoint == "packets",
                  saved.source == client.cacheIdentifier, saved.sessionID == client.sourceSession.id,
                  saved.region == region, saved.payloadType == payloadType else {
                throw AnalyzerPagingError.invalidCursor
            }
            query.append(URLQueryItem(name: "cursor", value: saved.value.queryValue))
        }
        let response: Page = try await client.get(["packets"], query: query)
        guard response.items.allSatisfy({ !$0.packetHash.isEmpty && $0.observationCount >= 0 }) else {
            throw APIError.invalidResponse
        }
        let next: AnalyzerCursor?
        if response.hasMore {
            guard !response.items.isEmpty, let value = response.nextCursor else {
                throw BeaconAPIError.malformedPage
            }
            let cursor = Cursor(
                endpoint: "packets", source: client.cacheIdentifier, sessionID: client.sourceSession.id,
                region: region, payloadType: payloadType, value: value
            )
            let encoder = JSONEncoder()
            encoder.outputFormatting = [.sortedKeys]
            next = AnalyzerCursor(value: try encoder.encode(cursor).base64EncodedString())
        } else {
            next = nil
        }
        try client.sourceSession.check()
        return AnalyzerPage(items: response.items.map(\.packet), nextCursor: next, total: nil)
    }

    func packetDetail(hash: String) async throws -> PacketDetailResponse {
        let response: Detail = try await client.get(["packets", hash])
        guard response.packetHash == hash else { throw APIError.invalidResponse }
        try client.sourceSession.check()
        return PacketDetailResponse(
            packet: response.packet, path: [], observationCount: response.observationCount,
            observations: response.observations.map { observation in
                PacketObservation(
                    id: observation.id, observerName: observation.observerName,
                    observerIata: observation.iata, snr: observation.snr, rssi: observation.rssi,
                    timestamp: observation.heardAt,
                    resolvedPath: observation.resolvedPath?.map(\.confirmedKey)
                )
            }
        )
    }

    private struct Cursor: Codable {
        let endpoint: String
        let source: String
        let sessionID: UUID
        let region: String?
        let payloadType: Int?
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

    private struct Page: Decodable, Sendable {
        let items: [Summary]
        let hasMore: Bool
        let nextCursor: CursorValue?
    }

    private struct Summary: Decodable, Sendable {
        struct Observer: Decodable, Sendable {
            let id: String
            let displayName: String?
        }
        let packetHash: String
        let payloadType: Int
        let routeType: Int
        let firstHeardAt: Date
        let lastHeardAt: Date
        let observationCount: Int
        let latestObserver: Observer?

        var packet: Packet {
            Packet(
                serverID: nil, rawHex: nil, hash: packetHash, firstSeen: firstHeardAt,
                timestamp: lastHeardAt, routeType: routeType, payloadType: payloadType,
                payloadVersion: nil, decodedJson: nil, observationCount: observationCount,
                observerId: latestObserver?.id, observerName: latestObserver?.displayName,
                snr: nil, rssi: nil, pathJson: nil, direction: nil, score: nil
            )
        }
    }

    private struct Detail: Decodable, Sendable {
        struct Header: Decodable, Sendable {
            let routeType: Int
            let payloadType: Int
            let payloadVersion: Int?
        }
        struct Observation: Decodable, Sendable {
            let id: Int
            let observerName: String?
            let iata: String?
            let heardAt: Date
            let rssi: Double?
            let snr: Double?
            let resolvedPath: [ResolvedHop]?
        }
        let packetHash: String
        let header: Header
        let firstHeardAt: Date
        let lastHeardAt: Date
        let observationCount: Int
        let observations: [Observation]

        var packet: Packet {
            // rawPayload is not a complete packet; never expose it as CoreScope raw_hex.
            Packet(
                serverID: nil, rawHex: nil, hash: packetHash, firstSeen: firstHeardAt,
                timestamp: lastHeardAt, routeType: header.routeType, payloadType: header.payloadType,
                payloadVersion: header.payloadVersion, decodedJson: nil, observationCount: observationCount,
                observerId: nil, observerName: nil, snr: nil, rssi: nil, pathJson: nil,
                direction: nil, score: nil
            )
        }
    }

    struct ResolvedHop: Decodable, Sendable {
        struct Node: Decodable, Sendable { let publicKey: String? }
        let confidence: String
        let nodes: [Node]

        var confirmedKey: String? {
            guard confidence == "high", nodes.count == 1,
                  let key = nodes.first?.publicKey, key.count == 64,
                  key.utf8.allSatisfy({ (48...57).contains($0) || (65...70).contains($0) || (97...102).contains($0) }) else {
                return nil
            }
            return key.lowercased()
        }
    }
}
