import Foundation
import Testing
@testable import CoreScopeViewer

struct MyNodesVisitSessionTests {
    private let namespace = MyNodesNamespace(origin: "https://fixture.invalid", backend: "corescope",
                                             selectionScope: "local", policyVersion: 1)
    private let key = String(repeating: "a", count: 64)
    private let now = Date(timeIntervalSince1970: 1000)

    private func temporaryFile() -> URL {
        FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString).appendingPathComponent("snapshots.json")
    }

    private func open(_ store: MyNodesSnapshotStore, _ source: AnalyzerSourceSession) async throws -> MyNodesVisitSession {
        try await MyNodesVisitSession(store: store, source: source, namespace: namespace,
                                       nodes: [key], favoriteObservers: [], at: now)
    }

    @Test func reviewDistinguishesUnavailableFirstVisitChangedAndUnchanged() async throws {
        let file = temporaryFile()
        defer { try? FileManager.default.removeItem(at: file.deletingLastPathComponent()) }
        let store = try MyNodesSnapshotStore(fileURL: file)
        let source = AnalyzerSourceSession()
        let entity = MyNodesEntity(kind: .node, value: key)
        func metric(_ seen: Date, _ fetched: Date) -> MyNodesMetric {
            MyNodesMetric(activity: MyNodesActivity(lastSeen: seen, lastHeard: nil,
                fetchedAt: fetched, evaluatedAt: fetched, successfulRecord: true))
        }
        let first = try await open(store, source)
        let quiet = metric(now.addingTimeInterval(-1000), now)
        #expect(try await first.reviewed(entity: entity, metric: quiet, freshFetch: false, at: now).state == .unavailable)
        #expect(try await first.reviewed(entity: entity, metric: quiet, freshFetch: true, at: now).state == .firstVisit)
        try await first.finish()
        let later = now.addingTimeInterval(10)
        let visit = try await MyNodesVisitSession(store: store, source: source, namespace: namespace,
            nodes: [key], favoriteObservers: [], at: later)
        let active = metric(later, later)
        let changed = try await visit.reviewed(entity: entity, metric: active, freshFetch: true, at: later)
        #expect(changed.state == .changed)
        #expect(changed.event?.change == "newlyActive")
        let newer = later.addingTimeInterval(10)
        let repeated = try await visit.reviewed(entity: entity, metric: metric(newer, newer), freshFetch: true, at: newer)
        #expect(repeated.state == .changed)
        #expect(repeated.event?.id == changed.event?.id)
        // A failed/cache-only render must not be shown as no changes.
        #expect(try await visit.reviewed(entity: entity, metric: active, freshFetch: false, at: newer).state == .unavailable)
        try await visit.finish()
        let nextTime = newer.addingTimeInterval(10)
        let next = try await MyNodesVisitSession(store: store, source: source, namespace: namespace,
            nodes: [key], favoriteObservers: [], at: nextTime)
        #expect(try await next.reviewed(entity: entity, metric: metric(newer, nextTime), freshFetch: true, at: nextTime).state == .unchanged)
        try await next.finish()
        let expiredTime = nextTime.addingTimeInterval(8 * 86400)
        let expired = try await MyNodesVisitSession(store: store, source: source, namespace: namespace,
            nodes: [key], favoriteObservers: [], at: expiredTime)
        #expect(try await expired.reviewed(entity: entity, metric: metric(expiredTime, expiredTime),
                                         freshFetch: true, at: expiredTime).state == .expiredBaseline)
        try await expired.finish()
    }

    @Test func departurePromotesAndForegroundStartsNewVisit() async throws {
        let file = temporaryFile()
        defer { try? FileManager.default.removeItem(at: file.deletingLastPathComponent()) }
        let store = try MyNodesSnapshotStore(fileURL: file)
        let source = AnalyzerSourceSession()
        let session = try await open(store, source)
        let captured = await session.visit
        let metric = MyNodesMetric(activity: MyNodesActivity(lastSeen: now, lastHeard: nil,
            fetchedAt: now, evaluatedAt: now, successfulRecord: true))
        _ = try await session.rendered(entity: MyNodesEntity(kind: .node, value: key), metric: metric,
                                        freshFetch: true, at: now)
        #expect(await session.visit.id == captured.id)
        try await session.finish()
        #expect(source.isCurrent)
        await #expect(throws: CancellationError.self) { try await session.perform { 1 } }
        let foreground = try await open(store, source)
        #expect(await foreground.visit.id != captured.id)
        #expect(await foreground.visit.baselines.count == 1)
        try await foreground.finish()
    }

    @Test func sourceChangeAndCacheClearRejectLateResults() async throws {
        for clearsCache in [false, true] {
            let file = temporaryFile()
            defer { try? FileManager.default.removeItem(at: file.deletingLastPathComponent()) }
            let store = try MyNodesSnapshotStore(fileURL: file)
            let source = AnalyzerSourceSession()
            let session = try await open(store, source)
            let started = AsyncStream<Void>.makeStream()
            let release = AsyncStream<Void>.makeStream()
            let task = Task {
                try await session.perform {
                    started.continuation.yield(())
                    // A continuation simulates a transport that ignores task cancellation.
                    await withCheckedContinuation { continuation in
                        Task {
                            for await _ in release.stream { break }
                            continuation.resume()
                        }
                    }
                    return 1
                }
            }
            for await _ in started.stream { break }
            if clearsCache { try await store.clear() } else { source.invalidate() }
            release.continuation.yield(())
            release.continuation.finish()
            started.continuation.finish()
            await #expect(throws: CancellationError.self) { try await task.value }
            await #expect(throws: CancellationError.self) {
                try await session.rendered(entity: MyNodesEntity(kind: .node, value: key),
                    metric: MyNodesMetric(), freshFetch: true, at: now)
            }
            try await session.finish()
            if clearsCache { #expect(!FileManager.default.fileExists(atPath: file.path)) }
        }
    }

    @Test func closingOneVisitPreservesSharedAnalyticsConsumer() async throws {
        let file = temporaryFile()
        defer { try? FileManager.default.removeItem(at: file.deletingLastPathComponent()) }
        let store = try MyNodesSnapshotStore(fileURL: file)
        let source = AnalyzerSourceSession()
        let first = try await open(store, source)
        let second = try await open(store, source)
        let release = AsyncStream<Void>.makeStream()
        let repository = MyNodesAnalyticsRepository(source: "fixture", session: source) { _, _, _ in
            for await _ in release.stream { break }
            throw APIError.http(503)
        }
        let one = Task { try await first.loadAnalytics(repository: repository, publicKeys: [key]) }
        let two = Task { try await second.loadAnalytics(repository: repository, publicKeys: [key]) }
        for _ in 0..<10000 {
            if await repository.activeConsumerCount == 2 { break }
            await Task.yield()
        }
        #expect(await repository.activeConsumerCount == 2)
        try await first.finish()
        await #expect(throws: CancellationError.self) { try await one.value }
        #expect(await repository.activeConsumerCount == 1)
        release.continuation.finish()
        let rows = try await two.value
        #expect(rows.count == 1)
        #expect(rows.first?.state == .failed)
        #expect(source.isCurrent)
        try await second.finish()
    }
}
