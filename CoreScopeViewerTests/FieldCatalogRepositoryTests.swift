import Foundation
import Testing
@testable import CoreScopeViewer

struct FieldCatalogRepositoryTests {
    private actor Calls {
        private(set) var nodes = 0
        private(set) var observers = 0
        private(set) var reports = 0

        func recordNodes() { nodes += 1 }
        func recordObservers() { observers += 1 }
        func recordReports() { reports += 1 }
    }

    private struct Backend: FieldCatalogBackend {
        let sourceSession = AnalyzerSourceSession()
        let cacheIdentifier = "field-test-\(UUID())"
        let calls: Calls
        var reportJSON: String = #"{"repeaters":[]}"#

        func repeaterMetadata() async throws -> RepeaterMetadataCatalog {
            await calls.recordReports()
            return try APIClient.makeDecoder().decode(RepeaterMetadataCatalog.self, from: Data(reportJSON.utf8))
        }

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

extension FieldCatalogRepositoryTests {
    @Test func repeaterCatalogSharesRefreshAndSeparatesAnalyzerCaches() async {
        let key = String(repeating: "a", count: 64)
        let first = Backend(calls: Calls(), reportJSON: """
            {"repeaters":[{"pubkey":"\(key)","status":"maintenance"}]}
            """)
        let second = Backend(calls: Calls())
        let repository = FieldCatalogRepository(cache: APIResponseCache())
        async let firstLoad = repository.repeaterMetadata(for: first)
        async let secondLoad = repository.repeaterMetadata(for: first)
        let (a, b) = await (firstLoad, secondLoad)
        #expect(a[key]?.status == "maintenance")
        #expect(b[key]?.status == "maintenance")
        #expect(await first.calls.reports == 1)
        #expect(await repository.repeaterMetadata(for: second)[key] == nil)
        first.sourceSession.invalidate()
        #expect(await repository.repeaterMetadata(for: first)[key] == nil)
    }

    @Test func repeaterCatalogKeepsValidFieldsFromPartialRecords() throws {
        let key = String(repeating: "A", count: 64)
        let claimedOnly = String(repeating: "b", count: 64)
        let catalog = try APIClient.makeDecoder().decode(RepeaterMetadataCatalog.self, from: Data("""
            {"repeaters":[null, {"pubkey":"abc"}, {"pubkey":"\(key)","heightM":0,"gainDbi":"7",
              "status":"relocating","power":"solar","notes":"<b>plain</b>",
              "claimers":[null, {"displayName":"Operator","claimedAt":"bad date"}]},
              {"pubkey":"\(claimedOnly)","gainDbi":0,"claimers":[{"displayName":"Other"}]}]}
            """.utf8))
        let report = try #require(catalog[key])
        #expect(report.heightM == 0)
        #expect(report.applicableGainDbi == nil)
        #expect(report.status == nil)
        #expect(report.power == "solar")
        #expect(report.notes == "<b>plain</b>")
        #expect(report.claimers.first?.displayName == "Operator")
        #expect(report.claimers.first?.claimedAt == nil)
        #expect(catalog[claimedOnly]?.gainDbi == nil)
        #expect(catalog["abc"] == nil)
        #expect(catalog.repeaters.count == 2)
        #expect(abs(DistanceUnit.imperial.displayHeight(meters: 3.048) - 10) < 1e-6)
        let restored = try JSONDecoder().decode(RepeaterMetadataCatalog.self, from: JSONEncoder().encode(catalog))
        #expect(restored[key]?.notes == report.notes)
    }
}
