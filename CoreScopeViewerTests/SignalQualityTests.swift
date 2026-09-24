import Foundation
import Testing
@testable import CoreScopeViewer

/// Mirrors Android's `NodeAnalyticsTest` so both apps label the same link the same way.
struct SignalQualityTests {
    private func point(_ timestamp: TimeInterval, snr: Double, rssi: Double? = nil, id: String?, name: String?) -> NodeSignalPoint {
        NodeSignalPoint(
            timestamp: Date(timeIntervalSince1970: timestamp),
            snr: snr,
            rssi: rssi,
            observerID: id,
            observerName: name
        )
    }

    private var points: [NodeSignalPoint] {
        [
            point(3_600, snr: 6.5, rssi: -80, id: "OBS-NORTH", name: "North Observer"),
            point(0, snr: 5.0, id: "OBS-NORTH", name: "North Observer"),
            point(7_200, snr: -2.0, id: "OBS-SOUTH", name: nil)
        ]
    }

    private func observers(_ entries: [(id: String, name: String, radio: String)]) throws -> [MeshObserver] {
        let rows = entries.map { entry in
            """
            {"id":"\(entry.id)","name":"\(entry.name)","radio":"\(entry.radio)",
             "last_seen":"2026-09-23T12:00:00Z","first_seen":"2026-08-01T00:00:00Z",
             "packet_count":1,"packetsLastHour":1}
            """
        }
        let json = "{\"observers\":[\(rows.joined(separator: ","))],\"server_time\":\"2026-09-23T12:00:00Z\"}"
        return try APIClient.makeDecoder().decode(ObserversResponse.self, from: Data(json.utf8)).observers
    }

    @Test func decodeLimitsAndQualityBands() {
        #expect(SignalQualityMath.spreadingFactor(radio: "910.5250244,62.5,7,5") == 7)
        #expect(SignalQualityMath.spreadingFactor(radio: "910.5,62.5") == nil)
        #expect(SignalQualityMath.spreadingFactor(radio: nil) == nil)
        #expect(SignalQualityMath.snrFloor(spreadingFactor: 7) == -7.5)
        #expect(SignalQualityMath.snrFloor(spreadingFactor: 12) == -20)
        #expect(SignalQuality(margin: 15) == .strong)
        #expect(SignalQuality(margin: 10) == .good)
        #expect(SignalQuality(margin: 5) == .weak)
        #expect(SignalQuality(margin: 4.9) == .nearLimit)
    }

    @Test func summariesCarryNumbersAndQualityFromEachObserversRadio() throws {
        let summaries = ObserverSignalSummary.summaries(
            points: points,
            observers: try observers([
                ("OBS-NORTH", "North Observer", "910.525,62.5,7,5"),
                ("OBS-SOUTH", "South Observer", "910.525,62.5,9,5")
            ])
        )
        // A sample without a name is labeled from the observer list.
        #expect(summaries.map(\.observer) == ["North Observer", "South Observer"])
        let north = summaries[0]
        #expect(north.median == 5.75)
        #expect(north.minimum == 5.0 && north.maximum == 6.5)
        #expect(north.medianRSSI == -80)
        #expect(north.margin == 13.25)
        #expect(north.quality == .good)
        #expect(north.hasFewReadings)
        // South uses its own SF9 limit of −12.5 dB.
        #expect(summaries[1].margin == 10.5)
        #expect(summaries[1].quality == .good)
    }

    @Test func unknownRadioFallsBackToTheNetworkOrToNumbersOnly() throws {
        let network = ObserverSignalSummary.summaries(
            points: points,
            observers: try observers([("OTHER", "Other", "910.525,62.5,8,5")])
        )
        #expect(network.map(\.spreadingFactor) == [8, 8])
        let none = ObserverSignalSummary.summaries(points: points, observers: [])
        #expect(none.allSatisfy { $0.quality == nil && $0.margin == nil })
        #expect(none[0].median == 5.75)
    }

    @Test func readingsBelongToOneObserverOldestFirst() {
        let summaries = ObserverSignalSummary.summaries(points: points, observers: [])
        let north = try? #require(summaries.first { $0.observer == "North Observer" })
        #expect(north?.readings(in: points).map(\.snr) == [5.0, 6.5])
    }

    @Test func tiedObserversKeepAStableOrderAcrossRebuilds() {
        let tied = [
            point(0, snr: 12, id: "C", name: "Charlie"),
            point(0, snr: 12, id: "A", name: "alpha"),
            point(0, snr: 12, id: "B", name: "Bravo"),
            point(1, snr: 12, id: "B", name: "Bravo"),
            point(0, snr: 13, id: "D", name: "Delta")
        ]
        let expected = ["Delta", "Bravo", "alpha", "Charlie"]
        // Each rebuild uses a fresh Dictionary, whose iteration order varies.
        for _ in 0..<20 {
            #expect(ObserverSignalSummary.summaries(points: tied, observers: []).map(\.observer) == expected)
        }
    }
}
