import Foundation

/// Share one instance across windows. All disk mutations are serialized without suspension.
/// This file is disposable feature storage and never contains favorites or credentials.
actor MyNodesSnapshotStore {
    struct Visit: Sendable {
        let id: UUID
        let namespace: MyNodesNamespace
        let startedAt: Date
        let baselines: [String: MyNodesBaseline]
        let expiredKeys: Set<String>
        let omittedObserverCount: Int
    }

    private struct ActiveVisit {
        let visit: Visit
        var entities: Set<MyNodesEntity>
        var latestTime: Date
        let lifetime: AnalyzerSourceSession?
    }

    private struct Entry: Codable {
        let namespace: MyNodesNamespace
        let entity: MyNodesEntity
        var baseline: MyNodesBaseline?
        var candidate: MyNodesBaseline?
        var candidateOwner: UUID?
    }

    private struct Document: Codable {
        var schemaVersion = 1
        var entries: [String: Entry] = [:]
    }

    private let fileURL: URL
    private var document: Document
    private var visits: [UUID: ActiveVisit] = [:]

    init(fileURL: URL) throws {
        self.fileURL = fileURL
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .millisecondsSince1970
        let data = FileManager.default.fileExists(atPath: fileURL.path) ? try Data(contentsOf: fileURL) : nil
        var loaded = data.flatMap { try? decoder.decode(Document.self, from: $0) }
        if loaded?.schemaVersion != 1 { loaded = nil }
        var recovered = loaded ?? Document()
        // Persisted candidates were already rendered. Recover them after process termination.
        for key in Array(recovered.entries.keys) {
            if let candidate = recovered.entries[key]?.candidate {
                recovered.entries[key]?.baseline = candidate
                recovered.entries[key]?.candidate = nil
                recovered.entries[key]?.candidateOwner = nil
            }
        }
        document = recovered
        if FileManager.default.fileExists(atPath: fileURL.path) {
            try Self.save(recovered, to: fileURL)
        }
    }

    static func metricKey(entity: MyNodesEntity, category: MyNodesMetric.Category) -> String {
        MyNodesIdentity.encode([entity.id, category.rawValue])
    }

    /// Selection is authoritative. Removed identities also lose write access in existing visits.
    /// Favorite observers are unlimited; additional covering observers are capped deterministically.
    func begin(namespace: MyNodesNamespace, nodes: Set<String>, favoriteObservers: Set<String>,
               coveringObservers: Set<String> = [], at now: Date,
               lifetime: AnalyzerSourceSession? = nil) throws -> Visit {
        try lifetime?.check()
        let extra = coveringObservers.subtracting(favoriteObservers).sorted()
        let observers = favoriteObservers.union(extra.prefix(200))
        let entities = Set(nodes.map { MyNodesEntity(kind: .node, value: $0) } +
                           observers.map { MyNodesEntity(kind: .observer, value: $0) })
        var next = document
        next.entries = next.entries.filter { $0.value.namespace != namespace || entities.contains($0.value.entity) }
        try commit(next)
        for id in Array(visits.keys) where visits[id]?.visit.namespace == namespace {
            visits[id]?.entities.formIntersection(entities)
        }
        var baselines: [String: MyNodesBaseline] = [:]
        var expired: Set<String> = []
        for entry in document.entries.values where entry.namespace == namespace {
            guard let baseline = entry.baseline else { continue }
            let key = Self.metricKey(entity: entry.entity, category: baseline.metric.category)
            let age = now.timeIntervalSince(baseline.metric.evaluatedAt)
            if age >= 0 && age <= 7 * 86400 { baselines[key] = baseline }
            else { expired.insert(key) }
        }
        let visit = Visit(id: UUID(), namespace: namespace, startedAt: now, baselines: baselines,
                          expiredKeys: expired, omittedObserverCount: max(0, extra.count - 200))
        visits[visit.id] = ActiveVisit(visit: visit, entities: entities, latestTime: now, lifetime: lifetime)
        return visit
    }

    /// Call only after a fresh successful metric is rendered. Cache-only display is rejected.
    /// Returns the net event against the fixed visit baseline; nil removes any previous net event.
    func rendered(visit: Visit, entity: MyNodesEntity, metric: MyNodesMetric,
                  freshFetch: Bool, at now: Date) throws -> MyNodesEvent? {
        try reviewed(visit: visit, entity: entity, metric: metric, freshFetch: freshFetch, at: now).event
    }

    /// Call after rendering, just like rendered; returns an explicit comparison availability state.
    func reviewed(visit: Visit, entity: MyNodesEntity, metric: MyNodesMetric,
                  freshFetch: Bool, at now: Date) throws -> MyNodesReviewResult {
        guard var active = visits[visit.id], active.visit.namespace == visit.namespace else { return .unavailable }
        try active.lifetime?.check()
        guard now >= active.latestTime else {
            active.lifetime?.invalidate()
            visits.removeValue(forKey: visit.id)
            return .unavailable
        }
        active.latestTime = now
        visits[visit.id] = active
        let age = now.timeIntervalSince(metric.fetchedAt)
        let maximumAge: TimeInterval = metric.category == .signal ? 900 : 300
        guard active.entities.contains(entity), freshFetch, metric.eligible(at: now),
              metric.fetchedAt >= visit.startedAt, metric.evaluatedAt >= visit.startedAt,
              metric.evaluatedAt <= now, age >= 0, age <= maximumAge,
              (entity.kind == .observer) == (metric.category == .observer) else { return .unavailable }
        let localKey = Self.metricKey(entity: entity, category: metric.category)
        let key = MyNodesIdentity.encode([visit.namespace.id, localKey])
        let previous = document.entries[key]?.candidate ?? document.entries[key]?.baseline
        // Fresh confirmation of an unchanged measurement may advance recency, but older
        // observations and older windows may never replace newer metric evidence.
        if let previous {
            guard metric.fetchedAt > previous.metric.fetchedAt,
                  metric.evaluatedAt >= previous.metric.evaluatedAt,
                  metric.measuredAt >= previous.metric.measuredAt else {
                return metric.review(namespace: visit.namespace, entity: entity, baseline: visit.baselines[localKey],
                             expired: visit.expiredKeys.contains(localKey))
            }
        }
        var next = document
        var entry = next.entries[key] ?? Entry(namespace: visit.namespace, entity: entity)
        entry.candidate = MyNodesBaseline(revision: UUID().uuidString.lowercased(), metric: metric)
        entry.candidateOwner = visit.id
        next.entries[key] = entry
        try commit(next)
        return metric.review(namespace: visit.namespace, entity: entity, baseline: visit.baselines[localKey],
                             expired: visit.expiredKeys.contains(localKey))
    }

    func end(_ visit: Visit) throws {
        guard let active = visits[visit.id] else { return }
        active.lifetime?.invalidate()
        var next = document
        for key in Array(next.entries.keys) where next.entries[key]?.candidateOwner == visit.id {
            let candidate = next.entries[key]?.candidate
            next.entries[key]?.baseline = candidate
            next.entries[key]?.candidate = nil
            next.entries[key]?.candidateOwner = nil
        }
        try commit(next)
        visits.removeValue(forKey: visit.id)
    }

    func clear() throws {
        visits.values.forEach { $0.lifetime?.invalidate() }
        visits.removeAll()
        document = Document()
        if FileManager.default.fileExists(atPath: fileURL.path) {
            try FileManager.default.removeItem(at: fileURL)
        }
    }

    private func commit(_ next: Document) throws {
        try Self.save(next, to: fileURL)
        document = next
    }

    private static func save(_ document: Document, to url: URL) throws {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .millisecondsSince1970
        encoder.outputFormatting = [.sortedKeys]
        let data = try encoder.encode(document)
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try data.write(to: url, options: .atomic)
    }
}
