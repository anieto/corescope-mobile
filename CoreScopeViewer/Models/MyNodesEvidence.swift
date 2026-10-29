import Foundation

/// Evidence for one dashboard metric, independent of whether its numeric value is zero.
struct MyNodesEvidence: Codable, Sendable, Equatable {
    enum Completeness: String, Codable, Sendable {
        case unknown, complete, partial, unsupported
    }

    let completeness: Completeness
    let fetchedAt: Date
    let requestedFrom: Date
    let requestedTo: Date
    let coveredFrom: Date?
    let coveredTo: Date?

    init(context: AnalyticsContext?, fetchedAt: Date, requestedFrom: Date, requestedTo: Date) {
        switch context?.completeness {
        case .complete: completeness = .complete
        case .partial: completeness = .partial
        case .unsupported: completeness = .unsupported
        case nil: completeness = .unknown
        }
        self.fetchedAt = fetchedAt
        self.requestedFrom = requestedFrom
        self.requestedTo = requestedTo
        coveredFrom = context?.coveredFrom
        coveredTo = context?.coveredTo
    }

    /// A fresh HTTP response alone cannot prove that a server retained the requested history.
    func permitsHistoryComparison(at now: Date, maximumAge: TimeInterval = 15 * 60) -> Bool {
        guard completeness == .complete, maximumAge >= 0,
              fetchedAt <= now, now.timeIntervalSince(fetchedAt) <= maximumAge,
              requestedFrom < requestedTo, requestedTo <= fetchedAt,
              let coveredFrom, let coveredTo, coveredFrom < coveredTo else { return false }
        return coveredFrom <= requestedFrom && coveredTo >= requestedTo
    }
}
