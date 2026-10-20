import Foundation
import Testing
@testable import CoreScopeViewer

@MainActor
struct FavoriteChangesTests {
    private let key = String(repeating: "a", count: 64)
    private let other = String(repeating: "b", count: 64)

    @Test func onlyReviewedRowsAdvanceAndRefreshKeepsCapturedBaseline() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let store = try MyNodesSnapshotStore(fileURL: root.appendingPathComponent("snapshots.json"))
        let parent = AnalyzerSourceSession()
        let lifetime = AnalyzerSourceSession()
        let source = "corescope|https://fixture.invalid"
        let base = MyNodesBaseRepository(source: source, session: lifetime) { key, _, budget in
            try await budget.perform(node: key) {
                let date = ISO8601DateFormatter().string(from: .now)
                let data = Data("{\"public_key\":\"\(key)\",\"name\":\"Test\",\"role\":\"repeater\",\"last_seen\":\"\(date)\"}".utf8)
                let decoder = JSONDecoder()
                decoder.dateDecodingStrategy = .iso8601
                return try decoder.decode(MeshNode.self, from: data)
            }
        }
        let analytics = MyNodesAnalyticsRepository(source: source, session: lifetime) { _, _, _ in throw APIError.http(500) }
        let owned = MyNodesRepositories(parent: parent, lifetime: lifetime, base: base, analytics: analytics)
        let items = [key, other].map {
            FavoriteItem(source: "fixture.invalid", kind: .node, entityID: $0, title: "Test",
                subtitle: nil, favoritedAt: .now, node: nil, observer: nil, channel: nil)
        }
        let model = FavoriteChangesModel()
        let run = Task { await model.run(items: items, acquire: { owned }, snapshots: { store }) }
        try await waitUntil { model.rows.count == 2 && !model.loading }
        #expect(model.rows.allSatisfy { $0.result.state == .firstVisit })
        let visible = try #require(model.rows.first)
        await model.review(visible)
        model.refresh(force: true)
        try await waitUntil { model.rows.first?.revision != visible.revision && !model.loading }
        #expect(model.rows.allSatisfy { $0.result.state == .firstVisit })
        await model.review(try #require(model.rows.first))
        run.cancel()
        await run.value
        let namespace = MyNodesNamespace(origin: "https://fixture.invalid", backend: "corescope", selectionScope: "local", policyVersion: 1)
        let next = try await store.begin(namespace: namespace, nodes: [key, other], favoriteObservers: [], at: .now)
        #expect(next.baselines.count == 1)
        #expect(next.baselines[MyNodesSnapshotStore.metricKey(entity: visible.entity, category: .activity)] != nil)
        try await store.end(next)
        // A new visit reports unchanged for the reviewed row, first visit for the off-screen row.
        let lastRevision = model.rows.first?.revision
        let resumed = Task { await model.run(items: items, acquire: { owned }, snapshots: { store }) }
        try await waitUntil { model.rows.count == 2 && !model.loading && model.rows.first?.revision != lastRevision }
        #expect(model.rows.first?.result.state == .unchanged)
        #expect(model.rows.last?.result.state == .firstVisit)
        resumed.cancel()
        await resumed.value
    }

    private func waitUntil(_ condition: () -> Bool) async throws {
        for _ in 0..<500 {
            if condition() { return }
            try await Task.sleep(for: .milliseconds(10))
        }
        Issue.record("Changes did not reach the expected state")
    }
}
