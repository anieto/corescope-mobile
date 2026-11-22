import Foundation
import Testing
@testable import CoreScopeViewer

struct CoreScopeAccountSettingsTests {
    @Test func sharedDocumentPreservesRawStringsAndCategories() throws {
        let settings = try load("account-settings")
        #expect(settings.revision == 3)
        #expect(settings.generation == "synthetic-generation-a")
        #expect(settings.supportsNodeFavorites && settings.supportsOwnNodes)
        let document = try #require(settings.doc)
        #expect(document.keys["meshcore-theme"] == "dark")
        let own = try #require(document.keys["meshcore-my-nodes"])
        #expect(own.contains("futureField"))
        let encoded = try JSONEncoder().encode(settings)
        let roundTrip = try JSONDecoder().decode(CoreScopeAccountSettings.self, from: encoded)
        #expect(roundTrip.doc?.keys == document.keys)
    }

    @Test func emptyDocumentIsDistinctFromResetGeneration() throws {
        let empty = try load("account-settings-empty")
        #expect(empty.doc == nil && empty.hasSupportedDocument)
        #expect(empty.supportsNodeFavorites && empty.supportsOwnNodes)
        let inconsistent = CoreScopeAccountSettings(revision: 0, generation: "old-generation", doc: nil, allowlist: empty.allowlist)
        #expect(!inconsistent.hasSupportedDocument)
    }

    @Test func unknownSchemaOrIdentityDoesNotEnableSync() throws {
        let settings = try load("account-settings")
        let future = CoreScopeAccountSettings(revision: 1, generation: "future", doc: .init(v: 2, keys: [:]), allowlist: settings.allowlist)
        #expect(!future.supportsNodeFavorites && !future.supportsOwnNodes)
        let changed = CoreScopeAccountSettings(revision: 0, generation: "", doc: nil, allowlist: [
            .init(key: "meshcore-my-nodes", kind: "set", id: "name"),
            .init(key: "meshcore-favorites", kind: "future-kind", id: nil)
        ])
        #expect(!changed.supportsOwnNodes && !changed.supportsNodeFavorites)
        let duplicate = CoreScopeAccountSettings(revision: 0, generation: "", doc: nil, allowlist: settings.allowlist + settings.allowlist)
        #expect(!duplicate.supportsNodeFavorites && !duplicate.supportsOwnNodes)
    }

    @Test func nestedArrayInsteadOfRawStringIsRejected() throws {
        let data = Data(#"{"revision":1,"generation":"a","doc":{"v":1,"keys":{"meshcore-favorites":[]}},"allowlist":[]}"#.utf8)
        #expect(throws: DecodingError.self) {
            try JSONDecoder().decode(CoreScopeAccountSettings.self, from: data)
        }
    }

    private func load(_ name: String) throws -> CoreScopeAccountSettings {
        let url = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("shared/fixtures/my-nodes/\(name).json")
        return try JSONDecoder().decode(CoreScopeAccountSettings.self, from: Data(contentsOf: url))
    }
}

#if DEBUG
@MainActor
struct StagingPushRoutingTests {
    private let origin = "https://analyzer.example.org"
    private let other = "https://other.example.org"
    private let binding = StagingPush.Binding(analyzer: "analyzer", account: 42, subscription: "sub", expiresAt: nil)

    @Test func routeRequiresBothIDsAndFindsTheBoundSource() throws {
        let payload = ["version": "1", "analyzer_id": "analyzer", "subscription_id": "sub",
                       "node_pubkey": String(repeating: "a", count: 64)]
        let route = try #require(StagingPushRoute(payload))
        let otherBinding = StagingPush.Binding(analyzer: "analyzer", account: 42, subscription: "other", expiresAt: nil)
        let bindings = [origin: binding, other: otherBinding]
        #expect(StagingPush.matchingOrigin(for: route, selected: origin, bindings: bindings) == origin)
        #expect(StagingPush.matchingOrigin(for: route, selected: other, bindings: bindings) == origin)
        #expect(StagingPush.matchingOrigin(for: route, selected: other, bindings: [other: otherBinding]) == nil)
        #expect(StagingPush.matchingOrigin(for: route, selected: "unrelated", bindings: [origin: binding, other: binding]) == nil)
    }

    @Test func staleBindingRequiresDefiniteEvidence() {
        let current = StagingPushCapability(relayOrigin: StagingPush.origin, analyzerId: "analyzer",
                                            contractVersion: 1, platforms: ["ios"])
        let changed = StagingPushCapability(relayOrigin: StagingPush.origin, analyzerId: "replacement",
                                            contractVersion: 1, platforms: ["ios"])
        #expect(!StagingPush.isStale(binding, capability: current))
        #expect(StagingPush.isStale(binding, capability: changed))
        #expect(StagingPush.isStale(binding, capability: nil))
        let inactive = Data(#"{"error":{"code":"analyzer_inactive"}}"#.utf8)
        let expired = Data(#"{"error":{"code":"inactive_proof"}}"#.utf8)
        let unknown = Data(#"{"error":{"code":"unknown_analyzer"}}"#.utf8)
        #expect(StagingPush.isInactiveAnalyzer(status: 410, data: inactive, linkProof: false))
        #expect(!StagingPush.isInactiveAnalyzer(status: 503, data: inactive, linkProof: false))
        #expect(!StagingPush.isInactiveAnalyzer(status: 410, data: expired, linkProof: false))
        #expect(StagingPush.isInactiveAnalyzer(status: 404, data: unknown, linkProof: true))
        #expect(!StagingPush.isInactiveAnalyzer(status: 404, data: unknown, linkProof: false))
    }
    @Test func unknownInstallationRequiresExactRelayError() {
        func body(_ code: String) -> Data {
            Data(#"{"error":{"code":"\#(code)"}}"#.utf8)
        }
        #expect(StagingPush.isUnknownInstallation(status: 404, data: body("unknown_installation")))
        for code in ["unknown_analyzer", "unknown_subscription", "unknown_proof"] {
            #expect(!StagingPush.isUnknownInstallation(status: 404, data: body(code)))
        }
        #expect(!StagingPush.isUnknownInstallation(status: 401, data: body("invalid_credential")))
        #expect(!StagingPush.isUnknownInstallation(status: 401, data: body("unknown_installation")))
        #expect(!StagingPush.isUnknownInstallation(status: 404, data: Data()))
        #expect(!StagingPush.isUnknownInstallation(status: 404, data: Data("{".utf8)))
    }

}
#endif
