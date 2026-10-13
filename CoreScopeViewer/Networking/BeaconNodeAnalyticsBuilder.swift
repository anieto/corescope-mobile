import Foundation

enum BeaconNodeAnalyticsBuilder {
    static func build(
        history: BeaconNodeReader.ObservationHistory,
        requestedDays: Int,
        from requestedFrom: Date,
        to requestedTo: Date
    ) -> NodeAnalyticsResponse {
        let retainedFrom = max(
            requestedFrom,
            requestedTo.addingTimeInterval(-7 * 24 * 60 * 60)
        )
        let observations = history.observations.filter {
            $0.heardAt >= retainedFrom && $0.heardAt <= requestedTo
        }
        let coveredFrom = history.isComplete
            ? retainedFrom
            : observations.last?.heardAt ?? retainedFrom
        let explanation = history.isComplete
            ? nil
            : String(localized: "NodeScope reached its request limit before Beacon history was exhausted.")
        let context = AnalyticsContext.beaconObservations(
            requestedDays: requestedDays,
            coveredFrom: coveredFrom,
            coveredTo: requestedTo,
            isComplete: history.isComplete,
            explanation: explanation
        )

        let signalPoints = observations.compactMap { observation -> NodeSignalPoint? in
            guard let snr = observation.snr else { return nil }
            return NodeSignalPoint(
                timestamp: observation.heardAt,
                snr: snr,
                rssi: observation.rssi,
                observerID: nil,
                observerName: nil
            )
        }
        let snrValues = signalPoints.map(\.snr)
        let snrMean = mean(snrValues)
        let hopValues = observations.compactMap(\.hopCount)
        let relayPercentage = hopValues.isEmpty ? nil
            : Double(hopValues.count { $0 > 0 }) / Double(hopValues.count) * 100
        let coveredDays = max(requestedTo.timeIntervalSince(coveredFrom) / 86_400, 1.0 / 24.0)

        return NodeAnalyticsResponse(
            node: history.node.meshNode,
            timeRange: NodeAnalyticsTimeRange(
                from: retainedFrom,
                to: requestedTo,
                days: min(max(1, requestedDays), 7)
            ),
            context: context,
            activityTimeline: activityPoints(observations, from: retainedFrom, to: requestedTo),
            snrTrend: signalPoints,
            packetTypeBreakdown: Dictionary(grouping: observations, by: \.payloadType)
                .map { NodePacketTypeCount(payloadType: $0.key, count: $0.value.count) }
                .sorted { $0.payloadType < $1.payloadType },
            observerCoverage: [],
            hopDistribution: Dictionary(grouping: hopValues, by: { $0 })
                .map { NodeHopCount(hops: String($0.key), count: $0.value.count) }
                .sorted { (Int($0.hops) ?? .max) < (Int($1.hops) ?? .max) },
            peerInteractions: [],
            uptimeHeatmap: heatmap(observations),
            computedStats: NodeComputedStats(
                availabilityPct: nil,
                longestSilenceMs: nil,
                longestSilenceStart: nil,
                signalGrade: nil,
                snrMean: snrMean,
                snrStdDev: standardDeviation(snrValues, mean: snrMean),
                relayPct: relayPercentage,
                totalPackets: observations.count,
                uniqueObservers: nil,
                uniquePeers: nil,
                avgPacketsPerDay: Double(observations.count) / coveredDays
            )
        )
    }

    private static func activityPoints(
        _ observations: [BeaconNodeReader.Observation],
        from: Date,
        to: Date
    ) -> [NodeActivityPoint] {
        let calendar = utcCalendar
        let start = calendar.dateInterval(of: .hour, for: from)?.start ?? from
        var counts: [Date: Int] = [:]
        for observation in observations {
            let bucket = calendar.dateInterval(of: .hour, for: observation.heardAt)?.start ?? observation.heardAt
            counts[bucket, default: 0] += 1
        }

        var points: [NodeActivityPoint] = []
        var bucket = start
        while bucket <= to {
            points.append(NodeActivityPoint(bucket: bucket, count: counts[bucket, default: 0]))
            guard let next = calendar.date(byAdding: .hour, value: 1, to: bucket), next > bucket else { break }
            bucket = next
        }
        return points
    }

    private static func heatmap(
        _ observations: [BeaconNodeReader.Observation]
    ) -> [NodeUptimeCell] {
        let counts = Dictionary(grouping: observations) { observation in
            let components = utcCalendar.dateComponents([.weekday, .hour], from: observation.heardAt)
            return HeatmapKey(dayOfWeek: max(0, (components.weekday ?? 1) - 1), hour: components.hour ?? 0)
        }
        return counts.map {
            NodeUptimeCell(dayOfWeek: $0.key.dayOfWeek, hour: $0.key.hour, count: $0.value.count)
        }.sorted {
            ($0.dayOfWeek, $0.hour) < ($1.dayOfWeek, $1.hour)
        }
    }

    private static func mean(_ values: [Double]) -> Double? {
        guard !values.isEmpty else { return nil }
        return values.reduce(0, +) / Double(values.count)
    }

    private static func standardDeviation(_ values: [Double], mean: Double?) -> Double? {
        guard let mean, !values.isEmpty else { return nil }
        let variance = values.reduce(0) { $0 + pow($1 - mean, 2) } / Double(values.count)
        return sqrt(variance)
    }

    private struct HeatmapKey: Hashable {
        let dayOfWeek: Int
        let hour: Int
    }

    private static var utcCalendar: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0)!
        return calendar
    }
}
