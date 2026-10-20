import Foundation

/// Explicit status and inferred recency remain different comparison methods.
struct MyNodesObserverStatus: Codable, Sendable {
    enum Method: String, Codable, Sendable { case explicit, recentlySeen }
    enum State: String, Codable, Sendable { case unknown, active, inactive }

    let method: Method
    let state: State
    let measuredAt: Date?
    let fetchedAt: Date
    let evaluatedAt: Date
    let eligible: Bool

    init(explicitOnline: Bool?, statusAt: Date?, lastSeen: Date?, fetchedAt: Date,
         evaluatedAt: Date, successfulRecord: Bool) {
        self.fetchedAt = fetchedAt
        self.evaluatedAt = evaluatedAt
        if let explicitOnline, let statusAt {
            method = .explicit
            measuredAt = statusAt
            let fetchAge = evaluatedAt.timeIntervalSince(fetchedAt)
            let statusAge = evaluatedAt.timeIntervalSince(statusAt)
            let valid = successfulRecord && fetchAge.isFinite && fetchAge >= 0 &&
                statusAge.isFinite && statusAge >= 0
            state = valid ? (explicitOnline ? .active : .inactive) : .unknown
            eligible = valid && fetchAge <= 300 && statusAge <= 300
        } else {
            method = .recentlySeen
            let activity = MyNodesActivity(lastSeen: lastSeen, lastHeard: nil, fetchedAt: fetchedAt,
                                           evaluatedAt: evaluatedAt, successfulRecord: successfulRecord)
            measuredAt = activity.activityAt
            switch activity.state {
            case .unknown: state = .unknown
            case .recentlyHeard: state = .active
            case .notRecentlyHeard: state = .inactive
            }
            eligible = activity.eligible
        }
    }

    func changed(from baseline: Self) -> Bool {
        let elapsed = evaluatedAt.timeIntervalSince(baseline.evaluatedAt)
        guard eligible, baseline.eligible, method == baseline.method,
              state != .unknown, baseline.state != .unknown, state != baseline.state,
              elapsed >= 0, elapsed <= 7 * 24 * 60 * 60, fetchedAt > baseline.evaluatedAt,
              let measuredAt, let previous = baseline.measuredAt, measuredAt >= previous else { return false }
        // A new explicit status needs a newer status measurement; inferred silence may
        // retain the old last-seen time when a new successful catalog confirms it.
        return method == .recentlySeen && state == .inactive || measuredAt > previous
    }
}
