import Foundation

/// Plain-language link quality from how far a reading's SNR sits above the LoRa
/// decode limit for the observer's spreading factor. Shared with Android
/// (`NodeAnalyticsLogic.kt`) so both apps label the same link the same way.
enum SignalQuality: CaseIterable, Sendable {
    case strong
    case good
    case weak
    case nearLimit

    init(margin: Double) {
        switch margin {
        case 15...: self = .strong
        case 10..<15: self = .good
        case 5..<10: self = .weak
        default: self = .nearLimit
        }
    }

    var label: String {
        switch self {
        case .strong: String(localized: "Strong")
        case .good: String(localized: "Good")
        case .weak: String(localized: "Weak")
        case .nearLimit: String(localized: "Near limit")
        }
    }

    /// Lower edge of this zone, as a margin above the decode limit.
    var minimumMargin: Double? {
        switch self {
        case .strong: 15
        case .good: 10
        case .weak: 5
        case .nearLimit: nil
        }
    }
}

enum SignalQualityMath {
    /// Observer `radio` is "frequency,bandwidth,spreadingFactor,codingRate",
    /// e.g. "910.525,62.5,7,5".
    static func spreadingFactor(radio: String?) -> Int? {
        guard let radio else { return nil }
        let parts = radio.split(separator: ",", omittingEmptySubsequences: false)
        guard parts.count > 2,
              let value = Int(parts[2].trimmingCharacters(in: .whitespaces)),
              (5...12).contains(value) else { return nil }
        return value
    }

    /// Semtech SX126x demodulation SNR limit: −7.5 dB at SF7, 2.5 dB lower per SF step.
    static func snrFloor(spreadingFactor: Int) -> Double {
        -7.5 - 2.5 * Double(spreadingFactor - 7)
    }

    /// Linear-interpolated percentile of an ascending array.
    static func percentile(_ sorted: [Double], _ fraction: Double) -> Double {
        guard sorted.count > 1 else { return sorted.first ?? 0 }
        let position = fraction * Double(sorted.count - 1)
        let index = Int(position)
        let lower = sorted[index]
        let upper = sorted[min(index + 1, sorted.count - 1)]
        return lower + (upper - lower) * (position - Double(index))
    }
}

/// Everything an advanced reader may want for one observer. `floor` is nil when
/// the observer's spreading factor is unknown.
struct ObserverSignalSummary: Identifiable, Sendable {
    let observerID: String?
    let observer: String
    let count: Int
    let median: Double
    /// 10th percentile; keeps a single outlier from stretching the typical range.
    let low: Double
    /// 90th percentile.
    let high: Double
    let minimum: Double
    let maximum: Double
    let medianRSSI: Double?
    let lastHeard: Date?
    let floor: Double?
    let spreadingFactor: Int?

    var id: String { observerID ?? observer }
    var margin: Double? { floor.map { median - $0 } }
    var quality: SignalQuality? { margin.map(SignalQuality.init(margin:)) }
    /// A handful of readings is not enough to judge a link.
    var hasFewReadings: Bool { count < 5 }

    /// SNR per observer, best first. Each observer's decode limit comes from its
    /// own reported spreading factor, falling back to the most common one on the
    /// analyzer; with neither, observers keep their numbers but get no label.
    static func summaries(points: [NodeSignalPoint], observers: [MeshObserver]) -> [ObserverSignalSummary] {
        let observersByID = Dictionary(
            observers.map { ($0.id.lowercased(), $0) },
            uniquingKeysWith: { first, _ in first }
        )
        let networkSpreadingFactor = Dictionary(
            grouping: observers.compactMap { SignalQualityMath.spreadingFactor(radio: $0.radio) },
            by: { $0 }
        ).max { $0.value.count < $1.value.count }?.key

        let groups = Dictionary(grouping: points) { point in
            point.observerID?.lowercased() ?? point.observerName ?? "unknown"
        }
        return groups.values.compactMap { samples -> ObserverSignalSummary? in
            let snr = samples.map(\.snr).sorted()
            guard !snr.isEmpty else { return nil }
            let observerID = samples.lazy.compactMap(\.observerID).first
            let sampleName = samples.lazy.compactMap(\.observerName).first { !$0.isEmpty }
            let known = observerID.flatMap { observersByID[$0.lowercased()] }
                ?? sampleName.flatMap { name in observers.first { $0.name == name } }
            let spreadingFactor = SignalQualityMath.spreadingFactor(radio: known?.radio) ?? networkSpreadingFactor
            let rssi = samples.compactMap(\.rssi).sorted()
            return ObserverSignalSummary(
                observerID: observerID,
                observer: sampleName ?? known?.name ?? observerID.map { String($0.prefix(12)) } ?? String(localized: "Unknown"),
                count: snr.count,
                median: SignalQualityMath.percentile(snr, 0.5),
                low: SignalQualityMath.percentile(snr, 0.1),
                high: SignalQualityMath.percentile(snr, 0.9),
                minimum: snr[0],
                maximum: snr[snr.count - 1],
                medianRSSI: rssi.isEmpty ? nil : SignalQualityMath.percentile(rssi, 0.5),
                lastHeard: samples.map(\.timestamp).max(),
                floor: spreadingFactor.map(SignalQualityMath.snrFloor(spreadingFactor:)),
                spreadingFactor: spreadingFactor
            )
        }
        .sorted(by: rankedBefore)
    }

    /// Best median first, then more readings, then name. `Dictionary` grouping order is
    /// randomized per instance, so ties must be broken explicitly or rows reshuffle on
    /// every redraw (for example when a row is expanded).
    static func rankedBefore(_ lhs: ObserverSignalSummary, _ rhs: ObserverSignalSummary) -> Bool {
        if lhs.median != rhs.median { return lhs.median > rhs.median }
        if lhs.count != rhs.count { return lhs.count > rhs.count }
        let byName = lhs.observer.localizedStandardCompare(rhs.observer)
        if byName != .orderedSame { return byName == .orderedAscending }
        return lhs.id < rhs.id
    }

    /// This observer's individual readings, oldest first.
    func readings(in points: [NodeSignalPoint]) -> [NodeSignalPoint] {
        points.filter { point in
            if let observerID {
                return point.observerID?.caseInsensitiveCompare(observerID) == .orderedSame
            }
            return point.observerName == observer
        }
        .sorted { $0.timestamp < $1.timestamp }
    }
}
