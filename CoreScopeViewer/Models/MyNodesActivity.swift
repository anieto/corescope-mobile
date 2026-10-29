import Foundation

/// A captured activity assessment. Re-evaluating an old fetch can change its display state,
/// but cannot confirm a new activity event.
struct MyNodesActivity: Codable, Sendable, Equatable {
    enum State: String, Codable, Sendable {
        case unknown, recentlyHeard, notRecentlyHeard
    }

    enum Change: String, Codable, Sendable {
        case newlyActive, noLongerRecentlyHeard
    }

    let state: State
    let activityAt: Date?
    let fetchedAt: Date
    let evaluatedAt: Date
    let eligible: Bool

    init(lastSeen: Date?, lastHeard: Date?, fetchedAt: Date, evaluatedAt: Date,
         successfulRecord: Bool) {
        self.fetchedAt = fetchedAt
        self.evaluatedAt = evaluatedAt
        let timestamps = [lastSeen, lastHeard].compactMap { $0 }
            .filter { $0.timeIntervalSince1970.isFinite }
        activityAt = timestamps.max()
        let fetchAge = evaluatedAt.timeIntervalSince(fetchedAt)
        let activityAge = activityAt.map { evaluatedAt.timeIntervalSince($0) }
        let valid = successfulRecord && fetchAge.isFinite && fetchAge >= 0 &&
            activityAge.map { $0.isFinite && $0 >= -300 } == true
        if valid, let activityAge {
            state = activityAge < 900 ? .recentlyHeard : .notRecentlyHeard
        } else {
            state = .unknown
        }
        eligible = valid && fetchAge <= 300
    }

    /// Baselines retain their original evaluation time and state for the whole visit.
    func change(from baseline: Self) -> Change? {
        let baselineAge = evaluatedAt.timeIntervalSince(baseline.evaluatedAt)
        guard eligible, baseline.eligible, baselineAge >= 0, baselineAge <= 7 * 24 * 60 * 60,
              fetchedAt > baseline.evaluatedAt,
              let activityAt, let previousActivity = baseline.activityAt,
              activityAt >= previousActivity else { return nil }
        switch (baseline.state, state) {
        case (.notRecentlyHeard, .recentlyHeard) where activityAt > previousActivity:
            return .newlyActive
        case (.recentlyHeard, .notRecentlyHeard):
            return .noLongerRecentlyHeard
        default:
            return nil
        }
    }
}
