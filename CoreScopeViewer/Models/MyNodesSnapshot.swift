import Foundation

struct MyNodesNamespace: Codable, Hashable, Sendable {
    /// Supply the active backend's canonical origin, retaining its port and base path.
    let origin: String
    let backend: String
    /// "local", or an account identifier scoped to this origin. Never a session token.
    let selectionScope: String
    let policyVersion: Int

    var id: String { MyNodesIdentity.encode([origin, backend, selectionScope, String(policyVersion)]) }
}

enum MyNodesIdentity {
    /// Length-prefixed UTF-8 components avoid delimiter collisions across platforms.
    static func encode(_ components: [String]) -> String {
        components.map { "\($0.utf8.count):\($0)" }.joined()
    }
}

struct MyNodesEntity: Codable, Hashable, Sendable {
    enum Kind: String, Codable, Sendable { case node, observer }
    let kind: Kind
    let value: String

    init(kind: Kind, value: String) {
        self.kind = kind
        self.value = kind == .node ? value.lowercased() : value
    }

    var id: String { MyNodesIdentity.encode([kind.rawValue, value]) }
}

struct MyNodesMetric: Codable, Sendable {
    enum Category: String, Codable, Sendable { case activity, signal, observer }
    let activity: MyNodesActivity?
    let signal: MyNodesSignal.Summary?
    let observer: MyNodesObserverStatus?

    init(activity: MyNodesActivity? = nil, signal: MyNodesSignal.Summary? = nil,
         observer: MyNodesObserverStatus? = nil) {
        self.activity = activity
        self.signal = signal
        self.observer = observer
    }

    var category: Category { activity != nil ? .activity : signal != nil ? .signal : .observer }
    var evaluatedAt: Date { activity?.evaluatedAt ?? signal?.evaluatedAt ?? observer?.evaluatedAt ?? .distantPast }
    var fetchedAt: Date { activity?.fetchedAt ?? signal?.evidence.fetchedAt ?? observer?.fetchedAt ?? .distantPast }
    var measuredAt: Date { activity?.activityAt ?? signal?.evidence.requestedTo ?? observer?.measuredAt ?? .distantPast }
    var eligible: Bool {
        let count = [activity != nil, signal != nil, observer != nil].filter { $0 }.count
        return count == 1 && (activity?.eligible ?? signal?.eligible ?? observer?.eligible ?? false)
    }

    /// Recheck at render time: an assessment may have crossed a recency/freshness boundary
    /// while waiting for the UI, even though its original assessment was eligible.
    func eligible(at now: Date) -> Bool {
        guard eligible else { return false }
        if let activity {
            let current = MyNodesActivity(lastSeen: activity.activityAt, lastHeard: nil,
                fetchedAt: activity.fetchedAt, evaluatedAt: now, successfulRecord: true)
            return current.eligible && current.state == activity.state
        }
        if let signal { return signal.evidence.permitsHistoryComparison(at: now) }
        if let observer {
            let current = MyNodesObserverStatus(
                explicitOnline: observer.method == .explicit ? observer.state == .active : nil,
                statusAt: observer.method == .explicit ? observer.measuredAt : nil,
                lastSeen: observer.method == .recentlySeen ? observer.measuredAt : nil,
                fetchedAt: observer.fetchedAt, evaluatedAt: now, successfulRecord: true)
            return current.eligible && current.state == observer.state && current.method == observer.method
        }
        return false
    }

    func event(namespace: MyNodesNamespace, entity: MyNodesEntity, baseline: MyNodesBaseline) -> MyNodesEvent? {
        var change: String?
        var delta: Double?
        if let current = activity, let previous = baseline.metric.activity {
            change = current.change(from: previous)?.rawValue
        } else if let current = signal, let previous = baseline.metric.signal,
                  let result = current.visitChange(from: previous), result.isSignificant {
            change = result.decibels > 0 ? "snrIncreased" : "snrDecreased"
            delta = result.decibels
        } else if let current = observer, let previous = baseline.metric.observer,
                  current.changed(from: previous) {
            change = current.state == .active ? "observerActive" : "observerInactive"
        }
        guard let change else { return nil }
        return MyNodesEvent(id: MyNodesIdentity.encode([namespace.id, entity.id, category.rawValue, baseline.revision]),
                            category: category, change: change, snrDelta: delta,
                            previous: baseline.metric, current: self)
    }
}

/// A reviewed metric's comparison result. Unavailable evidence never means unchanged.
struct MyNodesReviewResult: Sendable {
    enum State: String, Sendable { case unavailable, firstVisit, expiredBaseline, unchanged, changed }
    let state: State
    let event: MyNodesEvent?
    static let unavailable = Self(state: .unavailable, event: nil)
}

extension MyNodesMetric {
    func review(namespace: MyNodesNamespace, entity: MyNodesEntity,
                baseline: MyNodesBaseline?, expired: Bool) -> MyNodesReviewResult {
        guard eligible else { return .unavailable }
        guard let baseline else {
            return MyNodesReviewResult(state: expired ? .expiredBaseline : .firstVisit, event: nil)
        }
        let previous = baseline.metric
        let elapsed = evaluatedAt.timeIntervalSince(previous.evaluatedAt)
        guard previous.eligible, elapsed >= 0, elapsed <= 7 * 86400,
              fetchedAt > previous.evaluatedAt, measuredAt >= previous.measuredAt else { return .unavailable }
        if let current = signal, let old = previous.signal {
            guard current.visitChange(from: old) != nil else { return .unavailable }
        } else if let current = observer, let old = previous.observer {
            guard current.method == old.method else { return .unavailable }
            if current.method == .explicit, current.state != old.state, measuredAt <= previous.measuredAt {
                return .unavailable
            }
        } else if activity == nil || previous.activity == nil {
            return .unavailable
        }
        let event = event(namespace: namespace, entity: entity, baseline: baseline)
        return MyNodesReviewResult(state: event == nil ? .unchanged : .changed, event: event)
    }
}

struct MyNodesBaseline: Codable, Sendable {
    let revision: String
    let metric: MyNodesMetric
}

struct MyNodesEvent: Sendable {
    let id: String
    let category: MyNodesMetric.Category
    let change: String
    let snrDelta: Double?
    let previous: MyNodesMetric
    let current: MyNodesMetric
}
