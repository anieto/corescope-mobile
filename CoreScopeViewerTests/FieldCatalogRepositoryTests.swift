import Foundation
import Testing
@testable import CoreScopeViewer

struct FieldCatalogRepositoryTests {
    private actor Calls {
        private(set) var nodes = 0
        private(set) var observers = 0

        func recordNodes() { nodes += 1 }
        func recordObservers() { observers += 1 }
    }

    private struct Backend: FieldCatalogBackend {
        let sourceSession = AnalyzerSourceSession()
        let cacheIdentifier = "field-test-\(UUID())"
        let calls: Calls

        func nodes(region: String?) async throws -> NodesResponse {
            await calls.recordNodes()
            return NodesResponse(
                nodes: [], total: 0,
                counts: RoleCounts(repeaters: 0, rooms: 0, companions: 0, sensors: 0)
            )
        }

        func observers() async throws -> ObserversResponse {
            await calls.recordObservers()
            return ObserversResponse(observers: [], serverTime: nil)
        }
    }

    @Test func simultaneousConsumersShareCatalogRefreshes() async throws {
        let calls = Calls()
        let backend = Backend(calls: calls)
        let repository = FieldCatalogRepository(cache: APIResponseCache())

        async let first = repository.refreshNodes(for: backend, region: nil)
        async let second = repository.refreshNodes(for: backend, region: nil)
        _ = try await (first, second)

        #expect(await calls.nodes == 1)
    }

    @Test func seededCatalogIsAvailableWithoutARequest() async throws {
        let calls = Calls()
        let backend = Backend(calls: calls)
        let repository = FieldCatalogRepository(cache: APIResponseCache())
        let response = ObserversResponse(observers: [], serverTime: nil)

        await repository.seedObservers(response, for: backend)
        let cached = await repository.cachedObservers(for: backend)

        #expect(cached?.value.observers.isEmpty == true)
        #expect(await calls.observers == 0)
    }
}
