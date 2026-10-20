import Foundation

/// Pure SNR policy; the caller supplies source-scoped samples and retained-history evidence.
struct MyNodesSignal: Codable, Sendable {
    enum Method: String, Codable, Sendable {
        case matchedObservers, mixedObservations
    }

    struct Sample: Codable, Sendable {
        let timestamp: Date
        let snr: Double
        let observerID: String?
    }

    struct Delta: Equatable, Sendable {
        let decibels: Double
        let comparableObservers: Int
        let permitsSignificantEvent: Bool

        var isSignificant: Bool { permitsSignificantEvent && abs(decibels) >= 5 }
    }

    let method: Method
    let evidence: MyNodesEvidence
    let evaluatedAt: Date
    let samples: [Sample]

    private var eligible: Bool {
        evidence.permitsHistoryComparison(at: evaluatedAt) &&
            evidence.requestedTo.timeIntervalSince(evidence.requestedFrom) == 24 * 60 * 60
    }

    private var validSamples: [Sample] {
        samples.filter {
            $0.snr.isFinite && $0.timestamp >= evidence.requestedFrom &&
                $0.timestamp < evidence.requestedTo
        }
    }

    func withinDayTrend() -> Delta? {
        guard eligible else { return nil }
        let midpoint = evidence.requestedFrom.addingTimeInterval(12 * 60 * 60)
        return Self.delta(older: validSamples.filter { $0.timestamp < midpoint },
                          newer: validSamples.filter { $0.timestamp >= midpoint }, method: method)
    }

    struct ObserverSummary: Codable, Sendable {
        let median: Double
        let sampleCount: Int
    }

    /// Compact persistence payload: no individual sample history.
    struct Summary: Codable, Sendable {
        let method: Method
        let evidence: MyNodesEvidence
        let evaluatedAt: Date
        let observers: [String: ObserverSummary]

        var eligible: Bool {
            method == .matchedObservers && evidence.permitsHistoryComparison(at: evaluatedAt) &&
                evidence.requestedTo.timeIntervalSince(evidence.requestedFrom) == 86400 &&
                !observers.isEmpty && observers.allSatisfy {
                    !$0.key.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
                        $0.value.sampleCount >= 5 && $0.value.median.isFinite
                }
        }

        func visitChange(from baseline: Self) -> Delta? {
            let elapsed = evaluatedAt.timeIntervalSince(baseline.evaluatedAt)
            guard eligible, baseline.eligible, elapsed >= 86400, elapsed <= 7 * 86400,
                  baseline.evidence.requestedTo <= evidence.requestedFrom else { return nil }
            let deltas = baseline.observers.compactMap { key, value -> Double? in
                guard let current = observers[key] else { return nil }
                let delta = current.median - value.median
                return delta.isFinite ? delta : nil
            }
            guard let value = MyNodesSignal.median(deltas) else { return nil }
            return Delta(decibels: value, comparableObservers: deltas.count, permitsSignificantEvent: true)
        }
    }

    var summary: Summary {
        let groups = Dictionary(grouping: validSamples.filter {
            !($0.observerID?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ?? true)
        }, by: { $0.observerID ?? "" })
        let observers = groups.compactMapValues { values -> ObserverSummary? in
            guard values.count >= 5, let value = Self.median(values.map(\.snr)) else { return nil }
            return ObserverSummary(median: value, sampleCount: values.count)
        }
        return Summary(method: method, evidence: evidence, evaluatedAt: evaluatedAt, observers: observers)
    }

    func visitChange(from baseline: Self) -> Delta? {
        summary.visitChange(from: baseline.summary)
    }

    private static func delta(older: [Sample], newer: [Sample], method: Method) -> Delta? {
        if method == .mixedObservations {
            guard older.count >= 5, newer.count >= 5,
                  let before = median(older.map(\.snr)), let after = median(newer.map(\.snr)),
                  (after - before).isFinite else { return nil }
            return Delta(decibels: after - before, comparableObservers: 0, permitsSignificantEvent: false)
        }
        let before = observerMedians(older)
        let after = observerMedians(newer)
        let deltas = before.compactMap { key, value -> Double? in
            guard let current = after[key], (current - value).isFinite else { return nil }
            return current - value
        }
        guard let result = median(deltas) else { return nil }
        return Delta(decibels: result, comparableObservers: deltas.count, permitsSignificantEvent: true)
    }

    private static func observerMedians(_ samples: [Sample]) -> [String: Double] {
        let identified = samples.filter { !($0.observerID?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ?? true) }
        return Dictionary(grouping: identified, by: { $0.observerID ?? "" }).compactMapValues {
            $0.count >= 5 ? median($0.map(\.snr)) : nil
        }
    }

    private static func median(_ values: [Double]) -> Double? {
        let sorted = values.sorted()
        guard !sorted.isEmpty else { return nil }
        let middle = sorted.count / 2
        return sorted.count.isMultiple(of: 2)
            ? sorted[middle - 1] / 2 + sorted[middle] / 2 : sorted[middle]
    }
}
