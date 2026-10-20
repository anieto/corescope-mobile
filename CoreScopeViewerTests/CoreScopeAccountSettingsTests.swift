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
