import Foundation

@MainActor
final class BeaconLiveRecovery {
    struct Row: Equatable, Sendable {
        let hash: String
        var firstSeen: Date?
        var lastHeard: Date
        var observationCount: Int
        let payloadType: Int
        let routeType: Int

        init(packet: Packet) {
            hash = packet.hash
            firstSeen = packet.firstSeen
            lastHeard = packet.timestamp
            observationCount = packet.observationCount
            payloadType = packet.payloadType
            routeType = packet.routeType
        }

        init(event: BeaconLiveProtocol.Observation) {
            hash = event.packetHash
            firstSeen = nil
            lastHeard = event.observation.heardAt
            observationCount = event.packet.observationCount
            payloadType = event.packet.payloadType
            routeType = event.packet.routeType
        }

        mutating func merge(_ other: Row) {
            if let date = other.firstSeen { firstSeen = firstSeen.map { min($0, date) } ?? date }
            lastHeard = max(lastHeard, other.lastHeard)
            observationCount = max(observationCount, other.observationCount)
        }
    }

    private(set) var rows: [Row] = []
    private(set) var possiblyIncomplete = false
    private(set) var recovering = false
    private var refreshRequested = false
    private let sourceSession: AnalyzerSourceSession
    private let region: String?
    private let payloadType: Int?
    private let fetch: @Sendable () async throws -> AnalyzerPage<Packet>
    private let maximumRows = 200

    init(reader: BeaconPacketReader, region: String?, payloadType: Int?) {
        sourceSession = reader.client.sourceSession
        self.region = region
        self.payloadType = payloadType
        fetch = { try await reader.packetPage(region: region, pageSize: 200, payloadType: payloadType, cursor: nil) }
    }

    init(
        sourceSession: AnalyzerSourceSession, region: String?, payloadType: Int?,
        fetch: @escaping @Sendable () async throws -> AnalyzerPage<Packet>
    ) {
        self.sourceSession = sourceSession
        self.region = region
        self.payloadType = payloadType
        self.fetch = fetch
    }

    func receive(_ event: BeaconLiveProtocol.Observation) throws {
        try sourceSession.check()
        if let payloadType, event.packet.payloadType != payloadType { return }
        if let region {
            let codes = Set(region.split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces).uppercased() })
            guard let iata = event.observation.iata, codes.contains(iata.uppercased()) else { return }
        }
        let row = Row(event: event)
        if let index = rows.firstIndex(where: { $0.hash == row.hash }) {
            rows[index].merge(row)
        } else {
            rows.insert(row, at: 0)
            if rows.count > maximumRows { rows.removeLast(rows.count - maximumRows) }
        }
    }

    func recover() async throws {
        try sourceSession.check()
        // A first page cannot prove that every missed observation has been recovered.
        markGap()
        guard !recovering else { return }
        recovering = true
        defer { recovering = false }
        while refreshRequested {
            refreshRequested = false
            let page = try await fetch()
            try sourceSession.check()
            merge(page)
        }
    }

    func markGap() {
        possiblyIncomplete = true
        refreshRequested = true
    }

    private func merge(_ page: AnalyzerPage<Packet>) {
        var merged: [Row] = []
        var positions: [String: Int] = [:]
        let existing = Dictionary(uniqueKeysWithValues: rows.map { ($0.hash, $0) })
        for packet in page.items {
            var row = Row(packet: packet)
            if let previous = existing[row.hash] { row.merge(previous) }
            if let index = positions[row.hash] {
                merged[index].merge(row)
            } else {
                positions[row.hash] = merged.count
                merged.append(row)
            }
        }
        let remaining = rows.filter { positions[$0.hash] == nil }
        let newest = page.items.map(\.timestamp).max()
        // Keep the server page order; live-only rows received after its snapshot stay ahead.
        let newer = remaining.filter { row in newest.map { row.lastHeard > $0 } ?? true }
        let older = remaining.filter { row in newest.map { row.lastHeard <= $0 } ?? false }
        rows = Array((newer + merged + older).prefix(maximumRows))
    }
}
