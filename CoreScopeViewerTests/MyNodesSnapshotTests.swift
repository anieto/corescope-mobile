import Foundation
import Testing
@testable import CoreScopeViewer

struct MyNodesSnapshotTests {
    private let namespace = MyNodesNamespace(origin: "https://example.test:8443/base", backend: "corescope",
                                             selectionScope: "local", policyVersion: 1)
    private let node = MyNodesEntity(kind: .node, value: String(repeating: "A", count: 64))
    private func date(_ seconds: Double) -> Date { Date(timeIntervalSince1970: seconds) }
    private func metric(_ time: Double, activity: Double) -> MyNodesMetric {
        MyNodesMetric(activity: MyNodesActivity(lastSeen: date(activity), lastHeard: nil,
                      fetchedAt: date(time), evaluatedAt: date(time), successfulRecord: true))
    }
    private func temporaryFile() throws -> URL {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory.appendingPathComponent("snapshots.json")
    }
    private func begin(_ store: MyNodesSnapshotStore, at time: Double) async throws -> MyNodesSnapshotStore.Visit {
        try await store.begin(namespace: namespace, nodes: [node.value], favoriteObservers: [], at: date(time))
    }
    private func seed(_ store: MyNodesSnapshotStore) async throws {
        let visit = try await begin(store, at: 1000)
        _ = try await store.rendered(visit: visit, entity: node, metric: metric(1000, activity: 0),
                                     freshFetch: true, at: date(1000))
        try await store.end(visit)
    }

    @Test func delayedRenderingAndWriteFailureDoNotAdvance() async throws {
        let file = try temporaryFile()
        defer { try? FileManager.default.removeItem(at: file.deletingLastPathComponent()) }
        let store = try MyNodesSnapshotStore(fileURL: file)
        let visit = try await begin(store, at: 1000)
        // Recent at assessment, but no longer recent when the UI renders it.
        _ = try await store.rendered(visit: visit, entity: node, metric: metric(1000, activity: 101),
                                     freshFetch: true, at: date(1002))
        try await store.end(visit)
        #expect(try await begin(store, at: 1003).baselines.isEmpty)
        let next = try await begin(store, at: 2000)
        try FileManager.default.removeItem(at: file)
        // Force atomic replacement to fail without depending on OS permissions.
        try FileManager.default.createDirectory(at: file, withIntermediateDirectories: true)
        try Data("block".utf8).write(to: file.appendingPathComponent("child"))
        await #expect(throws: (any Error).self) {
            try await store.rendered(visit: next, entity: node, metric: metric(2000, activity: 2000),
                                     freshFetch: true, at: date(2000))
        }
        try FileManager.default.removeItem(at: file)
        try await store.end(next)
        #expect(try await begin(store, at: 2001).baselines.isEmpty)
    }

    @Test func sharedWireFormatAndIdentity() async throws {
        struct IdentityCase: Decodable { let components: [String]; let expected: String }
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("shared/fixtures/my-nodes")
        let identities = try JSONDecoder().decode([IdentityCase].self,
            from: Data(contentsOf: root.appendingPathComponent("identity-cases.json")))
        for entry in identities { #expect(MyNodesIdentity.encode(entry.components) == entry.expected) }
        let file = try temporaryFile()
        defer { try? FileManager.default.removeItem(at: file.deletingLastPathComponent()) }
        try Data(contentsOf: root.appendingPathComponent("snapshot-v1.json")).write(to: file)
        let store = try MyNodesSnapshotStore(fileURL: file)
        let visit = try await begin(store, at: 2000)
        #expect(visit.baselines.count == 1)
        let event = try #require(try await store.rendered(visit: visit, entity: node,
            metric: metric(2000, activity: 2000), freshFetch: true, at: date(2000)))
        #expect(event.id == identities.last?.expected)
        try await store.end(visit)
        let roundTrip = try MyNodesSnapshotStore(fileURL: file)
        #expect(try await begin(roundTrip, at: 2001).baselines.values.first?.metric.fetchedAt == date(2000))
    }

    @Test func fixedBaselineAndStableNetEvent() async throws {
        let file = try temporaryFile()
        defer { try? FileManager.default.removeItem(at: file.deletingLastPathComponent()) }
        let store = try MyNodesSnapshotStore(fileURL: file)
        try await seed(store)
        let visit = try await begin(store, at: 2000)
        let event = try #require(try await store.rendered(visit: visit, entity: node,
            metric: metric(2000, activity: 2000), freshFetch: true, at: date(2000)))
        let refresh = try #require(try await store.rendered(visit: visit, entity: node,
            metric: metric(2100, activity: 2100), freshFetch: true, at: date(2100)))
        #expect(event.id == refresh.id)
        #expect(visit.baselines.values.first?.metric.activity?.state == .notRecentlyHeard)
        let returned = try await store.rendered(visit: visit, entity: node,
            metric: metric(3100, activity: 2100), freshFetch: true, at: date(3100))
        #expect(returned == nil)
        try await store.end(visit)
        let next = try await begin(store, at: 3200)
        #expect(next.baselines.values.first?.metric.fetchedAt == date(3100))
    }

    @Test func renderedCandidateRecoversAndCacheDisplayDoesNotAdvance() async throws {
        let file = try temporaryFile()
        defer { try? FileManager.default.removeItem(at: file.deletingLastPathComponent()) }
        let store = try MyNodesSnapshotStore(fileURL: file)
        let visit = try await begin(store, at: 1000)
        _ = try await store.rendered(visit: visit, entity: node, metric: metric(1000, activity: 1000),
                                     freshFetch: false, at: date(1000))
        let empty = try MyNodesSnapshotStore(fileURL: file)
        #expect(try await begin(empty, at: 1001).baselines.isEmpty)
        _ = try await store.rendered(visit: visit, entity: node, metric: metric(1002, activity: 1002),
                                     freshFetch: true, at: date(1002))
        let recovered = try MyNodesSnapshotStore(fileURL: file)
        let next = try await begin(recovered, at: 1003)
        #expect(next.baselines.values.first?.metric.fetchedAt == date(1002))
        #expect(next.baselines.count == 1)
    }

    @Test func olderWindowCannotReplaceNewerEvidence() async throws {
        let file = try temporaryFile()
        defer { try? FileManager.default.removeItem(at: file.deletingLastPathComponent()) }
        let store = try MyNodesSnapshotStore(fileURL: file)
        try await seed(store)
        let old = try await begin(store, at: 2000)
        let new = try await begin(store, at: 2001)
        _ = try await store.rendered(visit: new, entity: node, metric: metric(2004, activity: 2004),
                                     freshFetch: true, at: date(2004))
        _ = try await store.rendered(visit: old, entity: node, metric: metric(2003, activity: 2003),
                                     freshFetch: true, at: date(2005))
        try await store.end(old)
        try await store.end(new)
        let next = try await begin(store, at: 2006)
        #expect(next.baselines.values.first?.metric.fetchedAt == date(2004))
    }

    @Test func removalAndClearRejectLateWrites() async throws {
        let file = try temporaryFile()
        defer { try? FileManager.default.removeItem(at: file.deletingLastPathComponent()) }
        let store = try MyNodesSnapshotStore(fileURL: file)
        try await seed(store)
        let old = try await begin(store, at: 2000)
        _ = try await store.begin(namespace: namespace, nodes: [], favoriteObservers: [], at: date(2001))
        let readded = try await begin(store, at: 2002)
        #expect(readded.baselines.isEmpty)
        _ = try await store.rendered(visit: old, entity: node, metric: metric(2003, activity: 2003),
                                     freshFetch: true, at: date(2003))
        try await store.end(old)
        #expect(try await begin(store, at: 2004).baselines.isEmpty)
        try await store.clear()
        _ = try await store.rendered(visit: readded, entity: node, metric: metric(2005, activity: 2005),
                                     freshFetch: true, at: date(2005))
        try await store.end(readded)
        #expect(!FileManager.default.fileExists(atPath: file.path))
    }

    @Test func namespacesExpiryAndClockRollback() async throws {
        let file = try temporaryFile()
        defer { try? FileManager.default.removeItem(at: file.deletingLastPathComponent()) }
        let store = try MyNodesSnapshotStore(fileURL: file)
        try await seed(store)
        for scope in [
            MyNodesNamespace(origin: namespace.origin, backend: "beacon", selectionScope: "local", policyVersion: 1),
            MyNodesNamespace(origin: namespace.origin, backend: "corescope", selectionScope: "account:one", policyVersion: 1),
            MyNodesNamespace(origin: namespace.origin + "/other", backend: "corescope", selectionScope: "local", policyVersion: 1),
            MyNodesNamespace(origin: namespace.origin, backend: "corescope", selectionScope: "local", policyVersion: 2)
        ] {
            let visit = try await store.begin(namespace: scope, nodes: [node.value], favoriteObservers: [], at: date(2000))
            #expect(visit.baselines.isEmpty)
        }
        let expired = try await begin(store, at: 1000 + 7 * 86400 + 1)
        #expect(expired.baselines.isEmpty)
        #expect(expired.expiredKeys.count == 1)
        let visit = try await begin(store, at: 2000)
        _ = try await store.rendered(visit: visit, entity: node, metric: metric(1999, activity: 1999),
                                     freshFetch: true, at: date(1999))
        _ = try await store.rendered(visit: visit, entity: node, metric: metric(2001, activity: 2001),
                                     freshFetch: true, at: date(2001))
        try await store.end(visit)
        #expect(try await begin(store, at: 2002).baselines.values.first?.metric.fetchedAt == date(1000))
    }

    @Test func observerLimitAndCorruptionResetAreFeatureLocal() async throws {
        let file = try temporaryFile()
        defer { try? FileManager.default.removeItem(at: file.deletingLastPathComponent()) }
        let favorites = file.deletingLastPathComponent().appendingPathComponent("favorites.json")
        try Data("keep".utf8).write(to: favorites)
        for invalid in ["corrupt", "{\"schemaVersion\":99,\"entries\":{}}"] {
            try Data(invalid.utf8).write(to: file)
            let store = try MyNodesSnapshotStore(fileURL: file)
            let covering = Set((0..<202).map { String(format: "%03d", $0) })
            let visit = try await store.begin(namespace: namespace, nodes: [], favoriteObservers: ["favorite"],
                                               coveringObservers: covering, at: date(1000))
            #expect(visit.omittedObserverCount == 2)
            for id in ["000", "199", "200", "favorite"] {
                let status = MyNodesObserverStatus(explicitOnline: true, statusAt: date(1001), lastSeen: nil,
                                                  fetchedAt: date(1001), evaluatedAt: date(1001), successfulRecord: true)
                _ = try await store.rendered(visit: visit, entity: MyNodesEntity(kind: .observer, value: id),
                    metric: MyNodesMetric(observer: status), freshFetch: true, at: date(1001))
            }
            try await store.end(visit)
            let next = try await store.begin(namespace: namespace, nodes: [], favoriteObservers: ["favorite"],
                                              coveringObservers: covering, at: date(1002))
            #expect(next.baselines.count == 3)
            try await store.clear()
            #expect(try String(contentsOf: favorites, encoding: .utf8) == "keep")
        }
    }
}
