import Foundation

actor BeaconObserverAnalyticsReader {
    private struct Activity: Decodable, Sendable {
        struct Summary: Decodable, Sendable {
            let recordedPackets: Int
        }

        struct PayloadCount: Decodable, Sendable {
            let payloadType: Int
            let count: Int
        }

        struct Point: Decodable, Sendable {
            let t: Date
            let observations: Int
            let airtimeMs: Double?
            let snrAvg: Double?
            let snrMin: Double?
            let rssiAvg: Double?
        }

        let windowStart: Date
        let windowEnd: Date
        let generatedAt: Date
        let source: String
        let summary: Summary
        let rolledUntil: Date?
        let rawFrom: Date?
        let range: String
        let interval: String
        let payloadTypes: [PayloadCount]
        let points: [Point]
    }

    private struct Telemetry: Decodable, Sendable {
        struct Point: Decodable, Sendable {
            let t: Date
            let batteryMv: Int?
            let airtimeTxSecs: Int?
            let airtimeRxSecs: Int?
            let noiseFloorDb: Double?
            let uptimeSeconds: Int?
            let queueLength: Int?
            let receiveErrors: Int?
        }

        let range: String
        let interval: String
        let points: [Point]
    }

    private let client: BeaconAPIClient

    init(client: BeaconAPIClient, verifiedServerInfo: BeaconServerInfo) throws {
        try verifiedServerInfo.requireSupported()
        self.client = client
    }

    func analytics(observerID: String) async throws -> ObserverAnalyticsResponse {
        guard UUID(uuidString: observerID) != nil else { throw APIError.invalidResponse }
        let activityQuery = [
            URLQueryItem(name: "range", value: "24h"),
            URLQueryItem(name: "interval", value: "15m")
        ]
        let telemetryQuery = [
            URLQueryItem(name: "range", value: "24h"),
            URLQueryItem(name: "interval", value: "1h")
        ]
        async let activity: Activity = client.get(
            ["observers", observerID, "activity"], query: activityQuery
        )
        async let telemetry: Telemetry = client.get(
            ["observers", observerID, "telemetry"], query: telemetryQuery
        )
        let (activityResult, telemetryResult) = try await (activity, telemetry)
        try validate(activityResult)
        try validate(telemetryResult)
        try client.sourceSession.check()

        let explanation = String(localized: "Beacon reports observer activity as observations. Unique-node history, recent packet rows, and a true SNR distribution aren’t available from this endpoint.")
        let context = AnalyticsContext(
            completeness: .complete,
            countUnit: .observations,
            provenance: .server,
            requestedFrom: activityResult.windowStart,
            requestedTo: activityResult.windowEnd,
            coveredFrom: activityResult.windowStart,
            coveredTo: activityResult.windowEnd,
            explanation: explanation
        )
        return ObserverAnalyticsResponse(
            context: context,
            timeline: activityResult.points.map {
                LabeledCount(
                    label: $0.t.formatted(date: .abbreviated, time: .shortened),
                    count: $0.observations
                )
            },
            packetTypes: Dictionary(uniqueKeysWithValues: activityResult.payloadTypes.map {
                (String($0.payloadType), $0.count)
            }),
            nodesTimeline: [],
            snrDistribution: [],
            recentPackets: [],
            telemetry: telemetryResult.points.map {
                ObserverTelemetryPoint(
                    timestamp: $0.t,
                    batteryMillivolts: $0.batteryMv,
                    transmitAirtimeSeconds: $0.airtimeTxSecs,
                    receiveAirtimeSeconds: $0.airtimeRxSecs,
                    noiseFloorDecibels: $0.noiseFloorDb,
                    uptimeSeconds: $0.uptimeSeconds,
                    queueLength: $0.queueLength,
                    receiveErrors: $0.receiveErrors
                )
            }
        )
    }

    private func validate(_ activity: Activity) throws {
        guard activity.windowStart <= activity.windowEnd,
              activity.generatedAt >= activity.windowStart,
              activity.range == "24h", activity.interval == "15m",
              activity.summary.recordedPackets >= 0,
              activity.payloadTypes.allSatisfy({ $0.payloadType >= 0 && $0.count >= 0 }),
              activity.points.allSatisfy({ point in
                  point.t >= activity.windowStart && point.t <= activity.windowEnd &&
                      point.observations >= 0 &&
                      point.airtimeMs.map({ $0.isFinite && $0 >= 0 }) != false &&
                      point.snrAvg.map(\.isFinite) != false &&
                      point.snrMin.map(\.isFinite) != false &&
                      point.rssiAvg.map(\.isFinite) != false
              }) else { throw APIError.invalidResponse }
    }

    private func validate(_ telemetry: Telemetry) throws {
        guard telemetry.range == "24h", telemetry.interval == "1h",
              telemetry.points.allSatisfy({ point in
                  point.batteryMv.map({ $0 >= 0 }) != false &&
                      point.airtimeTxSecs.map({ $0 >= 0 }) != false &&
                      point.airtimeRxSecs.map({ $0 >= 0 }) != false &&
                      point.noiseFloorDb.map(\.isFinite) != false &&
                      point.uptimeSeconds.map({ $0 >= 0 }) != false &&
                      point.queueLength.map({ $0 >= 0 }) != false &&
                      point.receiveErrors.map({ $0 >= 0 }) != false
              }) else { throw APIError.invalidResponse }
    }
}
