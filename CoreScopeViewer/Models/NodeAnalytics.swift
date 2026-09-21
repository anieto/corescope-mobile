import Foundation

struct NodeAnalyticsResponse: Codable, Sendable {
    let node: MeshNode
    let timeRange: NodeAnalyticsTimeRange
    let activityTimeline: [NodeActivityPoint]
    let snrTrend: [NodeSignalPoint]
    let packetTypeBreakdown: [NodePacketTypeCount]
    let observerCoverage: [NodeObserverCoverage]
    let hopDistribution: [NodeHopCount]
    let peerInteractions: [NodePeerInteraction]
    let uptimeHeatmap: [NodeUptimeCell]
    let computedStats: NodeComputedStats

    enum CodingKeys: String, CodingKey {
        case node, timeRange, activityTimeline, snrTrend, packetTypeBreakdown
        case observerCoverage, hopDistribution, peerInteractions, uptimeHeatmap
        case computedStats
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        node = try container.decode(MeshNode.self, forKey: .node)
        timeRange = try container.decode(NodeAnalyticsTimeRange.self, forKey: .timeRange)
        activityTimeline = try container.decodeIfPresent([NodeActivityPoint].self, forKey: .activityTimeline) ?? []
        snrTrend = try container.decodeIfPresent([NodeSignalPoint].self, forKey: .snrTrend) ?? []
        packetTypeBreakdown = try container.decodeIfPresent(
            [NodePacketTypeCount].self,
            forKey: .packetTypeBreakdown
        ) ?? []
        observerCoverage = try container.decodeIfPresent(
            [NodeObserverCoverage].self,
            forKey: .observerCoverage
        ) ?? []
        hopDistribution = try container.decodeIfPresent([NodeHopCount].self, forKey: .hopDistribution) ?? []
        peerInteractions = try container.decodeIfPresent(
            [NodePeerInteraction].self,
            forKey: .peerInteractions
        ) ?? []
        uptimeHeatmap = try container.decodeIfPresent([NodeUptimeCell].self, forKey: .uptimeHeatmap) ?? []
        computedStats = try container.decode(NodeComputedStats.self, forKey: .computedStats)
    }
}

struct NodeAnalyticsTimeRange: Codable, Sendable {
    let from: Date
    let to: Date
    let days: Int
}

struct NodeActivityPoint: Codable, Sendable, Identifiable {
    let bucket: Date
    let count: Int

    var id: Date { bucket }
}

struct NodeSignalPoint: Codable, Sendable, Identifiable {
    let timestamp: Date
    let snr: Double
    let rssi: Double?
    let observerID: String?
    let observerName: String?

    var id: String {
        "\(timestamp.timeIntervalSince1970)-\(observerID ?? "unknown")"
    }

    enum CodingKeys: String, CodingKey {
        case timestamp, snr, rssi
        case observerID = "observer_id"
        case observerName = "observer_name"
    }
}

struct NodePacketTypeCount: Codable, Sendable, Identifiable {
    let payloadType: Int
    let count: Int

    var id: Int { payloadType }
    var name: String { PayloadType.name(for: payloadType) }

    enum CodingKeys: String, CodingKey {
        case payloadType = "payload_type"
        case count
    }
}

struct NodeObserverCoverage: Codable, Sendable, Identifiable {
    let observerID: String
    let observerName: String?
    let packetCount: Int
    let avgSnr: Double?
    let avgRssi: Double?
    let firstSeen: Date
    let lastSeen: Date

    var id: String { observerID }

    enum CodingKeys: String, CodingKey {
        case observerID = "observer_id"
        case observerName = "observer_name"
        case packetCount, avgSnr, avgRssi, firstSeen, lastSeen
    }
}

struct NodeHopCount: Codable, Sendable, Identifiable {
    let hops: String
    let count: Int

    var id: String { hops }
}

struct NodePeerInteraction: Codable, Sendable, Identifiable {
    let peerKey: String
    let peerName: String
    let messageCount: Int
    let lastContact: Date

    var id: String { peerKey }

    enum CodingKeys: String, CodingKey {
        case peerKey = "peer_key"
        case peerName = "peer_name"
        case messageCount, lastContact
    }
}

struct NodeUptimeCell: Codable, Sendable, Identifiable {
    let dayOfWeek: Int
    let hour: Int
    let count: Int

    var id: String { "\(dayOfWeek)-\(hour)" }
}

struct NodeComputedStats: Codable, Sendable {
    let availabilityPct: Double
    let longestSilenceMs: Double
    let longestSilenceStart: Date?
    let signalGrade: String
    let snrMean: Double
    let snrStdDev: Double
    let relayPct: Double
    let totalPackets: Int
    let uniqueObservers: Int
    let uniquePeers: Int
    let avgPacketsPerDay: Double
}
