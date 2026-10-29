import Foundation
import Testing
@testable import CoreScopeViewer

struct MyNodesCacheOwnershipTests {
    private let namespace = MyNodesNamespace(origin: "https://example.test", backend: "corescope",
                                             selectionScope: "local", policyVersion: 1)
    private let key = String(repeating: "a", count: 64)

    private actor Gate {
        var started = 0
        private var pending: [CheckedContinuation<Void, Never>] = []
        func wait() async {
            started += 1
            await withCheckedContinuation { pending.append($0) }
        }
        func release() { pending.forEach { $0.resume() }; pending.removeAll() }
    }

    @Test func repositoriesSharePerSessionAndClearPreservesParent() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let owner = CacheStorageService(cacheRoot: root)
        let parent = AnalyzerSourceSession()
        let client = APIClient(baseURL: try #require(URL(string: "https://example.test")))
        let first = try await owner.myNodesCoreScope(client: client, session: parent)
        let second = try await owner.myNodesCoreScope(client: client, session: parent)
        #expect(first === second)
        let other = try await owner.myNodesCoreScope(client: client, session: AnalyzerSourceSession())
        #expect(first !== other)
        try await first.base.seedObservers(.init(observers: [], fetchedAt: .now, completeness: .partial),
                                           source: first.base.source)
        try await owner.clearMyNodesSnapshots()
        #expect(parent.isCurrent)
        #expect(!first.lifetime.isCurrent)
        #expect(!other.lifetime.isCurrent)
        await #expect(throws: CancellationError.self) { try await first.base.observers() }
        await #expect(throws: CancellationError.self) { try await first.analytics.cached(publicKeys: [key]) }
        let fresh = try await owner.myNodesCoreScope(client: client, session: parent)
        #expect(fresh !== first)
        #expect(try await fresh.base.observers().snapshot == nil)
        parent.invalidate()
        #expect(!fresh.lifetime.isCurrent)
        await #expect(throws: CancellationError.self) { try await owner.myNodesCoreScope(client: client, session: parent) }
    }

    @Test func clearCancelsBothRepositoryLoadsAndRejectsLateResults() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let owner = CacheStorageService(cacheRoot: root)
        let parent = AnalyzerSourceSession()
        let gate = Gate()
        let owned = try await owner.myNodesRepositories(source: "fixture", session: parent) { lifetime in
            let base = MyNodesBaseRepository(source: "fixture", session: lifetime) { _, _, _ in
                await gate.wait()
                return nil
            }
            let analytics = MyNodesAnalyticsRepository(source: "fixture", session: lifetime) { _, _, _ in
                await gate.wait()
                throw APIError.http(503)
            }
            return (base, analytics)
        }
        let base = Task { try await owned.base.load(publicKeys: [key]) }
        let analytics = Task { try await owned.analytics.load(publicKeys: [key]) }
        for _ in 0..<10000 {
            if await gate.started == 2 { break }
            await Task.yield()
        }
        #expect(await gate.started == 2)
        try await owner.clearMyNodesSnapshots()
        await gate.release()
        await #expect(throws: CancellationError.self) { try await base.value }
        await #expect(throws: CancellationError.self) { try await analytics.value }
        #expect(parent.isCurrent)
        #expect(await owned.base.activeConsumerCount == 0)
        #expect(await owned.analytics.activeConsumerCount == 0)
    }

    @Test func sharedOwnerCountsSnapshotsAndClearRejectsLateWrites() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let favorites = root.appendingPathComponent("favorites.json")
        try Data("keep".utf8).write(to: favorites)
        let owner = CacheStorageService(cacheRoot: root)
        async let first = owner.myNodesSnapshots()
        async let second = owner.myNodesSnapshots()
        let (store, otherWindow) = try await (first, second)
        #expect(store === otherWindow)
        let now = Date(timeIntervalSince1970: 1000)
        let visit = try await store.begin(namespace: namespace, nodes: [key], favoriteObservers: [], at: now)
        let metric = MyNodesMetric(activity: MyNodesActivity(lastSeen: now, lastHeard: nil,
            fetchedAt: now, evaluatedAt: now, successfulRecord: true))
        _ = try await store.rendered(visit: visit, entity: MyNodesEntity(kind: .node, value: key),
                                    metric: metric, freshFetch: true, at: now)
        #expect(await owner.usage().fileCount == 1)
        try await owner.clearMyNodesSnapshots()
        _ = try await otherWindow.rendered(visit: visit, entity: MyNodesEntity(kind: .node, value: key),
                                          metric: metric, freshFetch: true, at: now)
        try await otherWindow.end(visit)
        #expect(await owner.usage().fileCount == 0)
        #expect(try String(contentsOf: favorites, encoding: .utf8) == "keep")
        let next = try await otherWindow.begin(namespace: namespace, nodes: [key], favoriteObservers: [], at: now)
        #expect(next.baselines.isEmpty)
        _ = try await store.rendered(visit: next, entity: MyNodesEntity(kind: .node, value: key),
                                    metric: metric, freshFetch: true, at: now)
        try await store.end(next)
        #expect(await owner.usage().fileCount == 1)
    }

    @Test func unopenedCorruptSnapshotClearsWithoutRecovery() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let directory = root.appendingPathComponent("MyNodesSnapshots")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let file = directory.appendingPathComponent("snapshots.json")
        try Data("not-json".utf8).write(to: file)
        let owner = CacheStorageService(cacheRoot: root)
        #expect(await owner.usage().fileCount == 1)
        try await owner.clearMyNodesSnapshots()
        #expect(!FileManager.default.fileExists(atPath: file.path))
        let store = try await owner.myNodesSnapshots()
        let visit = try await store.begin(namespace: namespace, nodes: [key], favoriteObservers: [], at: .now)
        #expect(visit.baselines.isEmpty)
    }
}
