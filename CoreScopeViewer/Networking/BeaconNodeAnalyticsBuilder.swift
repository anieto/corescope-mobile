import Foundation

enum BeaconNodeAnalyticsBuilder {
    /// Beacon keeps individual observations for seven days and hourly totals for longer;
    /// `GET /nodes/{id}/activity` serves at most the last 30 days of those totals.
    static let observationDays = 7
    static let activityDays = BeaconNodeReader.Activity.maximumHours / 24

    static func build(
        history: BeaconNodeReader.ObservationHistory,
        activity: BeaconNodeReader.Activity?,
        requestedDays: Int,
        from requestedFrom: Date,
        to requestedTo: Date
    ) -> NodeAnalyticsResponse {
        guard let activity else {
            return build(history: history, requestedDays: requestedDays, from: requestedFrom, to: requestedTo)
        }
        let safeDays = max(1, requestedDays)
        let observationFrom = max(
            requestedFrom,
            requestedTo.addingTimeInterval(-Double(observationDays) * 24 * 60 * 60)
        )
        let observations = history.observations.filter {
            $0.heardAt >= observationFrom && $0.heardAt <= requestedTo
        }
        // Activity, signal, the weekly pattern and the totals are complete hourly
        // counts. Packet types and hops still need individual observations.
        let samplesCoverWindow = safeDays <= observationDays && history.isComplete
        let explanation: String? = if safeDays > activityDays {
            String(localized: "Beacon provides up to 30 days of hourly activity. Packet types and hops cover the last seven days.")
        } else if safeDays > observationDays {
            String(localized: "Packet types and hops cover the last seven days, the individual observations Beacon keeps.")
        } else if !history.isComplete {
            String(localized: "Packet types and hops use the observations NodeScope downloaded before reaching its request limit.")
        } else {
            nil
        }
        let context = AnalyticsContext(
            completeness: explanation == nil ? .complete : .partial,
            countUnit: .observations,
            provenance: .derived,
            requestedFrom: requestedTo.addingTimeInterval(-Double(safeDays) * 24 * 60 * 60),
            requestedTo: requestedTo,
            coveredFrom: activity.windowStart,
            coveredTo: requestedTo,
            explanation: explanation
        )

        let signalPoints = activity.points.compactMap { point -> NodeSignalPoint? in
            guard let snr = point.snrAvg else { return nil }
            return NodeSignalPoint(timestamp: point.t, snr: snr, rssi: point.rssiAvg, observerID: nil, observerName: nil)
        }
        let sampledSnr = observations.compactMap(\.snr)
        let hopValues = observations.compactMap(\.hopCount)
        let relayPercentage = hopValues.isEmpty ? nil
            : Double(hopValues.count { $0 > 0 }) / Double(hopValues.count) * 100
        let windowDays = max(requestedTo.timeIntervalSince(activity.windowStart) / 86_400, 1.0 / 24.0)
        var hourly: [Date: Int] = [:]
        for point in activity.points { hourly[point.t, default: 0] += point.observations }

        return NodeAnalyticsResponse(
            node: history.node.meshNode,
            timeRange: NodeAnalyticsTimeRange(
                from: activity.windowStart,
                to: requestedTo,
                days: min(safeDays, activityDays)
            ),
            context: context,
            activityTimeline: hourlyPoints(hourly, from: activity.windowStart, to: requestedTo),
            snrTrend: signalPoints,
            packetTypeBreakdown: Dictionary(grouping: observations, by: \.payloadType)
                .map { NodePacketTypeCount(payloadType: $0.key, count: $0.value.count) }
                .sorted { $0.payloadType < $1.payloadType },
            observerCoverage: [],
            hopDistribution: Dictionary(grouping: hopValues, by: { $0 })
                .map { NodeHopCount(hops: String($0.key), count: $0.value.count) }
                .sorted { (Int($0.hops) ?? .max) < (Int($1.hops) ?? .max) },
            peerInteractions: [],
            uptimeHeatmap: heatmap(activity.points.map { ($0.t, $0.observations) }),
            computedStats: NodeComputedStats(
                availabilityPct: nil,
                longestSilenceMs: nil,
                longestSilenceStart: nil,
                signalGrade: nil,
                snrMean: activity.summary.snrAvg,
                // A spread needs individual readings; hourly averages would understate it.
                snrStdDev: samplesCoverWindow ? standardDeviation(sampledSnr, mean: mean(sampledSnr)) : nil,
                relayPct: relayPercentage,
                totalPackets: activity.summary.observations,
                uniqueObservers: nil,
                uniquePeers: nil,
                avgPacketsPerDay: Double(activity.summary.observations) / windowDays
            )
        )
    }

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
            uptimeHeatmap: heatmap(observations.map { ($0.heardAt, 1) }),
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
        var counts: [Date: Int] = [:]
        for observation in observations {
            counts[hourStart(observation.heardAt), default: 0] += 1
        }
        return hourlyPoints(counts, from: from, to: to)
    }

    /// One point per hour from `from` to `to`, zero where nothing was heard.
    private static func hourlyPoints(_ counts: [Date: Int], from: Date, to: Date) -> [NodeActivityPoint] {
        var points: [NodeActivityPoint] = []
        var bucket = hourStart(from)
        while bucket <= to {
            points.append(NodeActivityPoint(bucket: bucket, count: counts[bucket, default: 0]))
            guard let next = utcCalendar.date(byAdding: .hour, value: 1, to: bucket), next > bucket else { break }
            bucket = next
        }
        return points
    }

    private static func hourStart(_ date: Date) -> Date {
        utcCalendar.dateInterval(of: .hour, for: date)?.start ?? date
    }

    /// Day-of-week by hour counts from timestamped tallies (single observations or hourly totals).
    private static func heatmap(_ tallies: [(Date, Int)]) -> [NodeUptimeCell] {
        var counts: [HeatmapKey: Int] = [:]
        for (date, count) in tallies where count > 0 {
            let components = utcCalendar.dateComponents([.weekday, .hour], from: date)
            let key = HeatmapKey(dayOfWeek: max(0, (components.weekday ?? 1) - 1), hour: components.hour ?? 0)
            counts[key, default: 0] += count
        }
        return counts.map {
            NodeUptimeCell(dayOfWeek: $0.key.dayOfWeek, hour: $0.key.hour, count: $0.value)
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
