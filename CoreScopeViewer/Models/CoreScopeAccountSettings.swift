import Foundation
import Observation

/// Wire document for CoreScope's optional account API. Values deliberately remain raw
/// strings so reading node selections cannot discard unrelated settings or future fields.
struct CoreScopeAccountSettings: Codable, Sendable {
    struct Document: Codable, Sendable {
        let v: Int
        let keys: [String: String]
    }

    struct AllowedKey: Codable, Sendable {
        let key: String
        let kind: String
        let id: String?
    }

    let revision: Int64
    let generation: String
    let doc: Document?
    let allowlist: [AllowedKey]

    /// Revisions can restart after deletion; the generation must participate in every write.
    var hasSupportedDocument: Bool {
        if let doc {
            return revision > 0 && !generation.isEmpty && doc.v == 1
        }
        return revision == 0 && generation.isEmpty
    }

    var supportsNodeFavorites: Bool {
        supportsSet(key: "meshcore-favorites", identityField: nil)
    }

    var supportsOwnNodes: Bool {
        supportsSet(key: "meshcore-my-nodes", identityField: "pubkey")
    }

    private func supportsSet(key: String, identityField: String?) -> Bool {
        guard hasSupportedDocument else { return false }
        let entries = allowlist.filter { $0.key == key }
        guard entries.count == 1, let entry = entries.first else { return false }
        return entry.kind == "set" && entry.id == identityField
    }
}

/// Personal edits are scoped to one source/account; device favorites never seed this state.
struct CoreScopeAccountSelectionState: Codable, Sendable {
    var baseline: CoreScopeAccountSettings
    var favorites: Set<String>

    init(remote: CoreScopeAccountSettings) throws {
        baseline = remote
        favorites = Set(try CoreScopeFavoriteMerge.selection(remote).favorites.map { $0.lowercased() })
    }

    mutating func refresh(_ remote: CoreScopeAccountSettings) throws {
        guard baseline.generation == remote.generation else { throw CoreScopeAccountError.reset }
        let (_, selection) = try CoreScopeFavoriteMerge.document(base: baseline, local: favorites, remote: remote)
        favorites = Set(selection.favorites.map { $0.lowercased() })
        baseline = remote
    }

    var hasPendingEdits: Bool {
        guard let remote = try? CoreScopeFavoriteMerge.selection(baseline) else { return true }
        return favorites != Set(remote.favorites.map { $0.lowercased() })
    }

    /// Preserve edits made after the uploaded snapshot was captured.
    mutating func acknowledge(_ accepted: CoreScopeAccountSettings, sentFavorites: Set<String>) throws {
        let remote = try CoreScopeFavoriteMerge.selection(accepted)
        favorites = CoreScopeFavoriteMerge.merge(base: sentFavorites, local: favorites,
            remote: Set(remote.favorites.map { $0.lowercased() }))
        baseline = accepted
    }

    mutating func restore(onto remote: CoreScopeAccountSettings) throws {
        let previous = favorites
        self = try Self(remote: remote)
        favorites.formUnion(previous)
    }
}

@MainActor @Observable
final class CoreScopeAccountSelectionStore {
    static let shared = CoreScopeAccountSelectionStore()
    private let defaults: UserDefaults
    private(set) var revision = 0
    private(set) var activeAccounts: [String: Int64]

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        activeAccounts = defaults.data(forKey: "corescope-active-accounts-v2")
            .flatMap { try? JSONDecoder().decode([String: Int64].self, from: $0) } ?? [:]
    }

    func activate(origin: String, userID: Int64) {
        // Only one identity per source, even if the URL spelling changes.
        let source = AnalyzerSettings.normalizedHost(origin)
        activeAccounts = activeAccounts.filter { AnalyzerSettings.normalizedHost($0.key) != source }
        activeAccounts[origin] = userID
        persistActiveAccounts()
    }

    func deactivate(origin: String) {
        activeAccounts[origin] = nil
        persistActiveAccounts()
    }

    func visibleState(source: String) -> CoreScopeAccountSelectionState? {
        _ = revision
        guard let (origin, userID) = activeAccounts.first(where: {
            AnalyzerSettings.normalizedHost($0.key) == source
        }) else { return nil }
        return try? load(origin: origin, userID: userID)
    }

    var sources: [String] { activeAccounts.keys.map(AnalyzerSettings.normalizedHost).sorted() }

    func removeFavorite(_ key: String, source: String) throws {
        guard let (origin, userID) = activeAccounts.first(where: {
            AnalyzerSettings.normalizedHost($0.key) == source
        }), var state = try load(origin: origin, userID: userID) else { return }
        state.favorites.remove(key.lowercased())
        try save(state, origin: origin, userID: userID)
    }

    private func persistActiveAccounts() {
        defaults.set(try? JSONEncoder().encode(activeAccounts), forKey: "corescope-active-accounts-v2")
    }

    static func key(origin: String, userID: Int64) -> String {
        "corescope-account-selections-v2|\(origin)|\(userID)"
    }

    func load(origin: String, userID: Int64) throws -> CoreScopeAccountSelectionState? {
        guard let data = defaults.data(forKey: Self.key(origin: origin, userID: userID)) else { return nil }
        return try JSONDecoder().decode(CoreScopeAccountSelectionState.self, from: data)
    }

    func save(_ state: CoreScopeAccountSelectionState, origin: String, userID: Int64) throws {
        defaults.set(try JSONEncoder().encode(state), forKey: Self.key(origin: origin, userID: userID))
        revision += 1
    }

    func remove(origin: String, userID: Int64) {
        if activeAccounts[origin] == userID {
            activeAccounts[origin] = nil
            persistActiveAccounts()
        }
        revision += 1
        defaults.removeObject(forKey: Self.key(origin: origin, userID: userID))
        defaults.removeObject(forKey: "corescope-favorite-baseline|\(origin)|\(userID)")
    }
}
