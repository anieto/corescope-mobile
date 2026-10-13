import Foundation

enum AnalyticsCountUnit: String, Codable, Sendable, Equatable {
    case packets
    case observations
}

enum AnalyticsCompleteness: String, Codable, Sendable, Equatable {
    case complete
    case partial
    case unsupported
}

enum AnalyticsProvenance: String, Codable, Sendable, Equatable {
    case server
    case derived
}

/// Describes what an analytics payload can honestly claim without changing its visual model.
struct AnalyticsContext: Codable, Sendable, Equatable {
    let completeness: AnalyticsCompleteness
    let countUnit: AnalyticsCountUnit
    let provenance: AnalyticsProvenance
    let requestedFrom: Date
    let requestedTo: Date
    let coveredFrom: Date?
    let coveredTo: Date?
    let explanation: String?

    var hasKnownCoverage: Bool { coveredFrom != nil && coveredTo != nil }

    static func beaconObservations(
        requestedDays: Int,
        coveredFrom: Date,
        coveredTo: Date,
        isComplete: Bool = true,
        explanation: String? = nil
    ) -> AnalyticsContext {
        let safeDays = max(1, requestedDays)
        let requestedFrom = coveredTo.addingTimeInterval(-Double(safeDays) * 24 * 60 * 60)
        let capped = safeDays > 7
        return AnalyticsContext(
            completeness: isComplete && !capped ? .complete : .partial,
            countUnit: .observations,
            provenance: .derived,
            requestedFrom: requestedFrom,
            requestedTo: coveredTo,
            coveredFrom: coveredFrom,
            coveredTo: coveredTo,
            explanation: explanation ?? (capped
                ? String(localized: "Beacon retains up to seven days of observation history.")
                : nil)
        )
    }

    static func unsupported(
        countUnit: AnalyticsCountUnit,
        requestedFrom: Date,
        requestedTo: Date,
        explanation: String
    ) -> AnalyticsContext {
        AnalyticsContext(
            completeness: .unsupported,
            countUnit: countUnit,
            provenance: .server,
            requestedFrom: requestedFrom,
            requestedTo: requestedTo,
            coveredFrom: nil,
            coveredTo: nil,
            explanation: explanation
        )
    }
}
